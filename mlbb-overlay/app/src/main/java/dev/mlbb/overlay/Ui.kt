package dev.mlbb.overlay

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Палитра и маленькие помощники для экранов в стиле макета (тёмная тема). */
object Ui {
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

    /** Название без флага в начале. */
    fun cleanName(n: Subscription.Node) =
        n.name.replace(Regex("^[\\x{1F1E6}-\\x{1F1FF}]{2}\\s*"), "").trim()
}
