package dev.mlbb.overlay

import java.io.DataInputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Пинг боевого сервера. Порядок:
 *  1. ICMP echo (если сервер отвечает на ICMP);
 *  2. RTT TCP-хэндшейка самой игры до этого же IP (если игра к нему ходила по TCP);
 *  3. TCP-проба на 443 порт: время до SYN/ACK или RST (RST тоже даёт честный RTT).
 * В режиме цепочки вместо 2–3 — проба через SOCKS5 клиента (путь как у игры).
 */
class Pinger(private val minValidMs: Int = 0) {
    data class Result(val ip: String, val ms: Int, val method: String)

    @Volatile
    var last: Result? = null
        private set

    private var icmpFails = HashMap<String, Int>()

    /**
     * Когда наш трафик идёт через TUN VPN-клиента, его стек может отвечать на
     * ICMP/TCP сам, мгновенно. Такие «0–2 ms» — враньё, их отбрасываем.
     */
    fun probe(ip: String) {
        probeRaw(ip)
        val l = last
        if (l != null && l.ms in 0 until minValidMs) last = Result(ip, -1, "")
    }

    private fun probeRaw(ip: String) {
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

        if (AppSettings.chainEnabled) {
            socksProbe(ip)?.let { last = Result(ip, it, "SOCKS~"); return }
            last = Result(ip, -1, "")
            return
        }

        val hs = ConnTracker.handshakeRtt(ip)
        if (hs >= 0) {
            last = Result(ip, hs, "TCP-hs")
            return
        }

        tcpProbe(ip)?.let { last = Result(ip, it, "TCP"); return }
        last = Result(ip, -1, "")
    }

    /**
     * CONNECT через SOCKS5 к ip:443. Часть прокси (Xray) отвечает «успех» сразу,
     * не дожидаясь апстрима, — тогда ждём, пока прокси закроет соединение после
     * отказа сервера. Оценка грубая, поэтому помечена «~».
     */
    private fun socksProbe(ip: String): Int? {
        val addr = InetAddress.getByName(ip).address.takeIf { it.size == 4 } ?: return null
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(AppSettings.socksHost, AppSettings.socksPort), 1000)
                s.soTimeout = 1500
                s.tcpNoDelay = true
                val out = s.getOutputStream()
                val inp = DataInputStream(s.getInputStream())
                val user = AppSettings.socksUser
                if (user.isEmpty()) out.write(byteArrayOf(5, 1, 0)) else out.write(byteArrayOf(5, 2, 0, 2))
                val hello = ByteArray(2).also { inp.readFully(it) }
                if (hello[1].toInt() == 2) {
                    val u = user.toByteArray()
                    val p = AppSettings.socksPass.toByteArray()
                    out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
                    val st = ByteArray(2).also { inp.readFully(it) }
                    if (st[1].toInt() != 0) return null
                } else if (hello[1].toInt() != 0) {
                    return null
                }
                val start = System.nanoTime()
                out.write(byteArrayOf(5, 1, 0, 1) + addr + byteArrayOf(1, 0xBB.toByte()))
                val rep = ByteArray(2).also { inp.readFully(it) }
                val elapsed = ((System.nanoTime() - start) / 1_000_000).toInt()
                when (rep[1].toInt()) {
                    5 -> elapsed // connection refused — один RTT до сервера и обратно
                    0 -> {
                        if (elapsed >= 5) return elapsed // прокси честно ждал апстрим
                        // ответ мгновенный: ждём закрытия после отказа сервера
                        try {
                            while (s.getInputStream().read() >= 0) { /* данные нам не нужны */ }
                        } catch (_: Exception) {
                            return null
                        }
                        ((System.nanoTime() - start) / 1_000_000).toInt()
                    }
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
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
