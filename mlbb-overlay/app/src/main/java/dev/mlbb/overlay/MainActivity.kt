package dev.mlbb.overlay

import android.Manifest
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusLine: TextView
    private lateinit var serverFlag: TextView
    private lateinit var serverName: TextView
    private lateinit var serverSub: TextView
    private lateinit var serverPing: TextView
    private lateinit var battleCard: LinearLayout
    private lateinit var battleName: TextView
    private lateinit var battleSub: TextView
    private lateinit var battlePing: TextView
    private lateinit var playButton: TextView
    private lateinit var stopButton: TextView
    private lateinit var infoLine: TextView
    private lateinit var wifiCard: LinearLayout
    private lateinit var wifiLine: TextView
    private lateinit var wifiAdvice: TextView
    @Volatile private var wifiInfo: WifiBoost.Info? = null
    @Volatile private var wifiThread: Thread? = null

    private var shownError: String? = null
    private var afterVpnPermission: (() -> Unit)? = null
    @Volatile private var busy = false

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val next = afterVpnPermission
        afterVpnPermission = null
        if (it.resultCode == RESULT_OK) next?.invoke()
        else showInfo("Нужно разрешение", "Без разрешения на VPN приложение не может подключиться. Нажми ИГРАТЬ и согласись.")
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { play() }

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.load(this)
        Subscription.load(this)
        window.decorView.setBackgroundColor(Ui.BG)
        supportActionBar?.hide()
        setContentView(buildUi())
        Thread { GeoDb.load(applicationContext) }.start()
        if (savedInstanceState == null && AppSettings.autoCheckUpdates) checkUpdate(manual = false)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
        // Замер Wi-Fi (пинг до роутера) — в фоне раз в 5 секунд
        wifiThread = Thread {
            while (wifiThread === Thread.currentThread()) {
                wifiInfo = try { WifiBoost.info(applicationContext) } catch (_: Exception) { null }
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
            }
        }.also { it.start() }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
        wifiThread?.interrupt()
        wifiThread = null
    }

    // ------------------------------- UI -------------------------------

    private fun buildUi(): ScrollView {
        val d = { v: Float -> Ui.dp(this, v) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16f), d(20f), d(16f), d(16f))
        }

        // Заголовок
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.text(this, "MLBB Server", 22f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(Ui.button(this, " ⚙ ") { showSettings() })
        root.addView(top)
        root.addView(Ui.space(this, 14f))

        // Карточка статуса и сервера
        val status = Ui.card(this)
        statusLine = Ui.text(this, "", 15f, bold = true)
        status.addView(statusLine)
        val srv = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(10f), 0, 0) }
        serverFlag = Ui.text(this, "🌐", 28f)
        srv.addView(serverFlag)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(12f), 0, d(8f), 0) }
        serverName = Ui.text(this, "", 16f, bold = true)
        serverSub = Ui.text(this, "", 12f, Ui.MUTED)
        col.addView(serverName)
        col.addView(serverSub)
        srv.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        serverPing = Ui.text(this, "", 16f, Ui.GREEN, bold = true)
        srv.addView(serverPing)
        status.addView(srv)
        root.addView(status)

        // Кнопка ИГРАТЬ
        playButton = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF43D17A.toInt(), Ui.GREEN_DARK)
            ).apply { shape = GradientDrawable.OVAL }
            elevation = d(8f).toFloat()
            setOnClickListener { play() }
        }
        val playWrap = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, d(22f), 0, d(18f)) }
        playWrap.addView(playButton, LinearLayout.LayoutParams(d(200f), d(200f)))
        root.addView(playWrap)

        stopButton = Ui.button(this, "■  Отключить VPN") { stopAll() }.apply { gravity = Gravity.CENTER }
        root.addView(stopButton)
        root.addView(Ui.space(this, 12f))

        // Wi-Fi: диапазон, сигнал, роутер, подсказки
        wifiCard = Ui.card(this)
        wifiLine = Ui.text(this, "", 14f)
        wifiAdvice = Ui.text(this, "", 12f, Ui.YELLOW)
        wifiCard.addView(wifiLine)
        wifiCard.addView(wifiAdvice)
        root.addView(wifiCard)
        root.addView(Ui.space(this, 12f))

        // Боевой сервер
        battleCard = Ui.card(this)
        battleCard.addView(Ui.text(this, "Боевой сервер в последнем матче", 12f, Ui.MUTED))
        val b = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(8f), 0, 0) }
        val bc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        battleName = Ui.text(this, "", 16f, bold = true)
        battleSub = Ui.text(this, "", 12f, Ui.MUTED)
        bc.addView(battleName)
        bc.addView(battleSub)
        b.addView(bc, LinearLayout.LayoutParams(0, -2, 1f))
        battlePing = Ui.text(this, "", 16f, Ui.GREEN, bold = true)
        b.addView(battlePing)
        battleCard.addView(b)
        root.addView(battleCard)
        root.addView(Ui.space(this, 12f))

        // Сетка кнопок
        fun row(a: TextView, c: TextView) = LinearLayout(this).apply {
            addView(a, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = d(5f) })
            addView(c, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d(5f) })
        }
        root.addView(row(
            Ui.button(this, "🌐  Серверы") { startActivity(Intent(this, ServersActivity::class.java)) },
            Ui.button(this, "🔗  Подписка") { showSubscription() },
        ))
        root.addView(Ui.space(this, 10f))
        root.addView(row(
            Ui.button(this, "📋  Лог") { startActivity(Intent(this, LogActivity::class.java)) },
            Ui.button(this, "⚙  Настройки") { showSettings() },
        ))

        infoLine = Ui.text(this, "", 12f, Ui.MUTED).apply { setPadding(0, d(14f), 0, 0) }
        root.addView(infoLine)
        root.addView(Ui.text(this, "Автор: ${BuildConfig.AUTHOR} · версия ${BuildConfig.VERSION_NAME}", 11f, 0xFF5F6368.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(0, d(16f), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))

        return ScrollView(this).apply { addView(root) }
    }

    private fun nodeByTag(tag: String) = Subscription.nodes.firstOrNull { it.tag == tag }

    private fun anyRunning() = BoxVpnService.isRunning || CaptureVpnService.isRunning || MonitorService.isRunning

    private fun refresh() {
        val running = anyRunning()
        val err = BoxVpnService.lastError ?: CaptureVpnService.lastError ?: MonitorService.lastError

        statusLine.text = when {
            BoxVpnService.isStarting -> "◌  Подключаюсь…"
            busy -> "◌  Подбираю сервер…"
            running && AppSettings.mode == AppSettings.MODE_BOX -> "●  VPN подключён"
            running -> "●  Работает"
            err != null -> "✖  Не подключилось"
            else -> "○  Отключено"
        }
        statusLine.setTextColor(if (running) Ui.GREEN else if (err != null) Ui.RED else Ui.MUTED)

        when (AppSettings.mode) {
            AppSettings.MODE_BOX -> {
                val n = nodeByTag(AppSettings.selectedTag) ?: Subscription.nodes.firstOrNull()
                if (n == null) {
                    serverFlag.text = "🔗"
                    serverName.text = "Нет подписки"
                    serverSub.text = "Нажми «Подписка» и вставь ссылку"
                    serverPing.text = ""
                } else {
                    val r = ServerTester.results[n.tag]
                    serverFlag.text = Ui.flagFor(n)
                    serverName.text = Ui.cleanName(n)
                    serverSub.text = "${n.type}" + (if (AppSettings.autoSelect) " · автовыбор" else "") +
                        " · ${Subscription.nodes.size} серверов"
                    serverPing.text = r?.let { "${it.medianMs} ms" } ?: ""
                    serverPing.setTextColor(Ui.pingColor(r?.medianMs))
                }
            }
            AppSettings.MODE_DIRECT -> {
                serverFlag.text = "📶"; serverName.text = "Без VPN"; serverSub.text = "игра напрямую"; serverPing.text = ""
            }
            else -> {
                serverFlag.text = "🔌"; serverName.text = "Внешний VPN-клиент"
                serverSub.text = "через Clash API / Shizuku"; serverPing.text = ""
            }
        }

        playButton.text = if (running) "ИГРАТЬ\n" else "ИГРАТЬ"
        playButton.append(if (running) "запустить MLBB" else "")
        stopButton.visibility = if (running) android.view.View.VISIBLE else android.view.View.GONE

        val wi = wifiInfo
        if (wi == null) {
            wifiCard.visibility = android.view.View.GONE
        } else {
            wifiCard.visibility = android.view.View.VISIBLE
            val router = wi.routerPingMs?.let { " · роутер $it ms" } ?: ""
            val boost = if (WifiBoost.active) "  ⚡ игровой режим" else ""
            wifiLine.text = "📶 Wi-Fi ${wi.band} · сигнал ${WifiBoost.signalText(wi.rssi)} (${wi.rssi} dBm)$router$boost"
            val adv = WifiBoost.advice(wi)
            wifiAdvice.text = adv.joinToString("\n") { "• $it" }
            wifiAdvice.visibility = if (adv.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }

        val battle = ConnTracker.battleServer()
        if (battle == null) {
            battleCard.visibility = android.view.View.GONE
        } else {
            battleCard.visibility = android.view.View.VISIBLE
            val geo = GeoDb.lookup(battle.conn.dstIp)
            val ping = (MonitorService.lastPing ?: CaptureVpnService.pinger?.last)?.takeIf { it.ip == battle.conn.dstIp }
            battleName.text = (geo?.let { "${GeoDb.flag(it.countryCode)} ${it.city.ifEmpty { it.country }}" } ?: "🏳 неизвестно")
            battleSub.text = "${battle.conn.dstIp}:${battle.conn.dstPort} · UDP"
            battlePing.text = ping?.takeIf { it.ms >= 0 }?.let { "${it.ms} ms" } ?: ""
            battlePing.setTextColor(Ui.pingColor(ping?.ms))
        }

        if (!UpdateFlow.busy) {
            infoLine.text = if (GeoDb.isLoaded) "" else "Нет базы стран DB-IP — Настройки → «Скачать базу»"
        }

        if (err != null && err != shownError) {
            shownError = err
            showInfo("Не получилось", err)
        }
    }

    private fun showInfo(title: String, msg: String, action: Pair<String, () -> Unit>? = null) {
        if (isFinishing) return
        val b = AlertDialog.Builder(this).setTitle(title).setMessage(msg)
        if (action != null) {
            b.setPositiveButton(action.first) { _, _ -> action.second() }
            b.setNegativeButton("Отмена", null)
        } else {
            b.setPositiveButton("Понятно", null)
        }
        b.show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ------------------------------ ИГРАТЬ ------------------------------

    private fun isGameInstalled() = try {
        packageManager.getPackageInfo(CaptureVpnService.GAME_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun launchGame() {
        val i = packageManager.getLaunchIntentForPackage(CaptureVpnService.GAME_PACKAGE)
        if (i == null) toast("Не получилось запустить Mobile Legends") else startActivity(i)
    }

    private fun clearErrors() {
        BoxVpnService.lastError = null
        CaptureVpnService.lastError = null
        MonitorService.lastError = null
        shownError = null
    }

    private fun play() {
        if (busy) return
        if (!isGameInstalled()) {
            showInfo("Игра не найдена", "Mobile Legends (${CaptureVpnService.GAME_PACKAGE}) не установлена.")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("notif_asked", false)
        ) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notif_asked", true).apply()
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            showInfo(
                "Разреши плашку поверх игры",
                "Найди в списке «MLBB Server» и включи «Поверх других окон». Потом вернись и нажми ИГРАТЬ.",
                "Открыть настройки" to {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            )
            return
        }
        if (anyRunning()) {
            launchGame()
            return
        }
        clearErrors()
        when (AppSettings.mode) {
            AppSettings.MODE_BOX -> {
                if (Subscription.nodes.isEmpty()) {
                    showSubscription()
                    return
                }
                withVpnPermission { playBox() }
            }
            AppSettings.MODE_DIRECT -> withVpnPermission {
                CaptureVpnService.start(this)
                handler.postDelayed({ if (CaptureVpnService.isRunning) launchGame() }, 1500)
            }
            else -> startMonitorFlow { launchGame() }
        }
    }

    private fun withVpnPermission(next: () -> Unit) {
        val prep = VpnService.prepare(this)
        if (prep == null) next() else {
            afterVpnPermission = next
            vpnPermission.launch(prep)
        }
    }

    private fun playBox() {
        busy = true
        BoxVpnService.start(this)
        val progress = AlertDialog.Builder(this).setTitle("Подключаю VPN").setMessage("Секунду…").setCancelable(false).show()
        Thread {
            val deadline = System.currentTimeMillis() + 20_000
            while ((BoxVpnService.isStarting || !BoxVpnService.isRunning) && BoxVpnService.lastError == null &&
                System.currentTimeMillis() < deadline
            ) Thread.sleep(200)

            if (!BoxVpnService.isRunning) {
                busy = false
                runOnUiThread {
                    progress.dismiss()
                    if (BoxVpnService.lastError == null) BoxVpnService.lastError = "VPN не подключился за 20 секунд"
                }
                return@Thread
            }

            if (AppSettings.autoSelect) {
                runOnUiThread { progress.setTitle("Ищу самый быстрый сервер") }
                val best = ServerTester.testAll { done, total ->
                    runOnUiThread { progress.setMessage("Проверено $done из $total…") }
                }
                if (best != null) ServerTester.use(this, best)
            }
            busy = false
            runOnUiThread {
                progress.dismiss()
                val n = nodeByTag(AppSettings.selectedTag)
                val r = n?.let { ServerTester.results[it.tag] }
                if (n != null) toast("Сервер: ${Ui.cleanName(n)}" + (r?.let { " · ${it.medianMs} ms" } ?: ""))
                launchGame()
            }
        }.start()
    }

    private fun stopAll() {
        BoxVpnService.stop(this)
        CaptureVpnService.stop(this)
        MonitorService.stop(this)
    }

    // -------------------- Режим «внешний VPN-клиент» --------------------

    private fun startMonitorFlow(onStarted: () -> Unit) {
        if (ShizukuSource.granted()) {
            MonitorService.start(this)
            onStarted()
            return
        }
        val progress = AlertDialog.Builder(this).setTitle("Подключаюсь к VPN-клиенту")
            .setMessage("Секунду…").setCancelable(false).show()
        Thread {
            val found = ClashApi.find(AppSettings.apiPort, AppSettings.apiSecret) { msg ->
                runOnUiThread { progress.setMessage(msg) }
            }
            runOnUiThread {
                progress.dismiss()
                when (found?.second) {
                    ClashApi.Probe.OK -> {
                        AppSettings.apiPort = found.first
                        AppSettings.save(this)
                        MonitorService.start(this)
                        onStarted()
                    }
                    ClashApi.Probe.NEED_SECRET -> askSecret(found.first, onStarted)
                    else -> showInfo(
                        "VPN-клиент не отдаёт соединения",
                        "Режим «Внешний VPN-клиент» работает только с клиентами, у которых открыт Clash API " +
                            "(FlClash, Clash Meta). Проще выбрать в настройках режим «Встроенный VPN».\n\n" +
                            "Диагностика:\n" + ClashApi.lastReport
                    )
                }
            }
        }.start()
    }

    private fun askSecret(port: Int, onStarted: () -> Unit) {
        val input = EditText(this).apply { hint = "секрет (secret)" }
        AlertDialog.Builder(this)
            .setTitle("Нужен пароль от Clash API")
            .setMessage("VPN-клиент на порту $port просит секрет — он в настройках клиента рядом с Clash API.")
            .setView(input)
            .setPositiveButton("Готово") { _, _ ->
                AppSettings.apiPort = port
                AppSettings.apiSecret = input.text.toString().trim()
                AppSettings.save(this)
                startMonitorFlow(onStarted)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ----------------------------- Подписка -----------------------------

    private fun showSubscription() {
        val d = { v: Float -> Ui.dp(this, v) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(20f), d(8f), d(20f), 0)
        }
        box.addView(TextView(this).apply {
            text = "Ссылка подписки — та же, что в Karing, Hiddify или v2rayNG."
        })
        val input = EditText(this).apply {
            hint = "https://…"
            setText(AppSettings.subUrl)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addView(input)
        box.addView(TextView(this).apply {
            text = "📋  Вставить из буфера"
            setPadding(0, d(10f), 0, d(10f))
            setTextColor(Ui.GREEN)
            setOnClickListener {
                val clip = getSystemService(ClipboardManager::class.java).primaryClip
                val t = clip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()?.trim()
                if (t.isNullOrEmpty()) toast("В буфере пусто") else input.setText(t)
            }
        })
        Subscription.info?.let { info ->
            val gb = (info.download + info.upload) / 1_073_741_824.0
            val exp = if (info.expireSec > 0) " · до " + java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.US)
                .format(java.util.Date(info.expireSec * 1000)) else ""
            box.addView(TextView(this).apply {
                text = String.format(java.util.Locale.US, "Серверов: %d · израсходовано %.1f ГБ%s", Subscription.nodes.size, gb, exp)
            })
        }

        AlertDialog.Builder(this)
            .setTitle("Подписка")
            .setView(box)
            .setPositiveButton("Сохранить и обновить") { _, _ -> updateSubscription(input.text.toString().trim()) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun updateSubscription(url: String) {
        if (!url.startsWith("http")) {
            toast("Это не похоже на ссылку подписки")
            return
        }
        AppSettings.subUrl = url
        AppSettings.save(this)
        val progress = AlertDialog.Builder(this).setTitle("Обновляю подписку").setMessage("Секунду…")
            .setCancelable(false).show()
        Thread {
            val msg = try {
                val n = Subscription.update(applicationContext, url)
                "Готово: серверов $n" + if (BoxVpnService.isRunning) ". Переподключи VPN, чтобы применить." else ""
            } catch (e: Exception) {
                "Не получилось: ${e.message}"
            }
            runOnUiThread {
                progress.dismiss()
                toast(msg)
            }
        }.start()
    }

    // ----------------------------- Настройки -----------------------------

    private fun showSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun checkUpdate(manual: Boolean) {
        UpdateFlow.check(this, manual) { msg -> infoLine.text = msg }
    }
}
