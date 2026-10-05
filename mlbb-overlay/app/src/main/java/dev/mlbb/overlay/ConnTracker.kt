package dev.mlbb.overlay

/**
 * Журнал всех соединений игры. Нативный поток раз в секунду присылает
 * накопленные счётчики, UI и оверлей читают снимки.
 */
object ConnTracker {
    const val PROTO_ICMP = 1
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    private const val WINDOW_MS = 10_000L
    private const val MAX_ENTRIES = 20_000

    /** UDP-порты, которые точно не боевой сервер: DNS, NTP, QUIC/HTTP3. */
    private val IGNORED_UDP_PORTS = setOf(53, 123, 443)

    class Conn(
        val key: Long,
        val proto: Int,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int,
        val firstSeenMs: Long,
    ) {
        var lastSeenMs = firstSeenMs
        var bytesOut = 0L
        var bytesIn = 0L
        var pktsOut = 0L
        var pktsIn = 0L
        var hsRttMs = -1
        var closed = false

        /** Пары (время, всего пакетов) для подсчёта пакетов за последние 10 с. */
        val history = ArrayDeque<LongArray>()

        val totalPkts get() = pktsIn + pktsOut
        fun copy() = Conn(key, proto, srcPort, dstIp, dstPort, firstSeenMs).also {
            it.lastSeenMs = lastSeenMs; it.bytesOut = bytesOut; it.bytesIn = bytesIn
            it.pktsOut = pktsOut; it.pktsIn = pktsIn; it.hsRttMs = hsRttMs; it.closed = closed
        }
    }

    data class Battle(val conn: Conn, val pktsLast10s: Long)

    private val conns = LinkedHashMap<Long, Conn>()
    private var generation = 0L

    @Synchronized
    fun newSession() {
        generation++
    }

    @Synchronized
    fun clear() {
        conns.clear()
    }

    @Synchronized
    fun update(
        id: Int, proto: Int, srcPort: Int, dstIp: String, dstPort: Int,
        bytesOut: Long, bytesIn: Long, pktsOut: Long, pktsIn: Long,
        firstMs: Long, lastMs: Long, hsRttMs: Int, closed: Boolean,
    ) {
        val key = (generation shl 32) or id.toLong()
        val c = conns.getOrPut(key) { Conn(key, proto, srcPort, dstIp, dstPort, firstMs) }
        c.bytesOut = bytesOut
        c.bytesIn = bytesIn
        c.pktsOut = pktsOut
        c.pktsIn = pktsIn
        c.lastSeenMs = lastMs
        c.hsRttMs = hsRttMs
        c.closed = closed

        val now = System.currentTimeMillis()
        c.history.addLast(longArrayOf(now, c.totalPkts))
        // Храним одну точку старше окна — это и есть база для разности.
        while (c.history.size > 2 && c.history[1][0] <= now - WINDOW_MS) c.history.removeFirst()

        if (conns.size > MAX_ENTRIES) {
            val it = conns.values.iterator()
            while (conns.size > MAX_ENTRIES && it.hasNext()) {
                if (it.next().closed) it.remove()
            }
        }
    }

    private fun pktsInWindow(c: Conn, now: Long): Long {
        val from = now - WINDOW_MS
        if (c.lastSeenMs < from) return 0
        // Последняя точка, которая не моложе начала окна, — база.
        var base: Long? = null
        for (h in c.history) {
            if (h[0] <= from) base = h[1] else break
        }
        if (base == null) {
            // Соединение моложе окна — считаем все его пакеты.
            base = if (c.firstSeenMs >= from) 0 else c.history.firstOrNull()?.get(1) ?: 0
        }
        return (c.totalPkts - base).coerceAtLeast(0)
    }

    /** «Боевой сервер» — UDP-поток с наибольшим числом пакетов за последние 10 секунд. */
    @Synchronized
    fun battleServer(now: Long = System.currentTimeMillis()): Battle? {
        var best: Conn? = null
        var bestPkts = 0L
        for (c in conns.values) {
            if (c.proto != PROTO_UDP || c.dstPort in IGNORED_UDP_PORTS) continue
            val p = pktsInWindow(c, now)
            if (p > bestPkts) {
                best = c; bestPkts = p
            }
        }
        return best?.let { Battle(it.copy(), bestPkts) }
    }

    /** Последний известный RTT TCP-хэндшейка игры до этого IP. */
    @Synchronized
    fun handshakeRtt(ip: String): Int =
        conns.values.lastOrNull { it.proto == PROTO_TCP && it.dstIp == ip && it.hsRttMs >= 0 }?.hsRttMs ?: -1

    @Synchronized
    fun snapshot(): List<Conn> = conns.values.map { it.copy() }

    fun protoName(p: Int) = when (p) {
        PROTO_TCP -> "TCP"
        PROTO_UDP -> "UDP"
        PROTO_ICMP -> "ICMP"
        else -> p.toString()
    }
}
