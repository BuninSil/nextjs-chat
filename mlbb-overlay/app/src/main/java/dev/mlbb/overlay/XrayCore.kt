package dev.mlbb.overlay

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Второе ядро — Xray — для серверов с транспортом XHTTP, которого нет в sing-box.
 * Запускается отдельной программой (libxray.so из jniLibs, собран CI из исходников Xray-core).
 * На каждый XHTTP-сервер — свой локальный SOCKS-вход; sing-box отдаёт туда трафик этого сервера.
 * Наше приложение исключено из туннеля, поэтому соединения Xray идут напрямую, без петли.
 */
object XrayCore {
    private const val TAG = "Xray"
    /** Транспорты, которые умеет только Xray */
    val TRANSPORTS = setOf("xhttp", "splithttp")

    private var process: Process? = null

    fun binary(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libxray.so")
    fun available(ctx: Context) = binary(ctx).exists()

    private fun dec(s: String?) = if (s == null) "" else URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

    /** Ссылка vless:// или trojan:// с XHTTP -> outbound Xray. */
    fun outbound(link: String, tag: String): JSONObject? {
        val uri = Uri.parse(link)
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val user = dec(uri.encodedUserInfo)
        val q: (String) -> String? = { uri.getQueryParameter(it)?.takeIf { v -> v.isNotEmpty() } }

        val out = JSONObject().put("tag", tag)
        when (scheme) {
            "vless" -> out.put("protocol", "vless").put("settings", JSONObject().put("vnext", JSONArray().put(
                JSONObject().put("address", host).put("port", port).put("users", JSONArray().put(
                    JSONObject().put("id", user).put("encryption", q("encryption") ?: "none").apply {
                        q("flow")?.let { put("flow", it) }
                    }
                ))
            )))
            "trojan" -> out.put("protocol", "trojan").put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", host).put("port", port).put("password", user)
            )))
            else -> return null
        }

        val security = q("security") ?: if (scheme == "trojan") "tls" else "none"
        val stream = JSONObject().put("network", "xhttp").put("security", security)
        val sni = q("sni") ?: q("peer") ?: host
        when (security) {
            "reality" -> stream.put("realitySettings", JSONObject()
                .put("serverName", sni)
                .put("fingerprint", q("fp") ?: "chrome")
                .put("publicKey", q("pbk") ?: "")
                .put("shortId", q("sid") ?: "")
                .put("spiderX", q("spx") ?: ""))
            "tls" -> stream.put("tlsSettings", JSONObject()
                .put("serverName", sni)
                .put("fingerprint", q("fp") ?: "chrome")
                .apply {
                    q("alpn")?.let { put("alpn", JSONArray(it.split(','))) }
                    if (q("allowInsecure") == "1" || q("insecure") == "1") put("allowInsecure", true)
                })
        }
        val xhttp = JSONObject().put("path", q("path") ?: "/").put("mode", q("mode") ?: "auto")
        q("host")?.let { xhttp.put("host", it) }
        q("extra")?.let { e -> try { xhttp.put("extra", JSONObject(e)) } catch (_: Exception) {} }
        stream.put("xhttpSettings", xhttp)
        // Адрес сервера — через DNS из конфига (см. [dns]): системного резолвера у Xray на Android нет
        // tcpKeepAliveInterval: WS/CDN рвут простаивающее соединение (у Телеги ровно каждые ~1m32s) —
        // периодический keepalive держит долгие коннекты живыми
        stream.put("sockopt", JSONObject().put("domainStrategy", "UseIPv4").put("tcpKeepAliveInterval", 15))
        out.put("streamSettings", stream)
        return out
    }

    /** Тег «сервера» обхода блокировок без VPN-сервера */
    const val BYPASS = "bypass"

    /**
     * Способы обхода (ByeDPI-подобные): Xray режет начало TLS-соединения на куски, и система
     * блокировок провайдера не узнаёт сайт. Разные провайдеры — разные способы, перебираем по очереди.
     * packets, length, interval
     */
    val BYPASS_PRESETS = listOf(
        Triple("tlshello", "100-200", "10-20"),
        Triple("1-3", "1-5", "1-2"),
        Triple("tlshello", "3-5", "5-10"),
        Triple("1-2", "10-20", "5-10"),
    )

    /**
     * DNS для Xray. Без него Xray на Android ищет адреса через [::1]:53, где ничего нет, — обход
     * блокировок не открывал ни одного сайта по имени. Сначала зашифрованный DNS (провайдер не
     * подменит ответ для заблокированных сайтов), запасной — DNS самой сети.
     */
    private fun dns(ctx: Context): JSONObject {
        val servers = JSONArray()
            .put("https+local://1.1.1.1/dns-query")
            .put("https+local://8.8.8.8/dns-query")
        try {
            val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
            // Ядро ещё не подняло VPN — сеть по умолчанию и есть настоящая
            val net = BoxVpnService.coreNetwork ?: cm.activeNetwork
            if (net != null) {
                cm.getLinkProperties(net)?.dnsServers?.filterIsInstance<java.net.Inet4Address>()
                    ?.forEach { servers.put(it.hostAddress) }
            }
        } catch (_: Exception) {}
        servers.put("77.88.8.8")
        return JSONObject().put("servers", servers).put("queryStrategy", "UseIPv4")
    }

    private var lastNodes: Map<String, Pair<String, Int>> = emptyMap()
    private var lastBypassPort: Int? = null

    /** Запускает Xray для XHTTP-серверов (tag -> ссылка, порт) и/или обхода блокировок (свой порт). */
    @Synchronized
    fun start(ctx: Context, nodes: Map<String, Pair<String, Int>>, bypassPort: Int? = null) {
        stop()
        lastNodes = nodes
        lastBypassPort = bypassPort
        if ((nodes.isEmpty() && bypassPort == null) || !available(ctx)) return
        val inbounds = JSONArray()
        val outbounds = JSONArray()
        val rules = JSONArray()
        val listenPorts = ArrayList<Int>()
        for ((tag, v) in nodes) {
            val ob = outbound(v.first, tag)
                ?: throw IllegalArgumentException("Xray: неподдерживаемая конфигурация сервера «$tag»")
            require(v.second in 1..65535) { "Xray: некорректный локальный порт для «$tag»" }
            outbounds.put(ob)
            inbounds.put(JSONObject().put("tag", "in-$tag").put("listen", "127.0.0.1").put("port", v.second)
                .put("protocol", "socks").put("settings", JSONObject().put("udp", true).put("auth", "noauth")))
            rules.put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put("in-$tag")).put("outboundTag", tag))
            listenPorts.add(v.second)
        }
        if (bypassPort != null) {
            // Параметры, найденные лабораторией обхода, — важнее заготовок
            val (packets, length, interval) = bypassParams
                ?: BYPASS_PRESETS[AppSettings.bypassPreset.coerceIn(0, BYPASS_PRESETS.size - 1)]
            require(bypassPort in 1..65535) { "Xray: некорректный локальный порт обхода" }
            inbounds.put(JSONObject().put("tag", "in-$BYPASS").put("listen", "127.0.0.1").put("port", bypassPort)
                .put("protocol", "socks").put("settings", JSONObject().put("udp", true).put("auth", "noauth")))
            listenPorts.add(bypassPort)
            outbounds.put(JSONObject().put("tag", BYPASS).put("protocol", "freedom")
                .put("settings", JSONObject()
                    .put("fragment", JSONObject().put("packets", packets).put("length", length).put("interval", interval)))
                .put("streamSettings", JSONObject().put("sockopt", JSONObject().put("domainStrategy", "UseIPv4"))))
            rules.put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put("in-$BYPASS")).put("outboundTag", BYPASS))
        }
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        val config = JSONObject()
            // access: "none" — не писать строки «accepted tcp … [in-nX -> nX]» на каждое соединение (шум)
            .put("log", JSONObject().put("loglevel", "warning").put("access", "none"))
            .put("dns", dns(ctx))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("routing", JSONObject().put("rules", rules))
        val file = File(ctx.filesDir, "xray.json").apply { writeText(config.toString()) }

        val p = ProcessBuilder(binary(ctx).path, "run", "-c", file.path)
            .directory(ctx.filesDir)
            .redirectErrorStream(true)
            .start()
        process = p
        Thread({
            try {
                p.inputStream.bufferedReader().forEachLine {
                    Log.i(TAG, it)
                    // Штатный шум не пишем в журнал отчёта: нормальное закрытие WS и fake-IP «не подключён»
                    if (!it.contains("ws closed: 1000") && !it.contains("ws closed: 1001") &&
                        !(it.contains("endpoint not connected") && it.contains("198.18."))
                    ) VpnLog.add("xray: $it")
                }
            } catch (_: Exception) {
            }
        }, "xray-log").apply { isDaemon = true; start() }

        // Не отдаём sing-box конфиг с SOCKS-портами, пока Xray действительно не начал слушать.
        // Иначе VPN формально поднимался, но XHTTP-серверы первые запросы теряли из-за гонки старта.
        val deadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) {
                stop()
                throw IllegalStateException("Xray завершился при запуске (код " + runCatching { p.exitValue() }.getOrDefault(-1) + ")")
            }
            val ready = listenPorts.all { port ->
                try {
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 250) }
                    true
                } catch (_: Exception) { false }
            }
            if (ready) return
            try { Thread.sleep(100) } catch (_: InterruptedException) {
                stop()
                Thread.currentThread().interrupt()
                throw IllegalStateException("Ожидание запуска Xray прервано")
            }
        }
        stop()
        throw IllegalStateException("Xray не открыл локальные SOCKS-порты за 8 секунд")
    }

    /** Сменить способ обхода на лету: перезапускаем только Xray, VPN не рвётся. */
    /** Свои параметры дробления (packets, length, interval); null — заготовка [AppSettings.bypassPreset] */
    @Volatile var bypassParams: Triple<String, String, String>? = null
        private set

    fun setBypassParams(ctx: Context, p: Triple<String, String, String>?) {
        bypassParams = p
        if (lastBypassPort != null) start(ctx, lastNodes, lastBypassPort)
    }

    /** Перед запуском VPN: способ, запомненный для этой сети, — без перезапуска Xray */
    fun presetBypassParams(p: Triple<String, String, String>?) {
        if (p != null) bypassParams = p
    }

    @Synchronized
    fun stop() {
        val old = process
        process = null
        if (old != null) {
            try {
                if (old.isAlive) {
                    old.destroy()
                    if (!old.waitFor(1200, TimeUnit.MILLISECONDS)) {
                        old.destroyForcibly()
                        old.waitFor(800, TimeUnit.MILLISECONDS)
                    }
                }
            } catch (_: InterruptedException) {
                old.destroyForcibly()
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                try { old.destroyForcibly() } catch (_: Exception) {}
            }
        }
    }
}
