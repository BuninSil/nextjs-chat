package dev.mlbb.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator

/**
 * Кольцо вокруг большой кнопки: пока идёт подключение — по нему бежит светящаяся дуга,
 * в момент подключения — вспышка (кольцо расходится и гаснет).
 */
class ConnectRing(ctx: Context) : View(ctx) {
    private val stroke = Ui.dp(ctx, 4f).toFloat()
    private val glow = Ui.dp(ctx, 10f).toFloat()
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
    }
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val oval = RectF()

    private var angle = 0f
    private var spinner: ValueAnimator? = null
    private var flashT = -1f
    private var flasher: ValueAnimator? = null

    init {
        // Свечение дуги рисуется тенью — нужна программная отрисовка этого слоя
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    val spinning get() = spinner != null

    fun spin(on: Boolean) {
        if (on == spinning) return
        if (on) {
            spinner = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 1100
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { angle = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            spinner?.cancel()
            spinner = null
            invalidate()
        }
    }

    /** Вспышка: кольцо расходится от кнопки и гаснет. */
    fun flash() {
        flasher?.cancel()
        flasher = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            interpolator = DecelerateInterpolator()
            addUpdateListener { flashT = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { flashT = -1f; invalidate() }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        spinner?.cancel()
        flasher?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val color = Ui.GREEN
        val cx = width / 2f
        val cy = height / 2f
        val maxR = minOf(width, height) / 2f - glow - stroke
        val r = maxR - Ui.dp(context, 12f)
        if (spinning) {
            oval.set(cx - r, cy - r, cx + r, cy + r)
            trackPaint.color = (color and 0x00FFFFFF) or 0x30000000
            c.drawOval(oval, trackPaint)
            arcPaint.color = color
            arcPaint.setShadowLayer(glow, 0f, 0f, color)
            c.drawArc(oval, angle - 90f, 100f, false, arcPaint)
        }
        if (flashT >= 0f) {
            val fr = r + (maxR - r) * flashT
            val alpha = ((1f - flashT) * 255).toInt().coerceIn(0, 255)
            flashPaint.color = (color and 0x00FFFFFF) or (alpha shl 24)
            flashPaint.strokeWidth = stroke * (1.6f - flashT)
            flashPaint.setShadowLayer(glow, 0f, 0f, flashPaint.color)
            c.drawCircle(cx, cy, fr, flashPaint)
        }
    }

    companion object {
        /** Вибрация: strong — «подключено» (короткий уверенный толчок), иначе лёгкий щелчок. */
        fun buzz(v: View, strong: Boolean) {
            val constant = when {
                strong && Build.VERSION.SDK_INT >= 30 -> HapticFeedbackConstants.CONFIRM
                strong -> HapticFeedbackConstants.LONG_PRESS
                else -> HapticFeedbackConstants.CLOCK_TICK
            }
            val done = v.performHapticFeedback(constant)
            // Некоторые прошивки глушат системную отдачу — для «подключено» дублируем вибромотором
            if (strong && !done) try {
                val vib = v.context.getSystemService(Vibrator::class.java)
                vib?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (_: Exception) {
            }
        }
    }
}
