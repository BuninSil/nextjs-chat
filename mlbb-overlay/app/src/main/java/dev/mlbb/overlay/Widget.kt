package dev.mlbb.overlay

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews

/** Виджет на рабочий стол: статус, сервер с пингом и кнопка. Заодно обновляет плитку в шторке. */
object Widget {
    private const val ACTION_TOGGLE = "dev.mlbb.overlay.WIDGET_TOGGLE"

    /** Перерисовать виджеты и плитку — вызывается при любой смене состояния VPN. */
    fun update(ctx: Context) {
        try {
            TileService.requestListeningState(ctx, ComponentName(ctx, VpnTile::class.java))
        } catch (_: Exception) {
        }
        val mgr = AppWidgetManager.getInstance(ctx) ?: return
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, Provider::class.java))
        if (ids.isEmpty()) return
        AppSettings.load(ctx)
        mgr.updateAppWidget(ids, views(ctx))
    }

    /** Что показать: (заголовок, строка сервера). Общее для виджета и плитки. */
    fun state(ctx: Context): Pair<String, String> {
        val running = BoxVpnService.isRunning
        val n = Subscription.usable(ctx).let { all -> all.firstOrNull { it.tag == AppSettings.selectedTag } ?: all.firstOrNull() }
        val ping = n?.let { ServerTester.results[it.tag]?.medianMs }
        val server = when {
            n == null -> "Нет подписки — открой приложение"
            else -> Ui.cleanName(n) + (ping?.let { " · $it ms" } ?: "")
        }
        val title = when {
            BoxVpnService.isStarting -> "Подключаюсь…"
            Connector.busy && running -> "Подключён · ${Connector.busyText}"
            Connector.busy -> Connector.busyText
            running -> "VPN подключён"
            else -> "VPN выключен"
        }
        return title to server
    }

    private fun views(ctx: Context): RemoteViews {
        val running = BoxVpnService.isRunning || BoxVpnService.isStarting
        val (title, server) = state(ctx)
        val v = RemoteViews(ctx.packageName, R.layout.widget)
        v.setInt(R.id.root, "setBackgroundResource", if (Ui.theme.light) R.drawable.widget_bg_light else R.drawable.widget_bg_dark)
        v.setTextViewText(R.id.status, title)
        v.setTextColor(R.id.status, if (running) Ui.GREEN else Ui.TEXT)
        v.setTextViewText(R.id.server, server)
        v.setTextColor(R.id.server, Ui.MUTED)
        v.setTextViewText(R.id.btn, if (running) "Отключить" else "Подключить")
        v.setInt(R.id.btn, "setBackgroundResource", if (running) R.drawable.widget_btn_off else R.drawable.widget_btn_on)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        v.setOnClickPendingIntent(R.id.root, open)
        // Без разрешения на VPN или без подписки кнопка открывает приложение
        val btn = if (running || Connector.canStart(ctx)) PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, Provider::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ) else open
        v.setOnClickPendingIntent(R.id.btn, btn)
        return v
    }

    class Provider : AppWidgetProvider() {
        override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            AppSettings.load(ctx)
            mgr.updateAppWidget(ids, views(ctx))
        }

        override fun onReceive(ctx: Context, intent: Intent) {
            super.onReceive(ctx, intent)
            if (intent.action == ACTION_TOGGLE) {
                AppSettings.load(ctx)
                Connector.toggle(ctx)
                update(ctx)
            }
        }
    }
}

/** Плитка «Fast VPN» в шторке быстрых настроек. */
class VpnTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        AppSettings.load(this)
        val tile = qsTile ?: return
        val running = BoxVpnService.isRunning || BoxVpnService.isStarting
        val (title, server) = Widget.state(this)
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (running) server.substringBefore(" · ") else title
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        AppSettings.load(this)
        if (Connector.toggle(applicationContext)) {
            onStartListening()
            return
        }
        // Нет разрешения на VPN или подписки — открываем приложение
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(i)
        }
    }
}
