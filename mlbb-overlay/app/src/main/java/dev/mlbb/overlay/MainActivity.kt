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
    private lateinit var subWarn: TextView
    private lateinit var serverName: TextView
    private lateinit var serverSub: TextView
    private lateinit var serverPing: TextView
    /** «Твой IP: … · страна» в карточке сервера */
    private lateinit var ipLine: TextView
    private lateinit var battleCard: LinearLayout
    private lateinit var battleName: TextView
    private lateinit var battleSub: TextView
    private lateinit var battlePing: TextView
    private lateinit var playButton: TextView
    private lateinit var ring: ConnectRing
    /** Звёзды на весь экран (анимация «Гиперпрыжок») и белая вспышка поверх всего */
    private var stars: StarField? = null
    /** Анимация «Глобус» (кнопка меньше, поверх Земли) */
    private var globe: GlobeView? = null
    private var globeMode = false
    private lateinit var flashView: android.view.View
    /** Что сейчас нарисовано на кнопке — чтобы не перерисовывать каждую секунду */
    private var playKey = ""
    /** Было ли подключено на прошлом обновлении — для вспышки и вибрации на переходе */
    private var wasRunning: Boolean? = null
    private var pulse: android.animation.ObjectAnimator? = null
    /** Когда нажали «подключить»: анимация подключения идёт минимум [MIN_ANIM_MS], даже если VPN поднялся мгновенно */
    private var connectTapAt = 0L
    /** VPN выключили, а фоновый подбор ещё доделывается — это не «подключение» */
    private var afterStop = false
    private val MIN_ANIM_MS = 1800L
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
    /** Идёт подключение или подбор сервера — общее состояние с плиткой и виджетом */
    private var busy: Boolean
        get() = Connector.busy
        set(v) { Connector.busy = v }
    private val busyText get() = Connector.busyText

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val next = afterVpnPermission
        afterVpnPermission = null
        AppLog.vpn("запрос разрешения на VPN: " + if (it.resultCode == RESULT_OK) "разрешил" else "отказал")
        if (it.resultCode == RESULT_OK) next?.invoke()
        else showInfo("Нужно разрешение", "Без разрешения на VPN приложение не может подключиться. Нажми ИГРАТЬ и согласись.")
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { play() }

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            // Пока подключается — чаще, чтобы вспышка «подключено» была точно в момент
            // Часто — только пока идёт само подключение; фоновый подбор сервера хватает раз в полсекунды
            handler.postDelayed(this, when {
                BoxVpnService.isStarting || connectTapAt > 0 -> 120
                busy -> 500
                else -> 1000
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Тема — до создания экрана, чтобы системные окна были в её цветах
        AppSettings.load(this)
        setTheme(Ui.themeRes())
        super.onCreate(savedInstanceState)
        AppSettings.load(this)
        Subscription.load(this)
        Ui.applyWindow(this)
        supportActionBar?.hide()
        setContentView(buildUi())
        Thread { GeoDb.load(applicationContext) }.start()
        if (savedInstanceState == null) {
            CrashReport.take(this)?.let { showCrash(it) }
            // Только что обновились — показываем, что нового
            val updated = AutoUpdate.takeJustUpdated(this)
            if (updated != null) UpdateFlow.showUpdated(this, updated)
            else if (AppSettings.autoCheckUpdates) checkUpdate(manual = false)
            AutoUpdate.schedule(applicationContext)
            AutoConnect.arm(applicationContext)
            if (AppSettings.profile.isEmpty()) askProfile()
            else handleShortcut(intent)
        }
    }

    /** Первый запуск: Fast VPN или Fast VPN + MLBB. Потом меняется в Настройках. */
    private fun askProfile() {
        val d = { v: Float -> Ui.dp(this, v) }
        val dlg = android.app.Dialog(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.CARD, d(22f).toFloat())
            setPadding(d(20f), d(22f), d(20f), d(18f))
        }
        card.addView(Ui.text(this, "Для чего тебе приложение?", 20f, bold = true))
        card.addView(Ui.slogan(this, 13f).apply { setPadding(0, d(4f), 0, d(8f)) })
        fun choice(icon: Int, title: String, sub: String, value: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.CARD2, d(16f).toFloat())
            setPadding(d(14f), d(12f), d(14f), d(12f))
            addView(Ui.text(this@MainActivity, "", 16f, bold = true).apply { text = Ui.iconText(this@MainActivity, icon, title, 22f, 10f, under = Ui.CARD2) })
            addView(Ui.text(this@MainActivity, sub, 12f, Ui.MUTED).apply { setPadding(0, d(4f), 0, 0) })
            setOnClickListener { dlg.dismiss(); chooseProfile(value) }
        }
        card.addView(choice(R.drawable.ic_bolt, "Fast VPN", "Быстрый VPN по твоей подписке. Ничего лишнего", AppSettings.PROFILE_SIMPLE),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(10f) })
        card.addView(choice(R.drawable.ic_game, "Fast VPN + MLBB",
            "VPN + плашка с сервером матча поверх игры, российские серверы в приоритете, запуск игры кнопкой", AppSettings.PROFILE_GAME),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(10f) })
        card.addView(Ui.text(this, "Поменять можно потом в Настройках", 12f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, d(14f), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        dlg.setContentView(card)
        dlg.setCancelable(false)
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.9).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.show()
    }

    private fun chooseProfile(value: String) {
        AppLog.set("Режим приложения (первый запуск)", if (value == AppSettings.PROFILE_SIMPLE) "Fast VPN" else "Fast VPN + MLBB")
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
    private var builtTheme = ""
    private var builtAnim = ""

    override fun onResume() {
        super.onResume()
        AppSettings.load(this)
        AutoUpdate.uiVisible = true
        if (AppSettings.profile != builtProfile || AppSettings.theme != builtTheme || AppSettings.anim != builtAnim) {
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
        AutoUpdate.uiVisible = false
        handler.removeCallbacks(refresher)
        wifiThread?.interrupt()
        wifiThread = null
    }

    // ------------------------------- UI -------------------------------

    private fun buildUi(): android.view.View {
        builtProfile = AppSettings.profile
        builtTheme = AppSettings.theme
        builtAnim = AppSettings.anim
        val d = { v: Float -> Ui.dp(this, v) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16f), d(20f), d(16f), d(16f))
        }

        // Заголовок
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(Ui.text(this, "Fast VPN", 22f, bold = true))
        titleCol.addView(Ui.slogan(this, 12f))
        top.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(Ui.iconButton(this, R.drawable.ic_settings_w) { showSettings() })
        root.addView(top)
        root.addView(Ui.space(this, 14f))

        // Мастер первого запуска: что сделать по шагам, пока не всё готово
        setupCard = Ui.card(this).apply {
            background = Ui.rounded(Ui.SEL, d(18f).toFloat()).apply { setStroke(d(2f), Ui.GREEN) }
        }
        setupTitle = Ui.text(this, "", 16f, bold = true)
        setupText = Ui.text(this, "", 13f, Ui.SUB).apply { setPadding(0, d(6f), 0, d(12f)) }
        setupButton = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            background = Ui.rounded(Ui.BUTTON, d(14f).toFloat())
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
        serverFlag = Ui.text(this, "", 28f).apply { Ui.setFlag(this, "🌐") }
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
        ipLine = Ui.text(this, "", 12f, Ui.SUB).apply {
            setPadding(0, d(10f), 0, 0)
            setOnClickListener {
                AppLog.ui("нажал на строку IP — проверяю заново")
                text = "Узнаю IP…"
                MyIp.refresh(force = true) { runOnUiThread { text = MyIp.line() } }
            }
        }
        status.addView(ipLine)
        // Подписка скоро кончится (срок или трафик)
        subWarn = Ui.text(this, "", 12f, Ui.YELLOW).apply { setPadding(0, d(10f), 0, 0); visibility = android.view.View.GONE }
        status.addView(subWarn)
        root.addView(status)

        // Кнопка ИГРАТЬ
        playButton = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.theme.accentTop.toInt(), Ui.theme.accentBottom.toInt())
            ).apply { shape = GradientDrawable.OVAL }
            elevation = d(8f).toFloat()
            setOnClickListener { play() }
        }
        // Анимация вокруг кнопки рисуется отдельным слоем под всем экраном (см. ниже)
        ring = ConnectRing(this).apply { style = AppSettings.anim; anchor = playButton }
        val playBox = android.widget.FrameLayout(this)
        val playWrap = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, d(0f), 0, d(0f)) }
        if (AppSettings.anim == AppSettings.ANIM_GLOBE) {
            // Глобус: Земля с дугами к серверам, кнопка поменьше — внизу, поверх глобуса
            globeMode = true
            globe = GlobeView(this).also { g ->
                playBox.addView(g, android.widget.FrameLayout.LayoutParams(-1, -1))
                getSharedPreferences("settings", MODE_PRIVATE).getString("homeCountry", null)
                    ?.let { GlobeView.place(it) }?.let { g.setHome(it[0], it[1]) }
            }
            playBox.addView(playButton, android.widget.FrameLayout.LayoutParams(d(120f), d(120f), Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM))
            playWrap.addView(playBox, LinearLayout.LayoutParams(-1, d(330f)))
        } else {
            playBox.addView(playButton, android.widget.FrameLayout.LayoutParams(d(200f), d(200f), Gravity.CENTER))
            playWrap.addView(playBox, LinearLayout.LayoutParams(d(260f), d(260f)))
        }
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
            thumbTintList = android.content.res.ColorStateList(states, intArrayOf(0xFFFFFFFF.toInt(), 0xFFB0B4BA.toInt())) // белый ползунок во всех темах
            trackTintList = android.content.res.ColorStateList(states, intArrayOf(Ui.GREEN, Ui.SWITCH_OFF))
            setOnCheckedChangeListener { _, x -> onChange(x) }
        }
        val gameRow = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, d(6f)) }
        gameRow.addView(Ui.text(this, "", 15f, bold = true).apply {
            text = Ui.iconText(this@MainActivity, R.drawable.ic_game, "Игровой режим", 20f, under = Ui.BG)
            setPadding(0, 0, d(10f), 0)
        })
        gameRow.addView(greenSwitch(AppSettings.gameMode) { v ->
            AppSettings.gameMode = v
            AppLog.set("Игровой режим", v)
            AppSettings.save(this@MainActivity)
            if (anyRunning()) toast("Применится после переподключения")
        })
        // В режиме Fast VPN (без игры) про игру ничего не показываем
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
            thumbTintList = android.content.res.ColorStateList(states, intArrayOf(0xFFFFFFFF.toInt(), 0xFFB0B4BA.toInt())) // белый ползунок во всех темах
            trackTintList = android.content.res.ColorStateList(states, intArrayOf(Ui.GREEN, Ui.SWITCH_OFF))
            setOnCheckedChangeListener { _, v ->
                AppSettings.autoLaunch = v
                AppLog.set("Запускать MLBB после подключения", v)
                AppSettings.save(this@MainActivity)
            }
        }
        launchRow.addView(launchSwitch)
        root.addView(launchRow, LinearLayout.LayoutParams(-1, -2))
        stopButton = Ui.text(this, "", 15f, Ui.GREEN, bold = true).apply {
            text = Ui.iconText(this@MainActivity, R.drawable.ic_play, "Запустить MLBB", 16f)
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
            Ui.tile(this, R.drawable.ic_servers, "Серверы", "список, пинг, выбрать вручную") { startActivity(Intent(this, ServersActivity::class.java)) },
            Ui.tile(this, R.drawable.ic_link, "Подписка", "ссылка твоего VPN") { showSubscription() },
        ))
        root.addView(Ui.space(this, 10f))
        if (AppSettings.simple) {
            root.addView(row(
                Ui.tile(this, R.drawable.ic_speedtest, "Тест скорости", "пинг, загрузка и отдача") { speedTest() },
                Ui.tile(this, R.drawable.ic_settings, "Настройки", "автовыбор, Wi-Fi, обновления") { showSettings() },
            ))
        } else {
            root.addView(row(
                Ui.tile(this, R.drawable.ic_log, "Лог", "куда подключалась игра") { startActivity(Intent(this, LogActivity::class.java)) },
                Ui.tile(this, R.drawable.ic_settings, "Настройки", "режимы, база стран, обновления") { showSettings() },
            ))
            root.addView(Ui.space(this, 10f))
            root.addView(row(
                Ui.tile(this, R.drawable.ic_game, "Матчи", "пинг в каждом бою, лучшее время") { startActivity(Intent(this, MatchesActivity::class.java)) },
                Ui.tile(this, R.drawable.ic_speedtest, "Тест скорости", "пинг, загрузка и отдача") { speedTest() },
            ))
        }

        infoLine = Ui.text(this, "", 12f, Ui.MUTED).apply { setPadding(0, d(14f), 0, 0) }
        root.addView(infoLine)

        // Экран: звёзды (для «Гиперпрыжка») — фоном, поверх содержимого — вспышка
        val frame = android.widget.FrameLayout(this)
        stars = if (AppSettings.anim == AppSettings.ANIM_WARP) StarField(this).also {
            it.anchor = playButton
            frame.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1))
        } else null
        frame.addView(ring, android.widget.FrameLayout.LayoutParams(-1, -1))
        frame.addView(ScrollView(this).apply {
            addView(root)
            // Кнопка уехала при прокрутке — анимация за ней
            setOnScrollChangeListener { _, _, _, _, _ -> ring.invalidate(); stars?.invalidate() }
        }, android.widget.FrameLayout.LayoutParams(-1, -1))
        flashView = android.view.View(this).apply {
            setBackgroundColor(0xFFE8FFF0.toInt())
            visibility = android.view.View.GONE
        }
        frame.addView(flashView, android.widget.FrameLayout.LayoutParams(-1, -1))
        return frame
    }

    private fun nodeByTag(tag: String) = Subscription.usable(this).firstOrNull { it.tag == tag }

    private fun anyRunning() = BoxVpnService.isRunning || CaptureVpnService.isRunning || MonitorService.isRunning

    private fun refresh() {
        val realRunning = anyRunning()
        val sinceTap = android.os.SystemClock.uptimeMillis() - connectTapAt
        // Минимальное время анимации подключения — иначе при быстром подключении её не видно
        val holding = connectTapAt > 0 && sinceTap < MIN_ANIM_MS
        if (connectTapAt > 0 && !holding && (realRunning || (!busy && !BoxVpnService.isStarting))) connectTapAt = 0L
        if (afterStop && !busy && !BoxVpnService.isStarting) afterStop = false
        val running = realRunning && !holding
        val err = BoxVpnService.lastError ?: CaptureVpnService.lastError ?: MonitorService.lastError

        statusLine.text = when {
            holding || BoxVpnService.isStarting -> "◌  Подключаюсь…"
            busy && running -> "●  VPN подключён · $busyText"
            busy -> "◌  $busyText"
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
                    serverFlag.text = Ui.iconText(this, R.drawable.ic_link, "", 26f)
                    serverName.text = "Нет подписки"
                    serverSub.text = "Нажми «Подписка» и вставь ссылку"
                    serverPing.text = ""
                } else {
                    val r = ServerTester.results[n.tag]
                    Ui.setFlag(serverFlag, Ui.flagFor(n))
                    serverName.text = Ui.cleanName(n)
                    serverSub.text = "${n.type}" + (if (AppSettings.autoSelect) " · автовыбор" else "") +
                        " · ${Subscription.usable(this).size} серверов"
                    serverPing.text = r?.let { "${it.medianMs} ms" } ?: ""
                    serverPing.setTextColor(Ui.pingColor(r?.medianMs))
                }
            }
            AppSettings.MODE_DIRECT -> {
                serverFlag.text = Ui.iconText(this, R.drawable.ic_wifi, "", 26f); serverName.text = "Без VPN"; serverSub.text = "игра напрямую"; serverPing.text = ""
            }
            else -> {
                serverFlag.text = Ui.iconText(this, R.drawable.ic_plug, "", 26f); serverName.text = "Внешний VPN-клиент"
                serverSub.text = "через Clash API / Shizuku"; serverPing.text = ""
            }
        }

        // Подключение идёт: VPN поднимается или подбирается сервер, а интернета ещё нет
        val connecting = holding || (!afterStop && (BoxVpnService.isStarting || (busy && !realRunning)))
        val label = when {
            connecting -> "~connecting"
            running -> "ОТКЛЮЧИТЬ"
            AppSettings.gameMode && AppSettings.autoLaunch -> "ИГРАТЬ"
            else -> "ПОДКЛЮЧИТЬ"
        }
        if (playKey != label) {
            playKey = label
            if (connecting) {
                // Молния в кнопке «заряжается»: кнопка дышит, по кольцу бежит дуга
                playButton.text = Ui.iconText(this, R.drawable.ic_bolt, "", if (globeMode) 36f else 54f, tint = 0xFFFFFFFF.toInt())
            } else {
                playButton.text = label
                // Длинные надписи мельче, чтобы не вылезали за круг (и при крупном шрифте в системе)
                playButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, when {
                    globeMode -> if (label == "ИГРАТЬ") 17f else 13f
                    label == "ИГРАТЬ" -> 28f
                    else -> 22f
                })
            }
            // Подключено — кнопка красная (отключить), иначе зелёная
            val colors = if (running) intArrayOf(Ui.theme.redTop.toInt(), Ui.theme.redBottom.toInt())
            else intArrayOf(Ui.theme.accentTop.toInt(), Ui.theme.accentBottom.toInt())
            playButton.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
                .apply { shape = GradientDrawable.OVAL }
            if (connecting) {
                if (pulse == null) pulse = android.animation.ObjectAnimator.ofPropertyValuesHolder(
                    playButton,
                    android.animation.PropertyValuesHolder.ofFloat(android.view.View.SCALE_X, 1f, 0.95f),
                    android.animation.PropertyValuesHolder.ofFloat(android.view.View.SCALE_Y, 1f, 0.95f),
                ).apply {
                    duration = 650
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    repeatMode = android.animation.ValueAnimator.REVERSE
                    start()
                }
            } else {
                pulse?.cancel()
                pulse = null
                playButton.scaleX = 1f
                playButton.scaleY = 1f
            }
        }
        ring.setConnecting(connecting)
        ring.setRunning(running)
        ring.setSpeed(BoxVpnService.downBps)
        stars?.setState(connecting, running)
        globe?.let { g ->
            g.setState(connecting, running)
            g.setTargets(GlobeView.targetsFor(this))
            // Дом — страна без VPN (из «Моего IP»), запоминаем
            if (!BoxVpnService.isRunning) MyIp.country?.let { cc ->
                val p = getSharedPreferences("settings", MODE_PRIVATE)
                if (p.getString("homeCountry", null) != cc) p.edit().putString("homeCountry", cc).apply()
                GlobeView.place(cc)?.let { g.setHome(it[0], it[1]) }
            }
        }
        // Переходы: подключилось — вспышка и вибрация, отключилось — лёгкий щелчок
        val prev = wasRunning
        if (prev != null && prev != running) {
            if (running) {
                ring.connected()
                if (stars != null) StarField.flash(flashView)
                playButton.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).withEndAction {
                    playButton.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
                }.start()
                ConnectRing.buzz(playButton, strong = true)
            } else {
                ConnectRing.buzz(playButton, strong = false)
            }
        }
        wasRunning = running
        // Мой IP: пока подключается или подбирается сервер — не меряем (выход ещё скачет)
        if (connecting) ipLine.text = "Твой IP: подключаюсь…"
        else if (!busy) {
            if (MyIp.stale()) MyIp.refresh { runOnUiThread { ipLine.text = MyIp.line() } }
            ipLine.text = MyIp.line()
        }
        stopButton.visibility = if (running && AppSettings.gameMode) android.view.View.VISIBLE else android.view.View.GONE
        launchRow.visibility = if (AppSettings.gameMode) android.view.View.VISIBLE else android.view.View.GONE

        updateSetup()
        val warn = if (AppSettings.mode == AppSettings.MODE_BOX) Subscription.warning() else null
        subWarn.visibility = if (warn == null) android.view.View.GONE else android.view.View.VISIBLE
        if (warn != null) subWarn.text = "${warn.first}. ${warn.second}"
        playHint.text = when {
            busy && running -> "VPN уже работает — пользуйся. Лучший сервер подбираю в фоне."
            BoxVpnService.isStarting || busy -> "Подключаюсь…"
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
            val boost = if (WifiBoost.active) (if (AppSettings.simple) " · ускорен" else " · игровой режим") else ""
            wifiLine.text = Ui.iconText(this, R.drawable.ic_wifi, "Wi-Fi ${wi.band} · сигнал ${WifiBoost.signalText(wi.rssi)} (${wi.rssi} dBm)$router$boost", 16f)
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
            battleName.text = geo?.let { "${GeoDb.flag(it.countryCode)} ${it.city.ifEmpty { it.country }}" }
                ?: Ui.iconText(this, R.drawable.ic_servers, "неизвестно", 18f)
            battleSub.text = "${battle.conn.dstIp}:${battle.conn.dstPort} · UDP"
            battlePing.text = ping?.takeIf { it.ms >= 0 }?.let { "${it.ms} ms" } ?: ""
            battlePing.setTextColor(Ui.pingColor(ping?.ms))
        }

        // Ход обновления берём из общего состояния — переживает выход из приложения
        if (UpdateFlow.busy) infoLine.text = UpdateFlow.statusText
        else if (!dbDownloading) infoLine.text = ""

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
                setupButton.text = Ui.iconText(this, R.drawable.ic_link_w, "Вставить ссылку", 18f)
                setupButton.setOnClickListener { showSubscription() }
            }
            needDb -> {
                setupTitle.text = "Шаг $n из $total: скачай базу стран"
                setupText.text = "Нужна, чтобы показывать страну и город сервера игры. Один раз, около 100 МБ."
                setupButton.text = if (dbDownloading) infoLine.text.ifEmpty { "Скачиваю…" } else Ui.iconText(this, R.drawable.ic_download_w, "Скачать базу", 18f)
                setupButton.setOnClickListener { downloadDbFromSetup() }
            }
            else -> {
                setupTitle.text = "Шаг $n из $total: разреши плашку поверх игры"
                setupText.text = "В открывшемся списке найди «Fast VPN» и включи «Поверх других окон», потом вернись сюда."
                setupButton.text = "Открыть разрешение"
                setupButton.setOnClickListener {
                    AppLog.ui("нажал «Открыть разрешение» (плашка поверх игры)")
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        }
    }

    private fun downloadDbFromSetup() {
        AppLog.ui("нажал «Скачать базу стран»")
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
        AppLog.ui("окно «$title»: ${msg.replace('\n', ' ').take(400)}")
        if (isFinishing) return
        val b = AlertDialog.Builder(this).setTitle(title).setMessage(msg)
        if (action != null) {
            b.setPositiveButton(action.first) { _, _ -> AppLog.ui("в окне «$title» нажал «${action.first}»"); action.second() }
            b.setNegativeButton("Отмена", null)
        } else {
            b.setPositiveButton("Понятно", null)
        }
        b.show()
    }

    private fun toast(s: String) {
        AppLog.ui("сообщение: $s")
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }

    // ------------------------------ ИГРАТЬ ------------------------------

    private fun isGameInstalled() = try {
        packageManager.getPackageInfo(CaptureVpnService.GAME_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun launchGame() {
        val i = packageManager.getLaunchIntentForPackage(CaptureVpnService.GAME_PACKAGE)
        AppLog.ui("запуск Mobile Legends")
        if (i == null) toast("Не получилось запустить Mobile Legends") else startActivity(i)
    }

    private fun clearErrors() {
        BoxVpnService.lastError = null
        CaptureVpnService.lastError = null
        MonitorService.lastError = null
        shownError = null
    }

    private fun play() {
        AppLog.ui("нажал большую кнопку «${playButton.text}» (VPN ${if (anyRunning()) "включён" else "выключен"}" +
            (if (busy) ", идёт подбор" else "") + ")")
        // Отключить можно всегда, даже пока идёт подбор сервера
        if (anyRunning()) {
            stopAll()
            return
        }
        if (busy) return
        // Игра и плашка нужны только для игровых функций; Fast VPN без игры работает без них
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
                "Найди в списке «Fast VPN» и включи «Поверх других окон». Потом вернись и нажми ИГРАТЬ.",
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

    /** Подключение — общее с плиткой в шторке и виджетом (см. [Connector]). */
    private fun playBox() {
        val game = AppSettings.gameMode
        connectTapAt = android.os.SystemClock.uptimeMillis()
        afterStop = false
        handler.removeCallbacks(refresher)
        handler.post(refresher)
        Connector.connect(applicationContext) { msg ->
            runOnUiThread {
                if (msg != null) toast(msg)
                if (game && AppSettings.autoLaunch) launchGame()
            }
        }
    }

    private fun stopAll() {
        AppLog.vpn("выключаю VPN (кнопка в приложении)")
        connectTapAt = 0L
        afterStop = true
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
            text = Ui.iconText(this@MainActivity, R.drawable.ic_paste, "Вставить из буфера", 18f)
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
            .setPositiveButton("Сохранить и обновить") { _, _ ->
                val t = input.text.toString().trim()
                AppLog.sub("нажал «Сохранить и обновить»: " + if (t.startsWith("http")) "ссылка, сервер ${AppLog.host(t)}" else "вставлены серверы текстом (${t.lines().size} строк)")
                updateSubscription(t)
            }
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
            var geo = false
            val msg = try {
                val n = try {
                    Subscription.update(applicationContext, url)
                } catch (e: Subscription.GeoBlocked) {
                    // Панель пускает только российские адреса: если наш VPN включён — через сервер с выходом в России
                    if (!BoxVpnService.isRunning) throw e
                    Subscription.updateViaRuExit(applicationContext, url) { m -> runOnUiThread { progress.setMessage(m) } }
                }
                "Готово: серверов $n" + if (BoxVpnService.isRunning) ". Переподключи VPN, чтобы применить." else ""
            } catch (e: Exception) {
                geo = e is Subscription.GeoBlocked
                error = e.message
                ""
            }
            runOnUiThread {
                progress.dismiss()
                when {
                    error == null -> toast(msg)
                    // Напрямую не открылась или панель режет зарубежный адрес — через свой VPN на сохранённых серверах
                    canUpdateViaOwnVpn() -> withVpnPermission { updateSubscriptionViaVpn(url, geo) }
                    else -> showInfo("Подписка не загрузилась", error!!)
                }
            }
        }.start()
    }

    /** Есть сохранённые серверы — можно скачать подписку через свой VPN (сторонний Android отключит сам). */
    private fun canUpdateViaOwnVpn() =
        !BoxVpnService.isRunning && !CaptureVpnService.isRunning &&
            AppSettings.mode == AppSettings.MODE_BOX && Subscription.usable(this).isNotEmpty()

    /**
     * Подписка не скачалась: поднимаем свой VPN на уже сохранённых серверах, качаем подписку
     * через него и отключаемся. Если панель пускает только российские адреса — через сервер
     * с выходом в России.
     */
    private fun updateSubscriptionViaVpn(url: String, needRu: Boolean) {
        busy = true
        val progress = AlertDialog.Builder(this).setTitle("Обновляю подписку через VPN")
            .setMessage("Подключаюсь к сохранённому серверу…").setCancelable(false).show()
        Thread {
            var error: String? = null
            var n = 0
            if (!Connector.startAndWait(applicationContext)) {
                error = BoxVpnService.lastError ?: "VPN не подключился"
            } else {
                val say: (String) -> Unit = { m -> runOnUiThread { progress.setMessage(m) } }
                n = try {
                    if (needRu) Subscription.updateViaRuExit(applicationContext, url, say)
                    else try {
                        say("Скачиваю подписку…")
                        Subscription.update(applicationContext, url)
                    } catch (e: Subscription.GeoBlocked) {
                        Subscription.updateViaRuExit(applicationContext, url, say)
                    }
                } catch (e: Exception) {
                    error = e.message
                    0
                }
                BoxVpnService.stop(this, manual = false)
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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleShortcut(intent)
    }

    /** Ярлыки с иконки приложения (долгое нажатие на значок). */
    private fun handleShortcut(i: android.content.Intent?) {
        val action = i?.action ?: return
        if (!action.startsWith("dev.mlbb.overlay.SHORTCUT_")) return
        AppLog.ui("ярлык с иконки: ${action.removePrefix("dev.mlbb.overlay.SHORTCUT_")}")
        i.action = null // повторно при пересоздании экрана не срабатываем
        // Даём экрану открыться, потом действие
        handler.postDelayed({
            when (action) {
                "dev.mlbb.overlay.SHORTCUT_TOGGLE" -> play()
                "dev.mlbb.overlay.SHORTCUT_SERVERS" -> startActivity(android.content.Intent(this, ServersActivity::class.java))
                "dev.mlbb.overlay.SHORTCUT_SPEED" -> speedTest()
            }
        }, 300)
    }

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
        card.addView(Ui.text(this, "", 20f, bold = true).apply { text = Ui.iconText(this@MainActivity, R.drawable.ic_speedtest, "Тест скорости", 22f) })
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
            AppLog.add("SPEED", "тест скорости (${if (BoxVpnService.isRunning) "через VPN" else "напрямую"}): " +
                "пинг ${p?.first ?: "—"} ms, разброс ${p?.second ?: "—"} ms, загрузка ${dn?.let { mbps(it) } ?: "—"}, отдача ${upv?.let { mbps(it) } ?: "—"}")
            runOnUiThread {
                if (p == null && dn == null) state.text = "Нет доступа к интернету"
                else {
                    state.text = "Готово · быстрее нас — только свет"
                    state.setTextColor(Ui.GREEN)
                }
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
