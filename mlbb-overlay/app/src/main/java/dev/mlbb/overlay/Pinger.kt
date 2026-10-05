package dev.mlbb.overlay

import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Пинг боевого сервера. Порядок:
 *  1. ICMP echo (если сервер отвечает на ICMP);
 *  2. RTT TCP-хэндшейка самой игры до этого же IP (если игра к нему ходила по TCP);
 *  3. TCP-проба на 443 порт: время до SYN/ACK или RST (RST тоже даёт честный RTT).
 */
class Pinger {
    data class Result(val ip: String, val ms: Int, val method: String)

    @Volatile
    var last: Result? = null
        private set

    private var icmpFails = HashMap<String, Int>()

    fun probe(ip: String) {
        if (ip.contains(':')) { // IPv6 — только TCP
            tcpProbe(ip)?.let { last = Result(ip, it, "TCP"); return }
            last = Result(ip, -1, "")
            return
        }

        if ((icmpFails[ip] ?: 0) < 3) {
            val rtt = Native.icmpPing(ip, 1000)
            if (rtt >= 0) {
                icmpFails[ip] = 0
                last = Result(ip, rtt, "ICMP")
                return
            }
            icmpFails[ip] = (icmpFails[ip] ?: 0) + 1
        }

        val hs = ConnTracker.handshakeRtt(ip)
        if (hs >= 0) {
            last = Result(ip, hs, "TCP-hs")
            return
        }

        tcpProbe(ip)?.let { last = Result(ip, it, "TCP"); return }
        last = Result(ip, -1, "")
    }

    private fun tcpProbe(ip: String): Int? {
        val start = System.nanoTime()
        return try {
            Socket().use { it.connect(InetSocketAddress(ip, 443), 1000) }
            ((System.nanoTime() - start) / 1_000_000).toInt()
        } catch (e: ConnectException) {
            // Connection refused = пришёл RST, это тоже один RTT
            val ms = ((System.nanoTime() - start) / 1_000_000).toInt()
            if (e.message?.contains("ECONNREFUSED") == true || e.message?.contains("refused") == true) ms else null
        } catch (e: Exception) {
            null
        }
    }
}
