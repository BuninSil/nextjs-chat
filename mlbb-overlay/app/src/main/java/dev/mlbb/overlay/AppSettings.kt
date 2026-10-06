package dev.mlbb.overlay

import android.content.Context

object AppSettings {
    /** Только игра через наш VPN, без стороннего VPN */
    const val MODE_DIRECT = 0
    /** Свой VPN не поднимаем: читаем соединения у VPN-клиента через его Clash API */
    const val MODE_API = 1
    /** Старый режим: наш VPN + SOCKS5 клиента */
    const val MODE_CHAIN = 2
    /** Встроенный VPN-клиент по подписке (основной режим) */
    const val MODE_BOX = 3

    @Volatile var subUrl = ""
    @Volatile var selectedTag = ""
    @Volatile var autoSelect = true
    /** Через VPN только игра (весь канал — ей) */
    @Volatile var onlyGame = false
    /** Матч (UDP игры) напрямую, мимо туннеля */
    @Volatile var battleDirect = false
    /** Режим низкой задержки Wi-Fi во время игры */
    @Volatile var wifiBoost = true
    /** ИГРАТЬ сразу запускает MLBB; если выключено — кнопка просто подключает VPN */
    @Volatile var autoLaunch = true
    /** Игровой режим: оверлей, лог, приоритет серверов для MLBB, игровой Wi-Fi */
    @Volatile var gameMode = true

    /**
     * Для чего приложение: "game" — для Mobile Legends (оверлей, игровой режим, лог боёв),
     * "simple" — Fast VPN без игры, всё про игру скрыто; "" — ещё не выбрано (спросим при запуске).
     */
    @Volatile var profile = ""
    val simple get() = profile == PROFILE_SIMPLE
    const val PROFILE_GAME = "game"
    const val PROFILE_SIMPLE = "simple"

    @Volatile var mode = MODE_DIRECT
    val chainEnabled get() = mode == MODE_CHAIN

    @Volatile var apiPort = 0
    @Volatile var apiSecret = ""
    @Volatile var socksHost = "127.0.0.1"
    @Volatile var socksPort = 10808
    @Volatile var socksUser = ""
    @Volatile var socksPass = ""
    @Volatile var clientPackage = ""

    /** Вибро-предупреждение, если боевой сервер не из разрешённых стран. */
    @Volatile var alertEnabled = false
    @Volatile var allowedCountries: Set<String> = setOf("RU")

    @Volatile var autoCheckUpdates = true

    /** Российские сайты (банки, Госуслуги, маркетплейсы…) — напрямую, мимо VPN */
    @Volatile var ruDirect = true
    /** Блокировать рекламу и трекеры */
    @Volatile var adBlock = false

    /** Какие приложения через VPN: все / только выбранные / все, кроме выбранных */
    const val APPS_ALL = 0
    const val APPS_ONLY = 1
    const val APPS_EXCEPT = 2
    @Volatile var appsMode = APPS_ALL
    @Volatile var appsList: Set<String> = emptySet()

    /** Автоподключение: включать VPN на мобильном интернете / выключать на Wi-Fi */
    @Volatile var autoOnMobile = false
    @Volatile var autoOffWifi = true

    /** Тема оформления: dark (по умолчанию), light, neon, amoled */
    @Volatile var theme = "dark"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = prefs(ctx)
        mode = p.getInt("mode", MODE_BOX)
        subUrl = p.getString("subUrl", "") ?: ""
        selectedTag = p.getString("selectedTag", "") ?: ""
        autoSelect = p.getBoolean("autoSelect", true)
        onlyGame = p.getBoolean("onlyGame", false)
        battleDirect = p.getBoolean("battleDirect", false)
        wifiBoost = p.getBoolean("wifiBoost", true)
        autoLaunch = p.getBoolean("autoLaunch", true)
        gameMode = p.getBoolean("gameMode", true)
        // Режим цепочки убран из интерфейса: он включал наш VPN и выбивал VPN пользователя
        if (mode == MODE_CHAIN) mode = MODE_API
        // Версия 2.0: основной режим — встроенный VPN
        if (!p.getBoolean("v2", false)) {
            mode = MODE_BOX
            p.edit().putBoolean("v2", true).putInt("mode", MODE_BOX).apply()
        }
        apiPort = p.getInt("apiPort", 0)
        apiSecret = p.getString("apiSecret", "") ?: ""
        socksHost = p.getString("socksHost", "127.0.0.1") ?: "127.0.0.1"
        socksPort = p.getInt("socksPort", 10808)
        socksUser = p.getString("socksUser", "") ?: ""
        socksPass = p.getString("socksPass", "") ?: ""
        clientPackage = p.getString("clientPkg", "") ?: ""
        alertEnabled = p.getBoolean("alert", false)
        allowedCountries = parseCountries(p.getString("allowed", "RU") ?: "RU")
        // Автообновления теперь бесшовные — включаем всем (новый ключ, старый «проверять при запуске» не учитываем)
        autoCheckUpdates = p.getBoolean("updAuto2", true)
        theme = p.getString("theme", "dark") ?: "dark"
        ruDirect = p.getBoolean("ruDirect", true)
        autoOnMobile = p.getBoolean("autoOnMobile", false)
        autoOffWifi = p.getBoolean("autoOffWifi", true)
        appsMode = p.getInt("appsMode", APPS_ALL)
        appsList = p.getStringSet("appsList", emptySet())?.toSet() ?: emptySet()
        adBlock = p.getBoolean("adBlock", false)
        // Кто ставил приложение до появления выбора — уже пользуется им для игры
        profile = p.getString("profile", null) ?: if (p.contains("gameMode") || subUrl.isNotEmpty()) PROFILE_GAME else ""
        if (simple) applySimple()
    }

    /** Простой режим: только VPN, никаких игровых функций. */
    private fun applySimple() {
        mode = MODE_BOX
        gameMode = false
        onlyGame = false
        battleDirect = false
        alertEnabled = false
    }

    fun setProfile(ctx: Context, value: String) {
        profile = value
        if (simple) applySimple() else gameMode = true
        save(ctx)
    }

    fun save(ctx: Context) {
        prefs(ctx).edit()
            .putInt("mode", mode)
            .putString("subUrl", subUrl)
            .putString("selectedTag", selectedTag)
            .putBoolean("autoSelect", autoSelect)
            .putBoolean("onlyGame", onlyGame)
            .putBoolean("battleDirect", battleDirect)
            .putBoolean("wifiBoost", wifiBoost)
            .putBoolean("autoLaunch", autoLaunch)
            .putBoolean("gameMode", gameMode)
            .putInt("apiPort", apiPort)
            .putString("apiSecret", apiSecret)
            .putString("socksHost", socksHost)
            .putInt("socksPort", socksPort)
            .putString("socksUser", socksUser)
            .putString("socksPass", socksPass)
            .putString("clientPkg", clientPackage)
            .putBoolean("alert", alertEnabled)
            .putString("allowed", allowedCountries.joinToString(","))
            .putBoolean("updAuto2", autoCheckUpdates)
            .putString("profile", profile)
            .putString("theme", theme)
            .putBoolean("ruDirect", ruDirect)
            .putBoolean("autoOnMobile", autoOnMobile)
            .putBoolean("autoOffWifi", autoOffWifi)
            .putInt("appsMode", appsMode)
            .putStringSet("appsList", HashSet(appsList))
            .putBoolean("adBlock", adBlock)
            .apply()
    }

    fun parseCountries(s: String): Set<String> =
        s.split(',', ' ', ';').map { it.trim().uppercase() }.filter { it.length == 2 }.toSet()
            .ifEmpty { setOf("RU") }
}
