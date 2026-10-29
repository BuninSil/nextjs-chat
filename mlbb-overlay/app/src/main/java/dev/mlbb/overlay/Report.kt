package dev.mlbb.overlay

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Сообщить о проблеме»: собирает отчёт в файл и даёт отправить его разработчику любым
 * способом (Telegram, почта…). Ссылки подписки, адресов серверов и паролей в отчёте нет.
 */
object Report {
    fun build(ctx: Context): String {
        AppSettings.load(ctx)
        val sb = StringBuilder()
        fun line(k: String, v: Any?) = sb.append(k).append(": ").append(v).append('\n')
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        sb.append("=== Fast VPN — отчёт о проблеме ===\n")
        line("Время", now)
        line("Версия", "${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA.take(7)})")
        line("Телефон", "${Build.MANUFACTURER} ${Build.MODEL}")
        line("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        line("Сеть", ServerTester.netKey(ctx))

        sb.append("\n--- Настройки ---\n")
        line("Режим приложения", if (AppSettings.simple) "Fast VPN" else "Fast VPN + MLBB")
        line("Режим подключения", AppSettings.mode)
        line("Игровой режим", AppSettings.gameMode)
        line("Автовыбор", AppSettings.autoSelect)
        line("Только игра", AppSettings.onlyGame)
        line("Матч напрямую", AppSettings.battleDirect)
        line("Российские сайты напрямую", AppSettings.ruDirect)
        line("Блок рекламы", AppSettings.adBlock)
        line("Резерв (бесплатные серверы)", AppSettings.freeReserve)
        line("Режим совместимости (gVisor)", AppSettings.compatStack)
        // Частная DNS (DoT) в настройках Android может мешать VPN
        line("Частный DNS", try {
            val mode = android.provider.Settings.Global.getString(ctx.contentResolver, "private_dns_mode") ?: "—"
            val host = android.provider.Settings.Global.getString(ctx.contentResolver, "private_dns_specifier")
            mode + (host?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
        } catch (_: Exception) { "не прочитать" })
        line("Приложения", "режим ${AppSettings.appsMode}, выбрано ${AppSettings.appsList.size}")
        line("Автоподключение", "мобильный ${AppSettings.autoOnMobile}, выкл. на Wi-Fi ${AppSettings.autoOffWifi}")
        line("Ускорение Wi-Fi", AppSettings.wifiBoost)
        line("Тема", AppSettings.theme)
        line("Анимация", AppSettings.anim)

        sb.append("\n--- VPN ---\n")
        line("Подключён", BoxVpnService.isRunning)
        // Если явной ошибки запуска нет — берём последнюю ошибку из журнала (ядро пишет их в CORE)
        line("Последняя ошибка", BoxVpnService.lastError ?: CaptureVpnService.lastError ?: MonitorService.lastError
            ?: lastLogError() ?: "нет")
        line("Сеть сейчас", Autopilot.summary(ctx).ifBlank { ServerTester.netKey(ctx) })
        val nodes = Subscription.usable(ctx, includeHidden = true)
        line("Серверов в подписке", nodes.size)
        Subscription.info?.let { i ->
            line("Подписка", "использовано ${(i.upload + i.download) / 1_048_576} МБ из ${if (i.total > 0) "${i.total / 1_048_576} МБ" else "∞"}, " +
                "до ${if (i.expireSec > 0) SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(i.expireSec * 1000)) else "—"}")
        }
        Subscription.extras(ctx).takeIf { it.isNotEmpty() }?.let { ex ->
            line("Дополнительные подписки", ex.joinToString { "${AppLog.host(it)} (${Subscription.countFor(ctx, it)})" })
        }
        val sel = nodes.firstOrNull { it.tag == AppSettings.selectedTag }
        line("Выбранный сервер", sel?.let { "${Ui.cleanName(it)} (${it.type})" }
            ?: if (AppSettings.selectedTag == XrayCore.BYPASS) "обход блокировок без сервера" else "—")
        if (TelegramRoute.state.isNotEmpty()) line("Telegram", TelegramRoute.state)
        // Задержка через туннель, тип и страна выхода — без адресов и ключей. Показываем сколько есть (до 15).
        val measured = nodes.mapNotNull { n -> ServerTester.results[n.tag]?.let { n to it } }.sortedBy { it.second.score }
        sb.append("Замеры по задержке через туннель (${minOf(measured.size, 15)} из ${measured.size}):\n")
        measured.take(15).forEach { (n, r) ->
            sb.append("  ${Ui.cleanName(n)} · ${n.type} · ${r.medianMs} ms ±${r.jitterMs}" +
                (ServerTester.exitCountry[n.tag]?.let { " · выход $it" } ?: "") +
                (ServerTester.speed[n.tag]?.takeIf { it > 0 }?.let { String.format(Locale.US, " · %.0f Мбит/с", it) } ?: "") + "\n")
        }
        ServerTester.standby?.let { line("Запасной сервер", AppLog.name(ctx, it)) }
        if (ServerTester.lowTag != null) line("Сервер для звонков/игр", AppLog.name(ctx, ServerTester.lowTag!!))
        val dead = nodes.count { ServerTester.results.containsKey(it.tag) && ServerTester.results[it.tag] == null }
        line("Не ответили", dead)

        // Полный журнал: каждое нажатие, экраны, подключение по шагам, замеры, подписка, обновления,
        // автоподключение, сеть, строки VPN-ядра (CORE) и ошибки — с точным временем
        sb.append("\n--- Полный журнал действий (последний ~1 МБ) ---\n")
        sb.append("Категории: UI — нажатия и экраны, SET — настройки, VPN, CONN — подключение, SRV — замеры серверов, ")
        sb.append("SUB — подписка, UPD — обновления, AUTO — автоподключение, NET — сеть, TILE — плитка/виджет, ")
        sb.append("SPEED — тест скорости, CORE — VPN-ядро, ERR — ошибки\n\n")
        sb.append(collapse(redact(stripAnsi(AppLog.all()))).ifBlank { "(пусто)" }).append('\n')

        CrashReport.last(ctx)?.let {
            sb.append("\n--- Последнее падение ---\n").append(it).append('\n')
        }
        return sb.toString()
    }

    @Volatile private var building = false

    /** Копия для буфера: шапка + хвост журнала (~150 КБ), чтобы влезть в лимит Binder (~1 МБ) */
    private fun forClipboard(text: String): String {
        if (text.length <= 200_000) return text
        val head = text.substringBefore("\n--- Полный журнал")
        val tail = text.takeLast(150_000).let { it.substring(it.indexOf('\n') + 1) } // обрезаем по границе строки
        return head + "\n\n--- Полный журнал (только конец, ~150 КБ; весь — в файле) ---\n" + tail
    }

    /** Собрать отчёт и показать окно: отправить файлом, скопировать или закрыть. */
    fun show(a: Activity) {
        if (building) { android.widget.Toast.makeText(a, "Отчёт уже собирается…", android.widget.Toast.LENGTH_SHORT).show(); return }
        building = true
        AppLog.ui("нажал «Сообщить о проблеме» — собираю отчёт")
        val text = try { build(a) } catch (e: Exception) { "Не удалось собрать отчёт: ${e.message}" }
        building = false
        val dir = File(a.cacheDir, "reports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val file = File(dir, "fast-vpn-report-$stamp.txt").apply { writeText(text) }

        android.app.AlertDialog.Builder(a)
            .setTitle("Отчёт готов")
            .setMessage(
                "Отправь этот файл разработчику любым удобным способом — Telegram, почта, что угодно.\n\n" +
                    "В отчёте полный журнал: все нажатия, подключения, замеры серверов, ошибки — с точным временем. " +
                    "Ссылки подписки, адресов серверов и паролей в нём нет. В строках VPN-ядра могут встречаться " +
                    "адреса сайтов, которые открывались."
            )
            .setPositiveButton("Отправить") { _, _ ->
                val uri = FileProvider.getUriForFile(a, "${a.packageName}.files", file)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, "Fast VPN — отчёт о проблеме")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                a.startActivity(Intent.createChooser(send, "Отправить отчёт"))
            }
            .setNeutralButton("Скопировать") { _, _ ->
                // Весь лог (~1 МБ) не влезает в буфер обмена (лимит Binder ~1 МБ) — копируем шапку и хвост,
                // полный лог остаётся в файле. И никогда не падаем на этом.
                try {
                    a.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Fast VPN report", forClipboard(text)))
                    android.widget.Toast.makeText(a, "Скопирована шапка и конец лога — полный лог в файле, отправь его", android.widget.Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(a, "Не удалось скопировать (${e.message}) — отправь файл", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    /** Старые журналы (до исправления) могли содержать значение поля secret — вырезаем */
    private fun redact(log: String): String =
        log.replace(Regex("""(поле «secret» = ).*"""), "$1(скрыто)")

    /** Цветовые коды ядра (ESC[..m) в текстовом отчёте только мешают — убираем */
    private fun stripAnsi(s: String): String = s.replace(Regex("\u001B\\[[0-9;]*m"), "")

    /** Время в начале строки журнала («10-25 19:16:50.826 ») — чтобы сравнивать строки по смыслу */
    private val TS = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d\s+""")

    /**
     * Схлопывает подряд идущие одинаковые по смыслу строки (ядро плюётся одной ошибкой десятками
     * раз): остаётся первая с пометкой «×N», чтобы в журнале было видно суть, а не мусор.
     */
    private fun collapse(log: String): String {
        val lines = log.split('\n')
        val out = StringBuilder()
        var prevKey: String? = null
        var first = ""
        var count = 0
        fun flush() {
            if (count == 0) return
            out.append(first)
            if (count > 1) out.append("   ×$count")
            out.append('\n')
        }
        for (l in lines) {
            val key = l.replaceFirst(TS, "")
            if (key == prevKey) count++
            else { flush(); prevKey = key; first = l; count = 1 }
        }
        flush()
        return out.toString().trimEnd('\n')
    }

    /** Последняя строка журнала, похожая на ошибку — для шапки отчёта, когда явной ошибки запуска нет */
    private fun lastLogError(): String? =
        stripAnsi(AppLog.all()).split('\n').lastOrNull { l ->
            val u = l.uppercase()
            (u.contains("ERROR") || u.contains("[ERR]") || u.contains(" FAILED") || u.contains("ПАНИКА")) &&
                !u.contains("0 ERROR")
        }?.replaceFirst(TS, "")?.take(200)
}
