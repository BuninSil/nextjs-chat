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
