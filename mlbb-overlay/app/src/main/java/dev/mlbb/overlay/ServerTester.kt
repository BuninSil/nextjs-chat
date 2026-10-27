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


    /** Проверяемый сервер — только для замеров приложения, трафик пользователя не трогаем. */
    fun probe(p: BoxConfig.Ports, tag: String) = ClashApi.select(p.api, p.secret, "probe", tag)

    /** Сервер для всего трафика (и для замеров — тоже он). */
    fun selectMain(p: BoxConfig.Ports, tag: String): Boolean {
        val ok = ClashApi.select(p.api, p.secret, "proxy", tag)
        ClashApi.select(p.api, p.secret, "probe", tag)
        return ok
    }

    /** Звонки, игры и прочий UDP — через этот сервер (самый низкий пинг из рабочих); null — как «abroad» */
    @Volatile var lowTag: String? = null

    /** Горячий запасной: второй рабочий сервер, уже проверенный, — на него переключаемся за секунду */
    @Volatile var standby: String? = null

    /** Пинг до интернета через сервер (мс) или null — не отвечает. Трафик пользователя не трогает. */
    fun delayMs(tag: String, timeoutMs: Int = 3000): Int? {
        val p = BoxVpnService.ports ?: return null
        return ClashApi.delay(p.api, p.secret, tag, CHECK_URL, timeoutMs)
    }

    /**
     * Честная задержка через туннель: несколько запросов к generate_204 ЧЕРЕЗ этот сервер (а не
     * TCP до фронта CDN — тот у всех ~30 мс и ничего не говорит). Возвращает медиану, разброс и
     * число потерь; null — сервер не ответил ни разу. Трафик пользователя не трогает (Clash API
     * тестирует конкретный outbound, не переключая селектор).
     */
    private fun realDelay(p: BoxConfig.Ports, tag: String, samples: Int = 3, timeoutMs: Int = 2200): Triple<Int, Int, Int>? {
        val ms = ArrayList<Int>(samples)
        repeat(samples) { if (BoxVpnService.isRunning) ClashApi.delay(p.api, p.secret, tag, CHECK_URL, timeoutMs)?.let { ms.add(it) } }
        if (ms.isEmpty()) return null
        ms.sort()
        return Triple(ms[ms.size / 2], ms.last() - ms.first(), samples - ms.size)
    }

    /**
     * Найти горячий запасной: первый живой из лучших по пингу (кроме выбранного; в обычном
     * режиме — с выходом за границей). Проверка идёт прямо через сервер, без переключений.
     */
    fun refreshStandby(ctx: Context) {
        val sel = AppSettings.selectedTag
        val byTag = Subscription.usable(ctx).filterNot { isSeparator(it) }.associateBy { it.tag }
        val ranked = results.ranked().ifEmpty { byTag.keys.toList() }
        for (t in ranked.take(8)) {
            if (t == sel) continue
            val n = byTag[t] ?: continue
            if (!AppSettings.gameMode && exitsRussia(n)) continue
            if (delayMs(t) == null) { results.put(t, null); continue }
            if (standby != t) AppLog.srv("запасной сервер: «${Ui.cleanName(n)}»")
            standby = t
            return
        }
        standby = null
    }

    /**
     * Звонки и игры — через сервер с самым низким пингом из живых (с выходом за границей);
     * скачивание и видео — через выбранный, самый быстрый по скорости.
     */
    fun pickLowPing(ctx: Context, alive: Collection<String>) {
        if (AppSettings.gameMode || !AppSettings.autoSelect || AppSettings.selectedTag == XrayCore.BYPASS) return
        val p = BoxVpnService.ports ?: return
        val byTag = Subscription.usable(ctx).associateBy { it.tag }
        val best = alive.filter { t -> byTag[t]?.let { !exitsRussia(it) && it.xrayLink == null } == true }
            .minByOrNull { results[it]?.medianMs ?: Int.MAX_VALUE } ?: return
        if (ClashApi.select(p.api, p.secret, "lowping", best)) {
            if (lowTag != best) AppLog.srv("звонки и игры: через «${AppLog.name(ctx, best)}» — самый низкий пинг (${results[best]?.medianMs} ms)")
            lowTag = best
        }
    }

    /** Скорость загрузки через сервер (tag -> Мбит/с), из короткого замера. */
    val speed = ConcurrentHashMap<String, Double>()

    /**
     * Обычный режим: среди лучших по пингу рабочих серверов выбирает самый быстрый по загрузке.
     * Замеры идут через «probe» — трафик пользователя всё это время на текущем сервере.
     * Текущий сервер меряется тоже и меняется, только если другой заметно быстрее: короткий
     * замер шумный, и без этого сервер прыгал бы туда-сюда.
     */
    // Гистерезис: не дёргаем сервер из-за одного удачного замера соседа — переключаемся, только
    // когда кандидат заметно быстрее два замера подряд.
    @Volatile private var switchCandidate: String? = null
    @Volatile private var switchCount = 0

    /**
     * Выбор по честной задержке. Среди текущего и топ-кандидатов (ranked — уже по задержке) берём
     * самый быстрый по реальному RTT. Текущий меняем, только если другой стабильно (два замера)
     * быстрее на 20%+ И не дохлый по скорости (порог 5 Мбит/с). Скорость — только порог «живой»,
     * выбор идёт по задержке: раньше выбирали по скорости, а она упиралась в потолок канала и все
     * серверы казались одинаковыми.
     */
    fun pickFastest(ctx: Context, ranked: List<String>, candidates: Int = 6, firstWins: Boolean = false, progress: (Int, Int) -> Unit = { _, _ -> }): String? {
        val p = BoxVpnService.ports ?: return null
        val current = AppSettings.selectedTag
        val nodeOf = Subscription.usable(ctx).associateBy { it.tag }
        val cand = (listOf(current).filter { ranked.contains(it) } + ranked.filter { it != current }).take(candidates)
        // Свежая реальная задержка текущего и кандидатов — разом
        val delay = ConcurrentHashMap<String, Int>()
        val pool = Executors.newFixedThreadPool(cand.size.coerceIn(1, 8))
        val n = AtomicInteger()
        for (tag in cand) pool.execute {
            val d = realDelay(p, tag, samples = 3)
            if (d != null) { delay[tag] = d.first; results.put(tag, Result(d.first, d.second, d.first + d.second / 2 + d.third * 400)) }
            else { results.put(tag, null); nodeOf[tag]?.let { Autopilot.record(ctx, it, ok = false) } }
            progress(n.incrementAndGet(), cand.size)
        }
        pool.shutdown()
        pool.awaitTermination(15, TimeUnit.SECONDS)
        if (!BoxVpnService.isRunning) return null
        val alive = delay.keys
        // Запасной и сервер для звонков/игр — из живых, по задержке
        pickLowPing(ctx, alive)
        ranked.firstOrNull { t -> t != current && t in alive && nodeOf[t]?.let { !exitsRussia(it) } == true }?.let {
            if (it != standby) AppLog.srv("запасной сервер: «${AppLog.name(ctx, it)}» (${delay[it]} мс)")
            standby = it
        }
        val best = alive.minByOrNull { delay[it] ?: Int.MAX_VALUE }
        if (best == null) { AppLog.srv("подбор: ни один сервер не ответил через туннель"); return pickWorking(ctx, ranked) }
        val curD = delay[current]
        // Текущий жив и не сильно хуже лучшего — остаёмся
        if (current in alive && curD != null && (best == current || (delay[best] ?: 0) > curD * 0.8)) {
            switchCandidate = null; switchCount = 0
            nodeOf[current]?.let { Autopilot.record(ctx, it, ok = true) }
            AppLog.srv("оставляю «${AppLog.name(ctx, current)}» — $curD мс (лучший «${AppLog.name(ctx, best)}» ${delay[best]} мс, разница мала)")
            return current
        }
        // Лучший заметно быстрее. При ручном подключении (firstWins) переключаемся сразу, с первого
        // замера — пользователь нажал «Подключить» и ждёт лучший сервер. При ФОНОВОЙ плановой проверке
        // требуем подтверждения во втором замере подряд, чтобы не дёргаться из-за случайного спайка.
        if (!firstWins) {
            if (best != switchCandidate) { switchCandidate = best; switchCount = 1 } else switchCount++
            if (switchCount < 2 && current in alive) {
                AppLog.srv("«${AppLog.name(ctx, best)}» быстрее (${delay[best]} мс против $curD мс) — подтверждаю в следующий замер")
                return current
            }
        }
        // Порог «живой по скорости»: не уводить на быстрый по пингу, но дохлый по полосе
        if (probe(p, best)) {
            val mbps = SpeedTest.quickDownload()
            nodeOf[best]?.let { Autopilot.record(ctx, it, ok = mbps != null, mbps = mbps) }
            speed[best] = mbps ?: 0.0
            probe(p, current)
            if (mbps != null && mbps < 5.0 && current in alive) {
                AppLog.srv("«${AppLog.name(ctx, best)}» быстрый по пингу, но медленный (${String.format(java.util.Locale.US, "%.1f", mbps)} Мбит/с) — остаюсь на «${AppLog.name(ctx, current)}»")
                switchCandidate = null; switchCount = 0
                return current
            }
        }
        selectMain(p, best)
        AppSettings.selectedTag = best
        AppSettings.save(ctx)
        switchCandidate = null; switchCount = 0
        if (standby == best) standby = null
        applyAbroad(ctx)
        AppLog.srv("переключаю на «${AppLog.name(ctx, best)}» — ${delay[best]} мс" + (speed[best]?.takeIf { it > 0 }?.let { String.format(java.util.Locale.US, ", %.0f Мбит/с", it) } ?: ""))
        return best
    }

    @Volatile
    var testing = false
        private set

    private val ruWords = listOf("🇷🇺", "москва", "moscow", "россия", "russia", "санкт", "петербург", "spb", "msk")

    fun isRussian(n: Subscription.Node): Boolean {
        exitCountry[n.tag]?.let { return it == "RU" }
        val name = n.name.lowercase()
        if (ruWords.any { name.contains(it) }) return true
        // По базе — только для IP: домен пришлось бы резолвить (нельзя на главном потоке)
        if (!n.server.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return false
        return GeoDb.lookup(n.server)?.countryCode.equals("RU", true)
    }

    /** Строки-разделители в подписках («Локации под обход ⬇️») — не серверы. */
    fun isSeparator(n: Subscription.Node) = n.name.contains("⬇") || n.name.contains("⬆")

    /**
     * «Настоящая» сеть телефона (Wi-Fi/мобильная), не VPN — та, через которую реально идёт трафик.
     * Не первая попавшаяся: Xiaomi и др. держат мобильный интернет поднятым рядом с Wi-Fi, и пинг
     * уходил через мобильную сеть (с её белыми списками) — у серверов были кресты, хотя Wi-Fi работал.
     */
    fun underlying(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        fun real(n: Network?) = n?.let { cm.getNetworkCapabilities(it) }?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } == true
        // VPN включён — та же сеть, что у ядра
        BoxVpnService.coreNetwork?.takeIf { BoxVpnService.isRunning && real(it) }?.let { return it }
        // VPN выключен — сеть по умолчанию
        cm.activeNetwork?.takeIf { real(it) }?.let { return it }
        // Иначе лучшая из настоящих: рабочая (проверенная Android), Wi-Fi > кабель > мобильная
        @Suppress("DEPRECATION")
        return cm.allNetworks.filter { real(it) }.maxByOrNull { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@maxByOrNull 0
            (if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 10 else 0) + when {
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 3
                c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
                c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
                else -> 0
            }
        }
    }

    /**
     * Работает чужой VPN (не наш). Пока он включён, Android не пускает приложение мимо него,
     * и прямой замер пинга до серверов не проходит — мерить надо после подключения своего VPN.
     */
    fun otherVpnActive(ctx: Context): Boolean {
        if (BoxVpnService.isRunning || CaptureVpnService.isRunning) return false
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    private fun tcpRtt(net: Network?, addr: InetAddress, port: Int, timeoutMs: Int = 1500): Int? = try {
        Socket().use { s ->
            try { net?.bindSocket(s) } catch (_: Exception) {}
            val t = System.nanoTime()
            s.connect(InetSocketAddress(addr, port), timeoutMs)
            ((System.nanoTime() - t) / 1_000_000).toInt().coerceAtLeast(1)
        }
    } catch (_: Exception) {
        null
    }

    /** Достучаться ли напрямую (мимо туннеля) до host:port — для диагностики маршрутов (7844 и т.п.) */
    fun directReach(ctx: Context, host: String, port: Int, timeoutMs: Int = 2500): Boolean =
        tcpRtt(underlying(ctx), InetAddress.getByName(host), port, timeoutMs) != null

    /**
     * Есть ли вообще интернет (мимо VPN): Android подтверждает сеть и хоть один из DNS-серверов
     * (Яндекс, Google) отвечает. Нужно, чтобы не принять «пропала связь» (дорога, лифт, за городом)
     * за «сломался обход/сервер» — и не гонять зря подборы и уведомления.
     */
    fun internetUp(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = underlying(ctx) ?: return false
        // Прямая проверка мимо туннеля: несколько адресов и портов (у некоторых операторов 53 закрыт,
        // а 443 работает). Хоть один ответ — интернет есть, и тогда «сервер не ответил» = мёртвый сервер.
        val probes = listOf("77.88.8.8" to 53, "1.1.1.1" to 443, "8.8.8.8" to 53, "77.88.8.8" to 443)
        for ((ip, port) in probes) {
            if (tcpRtt(net, InetAddress.getByName(ip), port) != null) return true
        }
        // Ни один адрес не ответил, но Android уверен, что сеть рабочая — доверяем ему (а не считаем «нет сети»)
        return cm.getNetworkCapabilities(net)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    /**
     * Меряет задержку до всех серверов. Если VPN поднят — честно, через туннель до generate_204
     * (реальный RTT), иначе — приблизительно, по TCP до сервера (VPN ещё не запущен). Возвращает
     * теги от лучшего к худшему.
     */
    fun measure(ctx: Context, progress: (Int, Int) -> Unit): List<String> {
        // Резервные бесплатные серверы в обычный перебор не берём — они подключаются только
        // как крайний вариант, через [pickReserve]; иначе каждый замер пинговал бы их зря
        val nodes = Subscription.usable(ctx).filterNot { isSeparator(it) || Subscription.isReserve(it) }
        val net = underlying(ctx)
        val game = AppSettings.gameMode
        val p = BoxVpnService.ports
        loadExits(ctx)
        testing = true
        results.clear()
        val done = AtomicInteger()
        val pool = Executors.newFixedThreadPool(if (p != null) 20 else 16)
        for (n in nodes) {
            pool.execute {
                val r: Result? = if (p != null) {
                    // Честно: задержка через туннель (2 замера на сервер — быстрее при полном переборе)
                    realDelay(p, n.tag, samples = 2)?.let { (median, jitter, lost) ->
                        var score = median + jitter / 2 + lost * 400
                        if (game && isRussian(n)) score -= RU_BONUS
                        if (game && n.nativeUdp) score -= UDP_BONUS
                        Result(median, jitter, score)
                    }
                } else {
                    // VPN не поднят — приблизительно, по TCP до самого сервера
                    val addr = try {
                        (net?.getAllByName(n.server) ?: InetAddress.getAllByName(n.server))
                            .firstOrNull { it is Inet4Address }
                    } catch (_: Exception) { null }
                    val samples = if (addr == null) emptyList() else (1..3).mapNotNull { tcpRtt(net, addr, n.port) }
                    if (samples.isEmpty()) null else {
                        val best = samples.min()
                        val jitter = samples.max() - best
                        var score = best + jitter / 2
                        if (game && isRussian(n)) score -= RU_BONUS
                        if (game && n.nativeUdp) score -= UDP_BONUS
                        Result(best, jitter, score)
                    }
                }
                results.put(n.tag, r)
                progress(done.incrementAndGet(), nodes.size)
            }
        }
        pool.shutdown()
        pool.awaitTermination(80, TimeUnit.SECONDS)
        testing = false
        val byTag = nodes.associateBy { it.tag }
        var ranked = results.ranked()
        AppLog.srv("пинг до серверов: ответили ${ranked.size} из ${nodes.size} (сеть ${netKey(ctx)}); лучшие: " +
            ranked.take(5).joinToString { t -> "${byTag[t]?.let { Ui.cleanName(it) }} ${results[t]?.medianMs} ms" })
        if (game) {
            // Игровой режим: российские серверы всегда первыми (ближе к серверам MLBB в РФ),
            // зарубежные — только запасным вариантом
            val (ru, other) = ranked.partition { tag -> byTag[tag]?.let { isRussian(it) } == true }
            ranked = ru + other
        } else {
            // Обычный режим: VPN с выходом в России ничего не разблокирует (Telegram, YouTube…) —
            // такие серверы только запасным вариантом
            val (ru, other) = ranked.partition { tag -> byTag[tag]?.let { exitsRussia(it) } == true }
            // Автопилот: сначала то, что уже работало в этой сети, с учётом скорости
            ranked = Autopilot.order(ctx, other, byTag) { results[it]?.medianMs } + ru
        }
        for (n in nodes) Autopilot.recordPing(ctx, n, results[n.tag]?.medianMs)
        // Избранные пользователем — в начало, порядок внутри сохраняется
        val (fav, rest) = ranked.partition { tag -> byTag[tag]?.let { Subscription.isFavorite(it) } == true }
        return fav + rest
    }

    /**
     * Нужен запущенный VPN: идёт по списку от лучшего и берёт первый сервер,
     * через который реально проходит запрос. Мёртвые помечает ✖.
     */
    fun pickWorking(ctx: Context, ranked: List<String>, maxTries: Int = 8): String? {
        val p = BoxVpnService.ports ?: return null
        val game = AppSettings.gameMode
        loadExits(ctx)
        var fallback: String? = null
        var chosen: String? = null
        // Игровой режим: нужен выход в России — иначе игра считает, что ты за границей,
        // и кидает на зарубежный сервер. Проверяем до 20 кандидатов, обычный режим — до первого рабочего.
        val limit = if (game) 20 else maxTries
        val order = ranked.take(limit)
        // Обычный режим: живы ли кандидаты — проверяем все разом (через проверку пинга, без переключения
        // трафика), а не по очереди с перебором. Раньше каждый сервер отнимал до 4 секунд, набегало до минуты.
        if (!game) {
            val live = ConcurrentHashMap.newKeySet<String>()
            val pool = Executors.newFixedThreadPool(order.size.coerceIn(1, 12))
            for (tag in order) pool.execute { if (delayMs(tag, 3000) != null) live.add(tag) }
            pool.shutdown(); pool.awaitTermination(5, TimeUnit.SECONDS)
            val nodeOf = Subscription.usable(ctx).associateBy { it.tag }
            for (tag in order) {
                val ok = tag in live
                if (!ok) results.put(tag, null)
                nodeOf[tag]?.let { Autopilot.record(ctx, it, ok = ok) }
                if (ok && chosen == null) chosen = tag
            }
            AppLog.srv(chosen?.let { "подбор: «${AppLog.name(ctx, it)}» — работает (живых ${live.size} из ${order.size})" } ?: "подбор: рабочих серверов не нашлось")
        }
        for (tag in if (game) order else emptyList()) {
            if (!probe(p, tag)) { AppLog.srv("подбор: «${AppLog.name(ctx, tag)}» — не переключился"); continue }
            val node = Subscription.usable(ctx).firstOrNull { it.tag == tag }
            if (ClashApi.delay(p.api, p.secret, tag, CHECK_URL, 4000) == null) {
                AppLog.srv("подбор: «${AppLog.name(ctx, tag)}» — трафик не идёт, пропускаю")
                results.put(tag, null)
                node?.let { Autopilot.record(ctx, it, ok = false) }
                continue
            }
            AppLog.srv("подбор: «${AppLog.name(ctx, tag)}» — работает")
            node?.let { Autopilot.record(ctx, it, ok = true) }
            if (!game) { chosen = tag; break }
            if (fallback == null) fallback = tag
            // Страна выхода проверяется один раз и дальше берётся из памяти
            val loc = exitCountry[tag] ?: traceExit(p.mixed)?.also { exitCountry[tag] = it }
            AppLog.srv("подбор: «${AppLog.name(ctx, tag)}» — выход ${loc ?: "не определён"}")
            if (loc == "RU") { chosen = tag; break }
        }
        saveExits(ctx)
        val best = chosen ?: fallback ?: run {
            AppLog.srv("подбор: рабочих серверов не нашлось")
            probe(p, AppSettings.selectedTag)
            return null
        }
        AppLog.srv("подбор: выбран «${AppLog.name(ctx, best)}»" + if (game && chosen == null) " (запасной — с выходом в России не нашлось)" else "")
        selectMain(p, best)
        AppSettings.selectedTag = best
        AppSettings.save(ctx)
        // Сервер для звонков мог и упасть — пока через новый, самый низкий пинг найдёт подбор по скорости
        lowTag = null
        if (standby == best) standby = null
        applyAbroad(ctx)
        return best
    }

    /**
     * Крайний случай: ни один сервер подписки не работает, а пользователь включил резерв.
     * Берём бесплатные публичные серверы ([Subscription.reserveNodes]), проверяем их разом и
     * подключаемся к первому живому. Возвращает тег резервного сервера или null.
     */
    fun pickReserve(ctx: Context): String? {
        if (!AppSettings.freeReserve) return null
        val p = BoxVpnService.ports ?: return null
        Subscription.refreshReserve(ctx) // обновит список, если устарел; свежий не трогает
        val reserve = Subscription.usable(ctx).filter { Subscription.isReserve(it) }
        if (reserve.isEmpty()) { AppLog.srv("резерв: список бесплатных серверов пуст"); return null }
        AppLog.srv("резерв: проверяю ${reserve.size} бесплатных серверов")
        val live = ConcurrentHashMap.newKeySet<String>()
        val pool = Executors.newFixedThreadPool(reserve.size.coerceIn(1, 12))
        for (n in reserve) pool.execute { if (delayMs(n.tag, 4000) != null) live.add(n.tag) }
        pool.shutdown(); pool.awaitTermination(12, TimeUnit.SECONDS)
        // Из живых — самый быстрый по пингу (меряем ещё раз, их уже немного)
        val best = live.minByOrNull { delayMs(it, 3000) ?: Int.MAX_VALUE } ?: run {
            AppLog.srv("резерв: ни один бесплатный сервер не ответил")
            probe(p, AppSettings.selectedTag)
            return null
        }
        selectMain(p, best)
        AppSettings.selectedTag = best
        AppSettings.save(ctx)
        lowTag = null
        applyAbroad(ctx)
        AppLog.srv("резерв: подключил бесплатный сервер «${AppLog.name(ctx, best)}»")
        return best
    }

    /** Ключ кэша стран выхода. v2: старые записи были неверными (проверка шла через один сервер). */
    const val EXITS_KEY = "exits_v2"

    /** Реальная страна выхода в интернет (tag -> код страны), по ответу Cloudflare через сервер. */
    val exitCountry = ConcurrentHashMap<String, String>()
    private var exitsLoaded = false

    fun loadExits(ctx: Context) {
        if (exitsLoaded) return
        exitsLoaded = true
        val raw = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(EXITS_KEY, null) ?: return
        try {
            val o = org.json.JSONObject(raw)
            for (k in o.keys()) exitCountry[k] = o.getString(k)
        } catch (_: Exception) {
        }
    }

    /** Сбросить замеры и перечитать страны выхода (после загрузки настроек из файла). */
    fun reload(ctx: Context) {
        results.clear()
        speed.clear()
        exitCountry.clear()
        exitsLoaded = false
        loadExits(ctx)
    }

    private fun saveExits(ctx: Context) {
        val o = org.json.JSONObject()
        for ((k, v) in exitCountry) o.put(k, v)
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(EXITS_KEY, o.toString()).apply()
    }

    /**
     * Проверяет страну выхода у всех серверов заново (кнопка «Выходы»). Нужен запущенный VPN.
     * В конце возвращает ядро на выбранный сервер.
     */
    /** Остановить проверку выходов (кнопка «Стоп»). */
    @Volatile var cancelExits = false

    /**
     * Проверяет страну выхода у всех серверов заново (кнопка «Выходы»). Нужен запущенный VPN.
     * Неотвечающие серверы пропускаются быстро. В конце ядро возвращается на выбранный сервер.
     * Возвращает (определено, в России, не ответили).
     */
    fun checkAllExits(ctx: Context, progress: (Int, Int, String) -> Unit): Triple<Int, Int, Int> {
        val p = BoxVpnService.ports ?: return Triple(0, 0, 0)
        loadExits(ctx)
        cancelExits = false
        val nodes = Subscription.usable(ctx).filterNot { isSeparator(it) }
        val keep = AppSettings.selectedTag
        var found = 0
        var ru = 0
        var dead = 0
        try {
            for ((i, n) in nodes.withIndex()) {
                if (cancelExits || BoxVpnService.ports == null) break
                progress(i + 1, nodes.size, Ui.cleanName(n))
                if (!probe(p, n.tag)) continue
                // Мёртвый сервер — сразу дальше, не ждём таймаутов проверки выхода
                if (ClashApi.delay(p.api, p.secret, n.tag, CHECK_URL, 2500) == null) {
                    AppLog.srv("выход: «${Ui.cleanName(n)}» — не отвечает")
                    dead++
                    continue
                }
                val loc = traceExit(p.mixed)
                AppLog.srv("выход: «${Ui.cleanName(n)}» — ${loc ?: "не определён"}")
                if (loc == null) continue
                exitCountry[n.tag] = loc
                found++
                if (loc == "RU") ru++
            }
        } finally {
            saveExits(ctx)
            if (keep.isNotEmpty() && BoxVpnService.ports != null) probe(p, keep)
        }
        return Triple(found, ru, dead)
    }

    /** Адреса, где Cloudflare отвечает, с какого IP и из какой страны пришёл запрос (только https — с http редирект). */
    private val TRACE_URLS = listOf(
        "https://speed.cloudflare.com/cdn-cgi/trace",
        "https://www.cloudflare.com/cdn-cgi/trace",
        "https://1.1.1.1/cdn-cgi/trace",
    )

    /**
     * Через текущий сервер (SOCKS-вход ядра) спрашивает у Cloudflare, из какой страны пришёл запрос.
     * Если страны в ответе нет — определяет её по IP выхода через базу DB-IP.
     */
    private fun traceExit(mixedPort: Int): String? {
        for (url in TRACE_URLS) {
            val fields = trace(url, mixedPort) ?: continue
            fields["loc"]?.uppercase()?.takeIf { it.length == 2 && it != "XX" }?.let { return it }
            fields["ip"]?.let { ip -> GeoDb.lookup(ip)?.countryCode?.uppercase()?.let { return it } }
        }
        return null
    }

    /** Внешний IP и страна (ip, код страны): через VPN (mixedPort) или напрямую (null). */
    fun whoAmI(mixedPort: Int?): Pair<String, String?>? {
        for (url in TRACE_URLS) {
            val f = trace(url, mixedPort) ?: continue
            val ip = f["ip"] ?: continue
            val cc = f["loc"]?.uppercase()?.takeIf { it.length == 2 && it != "XX" } ?: GeoDb.lookup(ip)?.countryCode?.uppercase()
            return ip to cc
        }
        return null
    }

    /** Ответ Cloudflare /cdn-cgi/trace полями (ip, loc…). mixedPort — через VPN, null — напрямую. */
    private fun trace(url: String, mixedPort: Int?): Map<String, String>? = try {
        val proxy = if (mixedPort != null) java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", mixedPort))
        else java.net.Proxy.NO_PROXY
        val c = java.net.URL(url).openConnection(proxy) as java.net.HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 3000
        c.instanceFollowRedirects = true
        // Новое соединение на каждую проверку: иначе Android переиспользует открытое
        // через предыдущий сервер, и у всех серверов «выход» оказывается одинаковым
        c.setRequestProperty("Connection", "close")
        c.inputStream.bufferedReader().use { r ->
            r.readLines().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i > 0) line.substring(0, i) to line.substring(i + 1).trim() else null
            }.toMap()
        }
    } catch (_: Exception) {
        null
    }

    // ------------------- нужен ли полный подбор при подключении -------------------

    /** Полный подбор (пинг до всех + скорость) не чаще, чем раз в столько, если сеть не менялась. */
    private const val RANK_TTL_MS = 6 * 60 * 60 * 1000L

    /** Тип текущей сети: Wi-Fi или мобильная — при смене лучший сервер обычно другой. */
    fun netKey(ctx: Context): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val c = underlying(ctx)?.let { cm.getNetworkCapabilities(it) } ?: return "none"
        return when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
            else -> "other"
        }
    }

    /** Подбор устарел: давно не делали, сменилась сеть или режим. */
    fun rankingStale(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val at = p.getLong("rankAt", 0)
        return System.currentTimeMillis() - at > RANK_TTL_MS ||
            p.getString("rankNet", "") != netKey(ctx) ||
            p.getBoolean("rankGame", false) != AppSettings.gameMode
    }

    fun markRanked(ctx: Context) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putLong("rankAt", System.currentTimeMillis())
            .putString("rankNet", netKey(ctx))
            .putBoolean("rankGame", AppSettings.gameMode)
            .apply()
    }

    /** Сбросить подбор — например, после обновления подписки. */
    fun resetRanking(ctx: Context) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().remove("rankAt").apply()
    }

    /** Быстрая проверка: ходит ли трафик через выбранный сервер прямо сейчас. */
    fun alive(tag: String): Boolean {
        val p = BoxVpnService.ports ?: return false
        return ClashApi.delay(p.api, p.secret, tag, CHECK_URL, 3000) != null
    }

    /** Подходит ли сервер для игры: выход в России (если уже проверяли) — иначе проверяем сейчас. */
    fun exitOkForGame(ctx: Context, tag: String): Boolean {
        loadExits(ctx)
        exitCountry[tag]?.let { return it == "RU" }
        val p = BoxVpnService.ports ?: return false
        val loc = traceExit(p.mixed) ?: return false
        exitCountry[tag] = loc
        saveExits(ctx)
        return loc == "RU"
    }

    /**
     * «Сменить сервер» (кнопка в уведомлении): следующий по рейтингу рабочий сервер после текущего.
     * Избранные — первыми; в игровом режиме — только с выходом в России (если страна уже известна).
     * Возвращает новый сервер или null, если переключиться не на что.
     */
    fun switchNext(ctx: Context): Subscription.Node? {
        loadExits(ctx)
        val nodes = Subscription.usable(ctx).filterNot { isSeparator(it) }
        if (nodes.size < 2) return null
        val rank = results.ranked().withIndex().associate { it.value to it.index }
        val ordered = nodes
            .filterNot { results.containsKey(it.tag) && results[it.tag] == null } // не ответили при замере
            .filter { !AppSettings.gameMode || (exitCountry[it.tag] ?: "RU") == "RU" }
            .sortedWith(compareBy({ !Subscription.isFavorite(it) }, { rank[it.tag] ?: Int.MAX_VALUE }))
        if (ordered.isEmpty()) return null
        val cur = ordered.indexOfFirst { it.tag == AppSettings.selectedTag }
        // По кругу от текущего; до 5 попыток — пропускаем те, что сейчас не отвечают
        for (step in 1..minOf(5, ordered.size)) {
            val n = ordered[(cur + step).mod(ordered.size)]
            if (n.tag == AppSettings.selectedTag) continue
            if (!use(ctx, n.tag)) continue
            if (BoxVpnService.ports == null || alive(n.tag)) return n
            AppLog.srv("«${AppLog.name(ctx, n.tag)}» не отвечает — следующий")
        }
        return null
    }

    /** Переключает ядро на сервер и запоминает выбор. */
    fun use(ctx: Context, tag: String): Boolean {
        AppLog.srv("переключаю на «${AppLog.name(ctx, tag)}»")
        val p = BoxVpnService.ports
        val ok = p == null || selectMain(p, tag)
        if (ok) {
            AppSettings.selectedTag = tag
            AppSettings.save(ctx)
            applyAbroad(ctx)
        }
        return ok
    }

    // ------------------- заблокированные сервисы — через зарубежный сервер -------------------

    /**
     * Выход в интернет в России: по проверенной стране выхода, а пока она не известна — по названию.
     * Без базы IP: серверы «для белых списков» стоят в России, но выходят за границей.
     */
    fun exitsRussia(n: Subscription.Node): Boolean {
        exitCountry[n.tag]?.let { return it == "RU" }
        val name = n.name.lowercase()
        return ruWords.any { name.contains(it) }
    }

    /** Страна выхода выбранного сервера узнана из «Моего IP» — запоминаем. */
    fun rememberExit(ctx: Context, tag: String, country: String) {
        if (tag.isEmpty() || country.length != 2 || exitCountry[tag] == country) return
        loadExits(ctx)
        exitCountry[tag] = country
        saveExits(ctx)
        applyAbroad(ctx)
    }

    /**
     * Через какой сервер пускать Telegram, YouTube и прочее заблокированное: через выбранный,
     * если он выходит за границей, иначе — через лучший зарубежный.
     */
    fun abroadFor(ctx: Context, selected: String): String {
        loadExits(ctx)
        val nodes = Subscription.usable(ctx).filterNot { isSeparator(it) }
        val sel = nodes.firstOrNull { it.tag == selected }
        if (sel != null && !exitsRussia(sel)) return selected
        val byTag = nodes.associateBy { it.tag }
        return results.ranked().firstOrNull { t -> byTag[t]?.let { !exitsRussia(it) } == true }
            ?: nodes.firstOrNull { !exitsRussia(it) && !(results.containsKey(it.tag) && results[it.tag] == null) }?.tag
            ?: selected
    }

    /** Переключить группу «abroad» в ядре под текущий выбранный сервер. */
    fun applyAbroad(ctx: Context) {
        if (AppSettings.gameMode) return // игровой режим не трогаем
        val p = BoxVpnService.ports ?: return
        val sel = AppSettings.selectedTag
        val tag = abroadFor(ctx, sel)
        if (tag.isEmpty()) return
        // Сервер выбран вручную (или автовыбор ещё не мерил) — звонки и игры через него же
        if (!AppSettings.autoSelect || lowTag == null) { lowTag = null; ClashApi.select(p.api, p.secret, "lowping", tag) }
        if (ClashApi.select(p.api, p.secret, "abroad", tag) && tag != sel) {
            AppLog.srv(if (sel == XrayCore.BYPASS) "обход: Telegram и другое, что обход не пробивает, — через «${AppLog.name(ctx, tag)}»"
                else "выбран сервер с выходом в России — Telegram, YouTube и др. пойдут через «${AppLog.name(ctx, tag)}»")
        }
        // Telegram, когда он не напрямую, идёт через тот же заграничный сервер
        if (ClashApi.now(p.api, p.secret, "tg") != "direct") ClashApi.select(p.api, p.secret, "tg", tag)
    }

    /**
     * Жив ли сервер, через который идут Telegram, YouTube и прочее заблокированное (группа «abroad»).
     * Если умер — переключаем на живой зарубежный, иначе эти сервисы лежат, а никто не проверяет
     * (в обычном режиме abroad раньше не отслеживался — телега могла висеть мёртвой).
     */
    fun ensureAbroadAlive(ctx: Context) {
        if (AppSettings.gameMode) return
        val p = BoxVpnService.ports ?: return
        val cur = ClashApi.now(p.api, p.secret, "abroad") ?: return
        if (cur == AppSettings.selectedTag || delayMs(cur) != null) return // жив или это сам выбранный сервер
        val byTag = Subscription.usable(ctx).filterNot { isSeparator(it) }.associateBy { it.tag }
        val repl = (results.ranked() + byTag.keys).distinct().firstOrNull { t ->
            t != cur && byTag[t]?.let { !exitsRussia(it) } == true && delayMs(t) != null
        } ?: return
        ClashApi.select(p.api, p.secret, "abroad", repl)
        if (ClashApi.now(p.api, p.secret, "tg") != "direct") ClashApi.select(p.api, p.secret, "tg", repl)
        AppLog.srv("сервер для Telegram/YouTube «${AppLog.name(ctx, cur)}» не отвечает — переключил на «${AppLog.name(ctx, repl)}»")
    }

    /**
     * Обход без сервера (гибрид): подобрать рабочий зарубежный сервер для того, что обход не
     * пробивает (Telegram, Instagram…). Выбранный «сервер» (обход) не меняется.
     */
    fun pickAbroadForBypass(ctx: Context) {
        val p = BoxVpnService.ports ?: return
        if (!internetUp(ctx)) { AppLog.srv("обход: интернета нет — сервер для Telegram подберу позже"); return }
        val nodes = Subscription.usable(ctx).filterNot { isSeparator(it) }
        if (nodes.isEmpty()) return
        val ranked = measure(ctx) { _, _ -> }
        val byTag = nodes.associateBy { it.tag }
        for (tag in ranked.take(10)) {
            val n = byTag[tag] ?: continue
            if (exitsRussia(n)) continue
            val ok = ClashApi.delay(p.api, p.secret, tag, CHECK_URL, 4000) != null
            Autopilot.record(ctx, n, ok)
            if (!ok) { results.put(tag, null); continue }
            if (ClashApi.select(p.api, p.secret, "abroad", tag)) {
                AppLog.srv("обход: Telegram и другое, что обход не пробивает, — через «${Ui.cleanName(n)}»")
            }
            return
        }
        AppLog.srv("обход: рабочего зарубежного сервера для Telegram и др. не нашлось")
    }
}
