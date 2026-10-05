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

/** Палитра и маленькие помощники для экранов в стиле макета (тёмная тема). */
object Ui {
    /** Слоган приложения (в интерфейсе — с векторной молнией, см. [slogan]) */
    const val SLOGAN = "Быстрее нас — только свет"
    const val BG = 0xFF121417.toInt()
    const val CARD = 0xFF1A1D22.toInt()
    const val TEXT = 0xFFE8EAED.toInt()
    const val MUTED = 0xFF8B9098.toInt()
    const val GREEN = 0xFF34C759.toInt()
    const val GREEN_DARK = 0xFF1F8F4C.toInt()
    const val YELLOW = 0xFFF5C542.toInt()
    const val RED = 0xFFFF5A52.toInt()

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
        setOnClickListener { onClick() }
    }

    /** Векторный значок нужного размера (dp). */
    fun icon(ctx: Context, res: Int, sizeDp: Float): Drawable =
        ctx.getDrawable(res)!!.mutate().apply {
            val s = dp(ctx, sizeDp)
            setBounds(0, 0, s, s)
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
    fun iconText(ctx: Context, res: Int, text: CharSequence, sizeDp: Float = 18f, gapDp: Float = 8f): CharSequence =
        SpannableStringBuilder().apply {
            append(" ", IconSpan(icon(ctx, res, sizeDp), if (text.isEmpty()) 0 else dp(ctx, gapDp)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(text)
        }

    /** Слоган зелёным с молнией. */
    fun slogan(ctx: Context, size: Float = 12f, bold: Boolean = false) =
        text(ctx, "", size, GREEN, bold).apply { text = iconText(ctx, R.drawable.ic_bolt, SLOGAN, size, 4f) }

    /** Кнопка-значок (шестерёнка, обновить). */
    fun iconButton(ctx: Context, res: Int, onClick: () -> Unit) = ImageView(ctx).apply {
        setImageDrawable(icon(ctx, res, 22f))
        scaleType = ImageView.ScaleType.CENTER
        background = rounded(0xFF23272D.toInt(), dp(ctx, 12f).toFloat())
        layoutParams = LinearLayout.LayoutParams(dp(ctx, 44f), dp(ctx, 44f))
        setOnClickListener { onClick() }
    }

    /** Стрелка «назад» для шапки экрана. */
    fun backButton(ctx: Context, onClick: () -> Unit) = ImageView(ctx).apply {
        setImageDrawable(icon(ctx, R.drawable.ic_back_w, 24f))
        setPadding(0, 0, dp(ctx, 12f), 0)
        setOnClickListener { onClick() }
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
