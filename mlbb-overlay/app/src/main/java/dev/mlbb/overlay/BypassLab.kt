package dev.mlbb.overlay

import android.content.Context
import org.json.JSONObject
import kotlin.random.Random

/**
 * Лаборатория обхода блокировок: сама ищет, как резать начало TLS-соединения, чтобы система
 * блокировок провайдера не узнала сайт. Вместо четырёх заготовок — эволюционный поиск:
 * пробует варианты, лучшие «скрещивает» и слегка меняет, худшие отбрасывает. Мерило — открылся
 * ли YouTube через обход и за сколько. Найденное запоминается для каждой сети ([Autopilot]).
 *
 * Пробует только через Xray обхода; трафик пользователя при этом идёт как шёл.
 */
object BypassLab {
    /** Сайт-мерило: YouTube — главное, что режут */
    const val CHECK = "https://www.youtube.com/generate_204"

    /** Вариант дробления: какие пакеты резать, размер кусков (байт), паузы между ними (мс) */
    data class Genome(val packets: String, val lenMin: Int, val lenMax: Int, val ivMin: Int, val ivMax: Int) {
        fun params() = Triple(packets, "$lenMin-$lenMax", "$ivMin-$ivMax")
        fun toJson(): JSONObject = JSONObject().put("p", packets).put("l1", lenMin).put("l2", lenMax).put("i1", ivMin).put("i2", ivMax)
        override fun toString() = "$packets / $lenMin-$lenMax байт / $ivMin-$ivMax мс"

        companion object {
            fun fromJson(o: JSONObject): Genome? = try {
                Genome(o.getString("p"), o.getInt("l1"), o.getInt("l2"), o.getInt("i1"), o.getInt("i2")).normalized()
            } catch (_: Exception) { null }

            fun fromTriple(t: Triple<String, String, String>): Genome {
                fun range(s: String): Pair<Int, Int> {
                    val v = s.split('-').map { it.trim().toInt() }
                    return v[0] to v.getOrElse(1) { v[0] }
                }
                val (l1, l2) = range(t.second)
                val (i1, i2) = range(t.third)
                return Genome(t.first, l1, l2, i1, i2).normalized()
            }
        }

        fun normalized(): Genome {
            val p = if (packets == "tlshello" || Regex("""\d+-\d+""").matches(packets)) packets else "tlshello"
            val l1 = lenMin.coerceIn(1, 400)
            val i1 = ivMin.coerceIn(0, 60)
            return Genome(p, l1, lenMax.coerceIn(l1, 500), i1, ivMax.coerceIn(i1, 80))
        }
    }

    private val PACKETS = listOf("tlshello", "1-1", "1-2", "1-3", "1-5", "2-4")

    private fun random(): Genome {
        val l1 = listOf(1, 2, 3, 5, 10, 20, 50, 100, 150).random()
        val i1 = listOf(0, 1, 2, 5, 10, 20).random()
        return Genome(PACKETS.random(), l1, l1 * Random.nextInt(1, 4), i1, i1 + Random.nextInt(0, 15)).normalized()
    }

    private fun mutate(g: Genome): Genome {
        fun jitter(v: Int) = (v * (0.5 + Random.nextDouble())).toInt().coerceAtLeast(if (v == 0) 0 else 1) +
            if (Random.nextInt(4) == 0) Random.nextInt(-2, 3) else 0
        return when (Random.nextInt(4)) {
            0 -> g.copy(packets = PACKETS.random())
            1 -> jitter(g.lenMin).let { g.copy(lenMin = it, lenMax = maxOf(it, jitter(g.lenMax))) }
            2 -> jitter(g.ivMin).let { g.copy(ivMin = it, ivMax = maxOf(it, jitter(g.ivMax))) }
            else -> g.copy(lenMin = jitter(g.lenMin), ivMax = jitter(g.ivMax))
        }.normalized()
    }

    private fun cross(a: Genome, b: Genome) =
        Genome(if (Random.nextBoolean()) a.packets else b.packets, a.lenMin, maxOf(a.lenMin, b.lenMax), b.ivMin, maxOf(b.ivMin, a.ivMax)).normalized()

    /** Проверить вариант: перезапуск Xray обхода с ним и запрос к YouTube. мс — открылся, null — нет. */
    private fun test(ctx: Context, g: Genome): Int? {
        val p = BoxVpnService.ports ?: return null
        XrayCore.setBypassParams(ctx, g.params())
        Thread.sleep(700)
        return ClashApi.delay(p.api, p.secret, XrayCore.BYPASS, CHECK, 5000)
    }

    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)
    val running get() = busy.get()

    /**
     * Поиск: сначала лучшее, что знаем для этой сети, и заготовки, потом эволюция — пока не
     * кончатся попытки. Возвращает лучший рабочий вариант (и запоминает его) или null.
     * progress — для строки статуса. improve = false — остановиться на первом рабочем (при
     * подключении: каждая проба перезапускает Xray, а пользователь ждёт).
     */
    fun search(ctx: Context, maxTries: Int, improve: Boolean = true, progress: (String) -> Unit = {}): Genome? {
        // Одновременно — только один поиск (сторож и фоновая лаборатория могут позвать разом)
        if (!busy.compareAndSet(false, true)) return null
        try {
            val seeds = (listOfNotNull(Autopilot.bypassBest(ctx)) + XrayCore.BYPASS_PRESETS.map { Genome.fromTriple(it) }).distinct()
            val tried = HashMap<Genome, Int?>()
            var best: Pair<Genome, Int>? = null
            var n = 0
            fun run(g: Genome): Int? {
                // containsKey, а не tried[g]: «не открылся» хранится как null
                if (tried.containsKey(g)) return tried[g]
                if (n >= maxTries || !BoxVpnService.isRunning) return null
                n++
                progress("Подбираю способ обхода: попытка $n из $maxTries…")
                val ms = test(ctx, g)
                tried[g] = ms
                AppLog.conn("лаборатория обхода: $g — " + (ms?.let { "YouTube открылся за $it мс" } ?: "не открылся"))
                if (ms != null && (best == null || ms < best!!.second)) best = g to ms
                return ms
            }
            for (g in seeds) {
                run(g)
                // Нашли рабочий среди известных — чуть-чуть поищем быстрее и хватит
                if (best != null) break
            }
            // Эволюция: есть рабочий — улучшаем его; нет — разведка (мутации заготовок и случайные)
            var stale = 0
            var generated = 0
            while (n < maxTries && BoxVpnService.isRunning && (improve || best == null) && generated++ < maxTries * 10) {
                val b = best
                val g = when {
                    b != null && stale >= 3 -> break
                    b != null -> if (Random.nextInt(3) == 0) cross(b.first, tried.keys.random()) else mutate(b.first)
                    Random.nextInt(3) == 0 -> random()
                    else -> mutate(seeds.random())
                }
                if (g in tried) continue
                val before = best?.second
                run(g)
                if (best?.second == before) stale++ else stale = 0
            }
            val found = best
            if (found != null) {
                Autopilot.setBypassBest(ctx, found.first, found.second)
                XrayCore.setBypassParams(ctx, found.first.params())
                AppLog.conn("лаборатория обхода: лучший для ${Autopilot.networkLabel(ctx)} — ${found.first} (${found.second} мс), попыток $n")
            } else {
                // Ничего не открылось — Xray обратно на запомненный вариант (или заготовку)
                XrayCore.setBypassParams(ctx, Autopilot.bypassBest(ctx)?.params())
                AppLog.conn("лаборатория обхода: в ${Autopilot.networkLabel(ctx)} ни один из $n вариантов не открыл YouTube")
            }
            return found?.first
        } finally {
            busy.set(false)
        }
    }
}
