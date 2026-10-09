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
        line("Игры напрямую", AppSettings.gamesDirect)
        line("Блок рекламы", AppSettings.adBlock)
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
        line("Последняя ошибка", BoxVpnService.lastError ?: CaptureVpnService.lastError ?: MonitorService.lastError ?: "нет")
        val nodes = Subscription.usable(ctx, includeHidden = true)
        line("Серверов в подписке", nodes.size)
        Subscription.info?.let { i ->
            line("Подписка", "использовано ${(i.upload + i.download) / 1_048_576} МБ из ${if (i.total > 0) "${i.total / 1_048_576} МБ" else "∞"}, " +
                "до ${if (i.expireSec > 0) SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(i.expireSec * 1000)) else "—"}")
        }
        val sel = nodes.firstOrNull { it.tag == AppSettings.selectedTag }
        line("Выбранный сервер", sel?.let { "${Ui.cleanName(it)} (${it.type})" } ?: "—")
        // Только названия, тип, пинг и страна выхода — без адресов и ключей
        sb.append("Замеры (лучшие 15):\n")
        nodes.mapNotNull { n -> ServerTester.results[n.tag]?.let { n to it } }
            .sortedBy { it.second.score }.take(15)
            .forEach { (n, r) ->
                sb.append("  ${Ui.cleanName(n)} · ${n.type} · ${r.medianMs} ms ±${r.jitterMs}" +
                    (ServerTester.exitCountry[n.tag]?.let { " · выход $it" } ?: "") +
                    (ServerTester.speed[n.tag]?.let { String.format(Locale.US, " · %.0f Мбит/с", it) } ?: "") + "\n")
            }
        val dead = nodes.count { ServerTester.results.containsKey(it.tag) && ServerTester.results[it.tag] == null }
        line("Не ответили", dead)

        // Полный журнал: каждое нажатие, экраны, подключение по шагам, замеры, подписка, обновления,
        // автоподключение, сеть, строки VPN-ядра (CORE) и ошибки — с точным временем
        sb.append("\n--- Полный журнал действий (последний ~1 МБ) ---\n")
        sb.append("Категории: UI — нажатия и экраны, SET — настройки, VPN, CONN — подключение, SRV — замеры серверов, ")
        sb.append("SUB — подписка, UPD — обновления, AUTO — автоподключение, NET — сеть, TILE — плитка/виджет, ")
        sb.append("SPEED — тест скорости, CORE — VPN-ядро, ERR — ошибки\n\n")
        sb.append(AppLog.all().ifBlank { "(пусто)" }).append('\n')

        CrashReport.last(ctx)?.let {
            sb.append("\n--- Последнее падение ---\n").append(it).append('\n')
        }
        return sb.toString()
    }

    /** Собрать отчёт и показать окно: отправить файлом, скопировать или закрыть. */
    fun show(a: Activity) {
        AppLog.ui("нажал «Сообщить о проблеме» — собираю отчёт")
        val text = try { build(a) } catch (e: Exception) { "Не удалось собрать отчёт: ${e.message}" }
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
                a.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Fast VPN report", text))
                android.widget.Toast.makeText(a, "Отчёт скопирован — вставь в сообщение", android.widget.Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }
}
