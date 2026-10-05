package dev.mlbb.overlay

import android.app.Application
import android.content.Context
import java.io.File

/** Ловим падения, чтобы показать причину при следующем запуске. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
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
        return parts.joinToString("\n\n").ifBlank { null }
    }
}

/** Последние строки журнала VPN-ядра — для текста ошибок. */
object VpnLog {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(s: String) {
        lines.addLast(s.trim())
        while (lines.size > 200) lines.removeFirst()
    }

    @Synchronized
    fun tail(n: Int = 15): String = lines.toList().takeLast(n).joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
