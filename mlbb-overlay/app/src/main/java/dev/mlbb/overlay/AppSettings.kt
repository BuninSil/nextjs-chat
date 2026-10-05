package dev.mlbb.overlay

import android.content.Context

object AppSettings {
    /** Только игра через наш VPN, без стороннего VPN */
    const val MODE_DIRECT = 0
    /** Свой VPN не поднимаем: читаем соединения у VPN-клиента через его Clash API */
    const val MODE_API = 1
    /** Старый режим: наш VPN + SOCKS5 клиента */
    const val MODE_CHAIN = 2

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
        mode = p.getInt("mode", if (p.getBoolean("chain", false)) MODE_CHAIN else MODE_DIRECT)
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
