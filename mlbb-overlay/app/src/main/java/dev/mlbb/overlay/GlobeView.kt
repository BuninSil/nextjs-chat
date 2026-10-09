package dev.mlbb.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Анимация «Глобус»: Земля из точек суши, от твоего города тянутся дуги к странам серверов
 * (цвет — по пингу). Пока подключается — по дугам бегут кометы и глобус крутится быстрее;
 * подключилось — дуга к выбранному серверу вспыхивает, по ней течёт поток. Потом замирает (батарея).
 */
class GlobeView(ctx: Context) : View(ctx) {
    class Target(val lat: Double, val lon: Double, val ping: Int?, val selected: Boolean)

    private var home = doubleArrayOf(55.75, 37.62) // Москва, пока не знаем страну
    private var targets: List<Target> = emptyList()
    private var connecting = false
    private var running = false
    private var stateAt = SystemClock.uptimeMillis()
    private var connectedAt = 0L

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bufs = Array(3) { FloatArray(0) }

    init {
        // Свечение дуг — тенями; до Android 9 тени у линий есть только в программной отрисовке
        if (android.os.Build.VERSION.SDK_INT < 28) setLayerType(LAYER_TYPE_SOFTWARE, null)
        land(ctx)
    }

    fun setHome(lat: Double, lon: Double) {
        if (home[0] == lat && home[1] == lon) return
        home = doubleArrayOf(lat, lon); invalidate()
    }

    fun setTargets(list: List<Target>) {
        if (list.size == targets.size && list.zip(targets).all { (a, b) -> a.lat == b.lat && a.lon == b.lon && a.ping == b.ping && a.selected == b.selected }) return
        targets = list
        invalidate()
    }

    fun setState(connecting: Boolean, running: Boolean) {
        if (connecting == this.connecting && running == this.running) return
        if (running && !this.running) connectedAt = SystemClock.uptimeMillis()
        this.connecting = connecting
        this.running = running
        stateAt = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun alpha(c: Int, a: Float) = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    // Вид: центр взгляда (широта, долгота) и проекция точки на экран: x, y, z (z>0 — видна)
    private var vLat = 0.0
    private var vLon = 0.0
    private var cx = 0f
    private var cy = 0f
    private var r = 0f
    private val tmp = DoubleArray(3)

    private fun project(lat: Double, lon: Double, alt: Double = 1.0, out: DoubleArray = tmp): DoubleArray {
        val la = Math.toRadians(lat); val lo = Math.toRadians(lon - vLon); val l0 = Math.toRadians(vLat)
        val x = cos(la) * sin(lo)
        val y = cos(l0) * sin(la) - sin(l0) * cos(la) * cos(lo)
        val z = sin(l0) * sin(la) + cos(l0) * cos(la) * cos(lo)
        out[0] = cx + r * alt * x; out[1] = cy - r * alt * y; out[2] = z
        return out
    }

    /** Точка большого круга между a и b (t 0..1). */
    private fun slerp(a: DoubleArray, b: DoubleArray, t: Double): DoubleArray {
        fun vec(p: DoubleArray): DoubleArray {
            val la = Math.toRadians(p[0]); val lo = Math.toRadians(p[1])
            return doubleArrayOf(cos(la) * cos(lo), cos(la) * sin(lo), sin(la))
        }
        val va = vec(a); val vb = vec(b)
        val d = acos((va[0] * vb[0] + va[1] * vb[1] + va[2] * vb[2]).coerceIn(-1.0, 1.0))
        if (d < 1e-6) return a
        val s1 = sin((1 - t) * d) / sin(d); val s2 = sin(t * d) / sin(d)
        val x = s1 * va[0] + s2 * vb[0]; val y = s1 * va[1] + s2 * vb[1]; val z = s1 * va[2] + s2 * vb[2]
        return doubleArrayOf(Math.toDegrees(Math.atan2(z, sqrt(x * x + y * y))), Math.toDegrees(Math.atan2(y, x)))
    }

    override fun onDraw(c: Canvas) {
        val pts = landPts ?: return
        val now = SystemClock.uptimeMillis()
        val t = (now - stateAt) / 1000f
        cx = width / 2f
        r = min(width, height) / 2f * 0.86f
        cy = r + dp(8f)
        // Взгляд: чуть восточнее и южнее дома, лёгкое покачивание; при подключении крутится быстрее
        val sway = when {
            connecting -> (t * 25.0) % 360.0
            else -> 18.0 * sin(t / 3.5)
        }
        vLat = (home[0] - 18).coerceIn(-30.0, 40.0)
        vLon = home[1] + 12 + sway
        val accent = Ui.GREEN

        // Шар: тёмная сфера с подсветкой края
        fill.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.3f,
            intArrayOf(alpha(accent, 0.20f), alpha(Ui.CARD, 0.95f), alpha(Ui.BG, 1f)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, fill)
        fill.shader = null
        line.strokeWidth = dp(1.2f)
        line.color = alpha(accent, 0.35f)
        line.setShadowLayer(dp(10f), 0f, 0f, accent)
        c.drawCircle(cx, cy, r, line)
        line.clearShadowLayer()

        // Суша точками: за один проход раскладываем по трём яркостям (ближе к нам — ярче и крупнее)
        if (bufs[0].size < pts.size) for (b in 0 until 3) bufs[b] = FloatArray(pts.size)
        val cnt = IntArray(3)
        var i = 0
        while (i < pts.size) {
            val p = project(pts[i].toDouble(), pts[i + 1].toDouble())
            val z = p[2]
            if (z > 0) {
                val band = (z * 3).toInt().coerceAtMost(2)
                val arr = bufs[band]
                arr[cnt[band]++] = p[0].toFloat(); arr[cnt[band]++] = p[1].toFloat()
            }
            i += 2
        }
        for (band in 0 until 3) {
            dot.color = alpha(Ui.TEXT, 0.18f + band * 0.2f)
            dot.strokeWidth = dp(1.5f + band * 0.6f)
            c.drawPoints(bufs[band], 0, cnt[band], dot)
        }

        // Дуги к серверам
        val h = project(home[0], home[1]).copyOf()
        val steps = 40
        var more = connecting
        targets.forEachIndexed { idx, tg ->
            val col = if (tg.ping == null) Ui.MUTED else Ui.pingColor(tg.ping)
            val sel = tg.selected && (running || !connecting)
            line.strokeWidth = dp(if (sel && running) 2.4f else 1.3f)
            line.color = alpha(if (sel && running) accent else col, if (sel && running) 0.95f else 0.45f)
            if (sel && running) line.setShadowLayer(dp(8f), 0f, 0f, accent) else line.clearShadowLayer()
            var px = 0f; var py = 0f; var has = false
            val a = home; val b = doubleArrayOf(tg.lat, tg.lon)
            for (s in 0..steps) {
                val f = s / steps.toDouble()
                val g = slerp(a, b, f)
                val p = project(g[0], g[1], 1.0 + 0.18 * sin(PI * f))
                val vis = p[2] > -0.15
                if (vis && has) c.drawLine(px, py, p[0].toFloat(), p[1].toFloat(), line)
                px = p[0].toFloat(); py = p[1].toFloat(); has = vis
            }
            line.clearShadowLayer()
            // Точка сервера
            val pe = project(tg.lat, tg.lon)
            if (pe[2] > 0) {
                fill.color = if (sel && running) accent else col
                c.drawCircle(pe[0].toFloat(), pe[1].toFloat(), dp(if (sel) 4.5f else 3f), fill)
            }
            // Кометы: при подключении — по всем дугам по очереди; подключено — поток по выбранной
            val flow = (connecting) || (sel && running && (now - connectedAt) < 25_000)
            if (flow) {
                more = true
                val speed = if (connecting) 0.9 else 0.55
                val phase = ((now / 1000.0) * speed + idx * 0.37) % 1.0
                for (k in 0 until if (connecting) 1 else 3) {
                    val f = (phase + k / 3.0) % 1.0
                    val g = slerp(a, b, f)
                    val p = project(g[0], g[1], 1.0 + 0.18 * sin(PI * f))
                    if (p[2] > -0.15) {
                        fill.color = Color.WHITE
                        fill.setShadowLayer(dp(7f), 0f, 0f, accent)
                        c.drawCircle(p[0].toFloat(), p[1].toFloat(), dp(2.6f), fill)
                        fill.clearShadowLayer()
                    }
                }
            }
        }

        // Дом — пульсирующая точка
        if (h[2] > 0) {
            val pulse = ((now % 1600) / 1600f)
            fill.color = alpha(accent, 0.5f * (1 - pulse))
            c.drawCircle(h[0].toFloat(), h[1].toFloat(), dp(4f + 12f * pulse), fill)
            fill.color = accent
            c.drawCircle(h[0].toFloat(), h[1].toFloat(), dp(4f), fill)
        }

        // Покачивание первые 20 секунд после события, потом замирает (кроме подключения и потока)
        if (more || t < 20f) postInvalidateDelayed(33)
    }

    companion object {
        @Volatile private var landPts: FloatArray? = null
        private var centroids: Map<String, DoubleArray> = emptyMap()

        /** Точки суши и центры стран — из assets, один раз. */
        fun land(ctx: Context) {
            if (landPts != null) return
            try {
                val raw = ctx.assets.open("geo/land.txt").bufferedReader().use { it.readText() }
                val parts = raw.split(';')
                val arr = FloatArray(parts.size * 2)
                var n = 0
                for (p in parts) {
                    val i = p.indexOf(',')
                    if (i < 0) continue
                    arr[n++] = p.substring(0, i).toFloat(); arr[n++] = p.substring(i + 1).toFloat()
                }
                landPts = arr.copyOf(n)
                centroids = ctx.assets.open("geo/centroids.txt").bufferedReader().use { it.readText() }
                    .split(';').mapNotNull { s ->
                        val f = s.split(',')
                        if (f.size == 3) f[0] to doubleArrayOf(f[1].toDouble(), f[2].toDouble()) else null
                    }.toMap() +
                    // Для больших стран — где живёт большинство, а не геометрический центр
                    mapOf("RU" to doubleArrayOf(55.75, 37.62), "US" to doubleArrayOf(39.0, -77.0),
                        "CA" to doubleArrayOf(45.4, -75.7), "KZ" to doubleArrayOf(43.2, 76.9), "CN" to doubleArrayOf(31.2, 121.5),
                        "BR" to doubleArrayOf(-23.5, -46.6), "AU" to doubleArrayOf(-33.9, 151.2),
                        // 🇪🇺 в названиях — обычно Франкфурт
                        "EU" to doubleArrayOf(50.1, 8.7))
            } catch (e: Exception) {
                AppLog.err("глобус: данные карты", e)
            }
        }

        fun place(code: String): DoubleArray? = centroids[code.uppercase()]

        /** Дуги: страны серверов из подписки (до 12), цвет — лучший пинг в стране, выбранная подсвечена. */
        fun targetsFor(ctx: Context): List<Target> {
            land(ctx)
            val sel = AppSettings.selectedTag
            val by = LinkedHashMap<String, Triple<DoubleArray, Int?, Boolean>>()
            for (n in Subscription.usable(ctx)) {
                if (ServerTester.isSeparator(n)) continue
                val cc = countryOf(n) ?: continue
                val pos = place(cc) ?: continue
                val ping = ServerTester.results[n.tag]?.medianMs
                val old = by[cc]
                val best = listOfNotNull(old?.second, ping).minOrNull()
                by[cc] = Triple(pos, best, (old?.third ?: false) || n.tag == sel)
            }
            return by.values.sortedWith(compareBy({ !it.third }, { it.second ?: 9999 })).take(12)
                .map { Target(it.first[0], it.first[1], it.second, it.third) }
        }

        /** Страна сервера: флаг в названии, проверенный выход или база IP. */
        fun countryOf(n: Subscription.Node): String? {
            Regex("[\\x{1F1E6}-\\x{1F1FF}]{2}").find(n.name)?.value?.let { f ->
                val cps = f.codePoints().toArray()
                if (cps.size == 2) return String(charArrayOf('A' + (cps[0] - 0x1F1E6), 'A' + (cps[1] - 0x1F1E6)))
            }
            ServerTester.exitCountry[n.tag]?.let { return it }
            if (n.server.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return GeoDb.lookup(n.server)?.countryCode?.uppercase()
            return null
        }
    }
}
