package dev.mlbb.overlay

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Проверка и установка обновлений — общая для главного экрана и настроек. */
object UpdateFlow {
    @Volatile
    var busy = false
        private set

    fun check(a: Activity, manual: Boolean, status: (String) -> Unit) {
        if (busy) return
        busy = true
        if (manual) status("Проверяю обновления…")
        Thread {
            try {
                val rel = Updater.check()
                a.runOnUiThread {
                    status("")
                    if (rel == null) {
                        if (manual) Toast.makeText(a, "Версия ${BuildConfig.VERSION_NAME} — последняя", Toast.LENGTH_LONG).show()
                    } else if (!a.isFinishing) {
                        showDialog(a, rel, status)
                    }
                }
            } catch (e: Exception) {
                a.runOnUiThread {
                    status("")
                    if (manual) Toast.makeText(a, "Не удалось проверить: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                busy = false
            }
        }.start()
    }

    /** Окно обновления в стиле приложения: карточка, зелёная кнопка, тихое «Позже». */
    private fun showDialog(a: Activity, rel: Updater.Release, status: (String) -> Unit) {
        val d = { v: Float -> Ui.dp(a, v) }
        val dlg = Dialog(a)
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.CARD, d(22f).toFloat())
            setPadding(d(22f), d(22f), d(22f), d(16f))
        }
        card.addView(Ui.text(a, "⬆", 30f, Ui.GREEN))
        card.addView(Ui.text(a, "Доступно обновление", 20f, bold = true).apply { setPadding(0, d(6f), 0, 0) })
        card.addView(Ui.text(a, Ui.SLOGAN, 13f, Ui.GREEN).apply { setPadding(0, d(4f), 0, 0) })
        card.addView(Ui.text(a, "Версия ${rel.tag.removePrefix("mlbb-v")} · сейчас ${BuildConfig.VERSION_NAME}", 13f, Ui.MUTED)
            .apply { setPadding(0, d(4f), 0, d(12f)) })
        val notes = Ui.text(a, rel.notes.ifBlank { "Исправления и улучшения." }.take(1500), 14f, 0xFFC9CCD1.toInt())
        card.addView(ScrollView(a).apply { addView(notes) }, LinearLayout.LayoutParams(-1, -2).apply { height = -2 })
        card.addView(TextView(a).apply {
            text = "Обновить"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            background = Ui.rounded(0xFF2FB565.toInt(), d(14f).toFloat())
            setPadding(0, d(14f), 0, d(14f))
            setOnClickListener {
                dlg.dismiss()
                download(a, rel, status)
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(18f) })
        card.addView(TextView(a).apply {
            text = "Позже"
            gravity = Gravity.CENTER
            setTextColor(Ui.MUTED)
            textSize = 14f
            setPadding(0, d(12f), 0, d(4f))
            setOnClickListener { dlg.dismiss() }
        }, LinearLayout.LayoutParams(-1, -2))
        dlg.setContentView(card)
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dlg.window?.setLayout((a.resources.displayMetrics.widthPixels * 0.88).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.show()
    }

    /** Окно «Обновлено» после установки новой версии: что нового. */
    fun showUpdated(a: Activity, notes: String) {
        val d = { v: Float -> Ui.dp(a, v) }
        val dlg = Dialog(a)
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.CARD, d(22f).toFloat())
            setPadding(d(22f), d(22f), d(22f), d(16f))
        }
        card.addView(Ui.text(a, "✅", 30f))
        card.addView(Ui.text(a, "Обновлено до ${BuildConfig.VERSION_NAME}", 20f, bold = true).apply { setPadding(0, d(6f), 0, 0) })
        card.addView(Ui.text(a, Ui.SLOGAN, 13f, Ui.GREEN).apply { setPadding(0, d(4f), 0, d(12f)) })
        val text = Ui.text(a, notes.ifBlank { "Исправления и улучшения." }.take(1500), 14f, 0xFFC9CCD1.toInt())
        card.addView(ScrollView(a).apply { addView(text) }, LinearLayout.LayoutParams(-1, -2))
        card.addView(TextView(a).apply {
            this.text = "Отлично"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            background = Ui.rounded(0xFF2FB565.toInt(), d(14f).toFloat())
            setPadding(0, d(14f), 0, d(14f))
            setOnClickListener { dlg.dismiss() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(18f) })
        dlg.setContentView(card)
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dlg.window?.setLayout((a.resources.displayMetrics.widthPixels * 0.88).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.show()
    }

    private fun download(a: Activity, rel: Updater.Release, status: (String) -> Unit) {
        if (busy) return
        busy = true
        Thread {
            try {
                val apk = Updater.download(a.applicationContext, rel) { msg -> a.runOnUiThread { status(msg) } }
                AutoUpdate.rememberNotes(a.applicationContext, rel)
                if (!a.packageManager.canRequestPackageInstalls()) {
                    // Открывает экран разрешения установки
                    a.runOnUiThread {
                        status("")
                        Updater.install(a, apk)
                        Toast.makeText(a, "Разреши установку из этого приложения и проверь обновления ещё раз", Toast.LENGTH_LONG).show()
                    }
                } else {
                    a.runOnUiThread { status("Устанавливаю…") }
                    try {
                        // Через PackageInstaller: после первого раза обновления ставятся без вопросов
                        AutoUpdate.install(a.applicationContext, apk, interactive = true)
                    } catch (e: Exception) {
                        // Запасной путь — обычное окно установщика
                        a.runOnUiThread { Updater.install(a, apk) }
                    }
                    a.runOnUiThread { status("") }
                }
            } catch (e: Exception) {
                a.runOnUiThread { status("Ошибка обновления: ${e.message}") }
            } finally {
                busy = false
            }
        }.start()
    }
}
