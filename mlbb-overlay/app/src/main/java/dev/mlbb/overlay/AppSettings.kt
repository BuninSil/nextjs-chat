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
        autoCheckUpdates = p.getBoolean("updAuto", true)
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
            .putBoolean("updAuto", autoCheckUpdates)
            .apply()
    }

    fun parseCountries(s: String): Set<String> =
        s.split(',', ' ', ';').map { it.trim().uppercase() }.filter { it.length == 2 }.toSet()
            .ifEmpty { setOf("RU") }
}
