package dev.mlbb.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** «Матчи»: пинг и потери в каждом матче MLBB, лучшее время для игры. */
class MatchesActivity : AppCompatActivity() {
    private fun d(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        AppSettings.load(this)
        setTheme(Ui.themeRes())
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        Ui.applyWindow(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16f), d(20f), d(16f), d(24f))
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.backButton(this) { finish() })
        top.addView(Ui.text(this, "Матчи", 22f, bold = true))
        root.addView(top)

        val list = MatchTracker.history(this)
        if (list.isEmpty()) {
            root.addView(Ui.card(this).apply {
                addView(Ui.text(this@MatchesActivity, "Пока ни одного матча", 16f, bold = true))
                addView(Ui.text(this@MatchesActivity,
                    "Подключись в режиме MLBB и сыграй матч — здесь появится пинг, потери и сервер игры. " +
                        "Матчи короче 2 минут (тренировка, вылет) не считаются.", 13f, Ui.MUTED).apply { setPadding(0, d(6f), 0, 0) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(16f) })
            setContentView(ScrollView(this).apply { addView(root) })
            return
        }

        // Итог
        val avg = list.map { it.avgPing }.average().toInt()
        val loss = list.map { it.lossPct }.average()
        val best = MatchTracker.bestHour(list)
        root.addView(Ui.card(this).apply {
            val row = LinearLayout(this@MatchesActivity)
            fun stat(value: String, label: String, color: Int) = LinearLayout(this@MatchesActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(Ui.text(this@MatchesActivity, value, 22f, color, bold = true).apply { gravity = Gravity.CENTER })
                addView(Ui.text(this@MatchesActivity, label, 12f, Ui.MUTED).apply { gravity = Gravity.CENTER })
            }
            row.addView(stat("${list.size}", "матчей", Ui.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(stat("$avg мс", "средний пинг", Ui.pingColor(avg)), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(stat(String.format(Locale.US, "%.1f%%", loss), "потери", if (loss < 1) Ui.GREEN else if (loss < 5) Ui.YELLOW else Ui.RED),
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(row, LinearLayout.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(16f) })

        // Пинг по часам
        root.addView(Ui.card(this).apply {
            addView(Ui.text(this@MatchesActivity, "Пинг по времени суток", 15f, bold = true))
            addView(Ui.text(this@MatchesActivity,
                best?.let { (h, ms) -> "Лучшее время для игры: %02d:00–%02d:00 — в среднем $ms мс".format(h, (h + 1) % 24) }
                    ?: "Сыграй ещё пару матчей в разное время — подскажу, когда пинг ниже",
                12f, if (best != null) Ui.GREEN else Ui.MUTED).apply { setPadding(0, d(2f), 0, d(8f)) })
            addView(HourChart(this@MatchesActivity, MatchTracker.byHour(list), best?.first), LinearLayout.LayoutParams(-1, d(120f)))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(10f) })

        // Список матчей
        root.addView(Ui.text(this, "ПОСЛЕДНИЕ МАТЧИ", 12f, Ui.MUTED, bold = true).apply {
            setPadding(d(4f), d(20f), 0, d(8f)); letterSpacing = 0.08f
        })
        val fmt = SimpleDateFormat("d MMM, HH:mm", Locale("ru"))
        for (m in list.take(60)) {
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(d(14f), d(10f), d(14f), d(10f))
                background = Ui.rounded(Ui.CARD, d(14f).toFloat())
            }
            val flag = Ui.text(this, "", 24f).apply { Ui.setFlag(this, if (m.country.length == 2) GeoDb.flag(m.country) else "🌐", 24f) }
            row.addView(flag)
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(12f), 0, d(8f), 0) }
            col.addView(Ui.text(this, "${fmt.format(Date(m.start))} · ${m.durationSec / 60} мин", 14f, bold = true))
            col.addView(Ui.text(this, (m.city.ifEmpty { "сервер игры" }) + " · ${m.minPing}–${m.maxPing} мс" +
                (if (m.lossPct > 0) " · потери ${m.lossPct}%" else ""), 12f, Ui.MUTED))
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Ui.text(this, "${m.avgPing} мс", 16f, Ui.pingColor(m.avgPing), bold = true))
            root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(6f) })
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    /** Столбики среднего пинга по часам суток; лучший час подсвечен. */
    private class HourChart(ctx: Context, val values: Array<Int?>, val best: Int?) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onDraw(c: Canvas) {
            val dens = resources.displayMetrics.density
            val labelH = 14 * dens
            val h = height - labelH
            val w = width / 24f
            val max = (values.filterNotNull().maxOrNull() ?: 100).coerceAtLeast(60)
            p.textSize = 9 * dens
            p.textAlign = Paint.Align.CENTER
            for (i in 0 until 24) {
                val v = values[i]
                val x = i * w
                if (v == null) {
                    p.color = Ui.DIVIDER
                    r.set(x + w * 0.2f, h - 3 * dens, x + w * 0.8f, h)
                } else {
                    p.color = if (i == best) Ui.GREEN else Ui.pingColor(v).let { (it and 0x00FFFFFF) or 0xB0000000.toInt() }
                    r.set(x + w * 0.2f, h - (h - 4 * dens) * v / max, x + w * 0.8f, h)
                }
                c.drawRoundRect(r, 3 * dens, 3 * dens, p)
                if (i % 3 == 0) {
                    p.color = Ui.MUTED
                    c.drawText("$i", x + w / 2, height - 2 * dens, p)
                }
            }
        }
    }
}
