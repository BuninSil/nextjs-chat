package dev.mlbb.overlay

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Подробный журнал Fast VPN. Хранится между перезапусками, с ограниченной ротацией,
 * чтобы длительное логирование не разрасталось бесконечно и не забивало память телефона.
 */
object AppLog {
    private const val FILE = "applog.txt"
    private const val MAX_BYTES = 20L * 1024 * 1024
    private const val KEEP_BYTES = 16 * 1024 * 1024
    private const val MAX_STACK_LINES = 40

    @Volatile private var file: File? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** Вызывается при старте приложения. */
    fun init(ctx: Context) {
        val f = File(ctx.filesDir, FILE)
        file = f
        trim(f)
        add("APP", "запуск · версия ${BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE} · " +
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    }

    @Synchronized
    fun add(cat: String, msg: String) {
        val f = file ?: return
        try {
            // Каждая строка получает собственную метку времени и категорию, включая stack trace.
            msg.lineSequence().forEach { part ->
                val line = "${time.format(Date())} [$cat] $part"
                Log.i("FastVPN", line)
                f.appendText(line + "\n")
            }
            if (f.length() > MAX_BYTES) trim(f)
        } catch (e: Exception) {
            Log.e("FastVPN", "Не удалось записать журнал", e)
        }
    }

    fun ui(msg: String) = add("UI", msg)
    fun set(name: String, value: Any?) = add("SET", "$name = $value")
    fun vpn(msg: String) = add("VPN", msg)
    fun conn(msg: String) = add("CONN", msg)
    fun srv(msg: String) = add("SRV", msg)
    fun sub(msg: String) = add("SUB", msg)
    fun upd(msg: String) = add("UPD", msg)
    fun auto(msg: String) = add("AUTO", msg)
    fun net(msg: String) = add("NET", msg)

    /** Ошибка записывается вместе с ограниченным стеком, а не только названием исключения. */
    fun err(msg: String, e: Throwable? = null) {
        add("ERR", msg + (e?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
        if (e != null) {
            e.stackTraceToString().lineSequence().take(MAX_STACK_LINES).forEach { add("ERR", it) }
            var cause = e.cause
            var depth = 0
            while (cause != null && cause !== e && depth < 3) {
                add("ERR", "Причина: ${cause.javaClass.simpleName}: ${cause.message}")
                cause.stackTraceToString().lineSequence().take(12).forEach { add("ERR", it) }
                cause = cause.cause
                depth++
            }
        }
    }

    /** Оставить последние KEEP_BYTES байт, начиная с границы строки. */
    @Synchronized
    private fun trim(f: File) {
        try {
            if (!f.exists() || f.length() <= MAX_BYTES) return
            val bytes = f.readBytes()
            var start = (bytes.size - KEEP_BYTES).coerceAtLeast(0)
            while (start < bytes.size && bytes[start] != '\n'.code.toByte()) start++
            f.writeBytes(bytes.copyOfRange((start + 1).coerceAtMost(bytes.size), bytes.size))
        } catch (e: Exception) {
            Log.e("FastVPN", "Не удалось ротировать журнал", e)
        }
    }

    /** Весь журнал текстом (для отчёта). */
    @Synchronized
    fun all(): String = try { file?.takeIf { it.exists() }?.readText() ?: "" } catch (_: Exception) { "" }

    /** Название сервера без адреса — для журнала. */
    fun name(ctx: Context, tag: String): String =
        Subscription.usable(ctx, includeHidden = true).firstOrNull { it.tag == tag }?.let { Ui.cleanName(it) } ?: tag

    /** Хост ссылки без пути и токенов. */
    fun host(url: String): String = try { java.net.URI(url).host ?: "?" } catch (_: Exception) { "?" }
}
