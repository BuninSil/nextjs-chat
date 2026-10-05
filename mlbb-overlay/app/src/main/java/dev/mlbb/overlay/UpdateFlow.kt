package dev.mlbb.overlay

import android.app.Activity
import android.app.AlertDialog
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
                        AlertDialog.Builder(a)
                            .setTitle("Обновление ${rel.tag}")
                            .setMessage(rel.notes.ifBlank { "Новая версия доступна." }.take(1500))
                            .setPositiveButton("Скачать и установить") { _, _ -> download(a, rel, status) }
                            .setNegativeButton("Позже", null)
                            .show()
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

    private fun download(a: Activity, rel: Updater.Release, status: (String) -> Unit) {
        if (busy) return
        busy = true
        Thread {
            try {
                val apk = Updater.download(a.applicationContext, rel) { msg -> a.runOnUiThread { status(msg) } }
                a.runOnUiThread {
                    status("")
                    if (!Updater.install(a, apk)) {
                        Toast.makeText(a, "Разреши установку из этого приложения и проверь обновления ещё раз", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                a.runOnUiThread { status("Ошибка обновления: ${e.message}") }
            } finally {
                busy = false
            }
        }.start()
    }
}
