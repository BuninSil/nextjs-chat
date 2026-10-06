package dev.mlbb.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Автоподключение по сети: на мобильном интернете VPN включается сам, на Wi-Fi — выключается
 * (только если включался сам).
 *
 * Андроид будит приложение задачей с условием «мобильная сеть». Если система не даёт поднять VPN
 * из фона (бывает на части прошивок), приходит уведомление с кнопкой «Включить».
 * Выключил VPN руками — сам он не включится, пока не сменится сеть.
 */
object AutoConnect {
    private const val JOB_ID = 4202
    private const val CHANNEL = "autoconnect"
    private const val NOTIF_ID = 51

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Включил ли VPN автоподключением (тогда его можно и выключить на Wi-Fi). */
    var autoStarted: Boolean = false
        private set

    /** Поставить задачу «как появится мобильная сеть». delayMs — не раньше чем через. */
    fun arm(ctx: Context, delayMs: Long = 0) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        AppSettings.load(ctx)
        if (!AppSettings.autoOnMobile) {
            js.cancel(JOB_ID)
            return
        }
        val b = JobInfo.Builder(JOB_ID, ComponentName(ctx, Job::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_CELLULAR)
            .setPersisted(true)
        if (delayMs > 0) b.setMinimumLatency(delayMs)
        js.schedule(b.build())
    }

    /** VPN выключили руками — в этой сети сам больше не включаем. */
    fun onManualStop(ctx: Context) {
        autoStarted = false
        prefs(ctx).edit().putString("autoSuppressNet", ServerTester.netKey(ctx)).apply()
        arm(ctx, 10 * 60 * 1000L)
    }

    /** Сеть сменилась (вызывает VPN-сервис). На Wi-Fi выключаем VPN, если он включался сам. */
    fun onNetworkChanged(ctx: Context) {
        val net = ServerTester.netKey(ctx)
        val p = prefs(ctx)
        // Сеть сменилась — запрет «выключил руками» снимается
        if (p.getString("autoSuppressNet", "") != net) p.edit().remove("autoSuppressNet").apply()
        if (net == "wifi" && autoStarted && AppSettings.autoOffWifi && BoxVpnService.isRunning) {
            autoStarted = false
            VpnLog.add("автоподключение: Wi-Fi — выключаю VPN")
            BoxVpnService.stop(ctx, manual = false)
        }
    }

    class Job : JobService() {
        override fun onStartJob(params: JobParameters): Boolean {
            val ctx = applicationContext
            AppSettings.load(ctx)
            val net = ServerTester.netKey(ctx)
            val suppressed = prefs(ctx).getString("autoSuppressNet", "") == net
            when {
                !AppSettings.autoOnMobile -> {}
                BoxVpnService.isRunning || BoxVpnService.isStarting -> {}
                net != "cell" -> arm(ctx)
                suppressed -> arm(ctx, 10 * 60 * 1000L)
                !Connector.canStart(ctx) -> {}
                else -> start(ctx)
            }
            return false
        }

        override fun onStopJob(params: JobParameters) = false
    }

    private fun start(ctx: Context) {
        try {
            // Проверяем, что система даёт поднять VPN из фона; дальше — обычное быстрое подключение
            BoxVpnService.start(ctx)
            autoStarted = true
            VpnLog.add("автоподключение: мобильная сеть — включаю VPN")
            Connector.connect(ctx)
        } catch (_: Exception) {
            // Фоновый запуск запрещён — просим нажать
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Автоподключение", NotificationManager.IMPORTANCE_DEFAULT))
            val on = PendingIntent.getForegroundService(
                ctx, 7, Intent(ctx, BoxVpnService::class.java).setAction(BoxVpnService.ACTION_AUTO),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                nm.notify(NOTIF_ID, android.app.Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_mono)
                    .setContentTitle("Ты на мобильном интернете")
                    .setContentText("Нажми, чтобы включить Fast VPN")
                    .setContentIntent(on)
                    .setAutoCancel(true)
                    .build())
            } catch (_: SecurityException) {
            }
        }
    }

    /** Нажали «Включить» в уведомлении — VPN-сервис уже запущен, доделываем подключение. */
    fun onNotificationStart(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        autoStarted = true
        Connector.connect(ctx)
    }
}
