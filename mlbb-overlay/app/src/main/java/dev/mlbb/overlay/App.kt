package dev.mlbb.overlay

import android.app.Application
import android.content.Context
import java.io.File

/** Ловим падения, чтобы показать причину при следующем запуске. */
class App : Application() {
    companion object {
        /** Контекст приложения — для фоновых мест без своего Context */
        lateinit var ctx: android.content.Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        ctx = applicationContext
        AppLog.init(this)
        // Переходы между экранами — в журнал
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private fun name(a: android.app.Activity) = when (a) {
                is MainActivity -> "Главный"
                is ServersActivity -> "Серверы"
                is SettingsActivity -> "Настройки"
                is AppsActivity -> "Приложения через VPN"
                is LogActivity -> "Лог соединений"
                else -> a.javaClass.simpleName
            }
            override fun onActivityResumed(a: android.app.Activity) = AppLog.ui("открыт экран «${name(a)}»")
            override fun onActivityPaused(a: android.app.Activity) = AppLog.ui("ушёл с экрана «${name(a)}»")
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                AppLog.err("ПАДЕНИЕ в потоке ${t.name}", e)
                AppLog.add("ERR", e.stackTraceToString().take(3000))
                File(filesDir, CrashReport.KOTLIN).writeText(
                    "Версия ${BuildConfig.VERSION_NAME}, поток ${t.name}\n" + e.stackTraceToString().take(6000)
                )
            } catch (_: Exception) {
            }
            prev?.uncaughtException(t, e)
        }
    }
}

object CrashReport {
    /** Последнее падение (для отчёта о проблеме). */
    fun last(ctx: Context): String? = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("lastCrash", null)

    const val KOTLIN = "crash.txt"
    const val GO = "core-stderr.log"

    /** Текст прошлого падения (приложения или VPN-ядра) или null. Файлы после чтения удаляются. */
    fun take(ctx: Context): String? {
        val parts = ArrayList<String>()
        File(ctx.filesDir, KOTLIN).takeIf { it.exists() }?.let {
            parts.add(it.readText()); it.delete()
        }
        File(ctx.filesDir, GO).takeIf { it.exists() && it.length() > 0 }?.let {
            val t = it.readText()
            // В stderr ядра пишутся только паники и фатальные ошибки
            if (t.contains("panic") || t.contains("fatal")) parts.add("Ядро VPN:\n" + t.takeLast(6000))
            it.delete()
        }
        val text = parts.joinToString("\n\n").ifBlank { null }
        // Копия для отчёта о проблеме — файлы падения после показа удаляются
        if (text != null) ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("lastCrash", text.take(8000)).apply()
        return text
    }
}

/** Последние строки журнала VPN-ядра — для текста ошибок. */
object VpnLog {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(s: String) {
        AppLog.add("CORE", s.trim())
        lines.addLast(s.trim())
        while (lines.size > 200) lines.removeFirst()
    }

    @Synchronized
    fun tail(n: Int = 15): String = lines.toList().takeLast(n).joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
