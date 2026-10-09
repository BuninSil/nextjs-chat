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


    /** Скорость загрузки через сервер (tag -> Мбит/с), из короткого замера. */
    val speed = ConcurrentHashMap<String, Double>()

    /**
     * Обычный режим: среди лучших по пингу рабочих серверов выбирает самый быстрый
     * по загрузке (2 секунды на сервер). Нужен запущенный VPN.
     */
    fun pickFastest(ctx: Context, ranked: List<String>, candidates: Int = 6, progress: (Int, Int) -> Unit = { _, _ -> }): String? {
        val p = BoxVpnService.ports ?: return null
        var checked = 0
        var best: String? = null
        var bestSpeed = 0.0
        for (tag in ranked.take(candidates * 2)) {
            if (checked >= candidates) break
            if (!ClashApi.select(p.api, p.secret, "proxy", tag)) continue
            if (ClashApi.delay(p.api, p.secret, tag, CHECK_URL, 4000) == null) {
                results.put(tag, null)
                continue
            }
            checked++
            progress(checked, candidates)
            val mbps = SpeedTest.quickDownload(2)
            AppLog.srv("скорость: «${AppLog.name(ctx, tag)}» — ${mbps?.let { String.format(java.util.Locale.US, "%.1f Мбит/с", it) } ?: "не замерилась"}")
            if (mbps == null) continue
            speed[tag] = mbps
            if (mbps > bestSpeed) { bestSpeed = mbps; best = tag }
        }
        AppLog.srv("по скорости: лучший «${best?.let { AppLog.name(ctx, it) }}» ${String.format(java.util.Locale.US, "%.1f", bestSpeed)} Мбит/с")
        val chosen = best ?: return pickWorking(ctx, ranked)
        ClashApi.select(p.api, p.secret, "proxy", chosen)
        AppSettings.selectedTag = chosen
        AppSettings.save(ctx)
        applyAbroad(ctx)
        return chosen
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
        val nodes = Subscription.usable(ctx).filterNot { isSeparator(it) }
        val net = underlying(ctx)
        val game = AppSettings.gameMode
        loadExits(ctx)
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
            ranked = other + ru
        }
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
        for (tag in ranked.take(limit)) {
            if (!ClashApi.select(p.api, p.secret, "proxy", tag)) { AppLog.srv("подбор: «${AppLog.name(ctx, tag)}» — не переключился"); continue }
            if (ClashApi.delay(p.api, p.secret, tag, CHECK_URL, 4000) == null) {
                AppLog.srv("подбор: «${AppLog.name(ctx, tag)}» — трафик не идёт, пропускаю")
                results.put(tag, null)
                continue
            }
            AppLog.srv("подбор: «${AppLog.name(ctx, tag)}» — работает")
            if (!game) { chosen = tag; break }
            if (fallback == null) fallback = tag
            // Страна выхода проверяется один раз и дальше берётся из памяти
            val loc = exitCountry[tag] ?: traceExit(p.mixed)?.also { exitCountry[tag] = it }
            AppLog.srv("подбор: «${AppLog.name(ctx, tag)}» — выход ${loc ?: "не определён"}")
            if (loc == "RU") { chosen = tag; break }
        }
        saveExits(ctx)
        val best = chosen ?: fallback ?: run { AppLog.srv("подбор: рабочих серверов не нашлось"); return null }
        AppLog.srv("подбор: выбран «${AppLog.name(ctx, best)}»" + if (game && chosen == null) " (запасной — с выходом в России не нашлось)" else "")
        ClashApi.select(p.api, p.secret, "proxy", best)
        AppSettings.selectedTag = best
        AppSettings.save(ctx)
        applyAbroad(ctx)
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
                if (!ClashApi.select(p.api, p.secret, "proxy", n.tag)) continue
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
            if (keep.isNotEmpty() && BoxVpnService.ports != null) ClashApi.select(p.api, p.secret, "proxy", keep)
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
        val ok = p == null || ClashApi.select(p.api, p.secret, "proxy", tag)
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

    @Volatile private var lastGames: String? = null

    /**
     * Сервер для Brawl Stars и других игр Supercell: только зарубежный выход (Россию они не
     * пускают), из них — с меньшим пингом. Протоколы с настоящим UDP (Hysteria2, TUIC) в
     * приоритете, XHTTP — в последнюю очередь: UDP поверх HTTP в бою даёт лаги.
     */
    fun gamesFor(ctx: Context, selected: String): String? {
        loadExits(ctx)
        // Только серверы, которые ответили на последнем замере: до замеров (сразу после
        // подключения) — тот же зарубежный сервер, что и для Telegram/YouTube
        val nodes = Subscription.usable(ctx).filterNot { isSeparator(it) || exitsRussia(it) }
            .filter { results[it.tag] != null }
        if (nodes.isEmpty()) return abroadFor(ctx, selected).takeIf { it.isNotEmpty() && it != XrayCore.BYPASS }
        fun cost(n: Subscription.Node): Int {
            val ms = results[n.tag]!!.score
            val proto = when {
                n.nativeUdp -> 0
                n.xrayLink != null -> 80
                else -> 15
            }
            // Уже выбранный сервер чуть в плюсе — без лишних прыжков между почти равными
            return ms + proto - (if (n.tag == selected) 10 else 0)
        }
        return nodes.minByOrNull { cost(it) }?.tag
    }

    /** Переключить группы «abroad» и «games» в ядре под текущий выбранный сервер. */
    fun applyAbroad(ctx: Context) {
        val p = BoxVpnService.ports ?: return
        gamesFor(ctx, AppSettings.selectedTag)?.let { g ->
            if (ClashApi.select(p.api, p.secret, "games", g) && g != lastGames) {
                lastGames = g
                AppLog.srv("игры Supercell (Brawl Stars и др.) — через «${AppLog.name(ctx, g)}»")
            }
        }
        if (AppSettings.gameMode) return // игровой режим не трогаем
        val sel = AppSettings.selectedTag
        val tag = abroadFor(ctx, sel)
        if (tag.isEmpty()) return
        if (ClashApi.select(p.api, p.secret, "abroad", tag) && tag != sel) {
            AppLog.srv("выбран сервер с выходом в России — Telegram, YouTube и др. пойдут через «${AppLog.name(ctx, tag)}»")
        }
    }
}
