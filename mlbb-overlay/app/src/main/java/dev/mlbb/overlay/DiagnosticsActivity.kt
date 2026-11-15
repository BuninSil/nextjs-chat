package dev.mlbb.overlay

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileInputStream
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Локальная хронология и экспорт полного диагностического архива. */
class DiagnosticsActivity : AppCompatActivity() {
    private lateinit var summary: TextView
    private lateinit var history: TextView

    private val createArchive = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) export(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppSettings.load(this)
        setTheme(Ui.themeRes())
        super.onCreate(savedInstanceState)
        Ui.applyWindow(this)
        title = "Диагностика"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@DiagnosticsActivity, 16f), Ui.dp(this@DiagnosticsActivity, 12f),
                Ui.dp(this@DiagnosticsActivity, 16f), Ui.dp(this@DiagnosticsActivity, 16f))
            setBackgroundColor(Ui.BG)
        }
        summary = Ui.text(this, "", 13f, Ui.SUB)
        root.addView(summary)
        root.addView(Ui.space(this, 10f))
        root.addView(Ui.primaryBtn(this, "Отправить полную диагностику на сервер") {
            confirmUpload()
        })
        root.addView(Ui.space(this, 8f))
        root.addView(Ui.primaryBtn(this, "Экспорт полного архива · ZIP") {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            createArchive.launch("fastvpn-diagnostics-$stamp.zip")
        })
        root.addView(Ui.space(this, 8f))
        root.addView(Ui.secondaryBtn(this, "Обновить историю") { refresh() })
        root.addView(Ui.space(this, 12f))
        root.addView(Ui.text(this, "ПОСЛЕДНИЕ СОБЫТИЯ", 11f, Ui.MUTED, bold = true))
        val scroll = ScrollView(this)
        history = Ui.text(this, "", 12f, Ui.TEXT).apply {
            setPadding(Ui.dp(this@DiagnosticsActivity, 12f), Ui.dp(this@DiagnosticsActivity, 12f),
                Ui.dp(this@DiagnosticsActivity, 12f), Ui.dp(this@DiagnosticsActivity, 12f))
            background = Ui.surface(this@DiagnosticsActivity, Ui.CARD, 14f)
            setTextIsSelectable(true)
            setLineSpacing(Ui.dp(this@DiagnosticsActivity, 2f).toFloat(), 1f)
        }
        scroll.addView(history)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = Ui.dp(this@DiagnosticsActivity, 8f) })
        setContentView(root)
        refresh()
    }

    private fun confirmUpload() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Отправить полную диагностику?")
            .setMessage("Будут отправлены технические события за последние 7 дней и текстовый журнал приложения. Перед отправкой адреса, IP и длинные токены маскируются. Отправка выполняется только после этого подтверждения.")
            .setPositiveButton("Отправить") { _, _ -> DiagnosticsUploader.upload(this) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun refresh() {
        val events = HistoryStore.recent(500)
        summary.text = "Хранение: 7 дней · записей: ${HistoryStore.count()} · показаны последние ${events.size}"
        val fmt = java.text.SimpleDateFormat("dd.MM HH:mm:ss.SSS", Locale.getDefault())
        history.text = if (events.isEmpty()) "История пока пуста." else events.joinToString("\n\n") {
            "${fmt.format(Date(it.timeMs))}  [${it.category}]\n${it.message}"
        }
    }

    private fun export(uri: Uri) {
        Thread {
            var error: String? = null
            try {
                HistoryStore.checkpoint()
                val db = HistoryStore.databaseFile(this)
                val log = File(filesDir, "applog.txt")
                contentResolver.openOutputStream(uri)?.use { raw ->
                    ZipOutputStream(raw).use { zip ->
                        fun addFile(file: File, name: String) {
                            if (!file.exists()) return
                            zip.putNextEntry(ZipEntry(name))
                            FileInputStream(file).use { input -> input.copyTo(zip) }
                            zip.closeEntry()
                        }
                        addFile(db, "diagnostics.db")
                        addFile(log, "applog.txt")
                        zip.putNextEntry(ZipEntry("README.txt"))
                        zip.write(("Fast VPN diagnostic archive\n" +
                            "SQLite history: last 7 days, automatically size-limited.\n" +
                            "Text log: rotated local application log.\n" +
                            "Contains technical events and error details. Review before sharing.\n").toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                } ?: throw IllegalStateException("Не удалось открыть файл для записи")
            } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
            runOnUiThread {
                Toast.makeText(this, if (error == null) "Диагностический архив сохранён" else "Ошибка экспорта: $error",
                    Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}
