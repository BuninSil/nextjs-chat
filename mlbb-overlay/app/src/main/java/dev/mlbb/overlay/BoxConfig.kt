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

    /** Российские зоны и сервисы с не-.ru доменами, которые точно должны идти напрямую. */
    private val RU_SUFFIXES = JSONArray(listOf(
        "ru", "su", "xn--p1ai", "xn--80adxhks", "moscow", "xn--d1acj3b",
        // Банки и платежи
        "sberbank.com", "sber.ru", "tbank.ru", "tinkoff.ru", "vtb.ru", "alfabank.ru", "gazprombank.ru", "nspk.ru",
        "mironline.ru", "qiwi.com", "yoomoney.ru",
        // Госуслуги и госсервисы
        "gosuslugi.ru", "mos.ru", "nalog.gov.ru", "pfr.gov.ru",
        // Яндекс, VK, Mail.ru
        "yandex.net", "yandex.com", "yastatic.net", "ya.ru", "vk.com", "vk.me", "vkuser.net", "userapi.com",
        "vk-cdn.net", "vkuseraudio.net", "vkuservideo.net", "mycdn.me", "ok.ru", "mail.ru", "imgsmail.ru",
        // Маркетплейсы и сервисы
        "ozon.ru", "ozone.ru", "wildberries.ru", "wb.ru", "wbbasket.ru", "avito.ru", "avito.st", "kinopoisk.ru",
        "rutube.ru", "dzen.ru", "2gis.com", "kaspersky.com",
    ))

    /**
     * Сервисы, заблокированные в России: через сервер с выходом в России они не работают.
     * Их трафик всегда идёт через группу «abroad» — выбранный сервер, если он зарубежный,
     * иначе лучший зарубежный (переключает [ServerTester.applyAbroad]).
     */
    val BLOCKED_PACKAGES = JSONArray(listOf(
        // Telegram и клиенты
        "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta", "org.telegram.plus",
        "org.thunderdog.challegram", "tw.nekomimi.nekogram", "xyz.nextalone.nagram", "com.radolyn.ayugram",
        "uz.unnarsx.cherrygram", "com.exteragram.messenger", "ir.ilmili.telegraph",
        // YouTube
        "com.google.android.youtube", "com.google.android.apps.youtube.music", "app.revanced.android.youtube",
        "app.rvx.android.youtube", "com.vanced.android.youtube",
        // Meta, X, Discord и др.
        "com.instagram.android", "com.instagram.barcelona", "com.facebook.katana", "com.facebook.orca", "com.whatsapp",
        "com.twitter.android", "com.discord", "com.openai.chatgpt", "com.spotify.music", "com.linkedin.android",
        "com.viber.voip", "com.snapchat.android", "com.patreon.android", "tv.twitch.android.app",
    ))
    val BLOCKED_DOMAINS = JSONArray(listOf(
        "telegram.org", "telegram.me", "t.me", "telesco.pe", "tdesktop.com", "tg.dev", "telegra.ph", "graph.org",
        "youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "ggpht.com", "youtube-nocookie.com", "youtubei.googleapis.com",
        "instagram.com", "cdninstagram.com", "facebook.com", "fbcdn.net", "fb.com", "messenger.com", "threads.net",
        "whatsapp.com", "whatsapp.net", "x.com", "twitter.com", "twimg.com", "t.co",
        "discord.com", "discord.gg", "discordapp.com", "discordapp.net", "discord.media",
        "openai.com", "chatgpt.com", "oaistatic.com", "oaiusercontent.com", "spotify.com", "scdn.co",
        "linkedin.com", "licdn.com", "viber.com", "twitch.tv", "ttvnw.net", "patreon.com", "medium.com", "soundcloud.com",
    ))

    /** Папка с наборами правил: копируются из APK при первом запуске и после обновлений. */
    fun rulesDir(ctx: android.content.Context): String {
        val dir = java.io.File(ctx.filesDir, "rules").apply { mkdirs() }
        val stamp = java.io.File(dir, "version")
        val version = BuildConfig.VERSION_CODE.toString()
        if (stamp.takeIf { it.exists() }?.readText() != version) {
            val am = ctx.assets
            for (name in am.list("rules") ?: emptyArray()) {
                am.open("rules/$name").use { input -> java.io.File(dir, name).outputStream().use { input.copyTo(it) } }
            }
            stamp.writeText(version)
        }
        return dir.path
    }

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
        /** Российские сайты напрямую */
        ruDirect: Boolean = false,
        /** Блокировать рекламу и трекеры */
        adBlock: Boolean = false,
        /** Папка с наборами правил (.srs), см. [rulesDir] */
        rulesPath: String = "",
        /** Выбор приложений: AppSettings.APPS_* и список пакетов */
        appsMode: Int = AppSettings.APPS_ALL,
        appsList: Set<String> = emptySet(),
        /** Режим совместимости — стек gVisor */
        compatStack: Boolean = false,
        /** Сервер для заблокированных в России сервисов (см. [BLOCKED_DOMAINS]); null — без группы */
        abroad: String? = null,
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
        val abroadTag = abroad?.takeIf { a -> (0 until tags.length()).any { tags.getString(it) == a } }
        if (abroadTag != null) {
            outbounds.put(
                JSONObject().put("type", "selector").put("tag", "abroad")
                    .put("outbounds", tags).put("default", abroadTag)
                    .put("interrupt_exist_connections", false)
            )
        }
        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))

        val tun = JSONObject()
            .put("type", "tun").put("tag", "tun-in")
            .put("address", JSONArray().put("172.19.0.1/30"))
            // Обычный режим — 9000 (больше данных за пакет, выше скорость, как по умолчанию в sing-box);
            // игровой — 1500, без лишней буферизации для мелких пакетов игры
            .put("mtu", if (gameMode) 1500 else 9000)
            .put("auto_route", true)
            .put("strict_route", false)
            // mixed: TCP — системным стеком (быстрее), UDP — gVisor; gvisor — всё через gVisor (совместимость)
            .put("stack", if (compatStack) "gvisor" else "mixed")
        // Иначе через VPN идёт всё, включая нас самих (обновления и база DB-IP под блокировками)
        // Наше приложение — мимо туннеля: соединения ядра Xray (отдельный процесс) иначе ушли бы
        // обратно в VPN. Свои замеры через VPN приложение делает явно, через SOCKS-вход.
        val apps = appsList.filter { it != selfPackage }
        when {
            gameMode && onlyGame -> tun.put("include_package", JSONArray().put(GAME))
            // Через VPN — только выбранные приложения
            appsMode == AppSettings.APPS_ONLY && apps.isNotEmpty() -> tun.put("include_package", JSONArray(apps))
            // Все, кроме выбранных (и нас самих)
            appsMode == AppSettings.APPS_EXCEPT -> tun.put("exclude_package", JSONArray(apps + selfPackage))
            else -> tun.put("exclude_package", JSONArray().put(selfPackage))
        }

        val mixed = JSONObject().put("type", "mixed").put("tag", "mixed-in")
            .put("listen", "127.0.0.1").put("listen_port", ports.mixed)

        val rules = JSONArray()
            .put(JSONObject().put("action", "sniff").put("timeout", "100ms"))
            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
            .put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
        if (abroadTag != null) {
            // Свои замеры приложения (скорость, «Мой IP», страна выхода) — строго через выбранный сервер
            rules.put(JSONObject().put("inbound", JSONArray().put("mixed-in")).put("outbound", "proxy"))
        }
        val ruleSets = JSONArray()
        fun ruleSet(tag: String, file: String) {
            ruleSets.put(JSONObject().put("type", "local").put("tag", tag).put("format", "binary")
                .put("path", "$rulesPath/$file"))
        }
        if (adBlock && rulesPath.isNotEmpty()) {
            // Реклама и трекеры — соединение отклоняется
            ruleSet("ads", "geosite-category-ads-all.srs")
            rules.put(JSONObject().put("rule_set", JSONArray().put("ads")).put("action", "reject"))
        }
        if (abroadTag != null) {
            // Telegram, YouTube и другие заблокированные сервисы — всегда через зарубежный выход
            rules.put(JSONObject().put("package_name", BLOCKED_PACKAGES).put("outbound", "abroad"))
            rules.put(JSONObject().put("domain_suffix", BLOCKED_DOMAINS).put("outbound", "abroad"))
        }
        if (abroadTag != null && !ruDirect) {
            // Российские сайты без «напрямую» — через выбранный сервер (остальное идёт через зарубежный)
            rules.put(JSONObject().put("domain_suffix", RU_SUFFIXES).put("outbound", "proxy"))
            if (rulesPath.isNotEmpty()) {
                ruleSet("ru", "geosite-category-ru.srs")
                rules.put(JSONObject().put("rule_set", JSONArray().put("ru")).put("outbound", "proxy"))
            }
        }
        if (ruDirect) {
            // Российские сайты напрямую: банки и Госуслуги не пускают с зарубежных адресов, а
            // маркетплейсы и видео быстрее без лишнего круга. Только по доменам — российские IP
            // (в том числе серверы игр) не трогаем, они идут как раньше.
            rules.put(JSONObject().put("domain_suffix", RU_SUFFIXES).put("outbound", "direct"))
            if (rulesPath.isNotEmpty()) {
                ruleSet("ru", "geosite-category-ru.srs")
                rules.put(JSONObject().put("rule_set", JSONArray().put("ru")).put("outbound", "direct"))
            }
        }
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
                .put("rule_set", ruleSets)
                // Обычный режим: всё, кроме российского, — через зарубежный выход (abroad = выбранный, если он зарубежный)
                .put("final", if (abroadTag != null) "abroad" else "proxy")
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
