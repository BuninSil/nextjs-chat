package dev.mlbb.overlay

import android.os.SystemClock
import java.util.Locale

/**
 * «Мой IP» на главном: внешний адрес и страна — чтобы сразу видно, что VPN работает
 * и через какую страну идёт трафик. С VPN — через выбранный сервер, без VPN — напрямую.
 */
object MyIp {
    @Volatile var ip: String? = null
        private set
    @Volatile var country: String? = null
        private set
    /** Для какого состояния замер: сервер или "" (без VPN) */
    @Volatile private var forKey: String? = null
    @Volatile private var at = 0L
    @Volatile var loading = false
        private set

    private fun key() = if (BoxVpnService.isRunning) AppSettings.selectedTag else ""

    /** Замер устарел: сменился сервер, включили/выключили VPN или прошло 5 минут. */
    fun stale() = forKey != key() || SystemClock.elapsedRealtime() - at > 5 * 60_000

    /** Обновить в фоне, если нужно (или force). onDone — в фоновом потоке. */
    fun refresh(force: Boolean = false, onDone: () -> Unit = {}) {
        if (loading || (!force && !stale())) return
        if (BoxVpnService.isStarting) return
        loading = true
        val k = key()
        Thread {
            val port = if (BoxVpnService.isRunning) BoxVpnService.ports?.mixed else null
            val r = if (BoxVpnService.isRunning && port == null) null else ServerTester.whoAmI(port)
            if (r != null) {
                ip = r.first
                country = r.second
                // Через VPN — это страна выхода выбранного сервера: запоминаем, пригодится подбору
                if (k.isNotEmpty()) r.second?.let { ServerTester.rememberExit(App.ctx, k, it) }
                // Сам адрес в журнал не пишем — он попадёт в отчёт
                AppLog.net("мой IP: ${if (k.isEmpty()) "без VPN" else "через VPN"}, страна ${r.second ?: "?"}")
            } else {
                ip = null
                country = null
            }
            forKey = k
            at = SystemClock.elapsedRealtime()
            loading = false
            onDone()
        }.start()
    }

    /** Строка для главного: «IP 185.22.1.2 · 🇳🇱 Нидерланды». */
    fun line(): String {
        val i = ip ?: return if (loading) "Узнаю IP…" else "IP не определился — нажми, чтобы повторить"
        val c = country
        val name = c?.let { Locale("", it).getDisplayCountry(Locale("ru")).ifBlank { it } }
        return "Твой IP: $i" + (c?.let { " · ${GeoDb.flag(it)} $name" } ?: "")
    }
}
