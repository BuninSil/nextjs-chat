package dev.mlbb.overlay

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Подбор сервера под игру. Каждый сервер меряется 3 раза через ядро:
 * запрос к ya.ru (Москва — там же игровые сервера MLBB для РФ), считаются
 * медиана и разброс. Оценка = медиана + разброс − бонусы:
 * российский сервер (ближе к серверам игры) и протокол с настоящим UDP.
 */
object ServerTester {
    private const val TEST_URL = "http://ya.ru/"
    private const val RU_BONUS = 15
    private const val UDP_BONUS = 10

    data class Result(val medianMs: Int, val jitterMs: Int, val score: Int)

    /** tag -> результат; null — сервер не ответил */
    val results = ConcurrentHashMap<String, Result?>()

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

    /** Меряет все серверы. Возвращает лучший tag или null. Нужен запущенный BoxVpnService. */
    fun testAll(progress: (Int, Int) -> Unit): String? {
        val p = BoxVpnService.ports ?: return null
        val nodes = Subscription.nodes.filterNot { isSeparator(it) }
        testing = true
        results.clear()
        val done = AtomicInteger()
        val pool = Executors.newFixedThreadPool(12)
        for (n in nodes) {
            pool.execute {
                val samples = ArrayList<Int>()
                repeat(3) {
                    ClashApi.delay(p.api, p.secret, n.tag, TEST_URL, 2500)?.let { samples.add(it) }
                }
                results[n.tag] = if (samples.size >= 2) {
                    samples.sort()
                    val median = samples[samples.size / 2]
                    val jitter = samples.last() - samples.first()
                    var score = median + jitter
                    if (isRussian(n)) score -= RU_BONUS
                    if (n.nativeUdp) score -= UDP_BONUS
                    Result(median, jitter, score)
                } else null
                progress(done.incrementAndGet(), nodes.size)
            }
        }
        pool.shutdown()
        pool.awaitTermination(90, TimeUnit.SECONDS)
        testing = false
        return results.entries.filter { it.value != null }.minByOrNull { it.value!!.score }?.key
    }

    /** Переключает ядро на сервер и запоминает выбор. */
    fun use(ctx: android.content.Context, tag: String): Boolean {
        val p = BoxVpnService.ports ?: return false
        val ok = ClashApi.select(p.api, p.secret, "proxy", tag)
        if (ok) {
            AppSettings.selectedTag = tag
            AppSettings.save(ctx)
        }
        return ok
    }
}
