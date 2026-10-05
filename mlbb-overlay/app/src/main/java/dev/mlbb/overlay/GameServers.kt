package dev.mlbb.overlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Боевые серверы MLBB, на которых реально шли матчи (из оверлея), и замер
 * пинга до них. Адреса серверов игры нигде не публикуются, поэтому только так.
 */
object GameServers {
    data class Server(val ip: String, val port: Int, val lastSeen: Long)

    private const val KEY = "game_servers"
    private const val MAX = 10

    fun list(ctx: Context): List<Server> {
        val raw = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            Server(o.optString("ip"), o.optInt("port"), o.optLong("t"))
        }.filter { it.ip.isNotEmpty() }.sortedByDescending { it.lastSeen }
    }

    @Synchronized
    fun record(ctx: Context, ip: String, port: Int) {
        if (!ip.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return
        if (ip.startsWith("198.18.") || ip.startsWith("198.19.")) return // FakeIP — не настоящий адрес
        val now = System.currentTimeMillis()
        val items = list(ctx).filter { it.ip != ip }.toMutableList()
        items.add(0, Server(ip, port, now))
        val arr = JSONArray()
        items.take(MAX).forEach { arr.put(JSONObject().put("ip", it.ip).put("port", it.port).put("t", it.lastSeen)) }
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    /** Ориентиры в Москве (там же серверы MLBB для РФ) — пока не было ни одного матча. */
    val moscowRefs = listOf("ya.ru" to "Москва · Яндекс", "vk.com" to "Москва · VK")

    /**
     * Пинг до хоста по пути игры: через VPN — CONNECT к host:80 через SOCKS-вход и
     * время от HTTP-запроса до первого байта ответа (один круг от выхода VPN до хоста
     * плюс наш участок); без VPN — время TCP-рукопожатия.
     */
    fun pingHost(host: String): Int? {
        val mixed = BoxVpnService.ports?.mixed
        val samples = (1..3).mapNotNull { if (mixed != null) httpViaSocks(mixed, host) else directHost(host) }
        return samples.minOrNull()
    }

    private fun directHost(host: String): Int? = try {
        val addr = InetAddress.getByName(host)
        Socket().use { s ->
            val t = System.nanoTime()
            s.connect(InetSocketAddress(addr, 80), 2000)
            ((System.nanoTime() - t) / 1_000_000).toInt()
        }
    } catch (_: Exception) {
        null
    }

    private fun httpViaSocks(port: Int, host: String): Int? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 1000)
            s.soTimeout = 3000
            s.tcpNoDelay = true
            val out = s.getOutputStream()
            val inp = DataInputStream(s.getInputStream())
            out.write(byteArrayOf(5, 1, 0))
            ByteArray(2).also { inp.readFully(it) }
            val h = host.toByteArray()
            out.write(byteArrayOf(5, 1, 0, 3, h.size.toByte()) + h + byteArrayOf(0, 80))
            val head = ByteArray(4).also { inp.readFully(it) }
            if (head[1].toInt() != 0) return null
            // Добираем адрес из ответа SOCKS, чтобы дальше читать уже HTTP
            when (head[3].toInt()) {
                1 -> ByteArray(6).also { inp.readFully(it) }
                4 -> ByteArray(18).also { inp.readFully(it) }
                3 -> ByteArray(inp.readUnsignedByte() + 2).also { inp.readFully(it) }
            }
            val t = System.nanoTime()
            out.write("HEAD / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
            if (inp.read() < 0) return null
            ((System.nanoTime() - t) / 1_000_000).toInt()
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Пинг до сервера игры по тому же пути, что и игра: если VPN включён — через
     * его SOCKS-вход (CONNECT к серверу игры, ответ «отказано»/закрытие = один круг),
     * иначе напрямую (время TCP-ответа или отказа).
     */
    fun ping(ip: String): Int? {
        val mixed = BoxVpnService.ports?.mixed
        val samples = (1..3).mapNotNull { if (mixed != null) viaSocks(mixed, ip) else direct(ip) }
        return samples.minOrNull()
    }

    private fun direct(ip: String): Int? {
        val t = System.nanoTime()
        return try {
            Socket().use { it.connect(InetSocketAddress(ip, 443), 1500) }
            ((System.nanoTime() - t) / 1_000_000).toInt()
        } catch (e: ConnectException) {
            // Отказ (RST) пришёл от сервера — это тоже ровно один круг
            if (e.message?.contains("refused", true) == true) ((System.nanoTime() - t) / 1_000_000).toInt() else null
        } catch (_: Exception) {
            null
        }
    }

    private fun viaSocks(port: Int, ip: String): Int? {
        val addr = try { InetAddress.getByName(ip).address } catch (_: Exception) { return null }
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 1000)
                s.soTimeout = 2000
                s.tcpNoDelay = true
                val out = s.getOutputStream()
                val inp = DataInputStream(s.getInputStream())
                out.write(byteArrayOf(5, 1, 0))
                ByteArray(2).also { inp.readFully(it) }
                val t = System.nanoTime()
                out.write(byteArrayOf(5, 1, 0, 1) + addr + byteArrayOf(1, 0xBB.toByte()))
                val rep = ByteArray(2).also { inp.readFully(it) }
                val ms = ((System.nanoTime() - t) / 1_000_000).toInt()
                when (rep[1].toInt()) {
                    5 -> ms
                    0 -> {
                        if (ms >= 5) return ms
                        // Прокси ответил «успех» сразу — ждём, пока закроет после отказа сервера
                        try {
                            while (s.getInputStream().read() >= 0) { }
                        } catch (_: Exception) {
                            return null
                        }
                        ((System.nanoTime() - t) / 1_000_000).toInt()
                    }
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
