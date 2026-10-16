package dev.mlbb.overlay

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Окна управления подписками — общие для главного экрана и «Серверов». */
object SubsUi {
    private fun alive(a: Activity) = !a.isFinishing && !a.isDestroyed

    /** Название подписки в списках: хост ссылки или «Свои серверы» для вставленных вручную */
    fun title(url: String) = if (url.startsWith("http")) AppLog.host(url) else "Свои серверы"

    /** «серверов: 142 · до 18.10.2026 · 3.2 из 50 ГБ» */
    fun summary(ctx: Context, url: String, count: Int? = null): String {
        val parts = arrayListOf("серверов: ${count ?: Subscription.countFor(ctx, url)}")
        Subscription.infoFor(ctx, url)?.let { info ->
            if (info.expireSec > 0) parts += "до " + java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.US)
                .format(java.util.Date(info.expireSec * 1000))
            val used = (info.download + info.upload) / 1_073_741_824.0
            parts += if (info.total > 0) String.format(java.util.Locale.US, "%.1f из %.0f ГБ", used, info.total / 1_073_741_824.0)
            else String.format(java.util.Locale.US, "%.1f ГБ", used)
        }
        return parts.joinToString(" · ")
    }

    private fun applyHint() = if (BoxVpnService.isRunning) ". Переподключи VPN, чтобы применить." else ""

    /** Долгая операция с окном прогресса; итог — всплывающим сообщением или окном ошибки. */
    private fun background(a: Activity, title: String, work: () -> String, onDone: () -> Unit) {
        val progress = AlertDialog.Builder(a).setTitle(title).setMessage("Секунду…").setCancelable(false).show()
        Thread {
            val (ok, msg) = try { true to work() } catch (e: Exception) { false to (e.message ?: "не получилось") }
            a.runOnUiThread {
                if (!alive(a)) {
                    Toast.makeText(a.applicationContext, msg.lineSequence().first(), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                try { if (progress.isShowing) progress.dismiss() } catch (_: Exception) {}
                if (ok) Toast.makeText(a, msg, Toast.LENGTH_LONG).show()
                else AlertDialog.Builder(a).setTitle(title).setMessage(msg).setPositiveButton("Понятно", null).show()
                onDone()
            }
        }.start()
    }

    fun update(a: Activity, url: String, onDone: () -> Unit) {
        AppLog.ui("обновить подписку ${title(url)}")
        if (!url.startsWith("http")) {
            Toast.makeText(a, "Это серверы, вставленные вручную, — обновлять нечего", Toast.LENGTH_LONG).show()
            return
        }
        background(a, "Обновляю ${title(url)}", {
            val n = Subscription.update(a.applicationContext, url)
            "Готово: серверов $n" + applyHint()
        }, onDone)
    }

    fun add(a: Activity, url: String, onDone: () -> Unit) {
        if (!url.startsWith("http")) {
            Toast.makeText(a, "Нужна ссылка подписки (https://…)", Toast.LENGTH_LONG).show()
            return
        }
        background(a, "Добавляю подписку", {
            val n = Subscription.add(a.applicationContext, url)
            "Подписка добавлена: серверов $n" + applyHint()
        }, onDone)
    }

    fun confirmRemove(a: Activity, url: String, onDone: () -> Unit) {
        AlertDialog.Builder(a)
            .setTitle("Удалить подписку?")
            .setMessage("${title(url)} — её серверы пропадут из списка. Ссылку можно будет добавить снова.")
            .setPositiveButton("Удалить") { _, _ ->
                background(a, "Удаляю подписку", {
                    Subscription.remove(a.applicationContext, url)
                    "Подписка удалена" + applyHint()
                }, onDone)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Окно «Добавить подписку»; onUrl получает введённую ссылку. */
    fun showAdd(a: Activity, onUrl: (String) -> Unit) {
        val d = { v: Float -> Ui.dp(a, v) }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(20f), d(8f), d(20f), 0)
        }
        box.addView(TextView(a).apply {
            text = "Серверы новой подписки добавятся к уже имеющимся. Автовыбор будет выбирать из всех."
        })
        val input = EditText(a).apply { hint = "https://…" }
        box.addView(input)
        box.addView(TextView(a).apply {
            text = Ui.iconText(a, R.drawable.ic_paste, "Вставить из буфера", 18f)
            setPadding(0, d(10f), 0, d(10f))
            setTextColor(Ui.GREEN)
            setOnClickListener {
                val clip = a.getSystemService(ClipboardManager::class.java).primaryClip
                val t = clip?.getItemAt(0)?.coerceToText(a)?.toString()?.trim()
                if (t.isNullOrEmpty()) Toast.makeText(a, "В буфере пусто", Toast.LENGTH_SHORT).show() else input.setText(t)
            }
        })
        val dlg = AlertDialog.Builder(a)
            .setTitle("Добавить подписку")
            .setView(box)
            .setPositiveButton("Добавить") { _, _ -> onUrl(input.text.toString().trim()) }
            .setNegativeButton("Отмена", null)
            .create()
        // Ссылка с токеном — не в скриншоты
        dlg.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dlg.show()
    }
}
