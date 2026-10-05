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
    private lateinit var launchSwitch: androidx.appcompat.widget.SwitchCompat
    private lateinit var launchRow: LinearLayout
    private lateinit var infoLine: TextView
    private lateinit var setupCard: LinearLayout
    private lateinit var setupTitle: TextView
    private lateinit var setupText: TextView
    private lateinit var setupButton: TextView
    private lateinit var playHint: TextView
    @Volatile private var dbDownloading = false
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
        if (savedInstanceState == null) {
            CrashReport.take(this)?.let { showCrash(it) }
            if (AppSettings.autoCheckUpdates) checkUpdate(manual = false)
            if (AppSettings.profile.isEmpty()) askProfile()
        }
    }

    /** Первый запуск: для игры или просто VPN. Потом меняется в Настройках. */
    private fun askProfile() {
        AlertDialog.Builder(this)
            .setTitle("Для чего тебе приложение?")
            .setMessage(
                "🎮 Для Mobile Legends — VPN плюс плашка с сервером матча поверх игры, российские серверы " +
                    "в приоритете, запуск игры одной кнопкой.\n\n" +
                    "🌐 Просто VPN (Fast VPN) — только быстрый VPN по твоей подписке, ничего про игру.\n\n" +
                    "Поменять можно потом в Настройках."
            )
            .setCancelable(false)
            .setPositiveButton("🎮 Для MLBB") { _, _ -> chooseProfile(AppSettings.PROFILE_GAME) }
            .setNegativeButton("🌐 Просто VPN") { _, _ -> chooseProfile(AppSettings.PROFILE_SIMPLE) }
            .show()
    }

    private fun chooseProfile(value: String) {
        AppSettings.setProfile(this, value)
        recreate()
    }

    /** Прошлый запуск упал — показываем причину, чтобы её можно было скинуть разработчику. */
    private fun showCrash(text: String) {
        AlertDialog.Builder(this)
            .setTitle("Прошлый запуск упал")
            .setMessage("Скопируй и скинь разработчику — по этому тексту видно причину.\n\n" + text.take(3000))
            .setPositiveButton("Скопировать") { _, _ ->
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
                toast("Скопировано")
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    /** Для какого режима построен экран — если в Настройках поменяли, перестраиваем. */
    private var builtProfile = ""

    override fun onResume() {
        super.onResume()
        AppSettings.load(this)
        if (AppSettings.profile != builtProfile) {
            recreate()
            return
        }
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
        builtProfile = AppSettings.profile
        val d = { v: Float -> Ui.dp(this, v) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16f), d(20f), d(16f), d(16f))
        }

        // Заголовок
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(Ui.text(this, if (AppSettings.simple) "Fast VPN" else "MLBB Server", 22f, bold = true))
        titleCol.addView(Ui.text(this, "⚡ Быстрее нас — только свет", 12f, Ui.GREEN))
        top.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(Ui.button(this, " ⚙ ") { showSettings() })
        root.addView(top)
        root.addView(Ui.space(this, 14f))

        // Мастер первого запуска: что сделать по шагам, пока не всё готово
        setupCard = Ui.card(this).apply {
            background = Ui.rounded(0xFF182A20.toInt(), d(18f).toFloat()).apply { setStroke(d(2f), Ui.GREEN) }
        }
        setupTitle = Ui.text(this, "", 16f, bold = true)
        setupText = Ui.text(this, "", 13f, 0xFFC9CCD1.toInt()).apply { setPadding(0, d(6f), 0, d(12f)) }
        setupButton = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            background = Ui.rounded(0xFF2FB565.toInt(), d(14f).toFloat())
            setPadding(0, d(12f), 0, d(12f))
        }
        setupCard.addView(setupTitle)
        setupCard.addView(setupText)
        setupCard.addView(setupButton, LinearLayout.LayoutParams(-1, -2))
        root.addView(setupCard)
        root.addView(Ui.space(this, 12f))

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
        playHint = Ui.text(this, "", 13f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(d(16f), 0, d(16f), d(14f))
        }
        root.addView(playHint, LinearLayout.LayoutParams(-1, -2))

        // Под кнопкой: игровой режим, автозапуск игры и ссылка «Запустить MLBB»
        fun greenSwitch(v: Boolean, onChange: (Boolean) -> Unit) = androidx.appcompat.widget.SwitchCompat(this).apply {
            isChecked = v
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = android.content.res.ColorStateList(states, intArrayOf(0xFFFFFFFF.toInt(), 0xFFB0B4BA.toInt()))
            trackTintList = android.content.res.ColorStateList(states, intArrayOf(Ui.GREEN, 0xFF3A3F46.toInt()))
            setOnCheckedChangeListener { _, x -> onChange(x) }
        }
        val gameRow = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, d(6f)) }
        gameRow.addView(Ui.text(this, "🎮  Игровой режим", 15f, bold = true).apply { setPadding(0, 0, d(10f), 0) })
        gameRow.addView(greenSwitch(AppSettings.gameMode) { v ->
            AppSettings.gameMode = v
            AppSettings.save(this@MainActivity)
            if (anyRunning()) toast("Применится после переподключения")
        })
        // В простом режиме (просто VPN) про игру ничего не показываем
        if (!AppSettings.simple) {
            root.addView(gameRow, LinearLayout.LayoutParams(-1, -2))
            root.addView(Ui.text(this, "плашка с сервером матча поверх игры, российские серверы в приоритете", 12f, Ui.MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(d(24f), 0, d(24f), d(10f))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        val launchRow = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, d(4f)) }
        this.launchRow = launchRow
        launchRow.addView(Ui.text(this, "Запускать MLBB после подключения", 14f, Ui.MUTED).apply {
            setPadding(0, 0, d(10f), 0)
        })
        launchSwitch = androidx.appcompat.widget.SwitchCompat(this).apply {
            isChecked = AppSettings.autoLaunch
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = android.content.res.ColorStateList(states, intArrayOf(0xFFFFFFFF.toInt(), 0xFFB0B4BA.toInt()))
            trackTintList = android.content.res.ColorStateList(states, intArrayOf(Ui.GREEN, 0xFF3A3F46.toInt()))
            setOnCheckedChangeListener { _, v ->
                AppSettings.autoLaunch = v
                AppSettings.save(this@MainActivity)
            }
        }
        launchRow.addView(launchSwitch)
        root.addView(launchRow, LinearLayout.LayoutParams(-1, -2))
        stopButton = Ui.text(this, "▶  Запустить MLBB", 15f, Ui.GREEN, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, d(8f), 0, d(4f))
            setOnClickListener { launchGame() }
        }
        root.addView(stopButton, LinearLayout.LayoutParams(-1, -2))
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
        fun row(a: android.view.View, c: android.view.View) = LinearLayout(this).apply {
            addView(a, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = d(5f) })
            addView(c, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d(5f) })
        }
        root.addView(row(
            Ui.tile(this, "🌐  Серверы", "список, пинг, выбрать вручную") { startActivity(Intent(this, ServersActivity::class.java)) },
            Ui.tile(this, "🔗  Подписка", "ссылка твоего VPN") { showSubscription() },
        ))
        root.addView(Ui.space(this, 10f))
        if (AppSettings.simple) {
            root.addView(row(
                Ui.tile(this, "⚡  Тест скорости", "пинг, загрузка и отдача") { speedTest() },
                Ui.tile(this, "⚙  Настройки", "автовыбор, Wi-Fi, обновления") { showSettings() },
            ))
        } else {
            root.addView(row(
                Ui.tile(this, "📋  Лог", "куда подключалась игра") { startActivity(Intent(this, LogActivity::class.java)) },
                Ui.tile(this, "⚙  Настройки", "режимы, база стран, обновления") { showSettings() },
            ))
            root.addView(Ui.space(this, 10f))
            root.addView(Ui.tile(this, "⚡  Тест скорости", "пинг, загрузка и отдача — через VPN, если он включён") { speedTest() },
                LinearLayout.LayoutParams(-1, -2))
        }

        infoLine = Ui.text(this, "", 12f, Ui.MUTED).apply { setPadding(0, d(14f), 0, 0) }
        root.addView(infoLine)
        root.addView(Ui.text(this, "Автор: ${BuildConfig.AUTHOR} · версия ${BuildConfig.VERSION_NAME}", 11f, 0xFF5F6368.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(0, d(16f), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))

        return ScrollView(this).apply { addView(root) }
    }

    private fun nodeByTag(tag: String) = Subscription.usable(this).firstOrNull { it.tag == tag }

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
                val n = nodeByTag(AppSettings.selectedTag) ?: Subscription.usable(this).firstOrNull()
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
                        " · ${Subscription.usable(this).size} серверов"
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

        val label = when {
            running -> "ОТКЛЮЧИТЬ"
            AppSettings.gameMode && AppSettings.autoLaunch -> "ИГРАТЬ"
            else -> "ПОДКЛЮЧИТЬ"
        }
        if (playButton.text != label) {
            playButton.text = label
            playButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (running) 24f else 26f)
            // Подключено — кнопка красная (отключить), иначе зелёная
            val colors = if (running) intArrayOf(0xFFE5534B.toInt(), 0xFFA8322C.toInt())
            else intArrayOf(0xFF43D17A.toInt(), Ui.GREEN_DARK)
            playButton.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
                .apply { shape = GradientDrawable.OVAL }
        }
        stopButton.visibility = if (running && AppSettings.gameMode) android.view.View.VISIBLE else android.view.View.GONE
        launchRow.visibility = if (AppSettings.gameMode) android.view.View.VISIBLE else android.view.View.GONE

        updateSetup()
        playHint.text = when {
            BoxVpnService.isStarting || busy -> "Подключаюсь, подбираю сервер…"
            running -> "VPN работает. Нажми кнопку, чтобы отключить."
            AppSettings.mode != AppSettings.MODE_BOX -> "Нажми — включу слежение за сервером игры" +
                if (AppSettings.autoLaunch) " и запущу MLBB" else ""
            AppSettings.gameMode && AppSettings.autoLaunch -> "Нажми — подключу лучший сервер для игры и запущу MLBB"
            AppSettings.gameMode -> "Нажми — подключу лучший сервер для игры"
            else -> "Нажми — подключу самый быстрый VPN-сервер"
        }

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

        val battle = if (AppSettings.gameMode) ConnTracker.battleServer() else null
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
            // Про базу стран теперь говорит мастер первого запуска
            if (!dbDownloading) infoLine.text = ""
        }

        if (err != null && err != shownError) {
            shownError = err
            showInfo("Не получилось", err)
        }
    }

    /** Мастер первого запуска: показывает первый невыполненный шаг с кнопкой «сделать». */
    private fun updateSetup() {
        val needSub = AppSettings.mode == AppSettings.MODE_BOX && Subscription.usable(this).isEmpty()
        // Простой режим: нужна только подписка; база стран и плашка — для игры
        val needDb = !AppSettings.simple && !GeoDb.isLoaded
        val needOverlay = !AppSettings.simple && !Settings.canDrawOverlays(this)
        val steps = if (AppSettings.simple) listOf(needSub) else listOf(needSub, needDb, needOverlay)
        val total = steps.size
        val done = steps.count { !it }
        if (done == total) {
            setupCard.visibility = android.view.View.GONE
            return
        }
        setupCard.visibility = android.view.View.VISIBLE
        val n = done + 1
        when {
            needSub -> {
                setupTitle.text = if (total > 1) "Шаг $n из $total: добавь подписку" else "Добавь подписку"
                setupText.text = "Скопируй ссылку подписки своего VPN (ту же, что в Karing / Hiddify / v2rayNG) и вставь сюда."
                setupButton.text = "🔗  Вставить ссылку"
                setupButton.setOnClickListener { showSubscription() }
            }
            needDb -> {
                setupTitle.text = "Шаг $n из $total: скачай базу стран"
                setupText.text = "Нужна, чтобы показывать страну и город сервера игры. Один раз, около 100 МБ."
                setupButton.text = if (dbDownloading) infoLine.text.ifEmpty { "Скачиваю…" } else "⬇  Скачать базу"
                setupButton.setOnClickListener { downloadDbFromSetup() }
            }
            else -> {
                setupTitle.text = "Шаг $n из $total: разреши плашку поверх игры"
                setupText.text = "В открывшемся списке найди «MLBB Server» и включи «Поверх других окон», потом вернись сюда."
                setupButton.text = "Открыть разрешение"
                setupButton.setOnClickListener {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        }
    }

    private fun downloadDbFromSetup() {
        if (dbDownloading) return
        dbDownloading = true
        Thread {
            try {
                GeoDb.download(applicationContext) { msg -> runOnUiThread { infoLine.text = msg } }
            } catch (e: Exception) {
                runOnUiThread { showInfo("База не скачалась", "${e.message}\n\nМожно попробовать ещё раз или импортировать файл в Настройках.") }
            } finally {
                dbDownloading = false
                runOnUiThread { infoLine.text = "" }
            }
        }.start()
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
        if (anyRunning()) {
            stopAll()
            return
        }
        // Игра и плашка нужны только для игровых функций; просто VPN работает без них
        val forGame = !AppSettings.simple && (AppSettings.gameMode || AppSettings.mode != AppSettings.MODE_BOX)
        if (forGame && !isGameInstalled()) {
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
        if (forGame && !Settings.canDrawOverlays(this)) {
            showInfo(
                "Разреши плашку поверх игры",
                "Найди в списке «MLBB Server» и включи «Поверх других окон». Потом вернись и нажми ИГРАТЬ.",
                "Открыть настройки" to {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            )
            return
        }

        clearErrors()
        when (AppSettings.mode) {
            AppSettings.MODE_BOX -> {
                if (Subscription.usable(this).isEmpty()) {
                    showSubscription()
                    return
                }
                withVpnPermission { playBox() }
            }
            AppSettings.MODE_DIRECT -> withVpnPermission {
                CaptureVpnService.start(this)
                handler.postDelayed({ if (CaptureVpnService.isRunning && AppSettings.autoLaunch) launchGame() }, 1500)
            }
            else -> startMonitorFlow { if (AppSettings.autoLaunch) launchGame() }
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
        val progress = AlertDialog.Builder(this).setTitle("Подключаю VPN").setMessage("Секунду…").setCancelable(false).show()
        Thread {
            // Чужой VPN не пускает мимо себя — прямой пинг до серверов при нём не мерится.
            // Тогда сначала поднимаем свой VPN (Android сам отключит чужой), а мерим после.
            val otherVpn = ServerTester.otherVpnActive(applicationContext)

            // 1. Пинг до всех серверов напрямую — ещё до подключения, это быстро
            var ranked: List<String> = emptyList()
            if (AppSettings.autoSelect && !otherVpn) {
                runOnUiThread { progress.setTitle("Меряю пинг до серверов") }
                ranked = ServerTester.measure(applicationContext) { done, total ->
                    runOnUiThread { progress.setMessage("$done из $total…") }
                }
                ranked.firstOrNull()?.let { ServerTester.use(this, it) }
            }

            // 2. Подключаемся сразу к лучшему
            runOnUiThread {
                progress.setTitle("Подключаю VPN")
                progress.setMessage(if (otherVpn) "Отключаю другой VPN и подключаю свой…" else "Секунду…")
            }
            if (!startBoxAndWait()) {
                busy = false
                runOnUiThread { progress.dismiss() }
                return@Thread
            }

            // Пинг не намерился (мешал чужой VPN или сеть) — меряем теперь, когда свой VPN поднят
            if (AppSettings.autoSelect && ranked.isEmpty()) {
                Thread.sleep(1000)
                runOnUiThread { progress.setTitle("Меряю пинг до серверов"); progress.setMessage("Секунду…") }
                ranked = ServerTester.measure(applicationContext) { done, total ->
                    runOnUiThread { progress.setMessage("$done из $total…") }
                }
            }

            // 3. Проверяем, что через сервер реально ходит трафик; если нет — следующий
            if (ranked.isNotEmpty()) {
                if (AppSettings.gameMode) {
                    // Игре важен пинг: лучший рабочий по пингу до игры
                    runOnUiThread { progress.setTitle("Проверяю сервер"); progress.setMessage("Секунду…") }
                    ServerTester.pickWorking(applicationContext, ranked)
                } else {
                    // Обычный VPN: из быстрых по пингу — самый быстрый по скорости
                    runOnUiThread { progress.setTitle("Ищу самый быстрый по скорости") }
                    ServerTester.pickFastest(applicationContext, ranked) { done, total ->
                        runOnUiThread { progress.setMessage("Сервер $done из $total…") }
                    }
                }
            }
            busy = false
            runOnUiThread {
                progress.dismiss()
                val n = nodeByTag(AppSettings.selectedTag)
                val r = n?.let { ServerTester.results[it.tag] }
                if (n != null) toast("Сервер: ${Ui.cleanName(n)}" + (r?.let { " · ${it.medianMs} ms" } ?: ""))
                if (AppSettings.gameMode && AppSettings.autoLaunch) launchGame()
            }
        }.start()
    }

    /** Запускает встроенный VPN и ждёт подключения (до 20 с). Вызывать не с главного потока. */
    private fun startBoxAndWait(): Boolean {
        BoxVpnService.lastError = null
        BoxVpnService.start(this)
        Thread.sleep(300)
        val deadline = System.currentTimeMillis() + 20_000
        while ((BoxVpnService.isStarting || !BoxVpnService.isRunning) && BoxVpnService.lastError == null &&
            System.currentTimeMillis() < deadline
        ) Thread.sleep(200)
        if (!BoxVpnService.isRunning && BoxVpnService.lastError == null) {
            BoxVpnService.lastError = "VPN не подключился за 20 секунд"
        }
        return BoxVpnService.isRunning
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
            text = "Ссылка подписки — та же, что в Karing, Hiddify или v2rayNG.\n" +
                "Можно вставить и сами серверы: строки vless://, trojan://… или base64-блок."
        })
        val input = EditText(this).apply {
            hint = "https://…"
            setText(AppSettings.subUrl)
            // Многострочное: можно вставить и список серверов
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
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
                text = String.format(java.util.Locale.US, "Серверов: %d · израсходовано %.1f ГБ%s", Subscription.usable(this@MainActivity).size, gb, exp)
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
            // Вставили сами серверы, а не ссылку
            try {
                val n = Subscription.importText(applicationContext, url)
                toast("Готово: серверов $n")
            } catch (e: Exception) {
                showInfo("Не получилось", e.message ?: "")
            }
            return
        }
        AppSettings.subUrl = url
        AppSettings.save(this)
        val progress = AlertDialog.Builder(this).setTitle("Обновляю подписку").setMessage("Секунду…")
            .setCancelable(false).show()
        Thread {
            var error: String? = null
            val msg = try {
                val n = Subscription.update(applicationContext, url)
                "Готово: серверов $n" + if (BoxVpnService.isRunning) ". Переподключи VPN, чтобы применить." else ""
            } catch (e: Exception) {
                error = e.message
                ""
            }
            runOnUiThread {
                progress.dismiss()
                when {
                    error == null -> toast(msg)
                    // Напрямую адрес подписки часто заблокирован — пробуем через свой VPN на старых серверах
                    canUpdateViaOwnVpn() -> withVpnPermission { updateSubscriptionViaVpn(url) }
                    else -> showInfo("Подписка не загрузилась", error!!)
                }
            }
        }.start()
    }

    private fun canUpdateViaOwnVpn() =
        !BoxVpnService.isRunning && !CaptureVpnService.isRunning && !ServerTester.otherVpnActive(this) &&
            AppSettings.mode == AppSettings.MODE_BOX && Subscription.usable(this).isNotEmpty()

    /**
     * Подписка не скачалась напрямую: поднимаем свой VPN на уже сохранённых серверах,
     * качаем подписку через него и отключаемся. Сторонний VPN для обновления не нужен.
     */
    private fun updateSubscriptionViaVpn(url: String) {
        busy = true
        val progress = AlertDialog.Builder(this).setTitle("Обновляю подписку через VPN")
            .setMessage("Напрямую не открылась — подключаюсь к сохранённому серверу…").setCancelable(false).show()
        Thread {
            var error: String? = null
            var n = 0
            if (!startBoxAndWait()) {
                error = BoxVpnService.lastError ?: "VPN не подключился"
            } else {
                // Берём рабочий сервер: замер пинга и проверка, что через него ходит трафик
                runOnUiThread { progress.setMessage("Ищу рабочий сервер…") }
                val ranked = ServerTester.measure(applicationContext) { _, _ -> }
                if (ranked.isNotEmpty()) ServerTester.pickWorking(applicationContext, ranked, 6)
                runOnUiThread { progress.setMessage("Скачиваю подписку…") }
                try {
                    n = Subscription.update(applicationContext, url)
                } catch (e: Exception) {
                    error = e.message
                }
                BoxVpnService.stop(this)
            }
            busy = false
            runOnUiThread {
                progress.dismiss()
                if (error != null) showInfo("Подписка не загрузилась", error!!)
                else toast("Готово: серверов $n (обновлено через VPN)")
            }
        }.start()
    }

    // ----------------------------- Настройки -----------------------------

    // ----------------------------- Тест скорости -----------------------------

    @Volatile private var speedRunning = false

    private fun speedTest() {
        if (speedRunning) return
        speedRunning = true
        val d = { v: Float -> Ui.dp(this, v) }
        val via = if (BoxVpnService.isRunning) "через VPN" else "напрямую"
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.CARD, d(22f).toFloat())
            setPadding(d(22f), d(22f), d(22f), d(18f))
        }
        card.addView(Ui.text(this, "⚡ Тест скорости", 20f, bold = true))
        card.addView(Ui.text(this, "Cloudflare · $via", 13f, Ui.MUTED).apply { setPadding(0, d(4f), 0, d(16f)) })
        fun metric(title: String): TextView {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(6f), 0, d(6f)) }
            row.addView(Ui.text(this, title, 15f, Ui.MUTED), LinearLayout.LayoutParams(0, -2, 1f))
            val v = Ui.text(this, "—", 20f, bold = true)
            row.addView(v)
            card.addView(row)
            return v
        }
        val ping = metric("Пинг")
        val jitter = metric("Разброс")
        val down = metric("Загрузка")
        val up = metric("Отдача")
        val state = Ui.text(this, "Меряю пинг…", 13f, Ui.MUTED).apply { setPadding(0, d(10f), 0, 0) }
        card.addView(state)
        val dlg = android.app.Dialog(this)
        dlg.setContentView(card)
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.88).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.setOnDismissListener { speedRunning = false }
        dlg.show()

        fun mbps(v: Double) = if (v >= 100) "%.0f Мбит/с".format(v) else "%.1f Мбит/с".format(v)
        Thread {
            val p = SpeedTest.ping()
            runOnUiThread {
                ping.text = p?.let { "${it.first} ms" } ?: "✖"
                ping.setTextColor(Ui.pingColor(p?.first))
                jitter.text = p?.let { "${it.second} ms" } ?: "—"
                state.text = "Меряю загрузку…"
            }
            if (!speedRunning) return@Thread
            val dn = SpeedTest.download { v -> runOnUiThread { down.text = mbps(v) } }
            runOnUiThread {
                down.text = dn?.let { mbps(it) } ?: "✖"
                state.text = "Меряю отдачу…"
            }
            if (!speedRunning) return@Thread
            val upv = SpeedTest.upload { v -> runOnUiThread { up.text = mbps(v) } }
            runOnUiThread {
                up.text = upv?.let { mbps(it) } ?: "✖"
            }
            runOnUiThread {
                state.text = if (p == null && dn == null) "Нет доступа к интернету" else "Готово"
                speedRunning = false
            }
        }.start()
    }

    private fun showSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun checkUpdate(manual: Boolean) {
        UpdateFlow.check(this, manual) { msg -> infoLine.text = msg }
    }
}
