package dev.mlbb.overlay

import android.content.Context

/** Настройки автообновления. */
object AppSettings {
    @Volatile var updateRepo = BuildConfig.UPDATE_REPO
    @Volatile var updateToken = ""
    @Volatile var autoCheckUpdates = true

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = prefs(ctx)
        updateRepo = p.getString("updRepo", BuildConfig.UPDATE_REPO) ?: BuildConfig.UPDATE_REPO
        updateToken = p.getString("updToken", "") ?: ""
        autoCheckUpdates = p.getBoolean("updAuto", true)
    }

    fun save(ctx: Context) {
        prefs(ctx).edit()
            .putString("updRepo", updateRepo)
            .putString("updToken", updateToken)
            .putBoolean("updAuto", autoCheckUpdates)
            .apply()
    }
}
