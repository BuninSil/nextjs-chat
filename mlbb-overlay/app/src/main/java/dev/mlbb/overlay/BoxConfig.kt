package dev.mlbb.overlay

import org.json.JSONArray
import org.json.JSONObject

/**
 * Конфиг sing-box под игру:
 *  - без mux (мультиплексирование даёт задержки при потерях);
 *  - DNS через прокси, игровые IP без подмены;
 *  - сниффинг только для маршрутизации, без переписывания адресов;
 *  - выбор сервера через selector «proxy», переключаем его сами по замерам;
 *  - Clash API на 127.0.0.1 — для оверлея (кто куда подключён) и замеров пинга;
 *  - mixed-вход на 127.0.0.1 — для замера пинга до сервера игры через туннель.
 */
object BoxConfig {
    const val GAME = CaptureVpnService.GAME_PACKAGE

    data class Ports(val api: Int, val secret: String, val mixed: Int)

    fun build(
        nodes: List<Subscription.Node>,
        selected: String,
        ports: Ports,
        selfPackage: String,
        onlyGame: Boolean,
        battleDirect: Boolean,
        /** Игровой режим: плюшки для MLBB. Без него — просто быстрый VPN на всё. */
        gameMode: Boolean,
        /** tag XHTTP-сервера -> локальный SOCKS-порт Xray */
        xrayPorts: Map<String, Int> = emptyMap(),
    ): String {
        val outbounds = JSONArray()
        val tags = JSONArray()
        for (n in nodes) {
            val o = JSONObject(n.outbound.toString())
            if (n.xrayLink != null) {
                val port = xrayPorts[n.tag] ?: continue // Xray недоступен — сервер пропускаем
                o.put("server_port", port)
            }
            outbounds.put(o)
            tags.put(n.tag)
        }
        outbounds.put(
            JSONObject().put("type", "selector").put("tag", "proxy")
                .put("outbounds", tags).put("default", selected)
                .put("interrupt_exist_connections", false)
        )
        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))

        val tun = JSONObject()
            .put("type", "tun").put("tag", "tun-in")
            .put("address", JSONArray().put("172.19.0.1/30"))
            // Обычный режим — 9000 (больше данных за пакет, выше скорость, как по умолчанию в sing-box);
            // игровой — 1500, без лишней буферизации для мелких пакетов игры
            .put("mtu", if (gameMode) 1500 else 9000)
            .put("auto_route", true)
            .put("strict_route", false)
            .put("stack", "mixed")
        // Иначе через VPN идёт всё, включая нас самих (обновления и база DB-IP под блокировками)
        // Наше приложение — мимо туннеля: соединения ядра Xray (отдельный процесс) иначе ушли бы
        // обратно в VPN. Свои замеры через VPN приложение делает явно, через SOCKS-вход.
        if (gameMode && onlyGame) tun.put("include_package", JSONArray().put(GAME))
        else tun.put("exclude_package", JSONArray().put(selfPackage))

        val mixed = JSONObject().put("type", "mixed").put("tag", "mixed-in")
            .put("listen", "127.0.0.1").put("listen_port", ports.mixed)

        val rules = JSONArray()
            .put(JSONObject().put("action", "sniff").put("timeout", "100ms"))
            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
            .put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
        if (gameMode && battleDirect) {
            // Матч напрямую: UDP игры мимо туннеля (минимальный пинг, если оператор пускает)
            rules.put(
                JSONObject().put("type", "logical").put("mode", "and")
                    .put("rules", JSONArray()
                        .put(JSONObject().put("package_name", JSONArray().put(GAME)).put("network", JSONArray().put("udp")))
                        .put(JSONObject().put("port", JSONArray().put(53).put(123).put(443)).put("invert", true)))
                    .put("outbound", "direct")
            )
        }

        // FakeIP: приложения сразу получают адрес, домен разрешает VPN-сервер — без задержек DNS.
        // Адреса самих VPN-серверов резолвим через DNS сети (local).
        val dns = JSONObject()
            .put("servers", JSONArray()
                .put(JSONObject().put("tag", "remote").put("address", "https://1.1.1.1/dns-query").put("detour", "proxy"))
                .put(JSONObject().put("tag", "local").put("address", "local").put("detour", "direct"))
                .put(JSONObject().put("tag", "fakeip").put("address", "fakeip")))
            .put("rules", JSONArray()
                .put(JSONObject().put("outbound", "any").put("server", "local"))
                .put(JSONObject().put("query_type", JSONArray().put("A").put("AAAA")).put("server", "fakeip")))
            .put("fakeip", JSONObject().put("enabled", true).put("inet4_range", "198.18.0.0/15"))
            .put("independent_cache", true)
            .put("final", "remote")
            .put("strategy", "ipv4_only")

        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("dns", dns)
            .put("inbounds", JSONArray().put(tun).put(mixed))
            .put("outbounds", outbounds)
            .put("route", JSONObject()
                .put("rules", rules)
                .put("final", "proxy")
                .put("auto_detect_interface", true)
                // Определение приложения по каждому соединению — только для игровых функций
                .put("find_process", gameMode))
            .put("experimental", JSONObject()
                .put("clash_api", JSONObject()
                    .put("external_controller", "127.0.0.1:${ports.api}")
                    .put("secret", ports.secret)))
            .toString()
    }
}
