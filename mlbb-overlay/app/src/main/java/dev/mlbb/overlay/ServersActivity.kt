package dev.mlbb.overlay

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Список серверов подписки с пингом; тап — переключиться, ↻ — перемерить все. */
class ServersActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = Adapter()
    private lateinit var header: TextView
    private lateinit var hiddenToggle: TextView
    private var showHidden = false

    private val refresher = object : Runnable {
        override fun run() {
            adapter.reload()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Тема — до создания экрана, чтобы системные окна были в её цветах
        AppSettings.load(this)
        setTheme(Ui.themeRes())
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        Ui.applyWindow(this)
        val d = { v: Float -> Ui.dp(this, v) }
        AppSettings.load(this)
        Subscription.load(this)
        ServerTester.loadExits(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16f), d(20f), d(16f), 0)
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.backButton(this) { finish() })
        top.addView(Ui.text(this, "Серверы", 22f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(Ui.iconButton(this, R.drawable.ic_refresh_w) { retest() })
        root.addView(top)
        root.addView(Ui.space(this, 10f))
        // Отдельные проверки: страна выхода (запоминается) и скорость
        val actions = LinearLayout(this)
        actions.addView(Ui.tile(this, R.drawable.ic_exits, "Выходы", "в какой стране сервер выходит в интернет") { checkExits() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = d(5f) })
        actions.addView(Ui.tile(this, R.drawable.ic_bolt, "По скорости", "найти и включить самый быстрый") { speedRank() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d(5f) })
        root.addView(actions)
        root.addView(Ui.text(this, "Круглая стрелка вверху — перемерить пинг. Тап по серверу — подключиться к нему, долгое нажатие — в избранное или скрыть.", 12f, Ui.MUTED)
            .apply { setPadding(d(4f), d(10f), d(4f), 0) })
        root.addView(Ui.space(this, 12f))

        // Обход блокировок без сервера (нужен Xray — есть на 64-битных телефонах)
        if (XrayCore.available(this)) {
            val on = AppSettings.selectedTag == XrayCore.BYPASS
            val card = Ui.card(this).apply {
                if (on) background = Ui.rounded(Ui.SEL, d(18f).toFloat()).apply { setStroke(d(2f), Ui.GREEN) }
                addView(Ui.text(this@ServersActivity, "", 15f, bold = true).apply {
                    text = Ui.iconText(this@ServersActivity, R.drawable.ic_bolt, "Обход блокировок без сервера", 18f)
                })
                addView(Ui.text(this@ServersActivity,
                    if (on) "Включено. YouTube, Discord и другие идут напрямую — без VPN-сервера и без трафика подписки. Нажми ещё раз, чтобы выключить и вернуться на сервер"
                    else "YouTube, Discord и другие — напрямую, без VPN-сервера: бесплатно, полная скорость, не тратит трафик подписки. Работает не у всех провайдеров — способ подберётся сам",
                    12f, Ui.MUTED).apply { setPadding(0, d(4f), 0, 0) })
                setOnClickListener { pickBypass() }
            }
            root.addView(card)
            root.addView(Ui.space(this, 10f))
        }

        val autoCard = Ui.card(this)
        val auto = CheckBox(this).apply {
            text = "Автовыбор сервера"
            setTextColor(Ui.TEXT)
            buttonTintList = android.content.res.ColorStateList.valueOf(Ui.GREEN)
            isChecked = AppSettings.autoSelect
            setOnCheckedChangeListener { _, v ->
                AppLog.set("Автовыбор сервера (Серверы)", v)
                AppSettings.autoSelect = v
                AppSettings.save(this@ServersActivity)
            }
        }
        autoCard.addView(auto)
        header = Ui.text(this, "", 12f, Ui.MUTED)
        autoCard.addView(header)
        // Скрытые серверы — по кнопке, чтобы можно было вернуть
        hiddenToggle = Ui.text(this, "", 12f, Ui.GREEN, bold = true).apply {
            setPadding(0, d(8f), 0, 0)
            setOnClickListener { showHidden = !showHidden; AppLog.ui("показать скрытые серверы: $showHidden"); adapter.reload() }
        }
        autoCard.addView(hiddenToggle)
        root.addView(autoCard)
        root.addView(Ui.space(this, 10f))

        val list = RecyclerView(this)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    private fun retest() {
        if (ServerTester.testing) return
        // Чужой VPN (Karing, Happ…) не пускает приложение к серверам напрямую — пинг не пройдёт ни у одного
        if (ServerTester.otherVpnActive(applicationContext)) {
            AppLog.srv("пинг: включён другой VPN — замер не запускаю")
            Toast.makeText(this, "Включён другой VPN — выключи его, и пинг заработает", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            val ranked = ServerTester.measure(applicationContext) { done, total ->
                runOnUiThread { header.text = "Проверено $done из $total…" }
            }
            if (ranked.isEmpty()) runOnUiThread {
                Toast.makeText(this, "Ни один сервер не ответил: нет интернета или оператор режет соединения. " +
                    "Попробуй Wi-Fi или другой сервер", Toast.LENGTH_LONG).show()
            }
            // При включённом VPN и автовыборе — сразу переключаемся на лучший рабочий.
            // В игровом режиме — нет: смена сервера посреди матча = скачок пинга и вылет
            if (AppSettings.autoSelect && BoxVpnService.isRunning && !AppSettings.gameMode) {
                ServerTester.pickWorking(applicationContext, ranked)
            }
            runOnUiThread { header.text = "" }
        }.start()
    }

    /** Разрешение на VPN, если проверке нужно поднять VPN самой. */
    private var afterVpnPermission: (() -> Unit)? = null
    private val vpnPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        val next = afterVpnPermission
        afterVpnPermission = null
        if (it.resultCode == RESULT_OK) next?.invoke()
        else Toast.makeText(this, "Без разрешения на VPN проверку не запустить", Toast.LENGTH_LONG).show()
    }

    /**
     * Выполнить проверку через VPN. Если VPN выключен — поднимаем его на время проверки и потом
     * выключаем. work получает функцию для текста прогресса; вызывается не на главном потоке.
     */
    private fun withVpn(title: String, onStop: () -> Unit, work: ((String) -> Unit) -> String) {
        AppLog.ui("запуск «$title» (VPN ${if (BoxVpnService.isRunning) "включён" else "выключен — включу на время"})")
        if (ServerTester.testing) {
            Toast.makeText(this, "Сейчас идёт замер пинга — подожди пару секунд", Toast.LENGTH_SHORT).show()
            return
        }
        val run = {
            val dlg = android.app.AlertDialog.Builder(this).setTitle(title).setMessage("Секунду…")
                .setCancelable(false)
                .setNegativeButton("Стоп") { _, _ -> AppLog.ui("нажал «Стоп» в «$title»"); onStop() }
                .show()
            Thread {
                val startedHere = !BoxVpnService.isRunning
                val say: (String) -> Unit = { m -> runOnUiThread { dlg.setMessage(m) } }
                val result = if (startedHere) {
                    say("Включаю VPN на время проверки…")
                    if (Connector.startAndWait(applicationContext)) work(say)
                    else "VPN не подключился: ${BoxVpnService.lastError ?: "неизвестная ошибка"}"
                } else work(say)
                AppLog.srv("«$title» — итог: ${result.replace('\n', ' ')}")
                if (startedHere) BoxVpnService.stop(this, manual = false)
                runOnUiThread {
                    if (!isFinishing) {
                        dlg.dismiss()
                        android.app.AlertDialog.Builder(this).setTitle(title).setMessage(result)
                            .setPositiveButton("Понятно", null).show()
                    }
                    adapter.reload()
                }
            }.start()
        }
        val prep = android.net.VpnService.prepare(this)
        if (BoxVpnService.isRunning || prep == null) run()
        else {
            afterVpnPermission = run
            vpnPermission.launch(prep)
        }
    }

    /** Проверка реальной страны выхода у всех серверов (запоминается до обновления подписки). */
    private fun checkExits() {
        withVpn("Проверяю выходы", onStop = { ServerTester.cancelExits = true }) { say ->
            val (n, ru, dead) = ServerTester.checkAllExits(applicationContext) { done, total, name ->
                say("Сервер $done из $total\n$name")
            }
            if (n == 0) "Не удалось определить выход ни у одного сервера. Проверь, что интернет есть, и попробуй ещё раз."
            else "Выход определён у $n серверов.\nС выходом в России: $ru." +
                (if (dead > 0) "\nНе ответили: $dead." else "") +
                "\n\nВ списке у каждого теперь написано «выход» и флаг страны."
        }
    }

    /** Замер скорости через лучшие по пингу серверы; выбирает самый быстрый. */
    private fun speedRank() {
        withVpn("Ищу самый быстрый", onStop = {}) { say ->
            say("Меряю пинг до серверов…")
            val ranked = ServerTester.measure(applicationContext) { done, total -> say("Пинг: $done из $total") }
            val best = ServerTester.pickFastest(applicationContext, ranked, 6) { done, total ->
                say("Скорость: сервер $done из $total")
            }
            val n = Subscription.usable(applicationContext).firstOrNull { it.tag == best }
            if (n == null) "Не получилось замерить скорость — попробуй ещё раз"
            else "Самый быстрый: ${Ui.cleanName(n)}" +
                (ServerTester.speed[n.tag]?.let { String.format(java.util.Locale.US, " — %.0f Мбит/с", it) } ?: "") +
                ". Он выбран."
        }
    }

    /** Долгое нажатие: избранное / скрыть. */
    private fun marks(n: Subscription.Node) {
        AppLog.ui("долгое нажатие на сервер «${Ui.cleanName(n)}»")
        val fav = Subscription.isFavorite(n)
        val hid = Subscription.isHidden(n)
        val items = arrayOf(
            if (fav) "Убрать из избранного" else "В избранное — автовыбор берёт его первым",
            if (hid) "Показать снова" else "Скрыть — автовыбор его не берёт",
        )
        android.app.AlertDialog.Builder(this)
            .setTitle(Ui.cleanName(n))
            .setItems(items) { _, which ->
                if (which == 0) Subscription.toggleFavorite(this, n) else Subscription.toggleHidden(this, n)
                AppLog.ui("«${Ui.cleanName(n)}»: избранный ${Subscription.isFavorite(n)}, скрыт ${Subscription.isHidden(n)}")
                adapter.reload()
            }
            .show()
    }

    private fun pickBypass() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val turningOff = AppSettings.selectedTag == XrayCore.BYPASS
        if (turningOff) {
            // Выключаем обход — обратно на сервер, который был до него, и автовыбор, если был включён
            val before = prefs.getString("beforeBypass", null)
            val nodes = Subscription.usable(this).filterNot { ServerTester.isSeparator(it) }
            val back = nodes.firstOrNull { it.tag == before } ?: nodes.firstOrNull()
            if (back == null) {
                Toast.makeText(this, "В подписке нет серверов — обход не выключить", Toast.LENGTH_LONG).show()
                return
            }
            AppLog.ui("выключил «Обход блокировок без сервера» — обратно на «${Ui.cleanName(back)}»")
            AppSettings.autoSelect = prefs.getBoolean("beforeBypassAuto", true)
            AppSettings.selectedTag = back.tag
        } else {
            AppLog.ui("выбрал «Обход блокировок без сервера»")
            prefs.edit().putString("beforeBypass", AppSettings.selectedTag)
                .putBoolean("beforeBypassAuto", AppSettings.autoSelect).apply()
            AppSettings.autoSelect = false
            AppSettings.selectedTag = XrayCore.BYPASS
        }
        AppSettings.save(this)
        if (BoxVpnService.isRunning) {
            // Нужен перезапуск ядра: в обходе отключается QUIC, чтобы YouTube шёл по TCP
            Toast.makeText(this, if (turningOff) "Возвращаю на сервер…" else "Переключаю на обход блокировок…", Toast.LENGTH_SHORT).show()
            val ctx = applicationContext
            Thread {
                BoxVpnService.stop(ctx, manual = false)
                Thread.sleep(1500)
                Connector.connect(ctx)
            }.start()
        } else {
            Toast.makeText(this, "Готово — нажми ПОДКЛЮЧИТЬ на главном", Toast.LENGTH_LONG).show()
        }
        recreate()
    }

    private fun pick(n: Subscription.Node) {
        AppLog.ui("выбрал сервер вручную: «${Ui.cleanName(n)}» (${n.type}), автовыбор выключен")
        AppSettings.autoSelect = false
        if (BoxVpnService.isRunning) {
            Thread {
                val ok = ServerTester.use(this, n.tag)
                runOnUiThread {
                    Toast.makeText(this, if (ok) "Переключено: ${Ui.cleanName(n)}" else "Не удалось переключить", Toast.LENGTH_SHORT).show()
                }
            }.start()
        } else {
            AppSettings.selectedTag = n.tag
            AppSettings.save(this)
        }
        recreate()
    }

    private class Holder(v: LinearLayout) : RecyclerView.ViewHolder(v) {
        val root = v
        lateinit var flag: TextView
        lateinit var name: TextView
        lateinit var sub: TextView
        lateinit var ping: TextView
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        private var items: List<Subscription.Node> = emptyList()

        fun reload() {
            // Сначала проверенные по оценке, потом непроверенные, в конце не ответившие
            val all = Subscription.usable(this@ServersActivity, includeHidden = showHidden).filterNot { ServerTester.isSeparator(it) }
            val hiddenCount = Subscription.usable(this@ServersActivity, includeHidden = true).count { Subscription.isHidden(it) }
            hiddenToggle.visibility = if (hiddenCount > 0) android.view.View.VISIBLE else android.view.View.GONE
            hiddenToggle.text = if (showHidden) "Убрать скрытые из списка" else "Показать скрытые ($hiddenCount)"
            // Рабочие сверху; в игровом режиме среди них российские первыми
            val game = AppSettings.gameMode
            // Избранные — всегда сверху, скрытые — в самом низу
            items = all.sortedWith(compareBy<Subscription.Node> {
                when {
                    Subscription.isHidden(it) -> 2
                    Subscription.isFavorite(it) -> 0
                    else -> 1
                }
            }.thenBy {
                when {
                    !ServerTester.results.containsKey(it.tag) -> 1
                    ServerTester.results[it.tag] == null -> 2
                    else -> 0
                }
            }.thenBy { if (game && !ServerTester.isRussian(it)) 1 else 0 }
                .thenBy { ServerTester.results[it.tag]?.score ?: Int.MAX_VALUE })
            if (!ServerTester.testing && header.text.isEmpty()) {
                header.text = "${items.size} серверов · пинг напрямую до сервера"
            }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val d = { v: Float -> Ui.dp(ctx, v) }
            val row = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(d(12f), d(12f), d(12f), d(12f))
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = d(6f) }
            }
            val h = Holder(row)
            h.flag = Ui.text(ctx, "", 24f)
            row.addView(h.flag)
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(d(10f), 0, d(8f), 0) }
            h.name = Ui.text(ctx, "", 14f)
            h.sub = Ui.text(ctx, "", 11f, Ui.MUTED)
            col.addView(h.name)
            col.addView(h.sub)
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            h.ping = Ui.text(ctx, "", 15f, bold = true)
            row.addView(h.ping)
            return h
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: Holder, pos: Int) {
            val n = items[pos]
            val selected = n.tag == AppSettings.selectedTag
            h.root.background = Ui.rounded(if (selected) Ui.SEL else Ui.CARD, Ui.dp(h.root.context, 12f).toFloat())
            Ui.setFlag(h.flag, Ui.flagFor(n), 24f)
            val fav = Subscription.isFavorite(n)
            h.name.text = if (fav) Ui.iconText(h.root.context, R.drawable.ic_star, Ui.cleanName(n), 14f, 6f, tint = Ui.YELLOW)
            else Ui.cleanName(n)
            h.root.alpha = if (Subscription.isHidden(n)) 0.45f else 1f
            val r = ServerTester.results[n.tag]
            val udp = if (n.nativeUdp) " · UDP ✓" else ""
            val exit = ServerTester.exitCountry[n.tag]
            val sp = ServerTester.speed[n.tag]
            val forGame = if (AppSettings.gameMode && ServerTester.isRussian(n)) " · приоритет для игры" else ""
            h.sub.text = n.type + udp + forGame + (r?.let { " · разброс ${it.jitterMs} ms" } ?: "") +
                (sp?.let { " · ↓ " + String.format(java.util.Locale.US, "%.0f", it) + " Мбит/с" } ?: "") +
                (exit?.let { " · выход ${GeoDb.flag(it)}" } ?: "")
            h.ping.text = when {
                !ServerTester.results.containsKey(n.tag) -> if (ServerTester.testing) "…" else ""
                r == null -> "✖"
                else -> "${r.medianMs} ms"
            }
            h.ping.setTextColor(if (r == null && ServerTester.results.containsKey(n.tag)) Ui.RED else Ui.pingColor(r?.medianMs))
            h.root.setOnClickListener { pick(n) }
            h.root.setOnLongClickListener { marks(n); true }
        }
    }
}
