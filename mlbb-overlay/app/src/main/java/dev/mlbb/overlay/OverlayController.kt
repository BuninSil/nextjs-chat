package dev.mlbb.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

/** Перетаскиваемая плашка поверх всех окон (SYSTEM_ALERT_WINDOW). */
class OverlayController(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = ctx.getSharedPreferences("overlay", Context.MODE_PRIVATE)
    private var view: TextView? = null
    private val bg = GradientDrawable().apply {
        cornerRadius = dp(10f)
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = prefs.getInt("x", 24)
        y = prefs.getInt("y", 160)
    }

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (view != null) return
        val tv = TextView(ctx).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val p = dp(8f).toInt()
            setPadding(p, p / 2, p, p / 2)
            background = bg
            text = "Жду трафик игры…"
        }
        setColor(COLOR_UNKNOWN)

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        tv.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - downX).toInt()
                    params.y = startY + (e.rawY - downY).toInt()
                    view?.let { wm.updateViewLayout(it, params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
                    true
                }
                else -> false
            }
        }
        wm.addView(tv, params)
        view = tv
    }

    fun update(text: String, color: Int) {
        val v = view ?: return
        if (v.text != text) v.text = text
        setColor(color)
    }

    private fun setColor(color: Int) {
        bg.setColor(color)
    }

    fun hide() {
        view?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        view = null
    }

    companion object {
        const val COLOR_RUSSIA = 0xE02E7D32.toInt()
        const val COLOR_FOREIGN = 0xE0C62828.toInt()
        const val COLOR_UNKNOWN = 0xD0424242.toInt()
    }
}
