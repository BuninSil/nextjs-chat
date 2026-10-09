package dev.mlbb.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Звёздное небо на весь экран для анимации «Гиперпрыжок».
 * Покой — звёзды висят; подключение — срываются в лучи и разгоняются до световой;
 * подключено — короткий рывок и дальше медленный полёт, пока VPN включён.
 * Точка схода — центр [anchor] (большой кнопки).
 */
class StarField(ctx: Context) : View(ctx) {
    var anchor: View? = null

    private class Star(var x: Float, var y: Float, var z: Float, var pz: Float, val green: Boolean)

    private val stars = ArrayList<Star>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private var speed = 0f
    private var connecting = false
    private var running = false
    private var stateAt = 0L
    private var lastFrame = 0L
    private val loc = IntArray(2)
    private val myLoc = IntArray(2)

    init {
        repeat(150) { stars += newStar(true) }
    }

    private fun newStar(anywhere: Boolean): Star {
        val z = if (anywhere) 0.05f + Random.nextFloat() * 0.95f else 1f
        return Star(Random.nextFloat() * 2 - 1, Random.nextFloat() * 2 - 1, z, z, Random.nextFloat() < 0.22f)
    }

    fun setState(connecting: Boolean, running: Boolean) {
        if (connecting == this.connecting && running == this.running) return
        if (connecting != this.connecting || running != this.running) stateAt = SystemClock.uptimeMillis()
        this.connecting = connecting
        this.running = running
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = (now - stateAt) / 1000f
        val target = when {
            connecting -> 0.0025f + min(1f, t / 2.5f).pow(2.2f) * 0.055f
            running && t < 0.25f -> 0.08f
            // Медленный полёт первые 20 секунд, дальше звёзды замирают — не тратим батарею
            running && t < 20f -> 0.0026f
            else -> 0f
        }
        // Кадры бывают реже 60 в секунду — скорость считаем на 16 мс
        val dt = if (lastFrame == 0L) 1f else ((now - lastFrame) / 16f).coerceIn(0.5f, 3f)
        lastFrame = now
        speed += (target - speed) * (if (running && t >= 0.25f) 0.04f else 0.12f) * dt
        if (target == 0f && speed < 0.0004f) speed = 0f

        val w = width.toFloat(); val h = height.toFloat()
        var cx = w / 2; var cy = h * 0.4f
        anchor?.let { a ->
            a.getLocationInWindow(loc); getLocationInWindow(myLoc)
            cx = loc[0] - myLoc[0] + a.width / 2f
            cy = loc[1] - myLoc[1] + a.height / 2f
        }
        val f = max(w, h) * 0.55f
        val base = Ui.TEXT
        for (s in stars) {
            s.pz = s.z
            s.z -= speed * dt
            if (s.z <= 0.02f) { reset(s); continue }
            val x = cx + s.x / s.z * f; val y = cy + s.y / s.z * f
            if (x < -60 || x > w + 60 || y < -60 || y > h + 60) { reset(s); continue }
            val px = cx + s.x / s.pz * f; val py = cy + s.y / s.pz * f
            val a = min(1f, (1 - s.z) * 1.3f) * 0.9f
            val col = if (s.green) Ui.GREEN else base
            paint.color = (col and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
            paint.strokeWidth = max(1.2f, (1 - s.z) * 4f)
            c.drawLine(px, py, x + 0.01f, y, paint)
        }
        when {
            speed > 0.01f -> postInvalidateOnAnimation()
            speed > 0f -> postInvalidateDelayed(33) // медленный полёт — 30 кадров хватает
        }
    }

    private fun reset(s: Star) {
        val n = newStar(false)
        s.x = n.x; s.y = n.y; s.z = n.z; s.pz = n.z
    }

    companion object {
        /** Белая вспышка на весь экран — накладкой поверх всего. */
        fun flash(overlay: View) {
            overlay.animate().cancel()
            overlay.alpha = 0f
            overlay.visibility = VISIBLE
            overlay.animate().alpha(0.8f).setDuration(90).withEndAction {
                overlay.animate().alpha(0f).setDuration(520).withEndAction { overlay.visibility = GONE }.start()
            }.start()
        }
    }
}
