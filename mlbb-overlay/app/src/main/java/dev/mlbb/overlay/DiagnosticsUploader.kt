package dev.mlbb.overlay

import android.app.Activity
import android.content.Context
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Ручная отправка полной локальной диагностики частями на собственный сервер. */
object DiagnosticsUploader {
    // Тот же временный хостинг, который использует существующая отправка обращений.
    private const val ENDPOINT = "http://vladisl140.temp.swtest.ru/api/logs.php"
    private const val CLIENT_KEY = "android-v1"
    private const val CHUNK_CHARS = 60_000
    private const val MAX_PARTS = 200
    private const val MAX_TOTAL_CHARS = CHUNK_CHARS * MAX_PARTS

    private val forbiddenLine = Regex(
        "(?i)(subscription|подписк|password|парол|secret|token|authorization|cookie|private.?key|vless://|vmess://|trojan://|ss://)"
    )
    private val ipv4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    private val ipv6 = Regex("""(?i)\b(?:[0-9a-f]{1,4}:){2,}[0-9a-f:]{2,}\b""")
    private val urls = Regex("""(?i)https?://\S+""")
    private val longToken = Regex("""[A-Za-z0-9_+/=-]{36,}""")

    fun upload(activity: Activity) {
        Toast.makeText(activity, "Собираю полную диагностику…", Toast.LENGTH_SHORT).show()
        Thread {
            var error: String? = null
            var sentParts = 0
            try {
                val ctx = activity.applicationContext
                val text = collect(ctx)
                require(text.isNotBlank()) { "История диагностики пока пуста" }
                val chunks = split(text)
                require(chunks.size <= MAX_PARTS) { "Архив слишком большой для одной отправки" }
                val prefs = ctx.getSharedPreferences("fastvpn_support", Context.MODE_PRIVATE)
                var installId = prefs.getString("install_id", null)
                if (installId.isNullOrBlank()) {
                    installId = UUID.randomUUID().toString()
                    prefs.edit().putString("install_id", installId).apply()
                }
                val batchId = UUID.randomUUID().toString()
                chunks.forEachIndexed { index, chunk ->
                    sendPart(
                        installId!!, batchId, index + 1, chunks.size, chunk,
                        BuildConfig.VERSION_NAME, Build.MODEL.take(100), Build.VERSION.RELEASE.take(30)
                    )
                    sentParts = index + 1
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                AppLog.err("отправка полной диагностики: $error")
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (error == null) {
                    Toast.makeText(activity, "Полная диагностика отправлена · частей: $sentParts", Toast.LENGTH_LONG).show()
                    AppLog.ui("полная диагностика отправлена, частей: $sentParts")
                } else {
                    Toast.makeText(activity, "Не удалось отправить: $error", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun collect(ctx: Context): String {
        val history = HistoryStore.exportText()
        val textLog = AppLog.all()
        val combined = buildString {
            append("Fast VPN full diagnostics\\n")
            append("App: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\\n")
            append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\\n')
            append("Android: ").append(Build.VERSION.RELEASE).append(" / SDK ").append(Build.VERSION.SDK_INT).append("\\n")
            append("History window: last 7 days\\n\\n=== TIMELINE ===\\n")
            append(history)
            append("\\n=== APP LOG ===\\n")
            append(textLog)
        }
        val safe = combined.lineSequence().mapNotNull { line ->
            if (forbiddenLine.containsMatchIn(line)) null
            else line.replace(urls, "[URL скрыт]")
                .replace(ipv4, "[IP скрыт]")
                .replace(ipv6, "[IPv6 скрыт]")
                .replace(longToken, "[длинный токен скрыт]")
        }.joinToString("\\n")
        // Сервер принимает максимум 200 частей. Оставляем самый свежий хвост.
        return safe.takeLast(MAX_TOTAL_CHARS)
    }

    private fun split(value: String): List<String> {
        val result = ArrayList<String>()
        var offset = 0
        while (offset < value.length) {
            var end = (offset + CHUNK_CHARS).coerceAtMost(value.length)
            if (end < value.length && end > offset && Character.isHighSurrogate(value[end - 1])) end--
            result.add(value.substring(offset, end))
            offset = end
        }
        return result
    }

    private fun sendPart(
        installId: String, batchId: String, part: Int, parts: Int, log: String,
        version: String, model: String, android: String
    ) {
        val payload = JSONObject().apply {
            put("install_id", installId)
            put("batch_id", batchId)
            put("part", part)
            put("parts", parts)
            put("version", version)
            put("model", model)
            put("android", android)
            put("log", log)
        }.toString().toByteArray(Charsets.UTF_8)

        var lastError: Exception? = null
        repeat(2) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 25_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-FastVPN-Client", CLIENT_KEY)
                    setFixedLengthStreamingMode(payload.size)
                }
                connection.outputStream.use { it.write(payload) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IllegalStateException("HTTP $code: " + response.take(160))
                if (!JSONObject(response).optBoolean("ok", false)) {
                    throw IllegalStateException("сервер не подтвердил часть $part")
                }
                return
            } catch (e: Exception) {
                lastError = e
            } finally {
                connection?.disconnect()
            }
        }
        throw IllegalStateException("часть $part из $parts: ${lastError?.message ?: "ошибка сети"}")
    }
}
