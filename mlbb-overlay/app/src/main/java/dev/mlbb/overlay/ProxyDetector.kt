package dev.mlbb.overlay

import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Поиск локального SOCKS5 VPN-клиента и проверка, что через него ходит UDP. */
object ProxyDetector {
    private const val HOST = "127.0.0.1"

    /** Типичные порты клиентов: v2rayNG, Clash/FlClash, sing-box-клиенты, Tor и т.п. */
    private val KNOWN_PORTS = listOf(
        10808, 10809, 1080, 2080, 2334, 7890, 7891, 3066, 3067, 3057, 12334, 20808, 9050, 1081, 8080
    )

    /** SOCKS5 без авторизации (или с ней, если логин указан в настройках)? */
    fun isSocks5(port: Int, host: String = HOST): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 300)
            s.soTimeout = 500
            s.getOutputStream().write(byteArrayOf(5, 1, 0))
            val r = ByteArray(2)
            DataInputStream(s.getInputStream()).readFully(r)
            r[0].toInt() == 5 && r[1].toInt() == 0
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Ищет порт: сначала сохранённый и известные, потом все порты 1024–65535
     * на 127.0.0.1 (закрытые локальные порты отвечают отказом мгновенно).
     */
    fun findPort(saved: Int, progress: (String) -> Unit): Int? {
        progress("Проверяю известные порты…")
        for (p in listOf(saved) + KNOWN_PORTS) if (isSocks5(p)) return p

        progress("Сканирую порты, это пара секунд…")
        val pool = Executors.newFixedThreadPool(64)
        val open = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        val done = AtomicInteger()
        for (p in 1024..65535) {
            pool.execute {
                try {
                    Socket().use { it.connect(InetSocketAddress(HOST, p), 200) }
                    open.add(p)
                } catch (_: Exception) {
                }
                val n = done.incrementAndGet()
                if (n % 8000 == 0) progress("Сканирую порты… ${n * 100 / 64512}%")
            }
        }
        pool.shutdown()
        pool.awaitTermination(60, TimeUnit.SECONDS)

        progress("Нашёл открытых портов: ${open.size}, проверяю какой из них SOCKS5…")
        return open.sorted().firstOrNull { isSocks5(it) }
    }

    /**
     * Полная проверка UDP: UDP ASSOCIATE + DNS-запрос к 1.1.1.1 через прокси.
     * Без UDP игра (и DNS в режиме цепочки) работать не будет.
     */
    fun udpWorks(port: Int, host: String = HOST): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 1000)
            s.soTimeout = 2000
            val out = s.getOutputStream()
            val inp = DataInputStream(s.getInputStream())
            out.write(byteArrayOf(5, 1, 0))
            val hello = ByteArray(2).also { inp.readFully(it) }
            if (hello[1].toInt() != 0) return false

            out.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
            val head = ByteArray(4).also { inp.readFully(it) }
            if (head[1].toInt() != 0) return false
            val relayIp: ByteArray
            val relayPort: Int
            when (head[3].toInt()) {
                1 -> {
                    val b = ByteArray(6).also { inp.readFully(it) }
                    relayIp = b.copyOfRange(0, 4)
                    relayPort = ((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF)
                }
                4 -> {
                    val b = ByteArray(18).also { inp.readFully(it) }
                    relayIp = InetAddress.getByName(host).address
                    relayPort = ((b[16].toInt() and 0xFF) shl 8) or (b[17].toInt() and 0xFF)
                }
                else -> return false
            }
            val relay = if (relayIp.all { it.toInt() == 0 }) InetAddress.getByName(host) else InetAddress.getByAddress(relayIp)

            // DNS-запрос A example.com
            val dns = byteArrayOf(
                0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
                7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
                'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
                3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0, 0, 1, 0, 1
            )
            val pkt = byteArrayOf(0, 0, 0, 1, 1, 1, 1, 1, 0, 53) + dns
            DatagramSocket().use { u ->
                u.soTimeout = 3000
                u.send(DatagramPacket(pkt, pkt.size, relay, relayPort))
                val buf = ByteArray(1500)
                val resp = DatagramPacket(buf, buf.size)
                u.receive(resp)
                // заголовок SOCKS (10 байт) + ответ DNS с тем же id
                resp.length > 12 && buf[10] == 0x12.toByte() && buf[11] == 0x34.toByte()
            }
        }
    } catch (_: Exception) {
        false
    }
}
