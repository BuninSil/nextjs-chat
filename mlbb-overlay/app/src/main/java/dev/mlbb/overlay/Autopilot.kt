package dev.mlbb.overlay

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Автопилот: запоминает, что работает в каждой конкретной сети (оператор мобильной связи или
 * конкретный Wi-Fi), и выбирает сервер по опыту, а не перебором с нуля.
 *
 * Для каждой сети и каждого сервера — сколько раз подключение прошло и сколько нет, скорость
 * и пинг (скользящее среднее). Выбор — сэмплирование Томпсона: вероятность успеха берётся
 * случайно из бета-распределения по накопленной статистике и умножается на ожидаемую скорость.
 * Так надёжные быстрые серверы почти всегда первые, а новые и давно не проверенные иногда тоже
 * пробуются — автопилот не залипает на старом, если в сети что-то поменялось.
 *
 * Здесь же — лучшие найденные параметры обхода блокировок для сети (см. [BypassLab]).
 * Всё хранится только на телефоне.
 */
object Autopilot {
    private const val FILE = "autopilot.json"
    private const val MAX_NETS = 24
    private const val MAX_SERVERS = 400
    /** Старый опыт весит меньше: за неделю — вдвое */
    private const val HALF_LIFE_MS = 7 * 24 * 3600 * 1000.0

    private class Stat(
        var ok: Double = 0.0,
        var fail: Double = 0.0,
        var mbps: Double = 0.0,
        var ping: Double = 0.0,
        var at: Long = 0L,
    )

    private class Net(
        val servers: HashMap<String, Stat> = HashMap(),
        var bypass: JSONObject? = null,
        var seen: Long = 0L,
    )

    private val nets = HashMap<String, Net>()
    @Volatile private var loaded = false

    // ------------------------------- какая это сеть -------------------------------

    /**
     * Отпечаток сети без лишних разрешений: мобильная — код оператора (MCC+MNC),
     * Wi-Fi — DNS и шлюз роутера. Пусто — сети нет.
     */
    @Volatile private var keyCache: Pair<Long, String>? = null

    fun networkKey(ctx: Context): String {
        keyCache?.let { (at, k) -> if (System.currentTimeMillis() - at < 5000) return k }
        return computeKey(ctx).also { keyCache = System.currentTimeMillis() to it }
    }

    private fun computeKey(ctx: Context): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = ServerTester.underlying(ctx) ?: return ""
        val caps = cm.getNetworkCapabilities(net) ?: return ""
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            val op = try { ctx.getSystemService(TelephonyManager::class.java).networkOperator } catch (_: Exception) { null }
            return "cell:" + (op?.takeIf { it.isNotBlank() } ?: "?")
        }
        val lp = cm.getLinkProperties(net) ?: return "net:?"
        val sig = (lp.dnsServers.map { it.hostAddress } +
            lp.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway?.hostAddress }).sorted().joinToString(",")
        val kind = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) "wifi" else "net"
        return "$kind:" + java.security.MessageDigest.getInstance("SHA-1").digest(sig.toByteArray())
            .take(5).joinToString("") { "%02x".format(it) }
    }

    /** Название для журнала и экрана: «МТС (мобильная)», «Wi-Fi a1b2c3» */
    fun networkLabel(ctx: Context, key: String = networkKey(ctx)): String = when {
        key.startsWith("cell:") -> (try { ctx.getSystemService(TelephonyManager::class.java).networkOperatorName } catch (_: Exception) { null })
            ?.takeIf { it.isNotBlank() }?.let { "$it (мобильная)" } ?: "мобильная сеть"
        key.startsWith("wifi:") -> "Wi-Fi ${key.substringAfter(':')}"
        key.isEmpty() -> "нет сети"
        else -> "сеть ${key.substringAfter(':')}"
    }

    /** Сервер по сути, а не по номеру: номера n0, n1… меняются при обновлении подписки */
    fun id(n: Subscription.Node) = "${n.name}|${n.server}:${n.port}"

    // ------------------------------- опыт -------------------------------

    @Synchronized
    private fun net(ctx: Context, key: String): Net {
        load(ctx)
        return nets.getOrPut(key) { Net() }.also { it.seen = System.currentTimeMillis() }
    }

    /** Насколько ослаб опыт с последнего результата (за неделю — вдвое) */
    private fun weight(s: Stat, now: Long) = if (s.at == 0L) 1.0 else Math.pow(0.5, (now - s.at) / HALF_LIFE_MS)

    /** Перед новым результатом: старый опыт ослабляем и запоминаем, когда это было */
    private fun decay(s: Stat, now: Long) {
        val k = weight(s, now)
        s.ok *= k
        s.fail *= k
        s.at = now
    }

    /** Подключение через сервер прошло / не прошло (в этой сети). */
    @Synchronized
    fun record(ctx: Context, n: Subscription.Node, ok: Boolean, mbps: Double? = null) {
        val key = networkKey(ctx).ifEmpty { return }
        val s = net(ctx, key).servers.getOrPut(id(n)) { Stat() }
        val now = System.currentTimeMillis()
        decay(s, now)
        if (ok) s.ok += 1 else s.fail += 1
        if (mbps != null && mbps > 0) s.mbps = if (s.mbps == 0.0) mbps else s.mbps * 0.6 + mbps * 0.4
        s.at = now
        saveSoon(ctx)
    }

    @Synchronized
    fun recordPing(ctx: Context, n: Subscription.Node, ms: Int?) {
        val key = networkKey(ctx).ifEmpty { return }
        val s = net(ctx, key).servers[id(n)] ?: if (ms == null) return else Stat().also { net(ctx, key).servers[id(n)] = it }
        if (ms != null) s.ping = if (s.ping == 0.0) ms.toDouble() else s.ping * 0.7 + ms * 0.3
    }

    /** Насколько автопилот уверен в сервере в этой сети: доля успехов и сколько раз проверяли. */
    @Synchronized
    fun confidence(ctx: Context, n: Subscription.Node): Pair<Double, Int>? {
        val key = networkKey(ctx).ifEmpty { return null }
        val s = net(ctx, key).servers[id(n)] ?: return null
        val total = s.ok + s.fail
        if (total < 0.5) return null
        return (s.ok / total) to Math.round(total).toInt()
    }

    // ------------------------------- выбор -------------------------------

    /** Случайное из бета-распределения (через два гамма-распределения) */
    private fun beta(a: Double, b: Double): Double {
        val x = gamma(a)
        val y = gamma(b)
        return if (x + y == 0.0) 0.5 else x / (x + y)
    }

    /** Гамма-распределение, метод Марсальи — Цанга */
    private fun gamma(k: Double): Double {
        if (k < 1) return gamma(k + 1) * Random.nextDouble().coerceAtLeast(1e-9).let { Math.pow(it, 1 / k) }
        val d = k - 1.0 / 3
        val c = 1 / sqrt(9 * d)
        while (true) {
            var x: Double
            var v: Double
            do {
                x = gaussian()
                v = 1 + c * x
            } while (v <= 0)
            v *= v * v
            val u = Random.nextDouble()
            if (u < 1 - 0.0331 * x * x * x * x) return d * v
            if (ln(u) < 0.5 * x * x + d * (1 - v + ln(v))) return d * v
        }
    }

    private fun gaussian(): Double {
        val u1 = Random.nextDouble().coerceAtLeast(1e-12)
        val u2 = Random.nextDouble()
        return sqrt(-2 * ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }

    /**
     * Порядок серверов по опыту в этой сети. pingMs — свежий пинг (если мерили), им взвешиваются
     * серверы без опыта. Возвращает теги от лучшего к худшему.
     */
    @Synchronized
    fun order(ctx: Context, tags: List<String>, byTag: Map<String, Subscription.Node>, pingMs: (String) -> Int?): List<String> {
        val key = networkKey(ctx)
        if (key.isEmpty()) return tags
        val servers = net(ctx, key).servers
        val now = System.currentTimeMillis()
        val score = tags.associateWith { t ->
            val n = byTag[t] ?: return@associateWith 0.0
            val s = servers[id(n)]
            val ping = pingMs(t) ?: s?.ping?.takeIf { it > 0 }?.toInt()
            // Пинг 30 мс → 1, 300 мс → ~0,3
            val pingF = ping?.let { 1.0 / (1.0 + max(0, it - 30) / 120.0) } ?: 0.6
            if (s == null) return@associateWith beta(1.0, 1.0) * pingF * 0.8
            // Для выбора опыт ослабляем на копии — сама статистика меняется только новым результатом
            val k = weight(s, now)
            val p = beta(s.ok * k + 1, s.fail * k + 1)
            // Скорость: 0 → 0,5; 50 Мбит/с и больше → 1
            val speedF = if (s.mbps > 0) 0.5 + 0.5 * min(1.0, s.mbps / 50) else 0.6
            p * speedF * (0.7 + 0.3 * pingF)
        }
        return tags.sortedByDescending { score[it] ?: 0.0 }
    }

    /** Уверенный лидер этой сети: работал не раз и почти без провалов — к нему подключаемся сразу. */
    @Synchronized
    fun leader(ctx: Context, nodes: List<Subscription.Node>): Subscription.Node? {
        val key = networkKey(ctx)
        if (key.isEmpty()) return null
        val servers = net(ctx, key).servers
        val now = System.currentTimeMillis()
        return nodes.mapNotNull { n ->
            val s = servers[id(n)] ?: return@mapNotNull null
            val k = weight(s, now)
            val ok = s.ok * k
            val total = ok + s.fail * k
            if (ok < 2.5 || ok / total < 0.8) return@mapNotNull null
            n to (ok / total) * (0.5 + 0.5 * min(1.0, s.mbps / 50))
        }.maxByOrNull { it.second }?.first
    }

    // ------------------------------- обход блокировок -------------------------------

    @Synchronized
    fun bypassBest(ctx: Context): BypassLab.Genome? {
        val key = networkKey(ctx).ifEmpty { return null }
        return net(ctx, key).bypass?.let { BypassLab.Genome.fromJson(it) }
    }

    @Synchronized
    fun setBypassBest(ctx: Context, g: BypassLab.Genome?, ms: Int?) {
        val key = networkKey(ctx).ifEmpty { return }
        net(ctx, key).bypass = g?.toJson()?.put("ms", ms ?: 0)?.put("at", System.currentTimeMillis())
        saveSoon(ctx)
    }

    // ------------------------------- хранение -------------------------------

    @Synchronized
    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            for (k in o.keys()) {
                val no = o.getJSONObject(k)
                val n = Net(seen = no.optLong("seen"), bypass = no.optJSONObject("bypass"))
                val sv = no.optJSONObject("servers") ?: JSONObject()
                for (id in sv.keys()) {
                    val a = sv.getJSONArray(id)
                    n.servers[id] = Stat(a.optDouble(0), a.optDouble(1), a.optDouble(2), a.optDouble(3), a.optLong(4))
                }
                nets[k] = n
            }
        } catch (e: Exception) {
            AppLog.err("автопилот: файл повреждён, начинаю заново", e)
            nets.clear()
        }
    }

    @Volatile private var saveQueued = false

    private fun saveSoon(ctx: Context) {
        if (saveQueued) return
        saveQueued = true
        val app = ctx.applicationContext
        Thread {
            try { Thread.sleep(3000) } catch (_: InterruptedException) {}
            saveQueued = false
            save(app)
        }.start()
    }

    @Synchronized
    private fun save(ctx: Context) {
        // Старые сети и серверы — прочь, чтобы файл не рос бесконечно
        if (nets.size > MAX_NETS) nets.entries.sortedBy { it.value.seen }.take(nets.size - MAX_NETS).forEach { nets.remove(it.key) }
        val o = JSONObject()
        for ((k, n) in nets) {
            if (n.servers.size > MAX_SERVERS) {
                n.servers.entries.sortedBy { it.value.at }.take(n.servers.size - MAX_SERVERS).forEach { n.servers.remove(it.key) }
            }
            val sv = JSONObject()
            for ((id, s) in n.servers) sv.put(id, JSONArray().put(s.ok).put(s.fail).put(s.mbps).put(s.ping).put(s.at))
            o.put(k, JSONObject().put("seen", n.seen).put("servers", sv).apply { n.bypass?.let { put("bypass", it) } })
        }
        try {
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(File(ctx.filesDir, FILE))
        } catch (e: Exception) {
            AppLog.err("автопилот: не сохранился", e)
        }
    }

    /** Сколько знает о текущей сети — для экрана «Серверы» */
    @Synchronized
    fun summary(ctx: Context): String {
        val key = networkKey(ctx)
        if (key.isEmpty()) return ""
        val n = net(ctx, key)
        val known = n.servers.values.count { it.ok + it.fail >= 1 }
        return "Автопилот · ${networkLabel(ctx, key)}: " + if (known == 0) "изучаю сеть" else "знает $known серв."
    }
}
