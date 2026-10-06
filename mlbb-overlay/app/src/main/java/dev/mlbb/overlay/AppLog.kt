package dev.mlbb.overlay

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Полный журнал действий приложения — для «Сообщить о проблеме».
 *
 * Пишется в файл и переживает перезапуск: каждое нажатие, переключатели с новым значением,
 * переходы между экранами, подключение по шагам, замеры серверов, подписка, обновления,
 * автоподключение, смена сети, ошибки. Ссылок подписки, адресов серверов и паролей сюда
 * не пишем — только названия серверов.
 *
 * Категории в начале строки: UI (нажатия и экраны), SET (настройки), VPN, CONN (подключение),
 * SRV (замеры серверов), SUB (подписка), UPD (обновления), AUTO (автоподключение), NET (сеть),
 * TILE (плитка и виджет), ERR (ошибки).
 */
object AppLog {
    private const val FILE = "applog.txt"
    private const val MAX_BYTES = 1_500_000L
    private const val KEEP_BYTES = 1_000_000

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
        val line = "${time.format(Date())} [$cat] $msg"
        Log.i("FastVPN", line)
        val f = file ?: return
        try {
            f.appendText(line + "\n")
            if (f.length() > MAX_BYTES) trim(f)
        } catch (_: Exception) {
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
    fun err(msg: String, e: Throwable? = null) =
        add("ERR", msg + (e?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: ""))

    /** Оставить только последний ~1 МБ. */
    @Synchronized
    private fun trim(f: File) {
        try {
            if (!f.exists() || f.length() <= MAX_BYTES) return
            val bytes = f.readBytes()
            var start = bytes.size - KEEP_BYTES
            while (start < bytes.size && bytes[start] != '\n'.code.toByte()) start++
            f.writeBytes(bytes.copyOfRange((start + 1).coerceAtMost(bytes.size), bytes.size))
        } catch (_: Exception) {
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
