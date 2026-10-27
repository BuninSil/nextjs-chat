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
    /**
     * Что пробивает обход без сервера (блокировка по имени сайта): YouTube. Остальное заблокированное
     * (Telegram, Meta, X — по IP; ChatGPT, Spotify — сами не пускают Россию) обход не вытянет —
     * в гибридном режиме оно идёт через сервер подписки.
     */
    val BYPASS_PACKAGES = JSONArray(listOf(
        "com.google.android.youtube", "com.google.android.apps.youtube.music", "app.revanced.android.youtube",
        "app.rvx.android.youtube", "com.vanced.android.youtube",
    ))
    val BYPASS_DOMAINS = JSONArray(listOf(
        "youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "ggpht.com", "youtube-nocookie.com", "youtubei.googleapis.com",
    ))

    /** Приложения Telegram — отдельно: у них свой маршрут (см. [TelegramRoute]) */
    val TG_PACKAGES = JSONArray(listOf(
        "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta", "org.telegram.plus",
        "org.thunderdog.challegram", "tw.nekomimi.nekogram", "xyz.nextalone.nagram", "com.radolyn.ayugram",
        "uz.unnarsx.cherrygram", "com.exteragram.messenger", "ir.ilmili.telegraph",
    ))
    val TG_DOMAINS = JSONArray(listOf(
        "telegram.org", "telegram.me", "t.me", "telesco.pe", "tdesktop.com", "tg.dev", "telegra.ph", "graph.org",
    ))

    /** Адреса Telegram (IPv4): клиенты ходят на IP напрямую, без имени сайта */
    val TELEGRAM_CIDRS = JSONArray(listOf(
        "91.108.4.0/22", "91.108.8.0/22", "91.108.12.0/22", "91.108.16.0/22", "91.108.20.0/22", "91.108.56.0/22",
        "149.154.160.0/20", "185.76.151.0/24", "95.161.64.0/20",
    ))

    /** Адреса Telegram (IPv6): у части операторов открыты, даже когда IPv4 закрыт */
    val TELEGRAM_CIDRS6 = JSONArray(listOf(
        "2001:67c:4e8::/48", "2001:b28:f23c::/48", "2001:b28:f23d::/48", "2001:b28:f23f::/48", "2a0a:f280::/32",
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
        /** Порт Xray для обхода блокировок без сервера (null — нет Xray) */
        bypassPort: Int? = null,
        /** Сервер с самым низким пингом — для звонков и игр (UDP); null — как у [abroad] */
        lowPing: String? = null,
        /** Файл кэша ядра (DNS FakeIP между перезапусками); пусто — без кэша */
        cachePath: String = "",
    ): String {
        val outbounds = JSONArray()
        val tags = JSONArray()
        if (bypassPort != null) {
            // «Обход блокировок»: трафик идёт напрямую через Xray, который режет начало TLS-соединения
            outbounds.put(JSONObject().put("type", "socks").put("tag", XrayCore.BYPASS)
                .put("server", "127.0.0.1").put("server_port", bypassPort).put("version", "5"))
            tags.put(XrayCore.BYPASS)
        }
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
        // «probe» — только для собственных замеров приложения (вход mixed): проверка серверов
        // переключает его, а не «proxy», и трафик пользователя не скачет по проверяемым серверам
        outbounds.put(
            JSONObject().put("type", "selector").put("tag", "probe")
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
            // «tg» — куда идёт Telegram: заграничный сервер (по умолчанию) или напрямую, если в этой
            // сети его адреса открыты. Переключается на лету ([TelegramRoute]), без перезапуска ядра.
            val tgMembers = JSONArray()
            for (i in 0 until tags.length()) tgMembers.put(tags.getString(i))
            tgMembers.put("direct")
            outbounds.put(
                JSONObject().put("type", "selector").put("tag", "tg")
                    .put("outbounds", tgMembers).put("default", abroadTag)
                    .put("interrupt_exist_connections", false)
            )
        }
        // «lowping» — звонки, игры и прочий UDP: им важен пинг, а не скорость скачивания.
        // Только обычный режим с сервером (в обходе и игровом режиме — всё как было)
        val bypassMode = selected == XrayCore.BYPASS
        val lowTag = if (abroadTag == null || bypassMode) null
            else lowPing?.takeIf { l -> (0 until tags.length()).any { tags.getString(it) == l } } ?: abroadTag
        if (lowTag != null) {
            outbounds.put(
                JSONObject().put("type", "selector").put("tag", "lowping")
                    .put("outbounds", tags).put("default", lowTag)
                    .put("interrupt_exist_connections", false)
            )
        }
        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))

        val tun = JSONObject()
            .put("type", "tun").put("tag", "tun-in")
            // IPv6 тоже в туннель: иначе Android пускает его мимо VPN напрямую через провайдера
            // (YouTube со своим DNS, игры) — с российского адреса, и часть приложений не работает
            .put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
            // Обычный режим — 9000 (больше данных за пакет, выше скорость, как по умолчанию в sing-box);
            // игровой — 1500, без лишней буферизации для мелких пакетов игры
            .put("mtu", if (gameMode) 1500 else 9000)
            .put("auto_route", true)
            // Строгая маршрутизация: трафик не обходит туннель (где платформа это поддерживает)
            .put("strict_route", true)
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
            // Частный DNS Android (DoT, порт 853): он стучался на адрес самого туннеля и висел 5 с по
            // таймауту (direct туда тоже не достучится, да и был бы утечкой DNS мимо VPN). Быстрый отказ —
            // и Android в режиме «автоматически» (opportunistic) сразу откатывается на обычный DNS,
            // который мы перехватываем в туннеле.
            .put(JSONObject().put("port", 853).put("action", "reject"))
            // cloudflared/WARP (служебный канал на 7844) через WS-сервер рвётся каждые ~1.5 с — направляем напрямую
            .put(JSONObject().put("port", 7844).put("outbound", "direct"))
        if (abroadTag != null) {
            // Telegram — по своему маршруту «tg». Его адреса в IPv6 (если у оператора есть IPv6) —
            // сразу напрямую: это ставим до общего отклонения IPv6 ниже.
            rules.put(JSONObject().put("ip_cidr", TELEGRAM_CIDRS6).put("outbound", "direct"))
            rules.put(JSONObject().put("domain_suffix", TG_DOMAINS).put("outbound", "tg"))
            rules.put(JSONObject().put("ip_cidr", TELEGRAM_CIDRS).put("outbound", "tg"))
        }
        rules
            // IPv6 не проксируем (у многих серверов его нет): отказ — и приложение сразу идёт по IPv4
            .put(JSONObject().put("ip_version", 6).put("action", "reject"))
        if (selected == XrayCore.BYPASS) {
            // В обходе без сервера QUIC (UDP 443) не пробить — отклоняем, приложения сразу идут по TCP
            rules.put(JSONObject().put("network", "udp").put("port", 443).put("action", "reject"))
        }
        // Свои замеры приложения (скорость, «Мой IP», страна выхода) — через «probe»: это выбранный
        // сервер, а на время проверки — проверяемый
        rules.put(JSONObject().put("inbound", JSONArray().put("mixed-in")).put("outbound", "probe"))
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
        if (lowTag != null) {
            // QUIC (UDP 443) через сервер: у VLESS/Trojan UDP идёт хуже TCP — видео грузится
            // медленнее и рвётся. Отклоняем — браузер и YouTube сразу переходят на TCP.
            // Российские сайты напрямую не трогаем: им QUIC не мешает.
            val quic = JSONObject().put("network", "udp").put("port", 443)
            if (ruDirect) {
                rules.put(JSONObject().put("type", "logical").put("mode", "and")
                    .put("rules", JSONArray().put(quic)
                        .put(JSONObject().put("domain_suffix", RU_SUFFIXES).put("invert", true)))
                    .put("action", "reject"))
            } else {
                rules.put(JSONObject(quic.toString()).put("action", "reject"))
            }
            // Звонки, игры и прочий UDP (кроме DNS и времени) — через сервер с самым низким пингом
            rules.put(JSONObject().put("type", "logical").put("mode", "and")
                .put("rules", JSONArray().put(JSONObject().put("network", "udp"))
                    .put(JSONObject().put("port", JSONArray().put(53).put(123).put(443)).put("invert", true)))
                .put("outbound", "lowping"))
        }
        if (bypassMode && abroadTag != null) {
            // Гибрид: YouTube — через обход (бесплатно, без трафика подписки), остальное
            // заблокированное — через сервер ниже
            rules.put(JSONObject().put("package_name", BYPASS_PACKAGES).put("outbound", "proxy"))
            rules.put(JSONObject().put("domain_suffix", BYPASS_DOMAINS).put("outbound", "proxy"))
        }
        if (abroadTag != null) {
            // YouTube, Meta, X и другие заблокированные сервисы — через зарубежный выход
            // (Telegram разведён выше по маршруту «tg»)
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
                .apply {
                    // Реклама — отказ уже на DNS: приложение сразу понимает, что адреса нет, и не ждёт
                    if (adBlock && rulesPath.isNotEmpty()) put(JSONObject().put("rule_set", JSONArray().put("ads")).put("action", "reject"))
                }
                .put(JSONObject().put("query_type", JSONArray().put("A").put("AAAA")).put("server", "fakeip")))
            .put("fakeip", JSONObject().put("enabled", true).put("inet4_range", "198.18.0.0/15"))
            .put("independent_cache", true)
            .put("final", "remote")
            .put("strategy", "ipv4_only")

        return JSONObject()
            .put("log", JSONObject().put("level", "info"))
            .put("dns", dns)
            .put("inbounds", JSONArray().put(tun).put(mixed))
            .put("outbounds", outbounds)
            .put("route", JSONObject()
                .put("rules", rules)
                .put("rule_set", ruleSets)
                // Обычный режим: всё, кроме российского, — через зарубежный выход (abroad = выбранный, если он зарубежный)
                // Обход: всё прочее — через обход (это напрямую); иначе — через зарубежный выход
                .put("final", if (abroadTag != null && !bypassMode) "abroad" else "proxy")
                .put("auto_detect_interface", true)
                // Определение приложения по каждому соединению — только для игровых функций
                .put("find_process", gameMode))
            .put("experimental", JSONObject()
                .put("clash_api", JSONObject()
                    .put("external_controller", "127.0.0.1:${ports.api}")
                    .put("secret", ports.secret))
                .apply {
                    // Кэш: адреса FakeIP переживают переподключение — приложения, запомнившие
                    // адрес, не ждут, пока сами переспросят DNS (в дороге переподключений много).
                    // Выбор серверов ядро тоже запоминает — после запуска его ставим заново (BoxVpnService)
                    if (cachePath.isNotEmpty()) put("cache_file", JSONObject()
                        .put("enabled", true).put("path", cachePath).put("store_fakeip", true))
                })
            .toString()
    }
}
