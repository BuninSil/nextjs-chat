package dev.mlbb.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log

/**
 * Режим «С моим VPN» без собственного VPN: VPN-клиент (Karing и т.п.) работает
 * как обычно, с TUN, а мы раз в секунду читаем его список соединений через
 * Clash API и показываем оверлей. Сеть телефона не трогаем вообще.
 */
class MonitorService : Service() {

    companion object {
        private const val TAG = "Monitor"
        private const val CHANNEL_ID = "capture"
        private const val NOTIF_ID = 2
        private const val ACTION_STOP = "dev.mlbb.overlay.MONITOR_STOP"
        /** ~20 КБ за 10 с — меньше этого боевым сервером не считаем для вибрации */
        private const val MIN_BATTLE_BYTES = 20_000L

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var lastError: String? = null

        /** true — клиент не говорит, какое приложение открыло соединение. */
        @Volatile
        var noProcessInfo = false
            private set

        /** Источник данных текущего запуска: "Clash API" или "Shizuku". */
        @Volatile
        var sourceName = ""
            private set

        @Volatile
        var lastPing: Pinger.Result? = null
        /** Номер последнего замера пинга — чтобы [MatchTracker] не посчитал один замер дважды */
        @Volatile var pingSeq = 0L
            private set

        /** apiPort > 0 — Clash API нашего встроенного ядра (порт/секрет известны заранее). */
        fun start(ctx: Context, apiPort: Int = 0, secret: String = "", socksPort: Int = 0) {
            ctx.startForegroundService(
                Intent(ctx, MonitorService::class.java)
                    .putExtra("api_port", apiPort).putExtra("secret", secret).putExtra("socks_port", socksPort)
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, MonitorService::class.java).setAction(ACTION_STOP))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: OverlayController? = null
    private var pollThread: Thread? = null
    private var pingThread: Thread? = null
    private var pinger = Pinger(minValidMs = 3)
    private var fixedApiPort = 0
    private var fixedSecret = ""
    private var socksPort = 0
    private var lastAlertIp: String? = null
    private var lastRecordedIp: String? = null

    /** id соединения в Clash API -> наш числовой id для ConnTracker */
    private val ids = HashMap<String, Int>()
    private var nextId = 0
    private var lastSeen = HashMap<String, ClashApi.Conn>()

    private val ticker = object : Runnable {
        override fun run() {
            refreshOverlay()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitor()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        fixedApiPort = intent?.getIntExtra("api_port", 0) ?: 0
        fixedSecret = intent?.getStringExtra("secret") ?: ""
        socksPort = intent?.getIntExtra("socks_port", 0) ?: 0
        if (!isRunning) startMonitor()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Мониторинг сервера", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_mono)
            .setContentTitle("Fast VPN")
            .setContentText("Слежу за сервером игры через VPN-клиент")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Стоп", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun startMonitor() {
        lastError = null
        AppSettings.load(this)
        GeoDb.load(this)
        ConnTracker.metricBytes = true
        ConnTracker.newSession()
        ids.clear()
        lastSeen = HashMap()
        lastAlertIp = null
        isRunning = true
        WifiBoost.acquire(this)

        val builtIn = fixedApiPort > 0
        val port = if (builtIn) fixedApiPort else AppSettings.apiPort
        val secret = if (builtIn) fixedSecret else AppSettings.apiSecret
        pinger = Pinger(minValidMs = 3, socksPort = socksPort)
        val useShizuku = !builtIn && ShizukuSource.granted()
        sourceName = if (useShizuku) "Shizuku" else "Clash API"
        ConnTracker.metricBytes = !useShizuku
        // У Shizuku нет счётчиков трафика: боевой — самый свежий UDP-сокет игры
        ConnTracker.battleOverride = if (useShizuku) ({ newestGameUdp() }) else null
        val gameUid = try {
            packageManager.getApplicationInfo(CaptureVpnService.GAME_PACKAGE, 0).uid
        } catch (_: Exception) {
            -1
        }

        pollThread = Thread({
            var failures = 0
            var reader: IProcReader? = null
            while (isRunning) {
                try {
                    if (useShizuku) {
                        val r = reader ?: ShizukuSource.bind() ?: throw RuntimeException("Shizuku не отвечает")
                        reader = r
                        val now = System.currentTimeMillis()
                        poll(ShizukuSource.sockets(r, gameUid).map {
                            ClashApi.Conn(
                                id = (if (it.udp) "u" else "t") + it.inode + ":" + it.remoteIp + ":" + it.remotePort,
                                udp = it.udp, dstIp = it.remoteIp, dstPort = it.remotePort, srcPort = it.localPort,
                                upload = 0, download = 0, startMs = now, isGame = true,
                            )
                        })
                    } else {
                        poll(ClashApi.connections(port, secret, CaptureVpnService.GAME_PACKAGE))
                    }
                    failures = 0
                } catch (e: Exception) {
                    Log.w(TAG, "poll failed", e)
                    reader = null
                    if (++failures >= 5) {
                        handler.post {
                            lastError = if (useShizuku) {
                                "Shizuku перестал отвечать (${e.message}). Скорее всего, телефон перезагружался — запусти Shizuku заново."
                            } else {
                                "VPN-клиент перестал отдавать список соединений (порт $port). " +
                                    "Проверь, что VPN подключён, и нажми СТАРТ ещё раз."
                            }
                            stopMonitor()
                            stopSelf()
                        }
                        break
                    }
                }
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "clash-poll").also { it.start() }

        pingThread = Thread({
            while (isRunning) {
                ConnTracker.battleServer()?.let {
                    pinger.probe(it.conn.dstIp)
                    lastPing = pinger.last
                    pingSeq++
                }
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "pinger").also { it.start() }

        if (Settings.canDrawOverlays(this)) {
            overlay = OverlayController(this).also { it.show() }
        }
        handler.post(ticker)
    }

    private fun newestGameUdp(): ConnTracker.Battle? {
        val c = ConnTracker.snapshot()
            .filter { !it.closed && it.proto == ConnTracker.PROTO_UDP && it.dstPort !in setOf(53, 123, 443) }
            .maxByOrNull { it.key } ?: return null
        return ConnTracker.Battle(c, 0)
    }

    private fun poll(conns: List<ClashApi.Conn>) {
        // Если клиент не отдаёт имя приложения — берём все соединения (в игре её UDP всё равно доминирует)
        noProcessInfo = conns.isNotEmpty() && conns.all { it.isGame == null }
        val now = System.currentTimeMillis()
        val current = HashMap<String, ClashApi.Conn>()

        for (c in conns) {
            if (c.isGame == false) continue
            current[c.id] = c
            val id = ids.getOrPut(c.id) { ++nextId }
            ConnTracker.update(
                id, if (c.udp) ConnTracker.PROTO_UDP else ConnTracker.PROTO_TCP, c.srcPort,
                c.dstIp, c.dstPort, c.upload, c.download, 0, 0,
                c.startMs, now, -1, false
            )
        }
        // Пропавшие из списка — закрылись
        for ((key, c) in lastSeen) {
            if (key !in current) {
                val id = ids.remove(key) ?: continue
                ConnTracker.update(
                    id, if (c.udp) ConnTracker.PROTO_UDP else ConnTracker.PROTO_TCP, c.srcPort,
                    c.dstIp, c.dstPort, c.upload, c.download, 0, 0,
                    c.startMs, now, -1, true
                )
            }
        }
        lastSeen = current
    }

    private fun refreshOverlay() {
        val battle = ConnTracker.battleServer()
        // Режим боя и статистика матчей — по боевому трафику игры, даже без плашки
        run {
            val active = battle != null && (!ConnTracker.metricBytes || battle.pktsLast10s >= MIN_BATTLE_BYTES)
            val g = battle?.let { GeoDb.lookup(it.conn.dstIp) }
            val p = pinger.last?.takeIf { battle != null && it.ip == battle.conn.dstIp }
            MatchTracker.tick(this, active, g?.countryCode?.uppercase() ?: "", g?.city?.ifEmpty { g.country } ?: "", p?.ms, pingSeq)
        }
        val ov = overlay ?: return
        if (battle == null) {
            ov.update("MLBB: жду трафик игры…", OverlayController.COLOR_UNKNOWN)
            return
        }
        val ip = battle.conn.dstIp
        val geo = GeoDb.lookup(ip)
        val ping = pinger.last?.takeIf { it.ip == ip }
        val pingText = when {
            ping == null -> "… ms"
            ping.ms < 0 -> "— ms"
            else -> "${ping.ms} ms"
        }
        val place = when {
            geo == null && !GeoDb.isLoaded -> "🏳 нет базы DB-IP"
            geo == null -> "🏳 неизвестно"
            else -> "${GeoDb.flag(geo.countryCode)} ${geo.city.ifEmpty { geo.country }}"
        }
        val allowed = geo != null && geo.countryCode.uppercase() in AppSettings.allowedCountries
        val color = when {
            geo == null -> OverlayController.COLOR_UNKNOWN
            allowed -> OverlayController.COLOR_RUSSIA
            else -> OverlayController.COLOR_FOREIGN
        }
        val warn = AppSettings.alertEnabled && geo != null && !allowed
        val prefix = if (warn) "⚠ " else ""
        val activity = if (ConnTracker.metricBytes) " · ${battle.pktsLast10s / 1024} КБ/10с" else " · UDP"
        ov.update("$prefix$place · $pingText\n$ip:${battle.conn.dstPort}$activity", color)

        val enough = !ConnTracker.metricBytes || battle.pktsLast10s >= MIN_BATTLE_BYTES
        // Запоминаем боевой сервер — по нему потом меряем пинг «до игры»
        if (enough && ip != lastRecordedIp) {
            lastRecordedIp = ip
            GameServers.record(this, ip, battle.conn.dstPort)
        }
        if (warn && ip != lastAlertIp && enough) {
            lastAlertIp = ip
            try {
                getSystemService(Vibrator::class.java)
                    ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 150, 400, 150, 400), -1))
            } catch (_: Exception) {
            }
        }
    }

    private fun stopMonitor() {
        MatchTracker.finish(this)
        handler.removeCallbacks(ticker)
        isRunning = false
        ConnTracker.battleOverride = null
        ShizukuSource.unbind()
        WifiBoost.release()
        pollThread?.interrupt()
        pollThread = null
        pingThread?.interrupt()
        pingThread = null
        overlay?.hide()
        overlay = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopMonitor()
        super.onDestroy()
    }
}
