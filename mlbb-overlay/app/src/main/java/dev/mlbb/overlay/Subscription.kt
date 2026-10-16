package dev.mlbb.overlay

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

/**
 * Подписка: скачивание по ссылке и разбор ссылок серверов
 * (vless, trojan, vmess, ss, hysteria2, tuic) в outbound'ы sing-box.
 */
object Subscription {
    data class Node(
        val tag: String,
        val name: String,
        val type: String,
        val server: String,
        val port: Int,
        /** true — протокол с настоящим UDP (лучше для игры), false — UDP поверх TCP */
        val nativeUdp: Boolean,
        val outbound: JSONObject,
        /** Ссылка для ядра Xray (XHTTP), если sing-box такой транспорт не умеет */
        val xrayLink: String? = null,
        /** Из какой подписки: "" — основная, иначе ссылка дополнительной */
        val sub: String = "",
    )

    data class Info(val upload: Long, val download: Long, val total: Long, val expireSec: Long)

    @Volatile
    var nodes: List<Node> = emptyList()
        private set

    @Volatile
    var info: Info? = null
        private set

    /** Серверы, которые работают на этом устройстве: XHTTP — только если есть ядро Xray (64 бита). */
    /**
     * Серверы, которыми можно пользоваться. Скрытые пользователем — только если includeHidden.
     * Если приложение ещё не открывали (виджет, плитка), подписка подгружается из файла.
     */
    fun usable(ctx: Context, includeHidden: Boolean = false): List<Node> {
        if (nodes.isEmpty()) load(ctx)
        loadMarks(ctx)
        val xray = XrayCore.available(ctx)
        return nodes.filter { (it.xrayLink == null || xray) && (includeHidden || it.name !in hidden) }
    }

    // ------------------------- избранные и скрытые -------------------------
    // Храним по названию сервера: номера (n0, n1…) меняются при обновлении подписки.

    val favorites: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    val hidden: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    @Volatile private var marksLoaded = false

    private fun loadMarks(ctx: Context) {
        if (marksLoaded) return
        marksLoaded = true
        val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        favorites.addAll(p.getStringSet("favorites", emptySet()) ?: emptySet())
        hidden.addAll(p.getStringSet("hidden", emptySet()) ?: emptySet())
    }

    private fun saveMarks(ctx: Context) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putStringSet("favorites", HashSet(favorites))
            .putStringSet("hidden", HashSet(hidden))
            .apply()
    }

    fun isFavorite(n: Node) = n.name in favorites
    fun isHidden(n: Node) = n.name in hidden

    fun toggleFavorite(ctx: Context, n: Node) {
        loadMarks(ctx)
        if (!favorites.remove(n.name)) { favorites.add(n.name); hidden.remove(n.name) }
        saveMarks(ctx)
    }

    fun toggleHidden(ctx: Context, n: Node) {
        loadMarks(ctx)
        if (!hidden.remove(n.name)) { hidden.add(n.name); favorites.remove(n.name) }
        saveMarks(ctx)
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "subscription.txt")
    private fun infoFile(ctx: Context) = File(ctx.filesDir, "subscription-info.txt")

    /** Перечитать всё с диска (после загрузки настроек из файла). */
    fun reload(ctx: Context) {
        nodes = emptyList()
        info = null
        favorites.clear()
        hidden.clear()
        marksLoaded = false
        load(ctx)
        loadMarks(ctx)
    }

    fun load(ctx: Context) {
        nodes = combined(ctx)
        infoFile(ctx).takeIf { it.exists() }?.readText()?.let { info = parseUserInfo(it) }
    }

    // ------------------------- дополнительные подписки -------------------------
    // Основная — как раньше (subscription.txt, AppSettings.subUrl): номера её серверов не меняются,
    // избранное, выходы и выбранный сервер не слетают. Дополнительные — своими файлами, их серверы
    // идут в общий список после основной.

    private const val EXTRA_KEY = "extraSubs"

    /** Ссылки дополнительных подписок, по порядку добавления */
    fun extras(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(EXTRA_KEY, null) ?: return emptyList()
        return try { JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } } catch (_: Exception) { emptyList() }
    }

    private fun saveExtras(ctx: Context, list: List<String>) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString(EXTRA_KEY, JSONArray(list).toString()).apply()
    }

    private fun key(url: String) = java.security.MessageDigest.getInstance("SHA-1")
        .digest(url.trim().toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    fun extraFile(ctx: Context, url: String) = File(ctx.filesDir, "subscription-${key(url)}.txt")
    private fun extraInfoFile(ctx: Context, url: String) = File(ctx.filesDir, "subscription-${key(url)}-info.txt")

    private fun isPrimary(url: String) = url.trim() == AppSettings.subUrl.trim()

    /** Основная + все дополнительные; номера (n0, n1…) — сквозные, основная первой. */
    private fun combined(ctx: Context): List<Node> {
        val out = ArrayList<Node>()
        file(ctx).takeIf { it.exists() }?.let { out += parseAll(it.readText()) }
        for (u in extras(ctx)) {
            val f = extraFile(ctx, u)
            if (!f.exists()) continue
            val parsed = try { parseAll(f.readText()) } catch (_: Exception) { emptyList() }
            for (n in parsed) {
                val tag = "n${out.size}"
                out += n.copy(tag = tag, outbound = JSONObject(n.outbound.toString()).put("tag", tag), sub = u)
            }
        }
        return out
    }

    /**
     * Добавить подписку откуда угодно: основной ещё нет — станет основной, иначе дополнительной.
     * Бросает исключение с понятным текстом.
     */
    fun add(ctx: Context, url: String): Int {
        val u = url.trim()
        if (!u.startsWith("http")) throw RuntimeException("нужна ссылка подписки (https://…)")
        if (!AppSettings.subUrl.startsWith("http") && !file(ctx).exists()) {
            AppSettings.subUrl = u
            AppSettings.save(ctx)
            return update(ctx, u)
        }
        return addExtra(ctx, u)
    }

    /** Ссылка подписки сервера: для основной — AppSettings.subUrl */
    fun urlOf(n: Node) = n.sub.ifEmpty { AppSettings.subUrl }

    /** Сколько серверов в подписке (для списка в окне «Подписки») */
    fun countFor(ctx: Context, url: String): Int {
        val f = if (isPrimary(url)) file(ctx) else extraFile(ctx, url)
        return try { if (f.exists()) parseAll(f.readText()).size else 0 } catch (_: Exception) { 0 }
    }

    fun infoFor(ctx: Context, url: String): Info? {
        val f = if (isPrimary(url)) infoFile(ctx) else extraInfoFile(ctx, url)
        return try { f.takeIf { it.exists() }?.readText()?.let { parseUserInfo(it) } } catch (_: Exception) { null }
    }

    /** Добавить дополнительную подписку и скачать её. Не скачалась — не добавляется. */
    fun addExtra(ctx: Context, url: String): Int {
        val u = url.trim()
        if (!u.startsWith("http")) throw RuntimeException("нужна ссылка подписки (https://…)")
        if (isPrimary(u) || u in extras(ctx)) throw RuntimeException("эта подписка уже добавлена")
        AppLog.sub("добавляю дополнительную подписку: ${AppLog.host(u)}")
        saveExtras(ctx, extras(ctx) + u)
        return try {
            update(ctx, u)
        } catch (e: Exception) {
            saveExtras(ctx, extras(ctx) - u)
            extraFile(ctx, u).delete()
            extraInfoFile(ctx, u).delete()
            throw e
        }
    }

    /** Удалить подписку (основную или дополнительную) вместе с её серверами. */
    fun remove(ctx: Context, url: String) {
        val u = url.trim()
        AppLog.sub("удаляю подписку ${AppLog.host(u)}")
        if (isPrimary(u)) {
            // Основная уходит — первая дополнительная становится основной
            val next = extras(ctx).firstOrNull()
            file(ctx).delete()
            infoFile(ctx).delete()
            info = null
            if (next != null) {
                extraFile(ctx, next).takeIf { it.exists() }?.renameTo(file(ctx))
                extraInfoFile(ctx, next).takeIf { it.exists() }?.renameTo(infoFile(ctx))
                info = infoFile(ctx).takeIf { it.exists() }?.readText()?.let { parseUserInfo(it) }
                saveExtras(ctx, extras(ctx) - next)
            }
            AppSettings.subUrl = next ?: ""
            AppSettings.save(ctx)
        } else {
            extraFile(ctx, u).delete()
            extraInfoFile(ctx, u).delete()
            saveExtras(ctx, extras(ctx) - u)
        }
        setNodes(ctx, combined(ctx))
    }

    /**
     * Панели (Remnawave, Marzban, 3x-ui) отдают подписку только знакомым клиентам
     * и часто требуют идентификатор устройства (HWID). Пробуем представиться
     * популярными клиентами по очереди, пока панель не отдаст серверы.
     */
    private val userAgents = listOf(
        "v2rayNG/1.9.16", "Happ/3.6.0", "Hiddify/2.5.7", "Karing/1.1.2.606", "sing-box/1.11.4", "v2RayTun/5.1",
        "ClashMetaForAndroid/2.11.1.Meta", "clash-verge/v2.0.3", "Streisand", "FoXray",
        // Неизвестный панели клиент — многие тогда отдают обычный список ссылок
        "curl/8.5.0",
    )

    /** Новый список серверов. Проверки (страна выхода, подбор) сбрасываются, только если он поменялся. */
    private fun setNodes(ctx: Context, parsed: List<Node>) {
        AppLog.sub("подписка обновлена: ${parsed.size} серверов" + extras(ctx).size.let { if (it > 0) " (подписок: ${it + 1})" else "" })
        val old = nodes.map { it.name + "|" + it.server + ":" + it.port }
        val new = parsed.map { it.name + "|" + it.server + ":" + it.port }
        // Добавились серверы в конец (новая дополнительная подписка) — старые номера на месте,
        // проверки выходов не сбрасываем. Иначе номера сдвинулись — проверяем заново
        val kept = new.size >= old.size && new.take(old.size) == old
        nodes = parsed
        if (!kept) resetExits(ctx)
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putLong("subAt", System.currentTimeMillis()).apply()
    }

    /**
     * Скачать подписку через сервер с выходом в России (нужен работающий встроенный VPN):
     * для панелей, которые пускают только российские адреса. Выбранный сервер потом возвращается.
     */
    fun updateViaRuExit(ctx: Context, url: String, progress: (String) -> Unit = {}): Int {
        val p = BoxVpnService.ports ?: throw RuntimeException("VPN не подключён")
        ServerTester.loadExits(ctx)
        val keep = AppSettings.selectedTag
        // Сначала с уже известным выходом в России, потом российские по названию, потом остальные
        val candidates = usable(ctx).filterNot { ServerTester.isSeparator(it) }.sortedBy {
            when {
                ServerTester.exitCountry[it.tag] == "RU" -> 0
                ServerTester.exitCountry[it.tag] == null && ServerTester.isRussian(it) -> 1
                ServerTester.exitCountry[it.tag] == null -> 2
                else -> 3
            }
        }.take(12)
        var last: Exception = RuntimeException("не нашёл сервер с выходом в России")
        try {
            for ((i, n) in candidates.withIndex()) {
                if (ServerTester.exitCountry[n.tag]?.let { it != "RU" } == true) break
                progress("Ищу сервер с выходом в России: ${i + 1} из ${candidates.size}…")
                if (!ServerTester.probe(p, n.tag)) continue
                if (!ServerTester.exitOkForGame(ctx, n.tag)) continue
                progress("Скачиваю подписку через «${Ui.cleanName(n)}»…")
                AppLog.sub("пробую скачать через «${Ui.cleanName(n)}» (выход в России)")
                try {
                    return update(ctx, url)
                } catch (e: Exception) {
                    last = e
                }
            }
        } finally {
            if (keep.isNotEmpty()) ServerTester.probe(p, keep)
        }
        throw last
    }

    /** Фоновое обновление подписки — не чаще раза в ~сутки. Ошибки молча пропускаем. */
    fun refreshInBackground(ctx: Context) {
        AppSettings.load(ctx)
        val urls = (listOf(AppSettings.subUrl) + extras(ctx)).filter { it.startsWith("http") }
        if (urls.isEmpty()) return
        val at = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getLong("subAt", 0)
        if (System.currentTimeMillis() - at < 20 * 60 * 60 * 1000L) return
        AppLog.sub("фоновое ежедневное обновление подписок: ${urls.size}")
        if (nodes.isEmpty()) load(ctx)
        for (url in urls) {
            try {
                update(ctx, url)
            } catch (_: GeoBlocked) {
                // Панель пускает только российские адреса — через сервер с выходом в России,
                // но не в игровом режиме: там сервер посреди сессии не трогаем
                if (BoxVpnService.isRunning && !AppSettings.gameMode) try { updateViaRuExit(ctx, url) } catch (_: Exception) {}
            } catch (_: Exception) {
            }
        }
    }

    /** Что сказать про подписку: скоро кончится срок или трафик. null — всё в порядке. */
    fun warning(): Pair<String, String>? {
        val i = info ?: return null
        if (i.expireSec > 0) {
            val days = (i.expireSec * 1000 - System.currentTimeMillis()) / 86_400_000.0
            if (days <= 0) return "Подписка закончилась" to "Продли её у своего VPN-сервиса, иначе серверы перестанут работать"
            if (days <= 3) {
                val d = kotlin.math.ceil(days).toInt()
                return "Подписка заканчивается через $d " + (if (d == 1) "день" else "дня") to
                    "Продли её заранее, чтобы VPN не отключился"
            }
        }
        if (i.total > 0) {
            val used = i.upload + i.download
            if (used >= i.total * 0.9) {
                val left = (i.total - used).coerceAtLeast(0) / 1_073_741_824.0
                return "Трафик подписки почти кончился" to
                    String.format(java.util.Locale.US, "Осталось %.1f ГБ из %.0f ГБ", left, i.total / 1_073_741_824.0)
            }
        }
        return null
    }

    private fun resetExits(ctx: Context) {
        ServerTester.exitCountry.clear()
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().remove(ServerTester.EXITS_KEY).apply()
        // Серверы новые — при следующем подключении подберём заново
        ServerTester.resetRanking(ctx)
    }

    /** Больше этого подписка не бывает (сотни серверов — десятки КБ); больше — мусор или атака */
    private const val MAX_BODY = 5 * 1024 * 1024

    /** Читает не больше [MAX_BODY] байт: огромный ответ не повесит приложение и не съест память. */
    private fun readLimited(raw: java.io.InputStream, limit: Int = MAX_BODY): String {
        // Сжатие сами: панель может заявить gzip и прислать обычный текст (или наоборот) —
        // встроенная распаковка Android на этом падала («ID1ID2: actual … != expected 0x1f8b»)
        val peek = java.io.BufferedInputStream(raw, 64 * 1024)
        peek.mark(2)
        val gz = peek.read() == 0x1f && peek.read() == 0x8b
        peek.reset()
        val input: java.io.InputStream = if (gz) java.util.zip.GZIPInputStream(peek, 64 * 1024) else peek
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        input.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > limit) throw RuntimeException("подписка слишком большая (больше ${limit / 1024 / 1024} МБ) — это не похоже на подписку")
            }
        }
        return out.toString("UTF-8")
    }

    /** Что прислала панель, если серверов не нашлось — для журнала (без содержимого: там ключи). */
    private fun formatOf(body: String): String {
        val t = body.trimStart().take(200).lowercase()
        return when {
            t.startsWith("<") || t.contains("<html") -> "веб-страница (HTML)"
            t.startsWith("[") -> "JSON-массив (конфиги Xray)"
            t.startsWith("{") -> "JSON (без серверов sing-box)"
            body.contains("proxies:") -> "Clash YAML"
            t.isEmpty() -> "пусто"
            else -> "текст, не похожий на ссылки серверов"
        }
    }

    /** Подписка, вставленная текстом (ссылки серверов или base64), а не URL. */
    fun importText(ctx: Context, text: String): Int {
        if (text.length > MAX_BODY) throw RuntimeException("текст слишком большой — это не похоже на подписку")
        val parsed = parseAll(text)
        if (parsed.isEmpty()) throw RuntimeException("в тексте не нашлось ссылок серверов (vless://, trojan://, ss://…)")
        file(ctx).writeText(text)
        infoFile(ctx).delete()
        info = null
        setNodes(ctx, combined(ctx))
        return parsed.size
    }

    private fun hwid(ctx: Context): String {
        val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        p.getString("hwid", null)?.let { return it }
        val id = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        p.edit().putString("hwid", id).apply()
        return id
    }

    /** Сервер подписки пускает только с российских адресов, а запрос пришёл из другой страны. */
    class GeoBlocked(val country: String) : RuntimeException(
        "Сервер подписки пускает только с российских адресов, а запрос пришёл из $country — " +
            "скорее всего, через включённый VPN с зарубежным сервером.\n\n" +
            "Что сделать: выключи сторонний VPN или переключи его на российский сервер и обнови ещё раз. " +
            "Если в Fast VPN уже есть серверы — он сам скачает подписку через сервер с выходом в России."
    )

    /** Скачивает подписку. Бросает исключение с понятным текстом. */
    fun update(ctx: Context, url: String): Int {
        AppLog.sub("обновляю подписку с ${AppLog.host(url)} " + if (BoxVpnService.ports != null) "через VPN" else "напрямую")
        var lastProblem = "сервер подписки не ответил"
        for (ua in userAgents) {
            try {
                val c = Net.open(url.trim())
                c.setRequestProperty("User-Agent", ua)
                c.setRequestProperty("Accept", "*/*")
                // Без автоматической распаковки: сжатый ответ распознаём и распаковываем сами
                c.setRequestProperty("Accept-Encoding", "identity")
                c.setRequestProperty("x-hwid", hwid(ctx))
                c.setRequestProperty("x-device-os", "Android")
                c.setRequestProperty("x-ver-os", android.os.Build.VERSION.RELEASE)
                c.setRequestProperty("x-device-model", android.os.Build.MODEL)
                val code = c.responseCode
                AppLog.sub("ответ сервера подписки: HTTP $code")
                if (code != 200) {
                    // Панели обычно объясняют отказ текстом — показываем его
                    val why = try {
                        c.errorStream?.let { readLimited(it, 64 * 1024) }
                            ?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("\\s+"), " ")?.trim()?.take(160)
                    } catch (_: Exception) { null }
                    // Панель режет не российские адреса — смена User-Agent тут не поможет
                    val country = why?.let { Regex("country code:\\s*([A-Z]{2})").find(it)?.groupValues?.get(1) }
                    if (code == 403 && country != null && country != "RU") {
                        AppLog.sub("сервер подписки пускает только российские адреса, запрос пришёл из $country")
                        throw GeoBlocked(country)
                    }
                    lastProblem = "сервер подписки ответил HTTP $code" + (why?.takeIf { it.isNotEmpty() }?.let { " («$it»)" } ?: "")
                    continue
                }
                val body = readLimited(c.inputStream)
                val parsed = parseAll(body)
                AppLog.sub("получено ${body.length} байт (${ua.substringBefore('/')}), серверов распознано ${parsed.size}" +
                    if (parsed.isEmpty()) ", формат: ${formatOf(body)}" else "")
                if (parsed.isEmpty()) {
                    lastProblem = "панель отдала подписку в формате, который я не понял"
                    continue
                }
                val primary = isPrimary(url)
                (if (primary) file(ctx) else extraFile(ctx, url)).writeText(body)
                c.getHeaderField("subscription-userinfo")?.let {
                    (if (primary) infoFile(ctx) else extraInfoFile(ctx, url)).writeText(it)
                    if (primary) info = parseUserInfo(it)
                }
                setNodes(ctx, combined(ctx))
                return parsed.size
            } catch (e: GeoBlocked) {
                throw e
            } catch (e: Exception) {
                AppLog.err("подписка: попытка не удалась", e)
                lastProblem = e.message ?: lastProblem
            }
        }
        throw RuntimeException(
            "$lastProblem.\n\nЕсли там про устройства (device / HWID / limit) — в боте или кабинете VPN " +
                "удали старое устройство или увеличь лимит. Обходной путь: открой «Подписка» и вставь " +
                "вместо ссылки сами серверы (строки vless://, trojan://… или base64-блок)."
        )
    }

    private fun parseUserInfo(s: String): Info {
        val m = s.split(';').mapNotNull {
            val kv = it.trim().split('=', limit = 2)
            if (kv.size == 2) kv[0].trim() to (kv[1].trim().toLongOrNull() ?: 0L) else null
        }.toMap()
        return Info(m["upload"] ?: 0, m["download"] ?: 0, m["total"] ?: 0, m["expire"] ?: 0)
    }

    private fun b64(s: String): String {
        val clean = s.trim().replace("\n", "").replace("\r", "")
        for (flags in listOf(Base64.DEFAULT, Base64.URL_SAFE)) {
            try {
                return String(Base64.decode(clean, flags or Base64.NO_WRAP), Charsets.UTF_8)
            } catch (_: Exception) {
            }
        }
        throw IllegalArgumentException("not base64")
    }

    /** Формат sing-box (JSON): берём outbound'ы поддерживаемых типов как есть. */
    private fun parseSingBox(body: String): List<Node> {
        val arr = try { JSONObject(body).optJSONArray("outbounds") } catch (_: Exception) { null } ?: return emptyList()
        val out = ArrayList<Node>()
        val types = mapOf(
            "vless" to false, "trojan" to false, "vmess" to false,
            "shadowsocks" to true, "hysteria2" to true, "tuic" to true,
        )
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val type = o.optString("type")
            val udp = types[type] ?: continue
            val tag = "n${out.size}"
            val name = o.optString("tag").ifEmpty { tag }
            o.remove("multiplex") // mux для игры вреден
            o.remove("detour")
            o.put("tag", tag)
            out.add(Node(tag, name, if (type == "shadowsocks") "ss" else type, o.optString("server"), o.optInt("server_port"), udp, o))
        }
        return out
    }

    fun parseAll(body: String): List<Node> {
        if (body.trimStart().startsWith("{")) return parseSingBox(body)
        val text = if (body.contains("://")) body else try {
            b64(body)
        } catch (_: Exception) {
            body
        }
        val out = ArrayList<Node>()
        for (line in text.lines()) {
            val l = line.trim()
            if (l.isEmpty()) continue
            try {
                parse(l, "n${out.size}")?.let { out.add(it) }
            } catch (_: Exception) {
                // кривые строки пропускаем, остальные серверы важнее
            }
        }
        return out
    }

    private fun dec(s: String?): String = if (s == null) "" else URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

    private fun tls(q: (String) -> String?, server: String, defaultOn: Boolean): JSONObject? {
        val security = q("security") ?: if (defaultOn) "tls" else "none"
        if (security == "none" || security.isEmpty()) return null
        val t = JSONObject().put("enabled", true)
        t.put("server_name", q("sni")?.takeIf { it.isNotEmpty() } ?: q("peer") ?: server)
        if (q("allowInsecure") == "1" || q("insecure") == "1") t.put("insecure", true)
        q("alpn")?.takeIf { it.isNotEmpty() }?.let { t.put("alpn", JSONArray(it.split(','))) }
        val fp = q("fp")?.takeIf { it.isNotEmpty() }
        if (security == "reality") {
            t.put("reality", JSONObject().put("enabled", true)
                .put("public_key", q("pbk") ?: "")
                .put("short_id", q("sid") ?: ""))
            t.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp ?: "chrome"))
        } else if (fp != null) {
            t.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
        }
        return t
    }

    private fun transport(type: String?, q: (String) -> String?): JSONObject? = when (type) {
        "grpc" -> JSONObject().put("type", "grpc").put("service_name", q("serviceName") ?: "")
        "ws" -> JSONObject().put("type", "ws").put("path", q("path") ?: "/").apply {
            q("host")?.takeIf { it.isNotEmpty() }?.let { put("headers", JSONObject().put("Host", it)) }
        }
        "http", "h2" -> JSONObject().put("type", "http").put("path", q("path") ?: "/").apply {
            q("host")?.takeIf { it.isNotEmpty() }?.let { put("host", JSONArray(it.split(','))) }
        }
        "httpupgrade" -> JSONObject().put("type", "httpupgrade").put("path", q("path") ?: "/").apply {
            q("host")?.takeIf { it.isNotEmpty() }?.let { put("host", it) }
        }
        else -> null // tcp
    }

    private fun parse(link: String, tag: String): Node? {
        val scheme = link.substringBefore("://").lowercase()
        if (scheme == "vmess") return parseVmess(link, tag)
        if (scheme == "ss") return parseSs(link, tag)

        val uri = Uri.parse(link)
        // Транспорты только из xray (xhttp, kcp и т.п.) ядро sing-box не умеет — такие серверы
        // подключались бы как обычный TCP и не работали; пропускаем их
        val transportType = uri.getQueryParameter("type")?.lowercase()
        val name = dec(uri.fragment ?: uri.encodedFragment).ifEmpty { uri.host ?: tag }
        val server = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        // XHTTP — через второе ядро Xray; outbound sing-box для него подставится при запуске (SOCKS к Xray)
        if (transportType in XrayCore.TRANSPORTS && (scheme == "vless" || scheme == "trojan")) {
            val placeholder = JSONObject().put("tag", tag).put("type", "socks")
                .put("server", "127.0.0.1").put("server_port", 1).put("version", "5")
            return Node(tag, name, "xhttp", server, port, false, placeholder, xrayLink = link)
        }
        // Прочие транспорты только из xray (kcp и т.п.) не поддерживаем
        if (transportType != null && transportType !in setOf("tcp", "grpc", "ws", "http", "h2", "httpupgrade", "")) return null
        val user = dec(uri.encodedUserInfo)
        val q: (String) -> String? = { uri.getQueryParameter(it) }
        val o = JSONObject().put("tag", tag).put("server", server).put("server_port", port)

        return when (scheme) {
            "vless" -> {
                o.put("type", "vless").put("uuid", user)
                q("flow")?.takeIf { it.isNotEmpty() }?.let { o.put("flow", it) }
                tls(q, server, false)?.let { o.put("tls", it) }
                transport(q("type"), q)?.let { o.put("transport", it) }
                o.put("packet_encoding", "xudp")
                Node(tag, name, "vless", server, port, false, o)
            }
            "trojan" -> {
                o.put("type", "trojan").put("password", user)
                tls(q, server, true)?.let { o.put("tls", it) }
                transport(q("type"), q)?.let { o.put("transport", it) }
                Node(tag, name, "trojan", server, port, false, o)
            }
            "hysteria2", "hy2" -> {
                o.put("type", "hysteria2").put("password", user)
                val t = JSONObject().put("enabled", true).put("server_name", q("sni") ?: server)
                if (q("insecure") == "1") t.put("insecure", true)
                o.put("tls", t)
                q("obfs")?.takeIf { it.isNotEmpty() }?.let {
                    o.put("obfs", JSONObject().put("type", it).put("password", q("obfs-password") ?: ""))
                }
                Node(tag, name, "hysteria2", server, port, true, o)
            }
            "tuic" -> {
                o.put("type", "tuic").put("uuid", user.substringBefore(':')).put("password", user.substringAfter(':', ""))
                q("congestion_control")?.let { o.put("congestion_control", it) }
                val t = JSONObject().put("enabled", true).put("server_name", q("sni") ?: server)
                q("alpn")?.let { t.put("alpn", JSONArray(it.split(','))) }
                if (q("allow_insecure") == "1" || q("insecure") == "1") t.put("insecure", true)
                o.put("tls", t)
                Node(tag, name, "tuic", server, port, true, o)
            }
            else -> null
        }
    }

    private fun parseVmess(link: String, tag: String): Node? {
        val j = JSONObject(b64(link.substringAfter("://")))
        val server = j.optString("add").ifEmpty { return null }
        val port = j.optString("port").toIntOrNull() ?: 443
        val params = mapOf(
            "security" to (if (j.optString("tls") == "tls") "tls" else "none"),
            "sni" to j.optString("sni"), "fp" to j.optString("fp"), "alpn" to j.optString("alpn"),
            "serviceName" to j.optString("path"), "path" to j.optString("path"), "host" to j.optString("host"),
        )
        val q: (String) -> String? = { params[it]?.takeIf { v -> v.isNotEmpty() } }
        val o = JSONObject().put("tag", tag).put("type", "vmess").put("server", server).put("server_port", port)
            .put("uuid", j.optString("id")).put("alter_id", j.optString("aid").toIntOrNull() ?: 0)
            .put("security", j.optString("scy").ifEmpty { "auto" })
        tls(q, server, false)?.let { o.put("tls", it) }
        transport(j.optString("net"), q)?.let { o.put("transport", it) }
        o.put("packet_encoding", "xudp")
        return Node(tag, j.optString("ps").ifEmpty { server }, "vmess", server, port, false, o)
    }

    private fun parseSs(link: String, tag: String): Node? {
        val body = link.substringAfter("://")
        val name = dec(body.substringAfter('#', "")).ifEmpty { tag }
        val main = body.substringBefore('#').substringBefore('?')
        val userInfo: String
        val hostPort: String
        if (main.contains('@')) {
            val raw = main.substringBeforeLast('@')
            userInfo = try { b64(raw) } catch (_: Exception) { dec(raw) }
            hostPort = main.substringAfterLast('@')
        } else {
            val full = b64(main)
            userInfo = full.substringBeforeLast('@')
            hostPort = full.substringAfterLast('@')
        }
        val server = hostPort.substringBeforeLast(':').trim('[', ']')
        val port = hostPort.substringAfterLast(':').toIntOrNull() ?: return null
        val o = JSONObject().put("tag", tag).put("type", "shadowsocks").put("server", server).put("server_port", port)
            .put("method", userInfo.substringBefore(':')).put("password", userInfo.substringAfter(':'))
        return Node(tag, name, "ss", server, port, true, o)
    }
}
