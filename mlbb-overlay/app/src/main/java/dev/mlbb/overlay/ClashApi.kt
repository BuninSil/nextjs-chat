package dev.mlbb.overlay

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Clash API (external controller) VPN-клиента: Karing, Hiddify (sing-box),
 * FlClash / Clash Meta (mihomo). Отдаёт список всех соединений клиента —
 * так мы видим трафик игры, не поднимая свой VPN.
 */
object ClashApi {
    private const val HOST = "127.0.0.1"

    /** Частые порты контроллеров: mihomo 9090/9097, sing-box-клиенты и т.п. */
    private val KNOWN_PORTS = listOf(9090, 9097, 9091, 9095, 9999, 3057, 3058, 16756, 6756, 7890, 8090, 9093)

    enum class Probe { OK, NEED_SECRET, NO }

    data class Conn(
        val id: String,
        val udp: Boolean,
        val dstIp: String,
        val dstPort: Int,
        val srcPort: Int,
        val upload: Long,
        val download: Long,
        val startMs: Long,
        val isGame: Boolean?, // null — клиент не сообщает, чьё это соединение
    )

    private fun open(port: Int, path: String, secret: String, timeout: Int): HttpURLConnection {
        // Proxy.NO_PROXY: запрос идёт прямо на loopback, мимо любых прокси
        val c = URL("http://$HOST:$port$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        c.connectTimeout = timeout
        c.readTimeout = timeout
        if (secret.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $secret")
        return c
    }

    /**
     * Сколько всего скачано и отдано через ядро (байты): (вниз, вверх).
     * Ядро отдаёт поля по алфавиту — счётчики идут после списка соединений, поэтому читаем ответ целиком.
     */
    fun totals(port: Int, secret: String): Pair<Long, Long>? = try {
        val c = open(port, "/connections", secret, 1500)
        val head = c.inputStream.bufferedReader().use { it.readText() }
        val down = Regex("\"downloadTotal\":(\\d+)").find(head)?.groupValues?.get(1)?.toLongOrNull()
        val up = Regex("\"uploadTotal\":(\\d+)").find(head)?.groupValues?.get(1)?.toLongOrNull()
        if (down != null && up != null) down to up else null
    } catch (_: Exception) {
        null
    }

    /** Отчёт по портам для диагностики, если ничего не нашлось. */
    @Volatile
    var lastReport: String = ""
        private set

    private fun get(port: Int, path: String, secret: String): Pair<Int, String>? = try {
        val c = open(port, path, secret, 600)
        val code = c.responseCode
        val body = try {
            (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText().take(300) } ?: ""
        } catch (_: Exception) {
            ""
        }
        code to body
    } catch (_: Exception) {
        null
    }

    fun probe(port: Int, secret: String): Probe {
        // Список соединений — то, что нам реально нужно
        val conn = get(port, "/connections", secret)
        if (conn != null) {
            if (conn.first == 200 && conn.second.contains("connections")) return Probe.OK
            if (conn.first == 401 || conn.first == 403) return Probe.NEED_SECRET
        }
        val ver = get(port, "/version", secret) ?: return Probe.NO
        return when {
            ver.first == 401 || ver.first == 403 -> Probe.NEED_SECRET
            else -> Probe.NO
        }
    }

    private fun describe(port: Int, secret: String): String {
        val root = get(port, "/", secret) ?: return "$port: не HTTP"
        val conns = get(port, "/connections", secret)
        val body = root.second.replace(Regex("\\s+"), " ").take(60)
        return "$port: HTTP ${root.first} «$body»" + (conns?.let { ", /connections → ${it.first}" } ?: "")
    }

    /** Возвращает (порт, результат проверки) или null. */
    fun find(savedPort: Int, secret: String, progress: (String) -> Unit): Pair<Int, Probe>? {
        progress("Ищу Clash API VPN-клиента…")
        for (p in listOf(savedPort) + KNOWN_PORTS) {
            if (p <= 0) continue
            val r = probe(p, secret)
            if (r != Probe.NO) return p to r
        }

        progress("Сканирую локальные порты, пара секунд…")
        val pool = Executors.newFixedThreadPool(64)
        val open = ConcurrentLinkedQueue<Int>()
        for (p in 1024..65535) {
            pool.execute {
                try {
                    Socket().use { it.connect(InetSocketAddress(HOST, p), 200) }
                    open.add(p)
                } catch (_: Exception) {
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(60, TimeUnit.SECONDS)

        progress("Открытых портов: ${open.size}, проверяю каждый…")
        var needSecret: Int? = null
        for (p in open.sorted()) {
            when (probe(p, secret)) {
                Probe.OK -> return p to Probe.OK
                Probe.NEED_SECRET -> if (needSecret == null) needSecret = p
                Probe.NO -> {}
            }
        }
        if (needSecret == null) {
            lastReport = if (open.isEmpty()) "Открытых локальных портов нет."
            else "Открытые локальные порты:\n" + open.sorted().take(15).joinToString("\n") { describe(it, secret) }
        }
        return needSecret?.let { it to Probe.NEED_SECRET }
    }

    private val isoFormats = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX"
    )

    private fun parseTime(s: String): Long {
        // "2026-10-05T19:00:00.123456789+03:00" — обрезаем доли до миллисекунд
        val norm = s.replace(Regex("""(\.\d{3})\d+"""), "$1")
        for (f in isoFormats) {
            try {
                return SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(norm)!!.time
            } catch (_: Exception) {
            }
        }
        return System.currentTimeMillis()
    }

    /** Задержка через конкретный сервер (мс) или null, если не прошло. */
    fun delay(port: Int, secret: String, tag: String, url: String, timeoutMs: Int): Int? = try {
        val q = "/proxies/" + java.net.URLEncoder.encode(tag, "UTF-8") +
            "/delay?timeout=$timeoutMs&url=" + java.net.URLEncoder.encode(url, "UTF-8")
        val c = open(port, q, secret, timeoutMs + 1500)
        if (c.responseCode != 200) null
        else JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optInt("delay", -1).takeIf { it > 0 }
    } catch (_: Exception) {
        null
    }

    /** Переключает селектор group на сервер tag. */
    fun select(port: Int, secret: String, group: String, tag: String): Boolean = try {
        val c = open(port, "/proxies/" + java.net.URLEncoder.encode(group, "UTF-8"), secret, 3000)
        c.requestMethod = "PUT"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(JSONObject().put("name", tag).toString().toByteArray()) }
        c.responseCode in 200..299
    } catch (_: Exception) {
        false
    }

    /** Текущие соединения клиента. Бросает исключение, если контроллер недоступен. */
    fun connections(port: Int, secret: String, gamePackage: String): List<Conn> {
        val c = open(port, "/connections", secret, 1500)
        if (c.responseCode != 200) throw RuntimeException("Clash API: HTTP ${c.responseCode}")
        val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val arr = json.optJSONArray("connections") ?: return emptyList()
        val out = ArrayList<Conn>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val m = o.optJSONObject("metadata") ?: continue
            val dst = m.optString("destinationIP").ifEmpty { m.optString("host") }
            if (dst.isEmpty()) continue

            // Имя приложения у разных ядер лежит в разных полях — ищем пакет игры во всех
            var hasProcessInfo = false
            var game = false
            for (key in listOf("process", "processPath", "processName", "package", "packageName", "uid")) {
                val v = m.optString(key)
                if (v.isNotEmpty()) {
                    hasProcessInfo = true
                    if (v.contains(gamePackage)) game = true
                }
            }

            out.add(
                Conn(
                    id = o.optString("id"),
                    udp = m.optString("network").equals("udp", true),
                    dstIp = dst,
                    dstPort = m.optString("destinationPort").toIntOrNull() ?: 0,
                    srcPort = m.optString("sourcePort").toIntOrNull() ?: 0,
                    upload = o.optLong("upload"),
                    download = o.optLong("download"),
                    startMs = parseTime(o.optString("start")),
                    isGame = if (hasProcessInfo) game else null,
                )
            )
        }
        return out
    }
}
