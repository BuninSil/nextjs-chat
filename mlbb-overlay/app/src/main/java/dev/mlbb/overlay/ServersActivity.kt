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

    private val refresher = object : Runnable {
        override fun run() {
            adapter.reload()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.decorView.setBackgroundColor(Ui.BG)
        val d = { v: Float -> Ui.dp(this, v) }
        AppSettings.load(this)
        Subscription.load(this)
        ServerTester.loadExits(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16f), d(20f), d(16f), 0)
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.text(this, "←", 22f, bold = true).apply {
            setPadding(0, 0, d(12f), 0); setOnClickListener { finish() }
        })
        top.addView(Ui.text(this, "Серверы", 22f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(Ui.button(this, " ↻ ") { retest() })
        root.addView(top)
        root.addView(Ui.space(this, 10f))
        // Отдельные проверки: страна выхода (запоминается) и скорость
        val actions = LinearLayout(this)
        actions.addView(Ui.tile(this, "🌍  Выходы", "в какой стране сервер выходит в интернет") { checkExits() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = d(5f) })
        actions.addView(Ui.tile(this, "⚡  По скорости", "найти и включить самый быстрый") { speedRank() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d(5f) })
        root.addView(actions)
        root.addView(Ui.text(this, "↻ вверху — перемерить пинг. Тапни сервер в списке, чтобы подключиться к нему вручную.", 12f, Ui.MUTED)
            .apply { setPadding(d(4f), d(10f), d(4f), 0) })
        root.addView(Ui.space(this, 12f))

        val autoCard = Ui.card(this)
        val auto = CheckBox(this).apply {
            text = "Авто: самый быстрый при ИГРАТЬ"
            setTextColor(Ui.TEXT)
            isChecked = AppSettings.autoSelect
            setOnCheckedChangeListener { _, v ->
                AppSettings.autoSelect = v
                AppSettings.save(this@ServersActivity)
            }
        }
        autoCard.addView(auto)
        header = Ui.text(this, "", 12f, Ui.MUTED)
        autoCard.addView(header)
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
        Thread {
            val ranked = ServerTester.measure(applicationContext) { done, total ->
                runOnUiThread { header.text = "Проверено $done из $total…" }
            }
            // При включённом VPN и автовыборе — сразу переключаемся на лучший рабочий
            if (AppSettings.autoSelect && BoxVpnService.isRunning) ServerTester.pickWorking(applicationContext, ranked)
            runOnUiThread { header.text = "" }
        }.start()
    }

    /** Проверка реальной страны выхода у всех серверов (запоминается до обновления подписки). */
    private fun checkExits() {
        if (!BoxVpnService.isRunning) {
            Toast.makeText(this, "Страна выхода проверяется через VPN — сначала подключись", Toast.LENGTH_LONG).show()
            return
        }
        if (ServerTester.testing) return
        Thread {
            val (n, ru) = ServerTester.checkAllExits(applicationContext) { done, total ->
                runOnUiThread { header.text = "Выходы: $done из $total…" }
            }
            runOnUiThread {
                header.text = ""
                val msg = if (n == 0) "Не удалось определить выход ни у одного сервера — проверь, что VPN работает"
                else "Выход определён у $n серверов, в России: $ru"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    /** Замер скорости через лучшие по пингу серверы; выбирает самый быстрый. */
    private fun speedRank() {
        if (!BoxVpnService.isRunning) {
            Toast.makeText(this, "Скорость меряется через VPN — сначала подключись", Toast.LENGTH_LONG).show()
            return
        }
        if (ServerTester.testing) return
        Thread {
            val ranked = ServerTester.measure(applicationContext) { done, total ->
                runOnUiThread { header.text = "Пинг: $done из $total…" }
            }
            ServerTester.pickFastest(applicationContext, ranked, 8) { done, total ->
                runOnUiThread { header.text = "Скорость: сервер $done из $total…" }
            }
            runOnUiThread { header.text = "" }
        }.start()
    }

    private fun pick(n: Subscription.Node) {
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
            val all = Subscription.usable(this@ServersActivity).filterNot { ServerTester.isSeparator(it) }
            // Рабочие сверху; в игровом режиме среди них российские первыми
            val game = AppSettings.gameMode
            items = all.sortedWith(compareBy<Subscription.Node> {
                when {
                    !ServerTester.results.containsKey(it.tag) -> 1
                    ServerTester.results[it.tag] == null -> 2
                    else -> 0
                }
            }.thenBy { if (game && !ServerTester.isRussian(it)) 1 else 0 }
                .thenBy { ServerTester.results[it.tag]?.score ?: Int.MAX_VALUE })
            if (!ServerTester.testing && header.text.isEmpty()) {
                header.text = "${items.size} серверов · пинг напрямую до сервера · ↻ перемерить" +
                    if (AppSettings.gameMode) " · ★ ближе к серверам игры" else ""
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
            h.root.background = Ui.rounded(if (selected) 0xFF182A20.toInt() else Ui.CARD, Ui.dp(h.root.context, 12f).toFloat())
            h.flag.text = Ui.flagFor(n)
            h.name.text = (if (AppSettings.gameMode && ServerTester.isRussian(n)) "★ " else "") + Ui.cleanName(n)
            val r = ServerTester.results[n.tag]
            val udp = if (n.nativeUdp) " · UDP ✓" else ""
            val exit = ServerTester.exitCountry[n.tag]
            val sp = ServerTester.speed[n.tag]
            h.sub.text = n.type + udp + (r?.let { " · разброс ${it.jitterMs} ms" } ?: "") +
                (sp?.let { " · ↓ " + String.format(java.util.Locale.US, "%.0f", it) + " Мбит/с" } ?: "") +
                (exit?.let { " · выход ${GeoDb.flag(it)}" } ?: "")
            h.ping.text = when {
                !ServerTester.results.containsKey(n.tag) -> if (ServerTester.testing) "…" else ""
                r == null -> "✖"
                else -> "${r.medianMs} ms"
            }
            h.ping.setTextColor(if (r == null && ServerTester.results.containsKey(n.tag)) Ui.RED else Ui.pingColor(r?.medianMs))
            h.root.setOnClickListener { pick(n) }
        }
    }
}
