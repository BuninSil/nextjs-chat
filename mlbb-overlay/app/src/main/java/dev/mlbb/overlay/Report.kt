package dev.mlbb.overlay

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
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
    private const val ENDPOINT = "http://vladisl140.temp.swtest.ru/api/report.php"
    private const val PREFS = "fastvpn_support"
    private const val CONSENT = "diagnostics_consent_v1"
    private const val MAX_DIAGNOSTICS = 5500

    /**
     * Собственное окно вместо системного AlertDialog: одинаково выглядит в старом
     * и новом интерфейсах и полностью берёт палитру из выбранной темы.
     */
    private fun styledDialog(
        a: Activity,
        title: String,
        message: String,
        positive: String,
        negative: String? = "Отмена",
        input: EditText? = null,
        onPositive: () -> Unit,
    ) {
        val dialog = android.app.Dialog(a)
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.surface(a, Ui.CARD, 22f)
            val p = Ui.dp(a, if (AppSettings.newDesign) 22f else 20f)
            setPadding(p, p, p, p)
        }
        root.addView(Ui.text(a, title, if (AppSettings.newDesign) 19f else 18f, Ui.TEXT, bold = true))
        root.addView(Ui.text(a, message, 13f, Ui.SUB).apply {
            setLineSpacing(Ui.dp(a, 3f).toFloat(), 1f)
            setPadding(0, Ui.dp(a, 9f), 0, if (input == null) Ui.dp(a, 18f) else Ui.dp(a, 12f))
        })
        input?.let { field ->
            field.apply {
                setTextColor(Ui.TEXT)
                setHintTextColor(Ui.HINT)
                textSize = 14f
                minLines = 3
                maxLines = 5
                setSingleLine(false)
                gravity = Gravity.TOP or Gravity.START
                setPadding(Ui.dp(a, 14f), Ui.dp(a, 12f), Ui.dp(a, 14f), Ui.dp(a, 12f))
                background = Ui.surface(a, Ui.INPUT, 14f)
            }
            root.addView(field, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = Ui.dp(a, 18f)
            })
        }

        val actions = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (negative != null) {
            actions.addView(Ui.secondaryBtn(a, negative) { dialog.dismiss() },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = Ui.dp(a, 8f) })
        }
        actions.addView(Ui.primaryBtn(a, positive) {
            dialog.dismiss()
            onPositive()
        }, LinearLayout.LayoutParams(0, -2, if (negative == null) 1f else 1.25f))
        root.addView(actions)

        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.58f }
        }
        dialog.show()
        dialog.window?.setLayout(
            (a.resources.displayMetrics.widthPixels - Ui.dp(a, 40f)).coerceAtLeast(Ui.dp(a, 280f)),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    fun show(a: Activity) {
        val prefs = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val message = "Fast VPN автоматически соберёт техническую диагностику: версию приложения, модель устройства, Android, состояние VPN и последние строки ошибок после очистки адресов и секретов.\n\n" +
            "В сам отчёт не включаются IP-адреса, ссылки подписок, ключи серверов и содержимое трафика. Хостинг технически видит адрес запроса и использует его хеш только для защиты от спама. Краткое описание можно добавить по желанию."
        if (!prefs.getBoolean(CONSENT, false)) {
            styledDialog(a, "Отправка диагностики", message, "Продолжить") {
                prefs.edit().putBoolean(CONSENT, true).apply()
                askDescription(a)
            }
        } else askDescription(a)
    }

    private fun askDescription(a: Activity) {
        val input = EditText(a).apply {
            hint = "Краткое описание (необязательно)"
        }
        styledDialog(
            a,
            "Сообщить о проблеме",
            "Диагностика соберётся автоматически. Опиши, что произошло, если есть детали.",
            "Отправить отчёт",
            input = input,
        ) {
            send(a, input.text?.toString()?.trim().orEmpty().take(500))
        }
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
        // Секретные строки исключаем целиком, а адреса и длинные токены дополнительно маскируем.
        val forbidden = Regex("(?i)(подписк|subscription|secret|password|token|private dns|частн(ый|ого) dns|vless://|vmess://|trojan://|ss://)")
        val ip = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
        val ipv6 = Regex("""(?i)\b(?:[0-9a-f]{1,4}:){2,}[0-9a-f:]{2,}\b""")
        val longToken = Regex("""[A-Za-z0-9_+/=-]{36,}""")
        val url = Regex("""(?i)https?://\S+""")
        val categories = listOf("[APP]", "[UI]", "[SET]", "[ERR]", "[VPN]", "[CONN]", "[CORE]",
            "[NET]", "[UPD]", "[AUTO]", "[SRV]", "[SUB]", "[TILE]")
        val selected = AppLog.all().lineSequence()
            .filter { line -> categories.any(line::contains) && !forbidden.containsMatchIn(line) }
            .toList()
            .takeLast(220)
            .map { line ->
                line.replace(url, "[адрес скрыт]")
                    .replace(ip, "[IP скрыт]")
                    .replace(ipv6, "[IPv6 скрыт]")
                    .replace(longToken, "[токен скрыт]")
                    .take(450)
            }
        val crash = CrashReport.last(ctx)?.let {
            "\nПоследнее падение:\n" + it
                .replace(url, "[адрес скрыт]")
                .replace(ip, "[IP скрыт]")
                .replace(ipv6, "[IPv6 скрыт]")
                .replace(longToken, "[токен скрыт]")
                .take(3200)
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
                    styledDialog(
                        a,
                        "Не удалось отправить",
                        "Отчёт не отправлен: $failure.\n\nПопробуй ещё раз, когда появится соединение.",
                        "Понятно",
                        negative = null,
                    ) {}                 .show()
                }
            }
        }.start()
    }
}
