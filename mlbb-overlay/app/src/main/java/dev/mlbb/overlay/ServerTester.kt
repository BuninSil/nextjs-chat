package dev.mlbb.overlay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Подбор сервера.
 *
 * Пинг — время TCP-рукопожатия напрямую до VPN-сервера, в обход туннеля
 * (через «настоящую» сеть телефона): один круг туда-обратно, как в Karing.
 * Три замера на сервер: лучший — пинг, разница — разброс.
 *
 * Работоспособность проверяется отдельно через ядро (запрос сквозь сервер),
 * только у лучших кандидатов — чтобы не выбрать живой по пингу, но мёртвый сервер.
 */
object ServerTester {
    private const val CHECK_URL = "http://www.gstatic.com/generate_204"
    private const val RU_BONUS = 10
    private const val UDP_BONUS = 8

    data class Result(val medianMs: Int, val jitterMs: Int, val score: Int)

    /**
     * Результаты замеров. results[tag] == null и containsKey(tag) — сервер не ответил.
     * ConcurrentHashMap не хранит null, поэтому неответившие лежат отдельным множеством.
     */
    class Results {
        private val ok = ConcurrentHashMap<String, Result>()
        private val failed = ConcurrentHashMap.newKeySet<String>()

        operator fun get(tag: String): Result? = ok[tag]
        fun containsKey(tag: String) = ok.containsKey(tag) || failed.contains(tag)
        fun clear() { ok.clear(); failed.clear() }
        fun put(tag: String, r: Result?) {
            if (r == null) { ok.remove(tag); failed.add(tag) } else { failed.remove(tag); ok[tag] = r }
        }
        fun ranked(): List<String> = ok.entries.sortedBy { it.value.score }.map { it.key }
    }

    val results = Results()

    @Volatile
    var testing = false
        private set

    private val ruWords = listOf("🇷🇺", "москва", "moscow", "россия", "russia", "санкт", "петербург", "spb", "msk")

    fun isRussian(n: Subscription.Node): Boolean {
        val name = n.name.lowercase()
        if (ruWords.any { name.contains(it) }) return true
        // По базе — только для IP: домен пришлось бы резолвить (нельзя на главном потоке)
        if (!n.server.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return false
        return GeoDb.lookup(n.server)?.countryCode.equals("RU", true)
    }

    /** Строки-разделители в подписках («Локации под обход ⬇️») — не серверы. */
    fun isSeparator(n: Subscription.Node) = n.name.contains("⬇") || n.name.contains("⬆")

    /** «Настоящая» сеть телефона (Wi-Fi/мобильная), не VPN. */
    private fun underlying(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull {
            val c = cm.getNetworkCapabilities(it) ?: return@firstOrNull false
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
    }

    private fun tcpRtt(net: Network?, addr: InetAddress, port: Int): Int? = try {
        Socket().use { s ->
            try { net?.bindSocket(s) } catch (_: Exception) {}
            val t = System.nanoTime()
            s.connect(InetSocketAddress(addr, port), 1500)
            ((System.nanoTime() - t) / 1_000_000).toInt().coerceAtLeast(1)
        }
    } catch (_: Exception) {
        null
    }

    /** Меряет пинг до всех серверов напрямую. Возвращает теги от лучшего к худшему. VPN не нужен. */
    fun measure(ctx: Context, progress: (Int, Int) -> Unit): List<String> {
        val nodes = Subscription.nodes.filterNot { isSeparator(it) }
        val net = underlying(ctx)
        val game = AppSettings.gameMode
        testing = true
        results.clear()
        val done = AtomicInteger()
        val pool = Executors.newFixedThreadPool(16)
        for (n in nodes) {
            pool.execute {
                val addr = try {
                    (net?.getAllByName(n.server) ?: InetAddress.getAllByName(n.server))
                        .firstOrNull { it is Inet4Address }
                } catch (_: Exception) {
                    null
                }
                val samples = if (addr == null) emptyList()
                else (1..3).mapNotNull { tcpRtt(net, addr, n.port) }
                results.put(n.tag, if (samples.isNotEmpty()) {
                    val best = samples.min()
                    val jitter = samples.max() - best
                    var score = best + jitter / 2
                    if (game && isRussian(n)) score -= RU_BONUS
                    if (game && n.nativeUdp) score -= UDP_BONUS
                    Result(best, jitter, score)
                } else null)
                progress(done.incrementAndGet(), nodes.size)
            }
        }
        pool.shutdown()
        pool.awaitTermination(60, TimeUnit.SECONDS)
        testing = false
        return results.ranked()
    }

    /**
     * Нужен запущенный VPN: идёт по списку от лучшего и берёт первый сервер,
     * через который реально проходит запрос. Мёртвые помечает ✖.
     */
    fun pickWorking(ctx: Context, ranked: List<String>, maxTries: Int = 8): String? {
        val p = BoxVpnService.ports ?: return null
        for (tag in ranked.take(maxTries)) {
            if (!ClashApi.select(p.api, p.secret, "proxy", tag)) continue
            if (ClashApi.delay(p.api, p.secret, tag, CHECK_URL, 4000) != null) {
                AppSettings.selectedTag = tag
                AppSettings.save(ctx)
                return tag
            }
            results.put(tag, null)
        }
        return null
    }

    /** Переключает ядро на сервер и запоминает выбор. */
    fun use(ctx: Context, tag: String): Boolean {
        val p = BoxVpnService.ports
        val ok = p == null || ClashApi.select(p.api, p.secret, "proxy", tag)
        if (ok) {
            AppSettings.selectedTag = tag
            AppSettings.save(ctx)
        }
        return ok
    }
}
