package dev.mlbb.overlay

import java.io.File
import kotlin.system.exitProcess

/**
 * Работает в отдельном процессе, запущенном Shizuku от имени shell (uid 2000):
 * ему таблица сокетов /proc/net доступна, обычному приложению на Android 10+ — нет.
 * Отдаёт только четыре файла и ничего больше не умеет.
 */
class ProcReaderService : IProcReader.Stub() {
    private val allowed = setOf("udp", "udp6", "tcp", "tcp6")

    override fun readProcNet(name: String): String {
        if (name !in allowed) return ""
        return try {
            File("/proc/net/$name").readText()
        } catch (e: Exception) {
            "ERROR ${e.message}"
        }
    }

    override fun destroy() {
        exitProcess(0)
    }
}
