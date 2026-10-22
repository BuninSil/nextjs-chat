package dev.mlbb.overlay

import android.content.Context
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Куда пускать Telegram. По умолчанию — через заграничный сервер подписки (как всё заблокированное).
 * Но у части операторов его адреса открыты напрямую (или по IPv6), и тогда сервер не нужен: меньше
 * нагрузка на подписку и ниже задержка. Здесь проверяем, доступен ли Telegram без сервера в этой
 * конкретной сети, и переключаем маршрут «tg» в ядре на лету (без перезапуска).
 *
 * IPv6-адреса Telegram в конфиге всегда идут напрямую (см. [BoxConfig]) — если у оператора есть
 * IPv6, клиент сам возьмёт его бесплатно. Здесь решаем только про IPv4: напрямую или через сервер.
 */
object TelegramRoute {
    /** Опорные адреса дата-центров Telegram — проверяем, отвечает ли хоть один */
    private val TEST_V4 = listOf("149.154.167.50", "149.154.175.50", "91.108.56.130")
    private val TEST_V6 = listOf("2001:67c:4e8:f004::a", "2001:b28:f23d:f002::a")
    private val PORTS = listOf(443, 80)

    /** Доступен ли хоть один адрес из списка (TCP, через саму сеть — наше приложение вне туннеля) */
    private fun reachable(ctx: Context, hosts: List<String>): Boolean {
        val net = ServerTester.underlying(ctx)
        for (h in hosts) for (port in PORTS) {
            try {
                Socket().use { s ->
                    try { net?.bindSocket(s) } catch (_: Exception) {}
                    s.connect(InetSocketAddress(h, port), 1500)
                    return true
                }
            } catch (_: Exception) {}
        }
        return false
    }

    /** Строка состояния для журнала и экрана «Серверы» */
    @Volatile var state: String = ""
        private set

    /**
     * Проверить Telegram в текущей сети и выставить маршрут. direct — адреса открыты напрямую,
     * иначе — через заграничный сервер (как было). Запоминаем на сеть, чтобы в следующий раз
     * выставить сразу, не дожидаясь проверки.
     */
    fun apply(ctx: Context) {
        val p = BoxVpnService.ports ?: return
        if (AppSettings.gameMode) return
        val net = Autopilot.networkKey(ctx).ifEmpty { return }
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

        val v4 = reachable(ctx, TEST_V4)
        val v6 = if (v4) false else reachable(ctx, TEST_V6)
        if (v4) {
            if (ClashApi.select(p.api, p.secret, "tg", "direct")) {
                state = "Telegram напрямую, без сервера"
                if (prefs.getString("tgRoute:$net", "") != "direct") AppLog.conn("Telegram в этой сети открыт напрямую — пускаю без сервера")
                prefs.edit().putString("tgRoute:$net", "direct").apply()
            }
            return
        }
        // Напрямую по IPv4 не вышло. IPv6 в конфиге и так идёт напрямую — если он есть, Telegram уже
        // работает без сервера. Сам маршрут «tg» в любом случае возвращаем на заграничный сервер:
        // он обслужит то, что пойдёт по IPv4.
        val abroad = ClashApi.now(p.api, p.secret, "abroad")
        if (abroad != null) ClashApi.select(p.api, p.secret, "tg", abroad)
        state = if (v6) "Telegram по IPv6, без сервера" else "Telegram через сервер «${abroad?.let { AppLog.name(ctx, it) } ?: "—"}»"
        if (prefs.getString("tgRoute:$net", "") != "server") AppLog.conn(if (v6) "Telegram доступен по IPv6 — идёт без сервера" else "Telegram напрямую недоступен — через сервер")
        prefs.edit().putString("tgRoute:$net", if (v6) "ipv6" else "server").apply()
    }

    /** Быстро выставить маршрут по памяти (до проверки), чтобы Telegram сразу пошёл верным путём */
    fun preset(ctx: Context) {
        val p = BoxVpnService.ports ?: return
        if (AppSettings.gameMode) return
        val net = Autopilot.networkKey(ctx).ifEmpty { return }
        val remembered = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("tgRoute:$net", null) ?: return
        if (remembered == "direct") ClashApi.select(p.api, p.secret, "tg", "direct")
    }
}
