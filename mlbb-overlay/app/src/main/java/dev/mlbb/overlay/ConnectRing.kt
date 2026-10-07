package dev.mlbb.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Анимация вокруг большой кнопки. Три стиля (Настройки → Оформление):
 *  - warp — гиперпрыжок: звёзды на весь экран рисует [StarField], здесь — ореол и ударные волны;
 *  - gauge — спидометр: шкала вокруг кнопки, при подключении стрелка улетает в красную зону,
 *    дальше показывает реальную скорость загрузки;
 *  - radar — луч радара ищет серверы, при подключении лучший берётся в прицел.
 */
class ConnectRing(ctx: Context) : View(ctx) {
    var style = AppSettings.ANIM_WARP
        set(v) { field = v; invalidate() }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val oval = RectF()

    private var connecting = false
    private var running = false
    private var stateAt = 0L
    private var connectedAt = 0L

    private class Wave(var r: Float, val v: Float, var a: Float, val w: Float)
    private class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val dec: Float, val color: Int)
    private class Blip(val a: Float, val r: Float, val born: Long)

    private val waves = ArrayList<Wave>()
    private val sparks = ArrayList<Spark>()
    private val blips = ArrayList<Blip>()
    private var lockA = 0f
    private var lockR = 0f

    // Спидометр: текущее положение стрелки (0..1) и цель
    private var gauge = 0f
    private var gaugeTarget = 0f
    private var mbit = 0.0

    init {
        // Свечение рисуется тенью — нужна программная отрисовка слоя
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private val now get() = SystemClock.uptimeMillis()

    /** Идёт подключение (VPN поднимается или подбирается сервер). */
    fun setConnecting(on: Boolean) {
        if (on == connecting) return
        connecting = on
        stateAt = now
        if (on) blips.clear()
        invalidate()
    }

    fun setRunning(on: Boolean) {
        if (on == running) return
        running = on
        if (!on) gaugeTarget = 0f
        invalidate()
    }

    /** Реальная скорость загрузки (байт/с) — для спидометра, пока VPN включён. */
    fun setSpeed(bytesPerSec: Double) {
        mbit = bytesPerSec * 8 / 1_000_000
        if (running && now - connectedAt > 700) {
            // Логарифмическая шкала: 1 Мбит/с — уже заметно, 300 — почти до упора
            gaugeTarget = (log10(1 + mbit) / log10(301.0)).toFloat().coerceIn(0.04f, 1f)
            invalidate()
        }
    }

    /** Момент «подключено»: у каждого стиля свой. */
    fun connected() {
        connectedAt = now
        val rb = buttonR()
        when (style) {
            AppSettings.ANIM_GAUGE -> {
                waves += Wave(rb + dp(13f), dp(2.4f), 0.9f, dp(3f))
                val a = (PI * 0.75 + PI * 1.5).toFloat()
                burst(width / 2f + cos(a) * (rb + dp(13f)), height / 2f + sin(a) * (rb + dp(13f)), 40, 0xFFFFD27A.toInt())
            }
            AppSettings.ANIM_RADAR -> {
                val b = blips.minByOrNull { it.r } ?: Blip(-0.9f, rb + dp(18f), now)
                lockA = b.a; lockR = b.r
                waves += Wave(rb, dp(2f), 0.9f, dp(2.5f))
            }
            else -> {
                waves += Wave(rb, dp(3.4f), 1f, dp(5f))
                waves += Wave(rb, dp(2.2f), 0.7f, dp(2.5f))
            }
        }
        invalidate()
    }

    private fun buttonR() = min(width, height) / 2f * (100f / 130f)

    private fun burst(x: Float, y: Float, n: Int, alt: Int) {
        repeat(n) {
            val a = Random.nextFloat() * 2 * PI.toFloat()
            val s = dp(1f + Random.nextFloat() * 2.6f)
            val c = when { Random.nextFloat() < 0.35f -> Color.WHITE; Random.nextFloat() < 0.5f -> alt; else -> Ui.GREEN }
            sparks += Spark(x, y, cos(a) * s, sin(a) * s, 1f, 0.015f + Random.nextFloat() * 0.02f, c)
        }
    }

    override fun onDetachedFromWindow() {
        waves.clear(); sparks.clear()
        super.onDetachedFromWindow()
    }

    private fun withAlpha(c: Int, a: Float) = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    override fun onDraw(c: Canvas) {
        val t = (now - stateAt) / 1000f
        var animate = connecting
        when (style) {
            AppSettings.ANIM_GAUGE -> animate = drawGauge(c, t) || animate
            AppSettings.ANIM_RADAR -> animate = drawRadar(c, t) || animate
            else -> drawWarp(c, t)
        }
        animate = drawEffects(c) || animate
        if (animate) postInvalidateOnAnimation()
    }

    private fun drawWarp(c: Canvas, t: Float) {
        if (!connecting) return
        val cx = width / 2f; val cy = height / 2f; val rb = buttonR()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = withAlpha(Ui.GREEN, 0.3f + 0.18f * sin(t * 7f))
        paint.setShadowLayer(dp(10f), 0f, 0f, Ui.GREEN)
        c.drawCircle(cx, cy, rb + dp(9f), paint)
        paint.clearShadowLayer()
    }

    /** Шкала спидометра. true — ещё двигается. */
    private fun drawGauge(c: Canvas, t: Float): Boolean {
        val cx = width / 2f; val cy = height / 2f; val rb = buttonR()
        val rg = rb + dp(13f)
        val a0 = 135f; val span = 270f
        val sinceConn = (now - connectedAt) / 1000f
        val prev = gauge
        when {
            connecting -> {
                val k = min(1f, t / 2.5f)
                gauge = (1 - (1 - k).pow(3)) * 0.82f + sin(t * 13f) * 0.02f
            }
            running && sinceConn < 0.18f -> gauge = 0.82f + 0.18f * (sinceConn / 0.18f)
            running && sinceConn < 1.2f -> {
                // Пружинит из красной зоны к реальной скорости
                val target = max(gaugeTarget, 0.04f)
                gauge = target + (1f - target) * exp(-sinceConn * 4f) * cos(sinceConn * 18f)
            }
            else -> gauge += (gaugeTarget - gauge) * 0.12f
        }
        gauge = gauge.coerceIn(0f, 1f)

        // Деления
        paint.style = Paint.Style.STROKE
        for (i in 0..30) {
            val f = i / 30f
            val a = Math.toRadians((a0 + span * f).toDouble())
            val big = i % 5 == 0
            val lit = f <= gauge + 1e-4f && gauge > 0.005f
            val red = i >= 25
            val col = if (lit) (if (red) Ui.RED else Ui.GREEN) else withAlpha(Ui.TEXT, 0.14f)
            paint.color = col
            paint.strokeWidth = dp(if (big) 2.2f else 1.2f)
            if (lit) paint.setShadowLayer(dp(5f), 0f, 0f, col) else paint.clearShadowLayer()
            val r1 = rg + dp(if (big) 4f else 6f); val r2 = rg + dp(12f)
            c.drawLine(cx + cos(a).toFloat() * r1, cy + sin(a).toFloat() * r1, cx + cos(a).toFloat() * r2, cy + sin(a).toFloat() * r2, paint)
        }
        paint.clearShadowLayer()
        // Дуга
        oval.set(cx - rg, cy - rg, cx + rg, cy + rg)
        paint.strokeWidth = dp(4f)
        paint.shader = null
        paint.color = withAlpha(Ui.TEXT, 0.08f)
        c.drawArc(oval, a0, span, false, paint)
        if (gauge > 0.005f) {
            paint.shader = LinearGradient(cx - rg, cy + rg, cx + rg, cy - rg,
                intArrayOf(Ui.theme.accentBottom.toInt(), Ui.theme.accentTop.toInt(), Ui.RED), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
            paint.setShadowLayer(dp(8f), 0f, 0f, Ui.GREEN)
            c.drawArc(oval, a0, span * gauge, false, paint)
            paint.shader = null
            paint.clearShadowLayer()
            val ha = Math.toRadians((a0 + span * gauge).toDouble())
            fill.color = Color.WHITE
            fill.setShadowLayer(dp(7f), 0f, 0f, Color.WHITE)
            c.drawCircle(cx + cos(ha).toFloat() * rg, cy + sin(ha).toFloat() * rg, dp(4f), fill)
            fill.clearShadowLayer()
        }
        // Скорость — только настоящая, пока VPN включён
        if (running && sinceConn > 0.7f) {
            text.color = Ui.TEXT
            text.textSize = dp(13f)
            val s = if (mbit >= 10) String.format(java.util.Locale.US, "%.0f", mbit) else String.format(java.util.Locale.US, "%.1f", mbit)
            c.drawText("$s Мбит/с", cx, cy + rb + dp(22f), text)
        }
        return kotlin.math.abs(gauge - prev) > 0.0005f || (running && sinceConn < 1.3f)
    }

    /** Радар. true — ещё двигается. */
    private fun drawRadar(c: Canvas, t: Float): Boolean {
        val cx = width / 2f; val cy = height / 2f; val rb = buttonR()
        val rIn = rb + dp(5f); val rOut = rb + dp(28f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = withAlpha(Ui.GREEN, 0.14f)
        c.drawCircle(cx, cy, rb + dp(14f), paint)
        c.drawCircle(cx, cy, rOut, paint)
        var moving = false
        if (connecting) {
            val sw = (t * 3.2f) % (2 * PI.toFloat())
            val mid = (rIn + rOut) / 2
            oval.set(cx - mid, cy - mid, cx + mid, cy + mid)
            paint.strokeWidth = rOut - rIn
            paint.strokeCap = Paint.Cap.BUTT
            for (i in 0 until 20) {
                paint.color = withAlpha(Ui.GREEN, 0.22f * (1 - i / 20f))
                c.drawArc(oval, Math.toDegrees((sw - i * 0.055f).toDouble()).toFloat() - 3f, 3.2f, false, paint)
            }
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = dp(1.6f)
            paint.color = Ui.GREEN
            paint.setShadowLayer(dp(6f), 0f, 0f, Ui.GREEN)
            c.drawLine(cx + cos(sw) * rIn, cy + sin(sw) * rIn, cx + cos(sw) * rOut, cy + sin(sw) * rOut, paint)
            paint.clearShadowLayer()
            if (Random.nextFloat() < 0.09f) blips += Blip(sw, rb + dp(8f) + Random.nextFloat() * dp(18f), now)
        }
        // Найденные серверы — гаснущие точки
        val it = blips.iterator()
        while (it.hasNext()) {
            val b = it.next()
            val al = 1f - (now - b.born) / 1600f
            if (al <= 0f || !connecting) { if (!connecting) break; it.remove(); continue }
            fill.color = withAlpha(Ui.GREEN, al)
            c.drawCircle(cx + cos(b.a) * b.r, cy + sin(b.a) * b.r, dp(2.6f), fill)
        }
        if (running && connectedAt > 0) {
            val a = (now - connectedAt) / 1000f
            val bx = cx + cos(lockA) * lockR; val by = cy + sin(lockA) * lockR
            val k = min(1f, a / 0.25f)
            val ex = cx + cos(lockA) * rb; val ey = cy + sin(lockA) * rb
            paint.strokeWidth = dp(2f)
            paint.color = withAlpha(Color.WHITE, max(0.35f, 1f - a * 0.6f))
            paint.setShadowLayer(dp(8f), 0f, 0f, Ui.GREEN)
            c.drawLine(bx, by, bx + (ex - bx) * k, by + (ey - by) * k, paint)
            paint.clearShadowLayer()
            fill.color = Color.WHITE
            c.drawCircle(bx, by, dp(3.5f), fill)
            paint.strokeWidth = dp(1.4f)
            paint.color = Ui.GREEN
            c.drawCircle(bx, by, dp(8f), paint)
            if (a < 1.2f) {
                paint.color = withAlpha(Ui.GREEN, 1f - a / 1.2f)
                c.drawCircle(bx, by, dp(6f) + a * dp(30f), paint)
                moving = true
            }
        }
        return moving
    }

    /** Ударные волны и искры. true — ещё есть что рисовать. */
    private fun drawEffects(c: Canvas): Boolean {
        val cx = width / 2f; val cy = height / 2f
        val maxR = hypot(width / 2f, height / 2f)
        paint.style = Paint.Style.STROKE
        val wi = waves.iterator()
        while (wi.hasNext()) {
            val w = wi.next()
            w.r += w.v; w.a -= 0.022f
            if (w.a <= 0f || w.r > maxR * 1.6f) { wi.remove(); continue }
            paint.strokeWidth = w.w
            paint.color = withAlpha(Ui.GREEN, w.a)
            paint.setShadowLayer(dp(9f), 0f, 0f, Ui.GREEN)
            c.drawCircle(cx, cy, w.r, paint)
        }
        paint.clearShadowLayer()
        val si = sparks.iterator()
        while (si.hasNext()) {
            val s = si.next()
            s.x += s.vx; s.y += s.vy; s.vx *= 0.95f; s.vy *= 0.95f; s.life -= s.dec
            if (s.life <= 0f) { si.remove(); continue }
            fill.color = withAlpha(s.color, s.life)
            c.drawCircle(s.x, s.y, dp(0.7f + s.life * 1.3f), fill)
        }
        return waves.isNotEmpty() || sparks.isNotEmpty()
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
