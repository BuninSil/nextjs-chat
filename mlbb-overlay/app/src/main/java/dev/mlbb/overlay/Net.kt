package dev.mlbb.overlay

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * HTTP для самого приложения (база DB-IP, обновления). Наше приложение в VPN
 * не входит, поэтому в режиме цепочки ходим через SOCKS5 клиента явно —
 * иначе на мобильном интернете с блокировками запрос не пройдёт.
 */
object Net {
    fun open(url: String): HttpURLConnection {
        val u = URL(url)
        val conn = if (AppSettings.chainEnabled && CaptureVpnService.isRunning) {
            u.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress(AppSettings.socksHost, AppSettings.socksPort)))
        } else {
            u.openConnection()
        }
        return (conn as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
    }
}
