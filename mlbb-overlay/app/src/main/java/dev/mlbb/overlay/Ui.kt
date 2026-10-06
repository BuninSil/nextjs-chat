package dev.mlbb.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Цветовая тема приложения. Все экраны берут цвета отсюда, а не зашитые в код. */
data class Theme(
    val id: String,
    val title: String,
    val light: Boolean,
    /** Фон экрана */
    val bg: Long,
    /** Карточки */
    val card: Long,
    /** Кнопки второго плана, шапочные кнопки, выбор в окнах */
    val card2: Long,
    /** Выбранная карточка */
    val sel: Long,
    /** Поле ввода */
    val input: Long,
    val text: Long,
    val muted: Long,
    /** Текст описаний (светлее приглушённого) */
    val sub: Long,
    val hint: Long,
    val divider: Long,
    /** Акцент: значки, «подключено», переключатели */
    val accent: Long,
    /** Градиент большой кнопки и главные кнопки */
    val accentTop: Long,
    val accentBottom: Long,
    val button: Long,
    val red: Long = 0xFFFF5A52,
    val redTop: Long = 0xFFE5534B,
    val redBottom: Long = 0xFFA8322C,
    val yellow: Long = 0xFFF5C542,
    val switchOff: Long,
)

/** Палитра и маленькие помощники для экранов в стиле макета. */
object Ui {
    /** Слоган приложения (в интерфейсе — с векторной молнией, см. [slogan]) */
    const val SLOGAN = "Быстрее нас — только свет"

    val THEMES = listOf(
        // Тёмная — тема по умолчанию, та же, что была
        Theme("dark", "Тёмная", false, bg = 0xFF121417, card = 0xFF1A1D22, card2 = 0xFF23272D, sel = 0xFF182A20,
            input = 0xFF0F1114, text = 0xFFE8EAED, muted = 0xFF8B9098, sub = 0xFFC9CCD1, hint = 0xFF5F6368,
            divider = 0xFF262A30, accent = 0xFF34C759, accentTop = 0xFF43D17A, accentBottom = 0xFF1F8F4C,
            button = 0xFF2FB565, switchOff = 0xFF3A3F46),
        Theme("light", "Светлая", true, bg = 0xFFF3F5F7, card = 0xFFFFFFFF, card2 = 0xFFE8EBEF, sel = 0xFFE2F5E8,
            input = 0xFFF1F3F5, text = 0xFF15181C, muted = 0xFF6B7280, sub = 0xFF3C4148, hint = 0xFF9AA0A6,
            divider = 0xFFE3E6EA, accent = 0xFF1FA34A, accentTop = 0xFF34C759, accentBottom = 0xFF178A3D,
            button = 0xFF22A350, red = 0xFFE5484D, yellow = 0xFFB7791F, switchOff = 0xFFC9CED6),
        Theme("neon", "Неон", false, bg = 0xFF07070F, card = 0xFF12122A, card2 = 0xFF1C1C3D, sel = 0xFF0C2633,
            input = 0xFF0A0A1C, text = 0xFFEAF6FF, muted = 0xFF8C90BE, sub = 0xFFC7CBEA, hint = 0xFF5C5F8A,
            divider = 0xFF24244A, accent = 0xFF00E5FF, accentTop = 0xFF00E5FF, accentBottom = 0xFF7A1FFF,
            button = 0xFF00A8C6, red = 0xFFFF3D7F, redTop = 0xFFFF3D7F, redBottom = 0xFF9C1AFF, yellow = 0xFFFFD54F,
            switchOff = 0xFF34345A),
        Theme("amoled", "AMOLED (чёрная)", false, bg = 0xFF000000, card = 0xFF0E0F11, card2 = 0xFF1A1C1F, sel = 0xFF0C2214,
            input = 0xFF000000, text = 0xFFE8EAED, muted = 0xFF8B9098, sub = 0xFFC9CCD1, hint = 0xFF5F6368,
            divider = 0xFF1E2024, accent = 0xFF34C759, accentTop = 0xFF43D17A, accentBottom = 0xFF1F8F4C,
            button = 0xFF2FB565, switchOff = 0xFF33373D),
    )

    val theme: Theme get() = THEMES.firstOrNull { it.id == AppSettings.theme } ?: THEMES[0]

    val BG get() = theme.bg.toInt()
    val CARD get() = theme.card.toInt()
    val CARD2 get() = theme.card2.toInt()
    val SEL get() = theme.sel.toInt()
    val INPUT get() = theme.input.toInt()
    val TEXT get() = theme.text.toInt()
    val MUTED get() = theme.muted.toInt()
    val SUB get() = theme.sub.toInt()
    val HINT get() = theme.hint.toInt()
    val DIVIDER get() = theme.divider.toInt()
    val GREEN get() = theme.accent.toInt()
    val GREEN_DARK get() = theme.accentBottom.toInt()
    val BUTTON get() = theme.button.toInt()
    val YELLOW get() = theme.yellow.toInt()
    val RED get() = theme.red.toInt()
    val SWITCH_OFF get() = theme.switchOff.toInt()

    /** Окно и полосы системы под тему. Вызывать в onCreate каждого экрана. */
    fun applyWindow(a: android.app.Activity) {
        a.window.decorView.setBackgroundColor(BG)
        a.window.statusBarColor = BG
        a.window.navigationBarColor = BG
        @Suppress("DEPRECATION")
        var flags = a.window.decorView.systemUiVisibility
        flags = if (theme.light) flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        else flags and (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
        @Suppress("DEPRECATION")
        a.window.decorView.systemUiVisibility = flags
    }

    /** Стиль системных окон (AlertDialog) под тему — ставить до super.onCreate. */
    fun themeRes() = if (theme.light) R.style.AppTheme_Light else R.style.AppTheme

    /** Переключатель в цветах темы. */
    fun styleSwitch(sw: androidx.appcompat.widget.SwitchCompat) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = android.content.res.ColorStateList(states, intArrayOf(0xFFFFFFFF.toInt(), 0xFFB0B4BA.toInt()))
        sw.trackTintList = android.content.res.ColorStateList(states, intArrayOf(GREEN, SWITCH_OFF))
    }

    fun dp(ctx: Context, v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, dp(ctx, 18f).toFloat())
        val p = dp(ctx, 16f)
        setPadding(p, dp(ctx, 14f), p, dp(ctx, 14f))
    }

    fun text(ctx: Context, s: String = "", size: Float = 15f, color: Int = TEXT, bold: Boolean = false) =
        TextView(ctx).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun button(ctx: Context, s: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = s
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(CARD, dp(ctx, 14f).toFloat())
        val p = dp(ctx, 14f)
        setPadding(p, p, p, p)
        setOnClickListener { onClick() }
    }

    /** Плитка: заголовок + пояснение мелким шрифтом, чтобы было понятно, что делает кнопка. */
    fun tile(ctx: Context, iconRes: Int, title: String, hint: String, onClick: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, dp(ctx, 14f).toFloat())
        val p = dp(ctx, 14f)
        setPadding(p, dp(ctx, 12f), p, dp(ctx, 12f))
        addView(text(ctx, "", 15f, bold = true).apply { maxLines = 1; text = iconText(ctx, iconRes, title, 18f) })
        addView(text(ctx, hint, 12f, MUTED).apply { setPadding(0, dp(ctx, 3f), 0, 0) })
        setOnClickListener { AppLog.ui("нажал плитку «$title»"); onClick() }
    }

    /** Векторный значок нужного размера (dp). */
    /** У каких значков есть вырезы — они красятся в цвет фона под значком. */
    private val CUTS = mapOf(
        R.drawable.ic_servers to R.drawable.ic_servers_cut,
        R.drawable.ic_exits to R.drawable.ic_exits_cut,
        R.drawable.ic_settings to R.drawable.ic_settings_cut,
        R.drawable.ic_game to R.drawable.ic_game_cut,
        R.drawable.ic_update to R.drawable.ic_update_cut,
        R.drawable.ic_csv to R.drawable.ic_csv_cut,
        R.drawable.ic_paste to R.drawable.ic_paste_cut,
    )
    /** Белые контурные значки навигации — цвета текста. */
    private val OUTLINE = setOf(
        R.drawable.ic_back_w, R.drawable.ic_refresh_w, R.drawable.ic_settings_w, R.drawable.ic_paste_w,
        R.drawable.ic_link_w, R.drawable.ic_download_w, R.drawable.ic_folder_w, R.drawable.ic_csv_w,
    )

    /**
     * Векторный значок нужного размера (dp) в цветах темы.
     * tint — цвет значка (по умолчанию акцент, у контурных — цвет текста);
     * under — цвет фона под значком, в него красятся вырезы.
     */
    fun icon(ctx: Context, res: Int, sizeDp: Float, tint: Int? = null, under: Int = CARD): Drawable {
        val color = tint ?: if (res in OUTLINE) TEXT else GREEN
        val body = ctx.getDrawable(res)!!.mutate().apply { setTint(color) }
        val cutRes = CUTS[res]
        val d = if (cutRes == null) body
        else android.graphics.drawable.LayerDrawable(arrayOf(body, ctx.getDrawable(cutRes)!!.mutate().apply { setTint(under) }))
        val s = dp(ctx, sizeDp)
        d.setBounds(0, 0, s, s)
        return d
    }

    /** Рисует значок внутри текста, по центру строки — работает и в тексте по центру, и посреди фразы. */
    private class IconSpan(private val d: Drawable, private val gap: Int) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            if (fm != null) {
                val pfm = paint.fontMetricsInt
                val h = d.bounds.height()
                val center = (pfm.descent + pfm.ascent) / 2
                fm.ascent = minOf(pfm.ascent, center - h / 2)
                fm.descent = maxOf(pfm.descent, center + h / 2)
                fm.top = fm.ascent
                fm.bottom = fm.descent
            }
            return d.bounds.width() + gap
        }

        override fun draw(c: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val pfm = paint.fontMetricsInt
            c.save()
            c.translate(x, y + (pfm.descent + pfm.ascent) / 2f - d.bounds.height() / 2f)
            d.draw(c)
            c.restore()
        }
    }

    /** Текст со значком перед ним. */
    fun iconText(
        ctx: Context, res: Int, text: CharSequence, sizeDp: Float = 18f, gapDp: Float = 8f,
        tint: Int? = null, under: Int = CARD,
    ): CharSequence =
        SpannableStringBuilder().apply {
            append(" ", IconSpan(icon(ctx, res, sizeDp, tint, under), if (text.isEmpty()) 0 else dp(ctx, gapDp)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(text)
        }

    /** Слоган зелёным с молнией. */
    fun slogan(ctx: Context, size: Float = 12f, bold: Boolean = false) =
        text(ctx, "", size, GREEN, bold).apply { text = iconText(ctx, R.drawable.ic_bolt, SLOGAN, size, 4f) }

    /** Кнопка-значок (шестерёнка, обновить). */
    fun iconButton(ctx: Context, res: Int, onClick: () -> Unit) = ImageView(ctx).apply {
        setImageDrawable(icon(ctx, res, 22f))
        scaleType = ImageView.ScaleType.CENTER
        background = rounded(CARD2, dp(ctx, 12f).toFloat())
        layoutParams = LinearLayout.LayoutParams(dp(ctx, 44f), dp(ctx, 44f))
        setOnClickListener {
            AppLog.ui("нажал кнопку «" + when (res) {
                R.drawable.ic_settings_w -> "Настройки (шестерёнка)"
                R.drawable.ic_refresh_w -> "перемерить пинг"
                else -> ctx.resources.getResourceEntryName(res)
            } + "»")
            onClick()
        }
    }

    /** Стрелка «назад» для шапки экрана. */
    fun backButton(ctx: Context, onClick: () -> Unit) = ImageView(ctx).apply {
        setImageDrawable(icon(ctx, R.drawable.ic_back_w, 24f))
        setPadding(0, 0, dp(ctx, 12f), 0)
        setOnClickListener { AppLog.ui("нажал «назад»"); onClick() }
    }

    fun space(ctx: Context, h: Float) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(ctx, h))
    }

    fun pingColor(ms: Int?) = when {
        ms == null || ms < 0 -> MUTED
        ms <= 60 -> GREEN
        ms <= 100 -> YELLOW
        else -> RED
    }

    /** Флаг из названия сервера (если там есть эмодзи-флаг) или по базе DB-IP. */
    fun flagFor(n: Subscription.Node): String {
        val m = Regex("[\\x{1F1E6}-\\x{1F1FF}]{2}").find(n.name)
        if (m != null) return m.value
        // Только для IP-адресов: домен пришлось бы резолвить, а это сеть на главном потоке
        if (!n.server.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return "🌐"
        return GeoDb.lookup(n.server)?.let { GeoDb.flag(it.countryCode) } ?: "🌐"
    }

    /** Флаг страны текстом, а если страна неизвестна — значок глобуса. */
    fun setFlag(tv: TextView, flag: String, sizeDp: Float = 26f) {
        tv.text = if (flag == "🌐") iconText(tv.context, R.drawable.ic_servers, "", sizeDp) else flag
    }

    /** Название без флага в начале. */
    fun cleanName(n: Subscription.Node) =
        n.name.replace(Regex("^[\\x{1F1E6}-\\x{1F1FF}]{2}\\s*"), "").trim()
}
