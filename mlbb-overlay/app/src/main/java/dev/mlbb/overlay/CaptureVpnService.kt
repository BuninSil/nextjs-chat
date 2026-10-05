package dev.mlbb.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import java.net.Inet4Address

class CaptureVpnService : VpnService() {

    companion object {
        const val TAG = "CaptureVpn"
        const val GAME_PACKAGE = "com.mobile.legends"
        const val ACTION_START = "dev.mlbb.overlay.START"
        const val ACTION_STOP = "dev.mlbb.overlay.STOP"
        private const val CHANNEL_ID = "capture"
        private const val NOTIF_ID = 1

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var lastError: String? = null

        @Volatile
        var pinger: Pinger? = null
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, CaptureVpnService::class.java).setAction(ACTION_START)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CaptureVpnService::class.java).setAction(ACTION_STOP))
        }
    }

    private var tunPfd: ParcelFileDescriptor? = null
    private var captureThread: Thread? = null
    private var pingThread: Thread? = null
    private var overlay: OverlayController? = null
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            refreshOverlay()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (!isRunning) startCapture()
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
            this, 1, Intent(this, CaptureVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("MLBB Server Overlay")
            .setContentText("Слежу за соединениями Mobile Legends")
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

    /** DNS текущей сети (IPv4), иначе публичные. Запросы игры к DNS тоже идут через zdtun. */
    private fun dnsServers(): List<String> {
        val cm = getSystemService(ConnectivityManager::class.java)
        val lp = cm.activeNetwork?.let { cm.getLinkProperties(it) }
        val sys = lp?.dnsServers?.filterIsInstance<Inet4Address>()?.mapNotNull { it.hostAddress }.orEmpty()
        return sys.ifEmpty { listOf("8.8.8.8", "77.88.8.8") }
    }

    private fun startCapture() {
        lastError = null
        val builder = Builder()
            .setSession("MLBB Server Overlay")
            .setMtu(1500)
            .addAddress("10.215.173.1", 30)
            .addRoute("0.0.0.0", 0)
            .setBlocking(true)
        // IPv6 намеренно не маршрутизируем: без адреса/маршрута v6 Android блокирует
        // его для игры, и она сразу откатывается на IPv4, который мы видим целиком.
        dnsServers().forEach { builder.addDnsServer(it) }
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

        try {
            builder.addAllowedApplication(GAME_PACKAGE)
        } catch (e: Exception) {
            fail("Mobile Legends ($GAME_PACKAGE) не установлена")
            return
        }

        val pfd = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e)
            null
        }
        if (pfd == null) {
            fail("Не удалось поднять VPN (нет разрешения?)")
            return
        }

        tunPfd = pfd
        isRunning = true
        ConnTracker.newSession()

        captureThread = Thread({
            val rv = Native.run(pfd.fd, this)
            Log.i(TAG, "native loop exited: $rv")
            if (rv != 0 && isRunning) handler.post {
                fail("Цикл пересылки упал (код $rv)")
            }
        }, "zdtun").also { it.start() }

        val p = Pinger()
        pinger = p
        pingThread = Thread({
            while (isRunning) {
                ConnTracker.battleServer()?.let { p.probe(it.conn.dstIp) }
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "pinger").also { it.start() }

        GeoDb.load(this)
        if (Settings.canDrawOverlays(this)) {
            overlay = OverlayController(this).also { it.show() }
        }
        handler.post(ticker)
    }

    private fun fail(msg: String) {
        Log.e(TAG, msg)
        lastError = msg
        stopCapture()
        stopSelf()
    }

    private fun refreshOverlay() {
        val ov = overlay ?: return
        val battle = ConnTracker.battleServer()
        if (battle == null) {
            ov.update("MLBB: нет активного UDP", OverlayController.COLOR_UNKNOWN)
            return
        }
        val ip = battle.conn.dstIp
        val geo = GeoDb.lookup(ip)
        val ping = pinger?.last?.takeIf { it.ip == ip }
        val pingText = when {
            ping == null -> "… ms"
            ping.ms < 0 -> "— ms"
            else -> "${ping.ms} ms (${ping.method})"
        }
        val place = when {
            geo == null && !GeoDb.isLoaded -> "🏳 нет базы DB-IP"
            geo == null -> "🏳 неизвестно"
            else -> "${GeoDb.flag(geo.countryCode)} ${geo.city.ifEmpty { geo.country }}"
        }
        val color = when {
            geo == null -> OverlayController.COLOR_UNKNOWN
            geo.countryCode.equals("RU", true) -> OverlayController.COLOR_RUSSIA
            else -> OverlayController.COLOR_FOREIGN
        }
        ov.update("$place · $pingText\n$ip:${battle.conn.dstPort} · ${battle.pktsLast10s} pkt/10s", color)
    }

    /** Вызывается из нативного потока раз в секунду для каждого изменившегося соединения. */
    @Suppress("unused")
    fun onConnUpdate(
        id: Int, proto: Int, srcPort: Int, dstIp: String, dstPort: Int,
        bytesOut: Long, bytesIn: Long, pktsOut: Long, pktsIn: Long,
        firstMs: Long, lastMs: Long, hsRttMs: Int, closed: Boolean,
    ) {
        ConnTracker.update(
            id, proto, srcPort, dstIp, dstPort, bytesOut, bytesIn,
            pktsOut, pktsIn, firstMs, lastMs, hsRttMs, closed
        )
    }

    private fun stopCapture() {
        handler.removeCallbacks(ticker)
        val wasRunning = isRunning
        isRunning = false
        if (wasRunning) Native.stop()
        captureThread?.join(3000)
        captureThread = null
        pingThread?.interrupt()
        pingThread = null
        pinger = null
        try {
            tunPfd?.close()
        } catch (_: Exception) {
        }
        tunPfd = null
        overlay?.hide()
        overlay = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        stopCapture()
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
