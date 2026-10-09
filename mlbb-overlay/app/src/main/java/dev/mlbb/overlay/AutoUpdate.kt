package dev.mlbb.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Обновления в фоне: раз в несколько часов проверяет релизы, сама скачивает новую версию
 * и присылает уведомление «Обновление скачано — нажми, чтобы установить». Ставит обычный
 * установщик Android — тихую установку убрали: на части телефонов (Xiaomi, окно Play Защиты)
 * она отклонялась.
 */
object AutoUpdate {
    private const val TAG = "AutoUpdate"
    private const val JOB_ID = 4201
    private const val CHANNEL = "updates"
    private const val PERIOD_MS = 6 * 60 * 60 * 1000L

    const val NOTIF_READY = 41
    const val NOTIF_DONE = 42
    const val NOTIF_SUB = 43

    /** Открыт ли сейчас главный экран — тогда фоновая установка не трогает приложение. */
    @Volatile var uiVisible = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // ------------------------------- расписание -------------------------------

    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        // Задача нужна всегда: кроме обновлений приложения она обновляет подписку
        if (js.getPendingJob(JOB_ID) != null) return
        js.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(ctx, Job::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build()
        )
    }

    class Job : JobService() {
        override fun onStartJob(params: JobParameters): Boolean {
            Thread {
                try {
                    runInBackground(applicationContext)
                } catch (e: Exception) {
                    Log.w(TAG, "background update failed", e)
                }
                jobFinished(params, false)
            }.start()
            return true
        }

        override fun onStopJob(params: JobParameters) = true
    }

    /** Фоновая проверка: скачать новую версию заранее и сообщить уведомлением. */
    private fun runInBackground(ctx: Context) {
        AppSettings.load(ctx)
        // Идёт матч MLBB — ничего не качаем, чтобы не отнимать канал у игры
        if (MatchTracker.inMatch) { AppLog.upd("фоновая задача: идёт матч — пропускаю"); return }
        AppLog.upd("фоновая задача: подписка и обновления")
        // Подписка: раз в сутки обновить и предупредить, если кончается срок или трафик
        try {
            Subscription.refreshInBackground(ctx)
            subscriptionWarning(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "subscription refresh failed", e)
        }
        // Приложение открыто — там и так покажется окно обновления
        if (!AppSettings.autoCheckUpdates || uiVisible) return
        val rel = Updater.check() ?: run { AppLog.upd("новой версии нет"); return }
        AppLog.upd("найдена версия ${rel.tag.removePrefix("mlbb-v")}, качаю в фоне")
        val apk = Updater.download(ctx, rel) { }
        AppLog.upd("скачано, отправил уведомление «нажми, чтобы установить»")
        rememberNotes(ctx, rel)
        notifyReady(ctx, rel, apk)
    }

    /** Предупреждение о подписке — не чаще раза в день. */
    fun subscriptionWarning(ctx: Context) {
        val (title, text) = Subscription.warning() ?: return
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        val p = prefs(ctx)
        if (p.getString("subWarnDay", "") == today) return
        p.edit().putString("subWarnDay", today).apply()
        AppLog.sub("предупреждение: $title — $text")
        notify(ctx, NOTIF_SUB, title, text, openApp(ctx))
    }

    fun rememberNotes(ctx: Context, rel: Updater.Release) {
        prefs(ctx).edit().putString("updNotes", rel.notes).putInt("updNotesFor", rel.versionCode).apply()
    }

    /** Уведомление «скачано»: нажатие сразу открывает установщик Android на готовый файл. */
    private fun notifyReady(ctx: Context, rel: Updater.Release, apk: File) {
        val version = rel.tag.removePrefix("mlbb-v")
        // Без разрешения на установку из приложения установщик не откроется — тогда ведём в приложение
        val tap = if (ctx.packageManager.canRequestPackageInstalls()) {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            PendingIntent.getActivity(ctx, 2, view, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else openApp(ctx)
        val first = rel.notes.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("Версия ") }
        notify(ctx, NOTIF_READY, "Обновление Fast VPN $version скачано",
            "Нажми, чтобы установить" + (first?.let { ".\n$it" } ?: ""), tap)
    }

    /** Приложение обновилось — уведомление «обновлено» (окно с описанием покажет главный экран). */
    class ReplacedReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            AppLog.upd("приложение обновлено до ${BuildConfig.VERSION_NAME}")
            ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_READY)
            val notes = updatedNotes(ctx)?.lineSequence()?.firstOrNull { it.isNotBlank() }
            notify(ctx, NOTIF_DONE, "Fast VPN обновлён до ${BuildConfig.VERSION_NAME}",
                notes ?: Ui.SLOGAN, openApp(ctx))
            AppSettings.load(ctx)
            schedule(ctx)
        }
    }

    /** Описание установленной версии, если мы его запомнили при скачивании. */
    fun updatedNotes(ctx: Context): String? {
        val p = prefs(ctx)
        return if (p.getInt("updNotesFor", 0) == BuildConfig.VERSION_CODE) p.getString("updNotes", null) else null
    }

    /**
     * Версия поменялась с прошлого запуска — вернёт описание для окна «Обновлено»
     * (пустую строку, если описания нет). null — показывать нечего.
     */
    fun takeJustUpdated(ctx: Context): String? {
        val p = prefs(ctx)
        val seen = p.getInt("seenVersion", 0)
        p.edit().putInt("seenVersion", BuildConfig.VERSION_CODE).apply()
        if (seen == 0 || seen >= BuildConfig.VERSION_CODE) return null
        // Обновились — скачанные APK больше не нужны
        Updater.cleanup(ctx)
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_DONE)
        return updatedNotes(ctx) ?: ""
    }

    // ------------------------------- уведомления -------------------------------

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun notify(ctx: Context, id: Int, title: String, text: String, tap: PendingIntent) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Обновления", NotificationManager.IMPORTANCE_DEFAULT))
        try {
            nm.notify(id, android.app.Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_mono)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build())
        } catch (_: SecurityException) {
            // Нет разрешения на уведомления — окно «Обновлено» всё равно покажется при запуске
        }
    }
}
