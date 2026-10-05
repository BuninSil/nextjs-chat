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
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast
import java.io.File

/**
 * Бесшовные обновления.
 *
 * Установка идёт через PackageInstaller. На Android 12+ с USER_ACTION_NOT_REQUIRED система ставит
 * обновление молча — но только если приложение само является «установщиком» себя. Поэтому первое
 * обновление после этой версии один раз попросит подтверждение, а дальше всё ставится само.
 *
 * Фоновая проверка — раз в несколько часов, только когда VPN выключен и приложение не открыто:
 * установка перезапускает приложение и оборвала бы VPN посреди игры.
 */
object AutoUpdate {
    private const val TAG = "AutoUpdate"
    private const val JOB_ID = 4201
    private const val CHANNEL = "updates"
    private const val PERIOD_MS = 6 * 60 * 60 * 1000L

    const val NOTIF_READY = 41
    const val NOTIF_DONE = 42

    /** Открыт ли сейчас главный экран — тогда фоновая установка не трогает приложение. */
    @Volatile var uiVisible = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // ------------------------------- расписание -------------------------------

    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (!AppSettings.autoCheckUpdates) {
            js.cancel(JOB_ID)
            return
        }
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

    private fun vpnBusy() = BoxVpnService.isRunning || BoxVpnService.isStarting ||
        CaptureVpnService.isRunning || MonitorService.isRunning

    /** Фоновая проверка: скачать и тихо поставить, если сейчас никому не помешает. */
    private fun runInBackground(ctx: Context) {
        AppSettings.load(ctx)
        if (!AppSettings.autoCheckUpdates || vpnBusy() || uiVisible) return
        val rel = Updater.check() ?: return
        // Без разрешения на установку или на старом Android тихо нельзя — просто сообщаем
        if (Build.VERSION.SDK_INT < 31 || !ctx.packageManager.canRequestPackageInstalls()) {
            notify(ctx, NOTIF_READY, "Доступно обновление ${rel.tag.removePrefix("mlbb-v")}",
                "Нажми, чтобы открыть Fast VPN и обновить", openApp(ctx))
            return
        }
        val apk = Updater.download(ctx, rel) { }
        // Пока качали, могли включить VPN или открыть приложение — тогда поставим в следующий раз
        if (vpnBusy() || uiVisible) return
        rememberNotes(ctx, rel)
        install(ctx, apk, interactive = false)
    }

    // -------------------------------- установка --------------------------------

    fun rememberNotes(ctx: Context, rel: Updater.Release) {
        prefs(ctx).edit().putString("updNotes", rel.notes).putInt("updNotesFor", rel.versionCode).apply()
    }

    /**
     * Ставит APK через PackageInstaller. interactive — пользователь сам нажал «Обновить»:
     * если система попросит подтверждение, сразу показываем её окно; иначе — уведомлением.
     */
    fun install(ctx: Context, apk: File, interactive: Boolean) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val intent = Intent(ctx, StatusReceiver::class.java)
                .putExtra("interactive", interactive)
                .putExtra("apk", apk.path)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }

    /** Ответ системы на установку. */
    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val interactive = intent.getBooleanExtra("interactive", false)
            val apk = intent.getStringExtra("apk")?.let { File(it) }?.takeIf { it.exists() }
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_SUCCESS -> setLastResult(ctx, null)
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    if (interactive && confirm != null) {
                        try {
                            ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            return
                        } catch (_: Exception) {
                        }
                    }
                    // Из фона подтверждение часто не показывается (особенно на Xiaomi) — сессию бросаем
                    // и даём уведомление с обычным установщиком на уже скачанный файл
                    abandon(ctx, intent)
                    setLastResult(ctx, "система попросила подтверждение")
                    fallback(ctx, apk, interactive)
                }
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "код $status"
                    Log.w(TAG, "install failed: $msg")
                    setLastResult(ctx, msg)
                    fallback(ctx, apk, interactive)
                }
            }
        }

        private fun abandon(ctx: Context, intent: Intent) {
            val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
            if (id >= 0) try { ctx.packageManager.packageInstaller.abandonSession(id) } catch (_: Exception) {}
        }

        /** Запасной путь — обычный установщик Android: сразу (если пользователь сам нажал) или из уведомления. */
        private fun fallback(ctx: Context, apk: File?, interactive: Boolean) {
            if (apk == null) {
                if (interactive) Toast.makeText(ctx, "Обновление не установилось, попробуй ещё раз", Toast.LENGTH_LONG).show()
                return
            }
            if (interactive) {
                try {
                    if (Updater.install(ctx, apk)) return
                } catch (_: Exception) {
                }
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            notify(ctx, NOTIF_READY, "Обновление Fast VPN скачано", "Нажми, чтобы установить",
                PendingIntent.getActivity(ctx, 2, view, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
    }

    /** Чем закончилась последняя установка обновления (null — успешно). Показывается в Настройках. */
    fun setLastResult(ctx: Context, error: String?) {
        val e = prefs(ctx).edit()
        if (error == null) e.remove("updLastError") else e.putString("updLastError", error)
        e.apply()
    }

    fun lastError(ctx: Context): String? = prefs(ctx).getString("updLastError", null)

    /** Приложение обновилось — уведомление «обновлено» (окно с описанием покажет главный экран). */
    class ReplacedReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
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
        setLastResult(ctx, null)
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
