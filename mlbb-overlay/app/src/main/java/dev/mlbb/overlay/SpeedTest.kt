package dev.mlbb.overlay

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * Тест скорости через Cloudflare: пинг (HTTP до ближайшего узла CDN),
 * загрузка и отдача в 4 потока по ~7 секунд. Если VPN включён — меряет через него.
 */
object SpeedTest {
    data class Result(val pingMs: Int?, val jitterMs: Int?, val downMbps: Double?, val upMbps: Double?)

    private const val BASE = "https://speed.cloudflare.com"

    /**
     * close = true — соединение не переиспользуется. Важно: после смены сервера Android взял бы
     * уже открытое соединение, проложенное через ПРЕДЫДУЩИЙ сервер, и замер был бы не того сервера.
     */
    private fun conn(path: String, close: Boolean = true): HttpURLConnection {
        val mixed = BoxVpnService.ports?.mixed
        val c = if (mixed != null) URL(BASE + path).openConnection(
            java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", mixed))
        ) else URL(BASE + path).openConnection()
        return (c as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "fast-vpn")
            if (close) setRequestProperty("Connection", "close")
        }
    }

    fun ping(): Pair<Int, Int>? {
        val samples = ArrayList<Int>()
        repeat(7) { i ->
            try {
                val t = System.nanoTime()
                // Первый запрос закрывает старое соединение (могло идти через прошлый сервер),
                // дальше одно свежее соединение переиспользуется — так пинг без рукопожатий
                val c = conn("/__down?bytes=0", close = i == 0)
                c.inputStream.use { it.readBytes() }
                samples.add(((System.nanoTime() - t) / 1_000_000).toInt())
            } catch (_: Exception) {
            }
        }
        // Первые два замера — со старым соединением и с рукопожатием TLS, их выкидываем
        val s = (if (samples.size > 2) samples.drop(2) else samples).sorted()
        if (s.isEmpty()) return null
        return s[s.size / 2] to (s.last() - s.first())
    }

    private fun parallel(seconds: Int, work: (AtomicLong, Long) -> Unit): Double? {
        val bytes = AtomicLong()
        val deadline = System.currentTimeMillis() + seconds * 1000L
        val start = System.nanoTime()
        val threads = (1..4).map { Thread { try { work(bytes, deadline) } catch (_: Exception) {} }.apply { start() } }
        threads.forEach { it.join(seconds * 1000L + 9000) }
        val sec = (System.nanoTime() - start) / 1e9
        if (bytes.get() < 50_000) return null
        return bytes.get() * 8 / sec / 1_000_000
    }

    fun download(progress: (Double) -> Unit): Double? {
        val started = System.nanoTime()
        return parallel(7) { bytes, deadline ->
            val buf = ByteArray(64 * 1024)
            while (System.currentTimeMillis() < deadline) {
                conn("/__down?bytes=50000000").inputStream.use { input ->
                    while (System.currentTimeMillis() < deadline) {
                        val n = input.read(buf)
                        if (n < 0) break
                        val total = bytes.addAndGet(n.toLong())
                        progress(total * 8 / ((System.nanoTime() - started) / 1e9) / 1_000_000)
                    }
                }
            }
        }
    }

    /** Короткий замер загрузки (для сравнения серверов между собой). */
    fun quickDownload(seconds: Int = 2): Double? = parallel(seconds) { bytes, deadline ->
        val buf = ByteArray(64 * 1024)
        while (System.currentTimeMillis() < deadline) {
            conn("/__down?bytes=25000000").inputStream.use { input ->
                while (System.currentTimeMillis() < deadline) {
                    val n = input.read(buf)
                    if (n < 0) break
                    bytes.addAndGet(n.toLong())
                }
            }
        }
    }

    fun upload(progress: (Double) -> Unit): Double? {
        val started = System.nanoTime()
        val chunk = ByteArray(256 * 1024)
        return parallel(6) { bytes, deadline ->
            while (System.currentTimeMillis() < deadline) {
                val c = conn("/__up")
                c.requestMethod = "POST"
                c.doOutput = true
                c.setFixedLengthStreamingMode(chunk.size * 16)
                c.outputStream.use { out ->
                    repeat(16) {
                        if (System.currentTimeMillis() >= deadline) return@use
                        out.write(chunk)
                        val total = bytes.addAndGet(chunk.size.toLong())
                        progress(total * 8 / ((System.nanoTime() - started) / 1e9) / 1_000_000)
                    }
                }
                try { c.responseCode } catch (_: Exception) {}
            }
        }
    }
}
