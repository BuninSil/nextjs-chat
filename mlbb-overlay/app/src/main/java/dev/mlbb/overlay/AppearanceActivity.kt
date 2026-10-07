package dev.mlbb.overlay

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Оформление: тема и анимация подключения (с живым превью). */
class AppearanceActivity : AppCompatActivity() {
    private fun d(v: Float) = Ui.dp(this, v)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var ring: ConnectRing
    private lateinit var demoButton: TextView
    private lateinit var flash: View
    private var stars: StarField? = null
    private var demo = false

    companion object {
        val ANIMS = listOf(
            Triple(AppSettings.ANIM_WARP, "Гиперпрыжок", "Звёзды на весь экран срываются в лучи, при подключении — вспышка"),
            Triple(AppSettings.ANIM_GAUGE, "Спидометр", "Шкала вокруг кнопки, стрелка улетает в красную зону, потом показывает скорость"),
            Triple(AppSettings.ANIM_RADAR, "Радар", "Луч ищет серверы, при подключении лучший берётся в прицел"),
        )

        fun animTitle(id: String) = ANIMS.firstOrNull { it.first == id }?.second ?: ANIMS[0].second
    }

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
        top.addView(Ui.text(this, "Оформление", 22f, bold = true))
        root.addView(top)

        // ---------- Тема ----------
        root.addView(section("Тема"))
        Ui.THEMES.chunked(2).forEach { pair ->
            val row = LinearLayout(this)
            pair.forEachIndexed { i, t ->
                row.addView(themeCard(t), LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (i == 0) marginEnd = d(5f) else marginStart = d(5f)
                })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = d(5f) })
            root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(10f) })
        }

        // ---------- Анимация подключения ----------
        root.addView(section("Анимация подключения"))
        root.addView(preview(), LinearLayout.LayoutParams(-1, d(300f)))
        root.addView(Ui.text(this, "Нажми на кнопку, чтобы посмотреть", 12f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, d(6f), 0, d(10f))
        }, LinearLayout.LayoutParams(-1, -2))
        for ((id, title, sub) in ANIMS) {
            root.addView(animCard(id, title, sub), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(8f) })
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun section(title: String) = Ui.text(this, title.uppercase(), 12f, Ui.MUTED, bold = true).apply {
        setPadding(d(4f), d(22f), 0, d(8f))
        letterSpacing = 0.08f
    }

    /** Превью: та же кнопка и та же анимация, что на главном экране. */
    private fun preview(): FrameLayout {
        val box = FrameLayout(this).apply {
            background = Ui.rounded(Ui.CARD, d(18f).toFloat())
            clipToOutline = true
        }
        demoButton = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            elevation = d(6f).toFloat()
            setOnClickListener { runDemo() }
        }
        ring = ConnectRing(this).apply { style = AppSettings.anim }
        if (AppSettings.anim == AppSettings.ANIM_WARP) {
            stars = StarField(this).also {
                it.anchor = demoButton
                box.addView(it, FrameLayout.LayoutParams(-1, -1))
            }
        }
        ring.anchor = demoButton
        box.addView(ring, FrameLayout.LayoutParams(-1, -1))
        box.addView(demoButton, FrameLayout.LayoutParams(d(180f), d(180f), Gravity.CENTER))
        flash = View(this).apply {
            setBackgroundColor(0xFFE8FFF0.toInt())
            visibility = View.GONE
        }
        box.addView(flash, FrameLayout.LayoutParams(-1, -1))
        showButton(connecting = false, running = false)
        return box
    }

    private fun showButton(connecting: Boolean, running: Boolean) {
        if (connecting) {
            demoButton.text = Ui.iconText(this, R.drawable.ic_bolt, "", 48f, tint = 0xFFFFFFFF.toInt())
        } else {
            demoButton.text = if (running) "ОТКЛЮЧИТЬ" else "ПОДКЛЮЧИТЬ"
            demoButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19f)
        }
        val colors = if (running) intArrayOf(Ui.theme.redTop.toInt(), Ui.theme.redBottom.toInt())
        else intArrayOf(Ui.theme.accentTop.toInt(), Ui.theme.accentBottom.toInt())
        demoButton.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { shape = GradientDrawable.OVAL }
    }

    /** Подключение 2,5 секунды → «подключено» → через 3 секунды обратно. */
    private fun runDemo() {
        if (demo) return
        demo = true
        AppLog.ui("превью анимации «${animTitle(AppSettings.anim)}»")
        showButton(connecting = true, running = false)
        ring.setConnecting(true)
        stars?.setState(true, false)
        val pulse = android.animation.ObjectAnimator.ofPropertyValuesHolder(
            demoButton,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.95f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.95f),
        ).apply {
            duration = 650
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            start()
        }
        handler.postDelayed({
            pulse.cancel()
            demoButton.scaleX = 1f; demoButton.scaleY = 1f
            showButton(connecting = false, running = true)
            ring.setConnecting(false)
            ring.setRunning(true)
            stars?.setState(false, true)
            ring.connected()
            if (stars != null) StarField.flash(flash)
            demoButton.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).withEndAction {
                demoButton.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
            }.start()
            ConnectRing.buzz(demoButton, strong = true)
            // Для спидометра — примерная скорость, как будто что-то качается
            handler.postDelayed({ ring.setSpeed(5_500_000.0) }, 800)
        }, 2500)
        handler.postDelayed({
            showButton(connecting = false, running = false)
            ring.setSpeed(0.0)
            ring.setRunning(false)
            stars?.setState(false, false)
            ConnectRing.buzz(demoButton, strong = false)
            demo = false
        }, 6500)
    }

    private fun animCard(id: String, title: String, sub: String): LinearLayout {
        val selected = AppSettings.anim == id
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(14f), d(12f), d(14f), d(12f))
            background = Ui.rounded(if (selected) Ui.SEL else Ui.CARD, d(16f).toFloat()).apply {
                if (selected) setStroke(d(2f), Ui.GREEN)
            }
        }
        c.addView(Ui.text(this, title, 15f, bold = selected))
        c.addView(Ui.text(this, sub, 12f, Ui.MUTED).apply { setPadding(0, d(2f), 0, 0) })
        c.setOnClickListener {
            if (AppSettings.anim == id) { runDemo(); return@setOnClickListener }
            AppSettings.anim = id
            AppLog.set("Анимация", title)
            AppSettings.save(this)
            recreate()
        }
        return c
    }

    /** Карточка темы: превью цветов и название; выбор сразу перекрашивает приложение. */
    private fun themeCard(t: Theme): LinearLayout {
        val selected = AppSettings.theme == t.id
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(12f), d(12f), d(12f), d(12f))
            background = Ui.rounded(if (selected) Ui.SEL else Ui.CARD, d(16f).toFloat()).apply {
                if (selected) setStroke(d(2f), Ui.GREEN)
            }
        }
        val preview = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(8f), d(8f), d(8f), d(8f))
            background = Ui.rounded(t.bg.toInt(), d(10f).toFloat()).apply { setStroke(d(1f), Ui.DIVIDER) }
        }
        preview.addView(View(this).apply { background = Ui.rounded(t.card.toInt(), d(5f).toFloat()) },
            LinearLayout.LayoutParams(0, d(16f), 1f).apply { marginEnd = d(6f) })
        preview.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(t.accentTop.toInt(), t.accentBottom.toInt()))
                .apply { shape = GradientDrawable.OVAL }
        }, LinearLayout.LayoutParams(d(18f), d(18f)))
        c.addView(preview, LinearLayout.LayoutParams(-1, -2))
        c.addView(Ui.text(this, t.title, 14f, bold = selected).apply { setPadding(0, d(8f), 0, 0) })
        c.setOnClickListener {
            if (AppSettings.theme == t.id) return@setOnClickListener
            AppSettings.theme = t.id
            AppLog.set("Тема", t.title)
            AppSettings.save(this)
            recreate()
        }
        return c
    }
}
