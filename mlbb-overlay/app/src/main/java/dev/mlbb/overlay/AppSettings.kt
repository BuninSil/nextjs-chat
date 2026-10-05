package dev.mlbb.overlay

import android.content.Context

object AppSettings {
    /** Режим VPN: только игра напрямую, или всё через сторонний VPN-клиент (SOCKS5). */
    @Volatile var chainEnabled = false
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
        chainEnabled = p.getBoolean("chain", false)
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
            .putBoolean("chain", chainEnabled)
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
