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
    ): String {
        val outbounds = JSONArray()
        val tags = JSONArray()
        for (n in nodes) {
            outbounds.put(JSONObject(n.outbound.toString()))
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
            .put("mtu", 1500) // без джамбо-кадров: меньше фрагментации на мобильной сети
            .put("auto_route", true)
            .put("strict_route", false)
            .put("stack", "mixed")
        // Иначе через VPN идёт всё, включая нас самих (обновления и база DB-IP под блокировками)
        if (onlyGame) tun.put("include_package", JSONArray().put(GAME).put(selfPackage))

        val mixed = JSONObject().put("type", "mixed").put("tag", "mixed-in")
            .put("listen", "127.0.0.1").put("listen_port", ports.mixed)

        val rules = JSONArray()
            .put(JSONObject().put("action", "sniff").put("timeout", "100ms"))
            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
            .put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
        if (battleDirect) {
            // Матч напрямую: UDP игры мимо туннеля (минимальный пинг, если оператор пускает)
            rules.put(
                JSONObject().put("type", "logical").put("mode", "and")
                    .put("rules", JSONArray()
                        .put(JSONObject().put("package_name", JSONArray().put(GAME)).put("network", JSONArray().put("udp")))
                        .put(JSONObject().put("port", JSONArray().put(53).put(123).put(443)).put("invert", true)))
                    .put("outbound", "direct")
            )
        }

        val dns = JSONObject()
            .put("servers", JSONArray()
                .put(JSONObject().put("tag", "remote").put("address", "tls://1.1.1.1").put("detour", "proxy"))
                .put(JSONObject().put("tag", "local").put("address", "local").put("detour", "direct")))
            .put("rules", JSONArray()
                .put(JSONObject().put("outbound", "any").put("server", "local")))
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
                .put("find_process", true))
            .put("experimental", JSONObject()
                .put("clash_api", JSONObject()
                    .put("external_controller", "127.0.0.1:${ports.api}")
                    .put("secret", ports.secret)))
            .toString()
    }
}
