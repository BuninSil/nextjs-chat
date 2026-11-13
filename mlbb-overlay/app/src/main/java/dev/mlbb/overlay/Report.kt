package dev.mlbb.overlay

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.widget.EditText
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Автоматическая отправка безопасной диагностики на собственный сервер Fast VPN.
 * В отчёт не попадают ссылки подписок, IP-адреса, ключи серверов и содержимое трафика.
 */
object Report {
    private const val ENDPOINT = "https://buninsil-fast-vpn.ru/api/report.php"
    private const val PREFS = "fastvpn_support"
    private const val CONSENT = "diagnostics_consent_v1"
    private const val MAX_DIAGNOSTICS = 3000

    fun show(a: Activity) {
        val prefs = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val message = "Fast VPN автоматически соберёт техническую диагностику: версию приложения, модель устройства, Android, состояние VPN и последние строки ошибок после очистки адресов и секретов.\n\n" +
            "В сам отчёт не включаются IP-адреса, ссылки подписок, ключи серверов и содержимое трафика. Хостинг технически видит адрес запроса и использует его хеш только для защиты от спама. Краткое описание можно добавить по желанию."
        if (!prefs.getBoolean(CONSENT, false)) {
            android.app.AlertDialog.Builder(a)
                .setTitle("Отправка диагностики")
                .setMessage(message)
                .setPositiveButton("Продолжить") { _, _ ->
                    prefs.edit().putBoolean(CONSENT, true).apply()
                    askDescription(a)
                }
                .setNegativeButton("Отмена", null)
                .show()
        } else askDescription(a)
    }

    private fun askDescription(a: Activity) {
        val input = EditText(a).apply {
            hint = "Краткое описание (необязательно)"
            minLines = 2
            maxLines = 4
            setSingleLine(false)
            setPadding(32, 20, 32, 20)
        }
        android.app.AlertDialog.Builder(a)
            .setTitle("Сообщить о проблеме")
            .setMessage("Диагностика соберётся автоматически.")
            .setView(input)
            .setPositiveButton("Отправить отчёт") { _, _ ->
                send(a, input.text?.toString()?.trim().orEmpty().take(500))
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun installId(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = p.getString("install_id", null)
        if (!saved.isNullOrBlank()) return saved
        val created = UUID.randomUUID().toString()
        p.edit().putString("install_id", created).apply()
        return created
    }

    private fun networkType(ctx: Context): String {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return "нет сети"
            val caps = cm.getNetworkCapabilities(network) ?: return "неизвестно"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная сеть"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "другая сеть"
            }
        } catch (_: Exception) { "неизвестно" }
    }

    private fun safeDiagnostics(ctx: Context): String {
        val forbidden = Regex("(?i)(подписк|subscription|secret|password|token|private dns|частн(ый|ого) dns|vless://|vmess://|trojan://|ss://|https?://)")
        val ip = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
        val ipv6 = Regex("""(?i)\b(?:[0-9a-f]{1,4}:){2,}[0-9a-f:]{2,}\b""")
        val longToken = Regex("""[A-Za-z0-9_+/=-]{36,}""")
        val selected = AppLog.all().lineSequence()
            .filter { line ->
                (line.contains("[ERR]") || line.contains("[VPN]") || line.contains("[CONN]") ||
                    line.contains("[CORE]") || line.contains("[NET]") || line.contains("[UPD]") ||
                    line.contains("[AUTO]")) && !forbidden.containsMatchIn(line)
            }
            .toList()
            .takeLast(100)
            .map { it.replace(ip, "[IP скрыт]").replace(ipv6, "[IPv6 скрыт]").replace(longToken, "[токен скрыт]").take(300) }
        val crash = CrashReport.last(ctx)?.let {
            "\nПоследнее падение:\n" + it
                .replace(ip, "[IP скрыт]")
                .replace(ipv6, "[IPv6 скрыт]")
                .replace(Regex("(?i)https?://\\S+"), "[адрес скрыт]")
                .replace(longToken, "[токен скрыт]")
                .take(1800)
        }.orEmpty()
        return (selected.joinToString("\n") + crash).takeLast(MAX_DIAGNOSTICS)
    }

    private fun payload(ctx: Context, description: String): JSONObject {
        val vpn = when {
            BoxVpnService.isRunning || CaptureVpnService.isRunning || MonitorService.isRunning -> "connected"
            BoxVpnService.isStarting -> "connecting"
            BoxVpnService.lastError != null || CaptureVpnService.lastError != null || MonitorService.lastError != null -> "error"
            else -> "off"
        }
        return JSONObject().apply {
            put("install_id", installId(ctx))
            put("version", BuildConfig.VERSION_NAME)
            put("version_code", BuildConfig.VERSION_CODE)
            put("manufacturer", Build.MANUFACTURER.take(80))
            put("model", Build.MODEL.take(100))
            put("android", Build.VERSION.RELEASE.take(30))
            put("sdk", Build.VERSION.SDK_INT)
            put("vpn_state", vpn)
            put("network", networkType(ctx))
            put("description", description)
            put("diagnostics", safeDiagnostics(ctx))
        }
    }

    private fun send(a: Activity, description: String) {
        Toast.makeText(a, "Собираю и отправляю диагностику…", Toast.LENGTH_SHORT).show()
        Thread {
            var ticket: String? = null
            var failure: String? = null
            var connection: HttpURLConnection? = null
            try {
                val body = payload(a.applicationContext, description).toString().toByteArray(Charsets.UTF_8)
                connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 10000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-FastVPN-Client", "android-v1")
                    setFixedLengthStreamingMode(body.size)
                }
                connection.outputStream.use { it.write(body) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IllegalStateException("сервер ответил HTTP $code")
                ticket = JSONObject(response).optString("ticket").takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("сервер не вернул номер обращения")
            } catch (e: Exception) {
                failure = when (e) {
                    is java.net.UnknownHostException -> "домен пока недоступен или нет интернета"
                    is java.net.SocketTimeoutException -> "сервер не ответил вовремя"
                    else -> e.message ?: "неизвестная ошибка"
                }
                AppLog.err("отправка отчёта: $failure")
            } finally {
                connection?.disconnect()
            }
            a.runOnUiThread {
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                if (ticket != null) {
                    Toast.makeText(a, "Отчёт отправлен · $ticket", Toast.LENGTH_LONG).show()
                    AppLog.ui("диагностический отчёт отправлен: $ticket")
                } else {
                    android.app.AlertDialog.Builder(a)
                        .setTitle("Не удалось отправить")
                        .setMessage("Отчёт не потерян намеренно, но отправка не состоялась: $failure.\n\nПопробуй ещё раз, когда появится соединение.")
                        .setPositiveButton("Понятно", null)
                        .show()
                }
            }
        }.start()
    }
}
