package dev.mlbb.overlay

import android.app.NotificationManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Режим боя MLBB: по трафику игры понимает, что начался матч, и на время матча
 *  - включает «Не беспокоить» (если разрешено) — уведомления не всплывают поверх игры;
 *  - ставит на паузу фоновые задачи самого приложения (подписка, обновления);
 * а после матча сохраняет его статистику: пинг (средний, лучший, худший), потери, сервер игры.
 *
 * Вызывается из [MonitorService] раз в секунду.
 */
object MatchTracker {
    data class Match(
        val start: Long,
        val durationSec: Int,
        val avgPing: Int,
        val minPing: Int,
        val maxPing: Int,
        val lossPct: Int,
        val country: String,
        val city: String,
    )

    /** Сейчас идёт матч */
    @Volatile var inMatch = false
        private set

    private const val KEY = "matches"
    private const val MAX_MATCHES = 150
    /** Столько секунд подряд боевого трафика — матч начался */
    private const val START_AFTER_SEC = 5
    /** Столько секунд без боевого трафика — матч закончился (загрузка, смерть — не конец) */
    private const val END_AFTER_SEC = 25
    /** Короче — не матч (тренировка, вылет), не сохраняем */
    private const val MIN_MATCH_SEC = 120

    private var activeSince = 0L
    private var lastActive = 0L
    private var matchStart = 0L
    private val pings = ArrayList<Int>()
    private var losses = 0
    private var lastPingAt = 0L
    private var country = ""
    private var city = ""
    private var dndPrev: Int? = null

    /**
     * Раз в секунду: идёт ли боевой трафик, страна/город сервера игры и последний замер пинга
     * (ms < 0 — пакет потерян; pingAt — время замера, чтобы не считать один замер дважды).
     */
    @Synchronized
    fun tick(ctx: Context, battle: Boolean, cc: String, place: String, ms: Int?, pingAt: Long) {
        val now = System.currentTimeMillis()
        if (battle) {
            if (activeSince == 0L) activeSince = now
            lastActive = now
            if (cc.isNotEmpty()) { country = cc; city = place }
        } else if (!inMatch) {
            activeSince = 0L
        }
        if (!inMatch && battle && now - activeSince >= START_AFTER_SEC * 1000) start(ctx, now)
        if (inMatch) {
            if (ms != null && pingAt != lastPingAt) {
                lastPingAt = pingAt
                if (ms >= 0) pings += ms else losses++
            }
            if (now - lastActive > END_AFTER_SEC * 1000) finish(ctx, lastActive)
        }
    }

    private fun start(ctx: Context, now: Long) {
        inMatch = true
        matchStart = activeSince.takeIf { it > 0 } ?: now
        pings.clear()
        losses = 0
        AppLog.add("GAME", "начался матч (сервер ${country.ifEmpty { "?" }})")
        AppSettings.load(ctx)
        if (AppSettings.battleDnd) dndOn(ctx)
    }

    /** Матч закончился (или VPN выключили посреди матча). */
    @Synchronized
    fun finish(ctx: Context, end: Long = System.currentTimeMillis()) {
        if (!inMatch) return
        inMatch = false
        activeSince = 0L
        dndOff(ctx)
        val dur = ((end - matchStart) / 1000).toInt()
        val total = pings.size + losses
        AppLog.add("GAME", "матч закончился: ${dur / 60} мин, замеров пинга ${pings.size}, потерь $losses")
        if (dur < MIN_MATCH_SEC || pings.isEmpty()) return
        val m = Match(
            start = matchStart, durationSec = dur,
            avgPing = pings.average().toInt(), minPing = pings.min(), maxPing = pings.max(),
            lossPct = if (total > 0) losses * 100 / total else 0,
            country = country, city = city,
        )
        save(ctx, m)
    }

    // ------------------------------- «Не беспокоить» -------------------------------

    fun dndAllowed(ctx: Context) =
        ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    private fun dndOn(ctx: Context) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (!nm.isNotificationPolicyAccessGranted) return
            val cur = nm.currentInterruptionFilter
            // Уже стоит «Не беспокоить» — не трогаем и потом не снимаем
            if (cur != NotificationManager.INTERRUPTION_FILTER_ALL) return
            dndPrev = cur
            // Приоритетные: будильники и звонки избранных проходят, остальное молчит
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            AppLog.add("GAME", "включил «Не беспокоить» на время матча")
        } catch (e: Exception) {
            AppLog.err("не беспокоить", e)
        }
    }

    private fun dndOff(ctx: Context) {
        val prev = dndPrev ?: return
        dndPrev = null
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            // Пользователь сам поменял режим за время матча — не перебиваем
            if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) nm.setInterruptionFilter(prev)
            AppLog.add("GAME", "выключил «Не беспокоить» — матч закончился")
        } catch (e: Exception) {
            AppLog.err("не беспокоить", e)
        }
    }

    // ---------------------------------- история ----------------------------------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun save(ctx: Context, m: Match) {
        val arr = try { JSONArray(prefs(ctx).getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }
        arr.put(JSONObject().put("s", m.start).put("d", m.durationSec).put("a", m.avgPing).put("mn", m.minPing)
            .put("mx", m.maxPing).put("l", m.lossPct).put("c", m.country).put("ci", m.city))
        val out = JSONArray()
        for (i in maxOf(0, arr.length() - MAX_MATCHES) until arr.length()) out.put(arr.get(i))
        prefs(ctx).edit().putString(KEY, out.toString()).apply()
        AppLog.add("GAME", "матч сохранён: пинг ${m.avgPing} мс (${m.minPing}–${m.maxPing}), потери ${m.lossPct}%")
    }

    /** Матчи, новые первыми. */
    fun history(ctx: Context): List<Match> {
        val arr = try { JSONArray(prefs(ctx).getString(KEY, "[]")) } catch (_: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Match(o.optLong("s"), o.optInt("d"), o.optInt("a"), o.optInt("mn"), o.optInt("mx"), o.optInt("l"),
                o.optString("c"), o.optString("ci"))
        }.reversed()
    }

    /** Средний пинг по часам суток (0..23); null — матчей в этот час мало. */
    fun byHour(list: List<Match>): Array<Int?> {
        val sums = IntArray(24)
        val counts = IntArray(24)
        val cal = java.util.Calendar.getInstance()
        for (m in list) {
            cal.timeInMillis = m.start
            val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
            sums[h] += m.avgPing
            counts[h]++
        }
        return Array(24) { h -> if (counts[h] > 0) sums[h] / counts[h] else null }
    }

    /** Лучшее время для игры: час с минимальным средним пингом (если по нему хотя бы 2 матча). */
    fun bestHour(list: List<Match>): Pair<Int, Int>? {
        val counts = IntArray(24)
        val cal = java.util.Calendar.getInstance()
        for (m in list) { cal.timeInMillis = m.start; counts[cal.get(java.util.Calendar.HOUR_OF_DAY)]++ }
        val avg = byHour(list)
        return (0 until 24).filter { avg[it] != null && counts[it] >= 2 }.minByOrNull { avg[it]!! }?.let { it to avg[it]!! }
    }
}
