package dev.mlbb.overlay

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Игровой режим Wi-Fi без VPN: режим низкой задержки (Wi-Fi-модуль не уходит
 * в энергосбережение между пакетами — именно это даёт скачки пинга) и
 * диагностика: диапазон, сигнал, пинг до роутера.
 */
object WifiBoost {
    private var lock: WifiManager.WifiLock? = null

    data class Info(val band: String, val rssi: Int, val linkMbps: Int, val routerPingMs: Int?)

    fun acquire(ctx: Context) {
        if (!AppSettings.wifiBoost || lock?.isHeld == true) return
        try {
            val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            lock = wm.createWifiLock(mode, "mlbb:game").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    fun release() {
        try {
            lock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        lock = null
    }

    val active get() = lock?.isHeld == true

    /** Состояние Wi-Fi или null, если сейчас не Wi-Fi. Делает сетевой замер — не с главного потока. */
    fun info(ctx: Context): Info? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        // Ищем сеть Wi-Fi среди всех: при включённом VPN активной будет VPN
        @Suppress("DEPRECATION")
        val wifiNet = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return null
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val wi = wm.connectionInfo ?: return null
        val band = when {
            wi.frequency >= 5925 -> "6 ГГц"
            wi.frequency >= 4900 -> "5 ГГц"
            wi.frequency > 0 -> "2.4 ГГц"
            else -> "?"
        }
        val gw = cm.getLinkProperties(wifiNet)?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress
        val ping = gw?.takeIf { !it.contains(':') }?.let { ip ->
            val samples = (1..3).mapNotNull { Native.icmpPing(ip, 500).takeIf { ms -> ms >= 0 } }
            samples.minOrNull()
        }
        return Info(band, wi.rssi, wi.linkSpeed, ping)
    }

    fun signalText(rssi: Int) = when {
        rssi >= -55 -> "отличный"
        rssi >= -67 -> "хороший"
        rssi >= -75 -> "слабый"
        else -> "очень слабый"
    }

    /** Подсказки, что поправить в Wi-Fi. */
    fun advice(i: Info): List<String> {
        val out = ArrayList<String>()
        if (i.band == "2.4 ГГц") out.add("Ты на 2.4 ГГц — если роутер умеет 5 ГГц, переключись: меньше помех и скачков пинга")
        if (i.rssi < -70) out.add("Слабый сигнал — подойди ближе к роутеру или убери преграды")
        i.routerPingMs?.let {
            if (it > 15) out.add(
                "До роутера $it ms — тормозит сам Wi-Fi" + (if (AppSettings.simple) "" else ", а не игра") +
                    " (кто-то качает или много помех)"
            )
        }
        return out
    }
}
