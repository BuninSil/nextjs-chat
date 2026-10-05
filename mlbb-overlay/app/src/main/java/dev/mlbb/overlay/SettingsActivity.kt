package dev.mlbb.overlay

import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/** Настройки в стиле приложения: карточки, переключатели, всё сохраняется сразу. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var dbStatus: TextView
    private lateinit var updStatus: TextView
    private lateinit var apiCard: LinearLayout
    private val modeCards = HashMap<Int, LinearLayout>()
    @Volatile private var dbBusy = false
    private var vpnChanged = false

    private val importDb = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importDatabase(uri)
    }

    private fun d(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.decorView.setBackgroundColor(Ui.BG)
        AppSettings.load(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16f), d(20f), d(16f), d(24f))
        }

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.backButton(this) { finish() })
        top.addView(Ui.text(this, "Настройки", 22f, bold = true))
        root.addView(top)
        val simple = AppSettings.simple

        // ---------- Для чего приложение ----------
        root.addView(section("Приложение"))
        root.addView(profileCard(AppSettings.PROFILE_GAME, R.drawable.ic_game, "Fast VPN + MLBB",
            "VPN + плашка с сервером матча, игровой режим, лог боёв, запуск игры"))
        root.addView(Ui.space(this, 8f))
        root.addView(profileCard(AppSettings.PROFILE_SIMPLE, R.drawable.ic_bolt, "Fast VPN",
            "Только быстрый VPN по подписке, всё про игру скрыто"))

        // ---------- Режим (только для игры) ----------
        if (!simple) {
            root.addView(section("Режим"))
            root.addView(modeCard(AppSettings.MODE_BOX, R.drawable.ic_shield, "Встроенный VPN",
                "Подключение по твоей подписке, автовыбор сервера. Рекомендую."))
            root.addView(Ui.space(this, 8f))
            root.addView(modeCard(AppSettings.MODE_DIRECT, R.drawable.ic_wifi, "Без VPN",
                "Игра напрямую. Плашка с сервером работает, пинг не меняется."))
            root.addView(Ui.space(this, 8f))
            root.addView(modeCard(AppSettings.MODE_API, R.drawable.ic_plug, "Внешний VPN-клиент",
                "Твой клиент (FlClash, Clash Meta) остаётся включённым, мы только читаем соединения."))
            highlightMode()
        }

        // ---------- Встроенный VPN ----------
        root.addView(section(if (simple) "VPN" else "Встроенный VPN"))
        root.addView(Ui.card(this).apply {
            addView(switchRow("Автовыбор сервера",
                if (simple) "Самый быстрый сервер при подключении" else "Самый быстрый и стабильный при нажатии ИГРАТЬ",
                AppSettings.autoSelect) {
                AppSettings.autoSelect = it
            })
            if (simple) return@apply
            addView(divider())
            addView(switchRow("Через VPN только игра", "Весь канал — игре, остальные приложения без VPN", AppSettings.onlyGame) {
                AppSettings.onlyGame = it; vpnChanged = true
            })
            addView(divider())
            addView(switchRow("Матч напрямую", "UDP матча мимо VPN — минимальный пинг, если оператор пускает", AppSettings.battleDirect) {
                AppSettings.battleDirect = it; vpnChanged = true
            })
        })

        // ---------- Wi-Fi ----------
        root.addView(section("Wi-Fi"))
        root.addView(Ui.card(this).apply {
            addView(switchRow(if (simple) "Ускорение Wi-Fi" else "Игровой режим Wi-Fi",
                "Wi-Fi не засыпает между пакетами — меньше скачков пинга. Работает и без VPN", AppSettings.wifiBoost) {
                AppSettings.wifiBoost = it
                if (!it) WifiBoost.release()
            })
        })

        // ---------- Предупреждение (только для игры) ----------
        if (!simple) root.addView(section("Предупреждение"))
        if (!simple) root.addView(Ui.card(this).apply {
            addView(switchRow("Вибрация на чужой сервер", "Если матч попал на сервер не из списка стран", AppSettings.alertEnabled) {
                AppSettings.alertEnabled = it
            })
            addView(Ui.space(this@SettingsActivity, 10f))
            addView(Ui.text(this@SettingsActivity, "Свои страны (коды через запятую)", 12f, Ui.MUTED))
            addView(input(AppSettings.allowedCountries.joinToString(","), "RU,BY,KZ") {
                AppSettings.allowedCountries = AppSettings.parseCountries(it)
            })
        })

        // ---------- База стран ----------
        root.addView(section("База стран"))
        root.addView(Ui.card(this).apply {
            dbStatus = Ui.text(this@SettingsActivity, "DB-IP: ${GeoDb.description()}", 13f, Ui.MUTED)
            addView(dbStatus)
            addView(Ui.space(this@SettingsActivity, 10f))
            addView(primary(Ui.iconText(this@SettingsActivity, R.drawable.ic_download_w, "Скачать базу", 18f)) { downloadDatabase() })
            addView(Ui.space(this@SettingsActivity, 8f))
            addView(secondary(Ui.iconText(this@SettingsActivity, R.drawable.ic_folder_w, "Импорт файла .mmdb", 18f)) { importDb.launch(arrayOf("*/*")) })
        })

        // ---------- Обновления ----------
        root.addView(section("Обновления"))
        root.addView(Ui.card(this).apply {
            addView(switchRow("Обновлять автоматически",
                "Сам скачивает и ставит новую версию в фоне, когда VPN выключен. Первый раз Android один раз спросит подтверждение",
                AppSettings.autoCheckUpdates) {
                AppSettings.autoCheckUpdates = it
                AppSettings.save(this@SettingsActivity)
                AutoUpdate.schedule(applicationContext)
            })
            addView(Ui.space(this@SettingsActivity, 10f))
            addView(secondary(Ui.iconText(this@SettingsActivity, R.drawable.ic_refresh_w, "Проверить обновления", 18f)) {
                UpdateFlow.check(this@SettingsActivity, true) { msg -> updStatus.text = msg }
            })
            updStatus = Ui.text(this@SettingsActivity, "", 12f, Ui.MUTED).apply { setPadding(0, d(6f), 0, 0) }
            addView(updStatus)
        })

        // ---------- Внешний VPN-клиент ----------
        apiCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(section("Внешний VPN-клиент"))
            addView(Ui.card(this@SettingsActivity).apply {
                addView(Ui.text(this@SettingsActivity, "Порт Clash API (пусто — найти самому)", 12f, Ui.MUTED))
                addView(input(if (AppSettings.apiPort > 0) AppSettings.apiPort.toString() else "", "9090", number = true) {
                    AppSettings.apiPort = it.toIntOrNull()?.takeIf { p -> p in 1..65535 } ?: 0
                })
                addView(Ui.space(this@SettingsActivity, 10f))
                addView(Ui.text(this@SettingsActivity, "Секрет (если клиент просит)", 12f, Ui.MUTED))
                addView(input(AppSettings.apiSecret, "secret") { AppSettings.apiSecret = it.trim() })
            })
        }
        root.addView(apiCard)
        apiCard.visibility = if (AppSettings.mode == AppSettings.MODE_API) View.VISIBLE else View.GONE

        root.addView(Ui.slogan(this, 13f, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, d(28f), 0, d(2f))
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(Ui.text(this, "Fast VPN · ${BuildConfig.AUTHOR} · версия ${BuildConfig.VERSION_NAME}", 11f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Показывает ход обновления, даже если его запустили до того, как зашли сюда. */
    private val updRefresher = object : Runnable {
        override fun run() {
            if (updStatus.text.toString() != UpdateFlow.statusText) updStatus.text = UpdateFlow.statusText
            handler.postDelayed(this, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(updRefresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updRefresher)
        AppSettings.save(this)
        if (vpnChanged && (BoxVpnService.isRunning || CaptureVpnService.isRunning || MonitorService.isRunning)) {
            Toast.makeText(this, "Применится после переподключения: Отключить → ИГРАТЬ", Toast.LENGTH_LONG).show()
            vpnChanged = false
        }
    }

    // ------------------------------ элементы ------------------------------

    private fun section(title: String) = Ui.text(this, title.uppercase(), 12f, Ui.MUTED, bold = true).apply {
        setPadding(d(4f), d(22f), 0, d(8f))
        letterSpacing = 0.08f
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(0xFF262A30.toInt())
        layoutParams = LinearLayout.LayoutParams(-1, d(1f)).apply { topMargin = d(12f); bottomMargin = d(12f) }
    }

    private fun switchRow(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(this, title, 15f))
        col.addView(Ui.text(this, sub, 12f, Ui.MUTED).apply { setPadding(0, d(2f), d(12f), 0) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val sw = SwitchCompat(this).apply {
            isChecked = value
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(0xFFFFFFFF.toInt(), 0xFFB0B4BA.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(Ui.GREEN, 0xFF3A3F46.toInt()))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        return row
    }

    private fun input(value: String, hint: String, number: Boolean = false, onChange: (String) -> Unit) =
        EditText(this).apply {
            setText(value)
            this.hint = hint
            setTextColor(Ui.TEXT)
            setHintTextColor(0xFF5F6368.toInt())
            textSize = 15f
            background = Ui.rounded(0xFF0F1114.toInt(), d(12f).toFloat())
            setPadding(d(12f), d(10f), d(12f), d(10f))
            if (number) inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(6f) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = onChange(s?.toString() ?: "")
            })
        }

    private fun primary(text: CharSequence, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        background = Ui.rounded(0xFF2FB565.toInt(), d(14f).toFloat())
        setPadding(d(14f), d(13f), d(14f), d(13f))
        setOnClickListener { onClick() }
    }

    private fun secondary(text: CharSequence, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(Ui.TEXT)
        textSize = 15f
        background = Ui.rounded(0xFF23272D.toInt(), d(14f).toFloat())
        setPadding(d(14f), d(12f), d(14f), d(12f))
        setOnClickListener { onClick() }
    }

    /** Карточка «для чего приложение»; выбор сразу перестраивает приложение. */
    private fun profileCard(value: String, iconRes: Int, title: String, sub: String): LinearLayout {
        val c = Ui.card(this)
        c.addView(Ui.text(this, "", 15f, bold = true).apply { text = Ui.iconText(this@SettingsActivity, iconRes, title, 20f) })
        c.addView(Ui.text(this, sub, 12f, Ui.MUTED).apply { setPadding(0, d(4f), 0, 0) })
        val selected = AppSettings.profile == value
        c.background = Ui.rounded(if (selected) 0xFF182A20.toInt() else Ui.CARD, d(18f).toFloat()).apply {
            if (selected) setStroke(d(2f), Ui.GREEN)
        }
        c.setOnClickListener {
            if (AppSettings.profile == value) return@setOnClickListener
            AppSettings.setProfile(this, value)
            vpnChanged = true
            Toast.makeText(this, if (AppSettings.simple) "Включён режим Fast VPN" else "Включён режим Fast VPN + MLBB", Toast.LENGTH_SHORT).show()
            recreate()
        }
        return c
    }

    private fun modeCard(mode: Int, iconRes: Int, title: String, sub: String): LinearLayout {
        val c = Ui.card(this)
        c.addView(Ui.text(this, "", 15f, bold = true).apply { text = Ui.iconText(this@SettingsActivity, iconRes, title, 20f) })
        c.addView(Ui.text(this, sub, 12f, Ui.MUTED).apply { setPadding(0, d(4f), 0, 0) })
        c.setOnClickListener {
            if (AppSettings.mode != mode) vpnChanged = true
            AppSettings.mode = mode
            highlightMode()
            apiCard.visibility = if (mode == AppSettings.MODE_API) View.VISIBLE else View.GONE
        }
        modeCards[mode] = c
        return c
    }

    private fun highlightMode() {
        for ((mode, card) in modeCards) {
            val selected = mode == AppSettings.mode
            card.background = Ui.rounded(if (selected) 0xFF182A20.toInt() else Ui.CARD, d(18f).toFloat()).apply {
                if (selected) setStroke(d(2f), Ui.GREEN)
            }
        }
    }

    // ------------------------------ база стран ------------------------------

    private fun downloadDatabase() {
        if (dbBusy) return
        dbBusy = true
        Thread {
            try {
                GeoDb.download(applicationContext) { msg -> runOnUiThread { dbStatus.text = msg } }
            } catch (e: Exception) {
                runOnUiThread { dbStatus.text = "Не скачалось: ${e.message}" }
            } finally {
                dbBusy = false
            }
        }.start()
    }

    private fun importDatabase(uri: Uri) {
        if (dbBusy) return
        dbBusy = true
        Thread {
            try {
                contentResolver.openInputStream(uri)!!.use { input ->
                    GeoDb.install(applicationContext, input) { msg -> runOnUiThread { dbStatus.text = msg } }
                }
            } catch (e: Exception) {
                runOnUiThread { dbStatus.text = "Импорт не удался: ${e.message}" }
            } finally {
                dbBusy = false
            }
        }.start()
    }
}
