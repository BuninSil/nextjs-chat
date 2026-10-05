package dev.mlbb.overlay

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

/**
 * Подписка: скачивание по ссылке и разбор ссылок серверов
 * (vless, trojan, vmess, ss, hysteria2, tuic) в outbound'ы sing-box.
 */
object Subscription {
    data class Node(
        val tag: String,
        val name: String,
        val type: String,
        val server: String,
        val port: Int,
        /** true — протокол с настоящим UDP (лучше для игры), false — UDP поверх TCP */
        val nativeUdp: Boolean,
        val outbound: JSONObject,
    )

    data class Info(val upload: Long, val download: Long, val total: Long, val expireSec: Long)

    @Volatile
    var nodes: List<Node> = emptyList()
        private set

    @Volatile
    var info: Info? = null
        private set

    private fun file(ctx: Context) = File(ctx.filesDir, "subscription.txt")
    private fun infoFile(ctx: Context) = File(ctx.filesDir, "subscription-info.txt")

    fun load(ctx: Context) {
        val f = file(ctx)
        if (f.exists()) nodes = parseAll(f.readText())
        infoFile(ctx).takeIf { it.exists() }?.readText()?.let { info = parseUserInfo(it) }
    }

    /** Скачивает подписку. Бросает исключение с понятным текстом. */
    fun update(ctx: Context, url: String): Int {
        val c = Net.open(url.trim())
        // Панели отдают формат по User-Agent; v2rayNG-совместимый — список ссылок в base64
        c.setRequestProperty("User-Agent", "v2rayNG/1.9.0")
        val code = c.responseCode
        if (code != 200) throw RuntimeException("сервер подписки ответил HTTP $code")
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val parsed = parseAll(body)
        if (parsed.isEmpty()) throw RuntimeException("в подписке не нашлось серверов, которые я умею читать")
        file(ctx).writeText(body)
        c.getHeaderField("subscription-userinfo")?.let {
            infoFile(ctx).writeText(it)
            info = parseUserInfo(it)
        }
        nodes = parsed
        return parsed.size
    }

    private fun parseUserInfo(s: String): Info {
        val m = s.split(';').mapNotNull {
            val kv = it.trim().split('=', limit = 2)
            if (kv.size == 2) kv[0].trim() to (kv[1].trim().toLongOrNull() ?: 0L) else null
        }.toMap()
        return Info(m["upload"] ?: 0, m["download"] ?: 0, m["total"] ?: 0, m["expire"] ?: 0)
    }

    private fun b64(s: String): String {
        val clean = s.trim().replace("\n", "").replace("\r", "")
        for (flags in listOf(Base64.DEFAULT, Base64.URL_SAFE)) {
            try {
                return String(Base64.decode(clean, flags or Base64.NO_WRAP), Charsets.UTF_8)
            } catch (_: Exception) {
            }
        }
        throw IllegalArgumentException("not base64")
    }

    fun parseAll(body: String): List<Node> {
        val text = if (body.contains("://")) body else try {
            b64(body)
        } catch (_: Exception) {
            body
        }
        val out = ArrayList<Node>()
        for (line in text.lines()) {
            val l = line.trim()
            if (l.isEmpty()) continue
            try {
                parse(l, "n${out.size}")?.let { out.add(it) }
            } catch (_: Exception) {
                // кривые строки пропускаем, остальные серверы важнее
            }
        }
        return out
    }

    private fun dec(s: String?): String = if (s == null) "" else URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

    private fun tls(q: (String) -> String?, server: String, defaultOn: Boolean): JSONObject? {
        val security = q("security") ?: if (defaultOn) "tls" else "none"
        if (security == "none" || security.isEmpty()) return null
        val t = JSONObject().put("enabled", true)
        t.put("server_name", q("sni")?.takeIf { it.isNotEmpty() } ?: q("peer") ?: server)
        if (q("allowInsecure") == "1" || q("insecure") == "1") t.put("insecure", true)
        q("alpn")?.takeIf { it.isNotEmpty() }?.let { t.put("alpn", JSONArray(it.split(','))) }
        val fp = q("fp")?.takeIf { it.isNotEmpty() }
        if (security == "reality") {
            t.put("reality", JSONObject().put("enabled", true)
                .put("public_key", q("pbk") ?: "")
                .put("short_id", q("sid") ?: ""))
            t.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp ?: "chrome"))
        } else if (fp != null) {
            t.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
        }
        return t
    }

    private fun transport(type: String?, q: (String) -> String?): JSONObject? = when (type) {
        "grpc" -> JSONObject().put("type", "grpc").put("service_name", q("serviceName") ?: "")
        "ws" -> JSONObject().put("type", "ws").put("path", q("path") ?: "/").apply {
            q("host")?.takeIf { it.isNotEmpty() }?.let { put("headers", JSONObject().put("Host", it)) }
        }
        "http", "h2" -> JSONObject().put("type", "http").put("path", q("path") ?: "/").apply {
            q("host")?.takeIf { it.isNotEmpty() }?.let { put("host", JSONArray(it.split(','))) }
        }
        "httpupgrade" -> JSONObject().put("type", "httpupgrade").put("path", q("path") ?: "/").apply {
            q("host")?.takeIf { it.isNotEmpty() }?.let { put("host", it) }
        }
        else -> null // tcp
    }

    private fun parse(link: String, tag: String): Node? {
        val scheme = link.substringBefore("://").lowercase()
        if (scheme == "vmess") return parseVmess(link, tag)
        if (scheme == "ss") return parseSs(link, tag)

        val uri = Uri.parse(link)
        val name = dec(uri.fragment ?: uri.encodedFragment).ifEmpty { uri.host ?: tag }
        val server = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val user = dec(uri.encodedUserInfo)
        val q: (String) -> String? = { uri.getQueryParameter(it) }
        val o = JSONObject().put("tag", tag).put("server", server).put("server_port", port)

        return when (scheme) {
            "vless" -> {
                o.put("type", "vless").put("uuid", user)
                q("flow")?.takeIf { it.isNotEmpty() }?.let { o.put("flow", it) }
                tls(q, server, false)?.let { o.put("tls", it) }
                transport(q("type"), q)?.let { o.put("transport", it) }
                o.put("packet_encoding", "xudp")
                Node(tag, name, "vless", server, port, false, o)
            }
            "trojan" -> {
                o.put("type", "trojan").put("password", user)
                tls(q, server, true)?.let { o.put("tls", it) }
                transport(q("type"), q)?.let { o.put("transport", it) }
                Node(tag, name, "trojan", server, port, false, o)
            }
            "hysteria2", "hy2" -> {
                o.put("type", "hysteria2").put("password", user)
                val t = JSONObject().put("enabled", true).put("server_name", q("sni") ?: server)
                if (q("insecure") == "1") t.put("insecure", true)
                o.put("tls", t)
                q("obfs")?.takeIf { it.isNotEmpty() }?.let {
                    o.put("obfs", JSONObject().put("type", it).put("password", q("obfs-password") ?: ""))
                }
                Node(tag, name, "hysteria2", server, port, true, o)
            }
            "tuic" -> {
                o.put("type", "tuic").put("uuid", user.substringBefore(':')).put("password", user.substringAfter(':', ""))
                q("congestion_control")?.let { o.put("congestion_control", it) }
                val t = JSONObject().put("enabled", true).put("server_name", q("sni") ?: server)
                q("alpn")?.let { t.put("alpn", JSONArray(it.split(','))) }
                if (q("allow_insecure") == "1" || q("insecure") == "1") t.put("insecure", true)
                o.put("tls", t)
                Node(tag, name, "tuic", server, port, true, o)
            }
            else -> null
        }
    }

    private fun parseVmess(link: String, tag: String): Node? {
        val j = JSONObject(b64(link.substringAfter("://")))
        val server = j.optString("add").ifEmpty { return null }
        val port = j.optString("port").toIntOrNull() ?: 443
        val params = mapOf(
            "security" to (if (j.optString("tls") == "tls") "tls" else "none"),
            "sni" to j.optString("sni"), "fp" to j.optString("fp"), "alpn" to j.optString("alpn"),
            "serviceName" to j.optString("path"), "path" to j.optString("path"), "host" to j.optString("host"),
        )
        val q: (String) -> String? = { params[it]?.takeIf { v -> v.isNotEmpty() } }
        val o = JSONObject().put("tag", tag).put("type", "vmess").put("server", server).put("server_port", port)
            .put("uuid", j.optString("id")).put("alter_id", j.optString("aid").toIntOrNull() ?: 0)
            .put("security", j.optString("scy").ifEmpty { "auto" })
        tls(q, server, false)?.let { o.put("tls", it) }
        transport(j.optString("net"), q)?.let { o.put("transport", it) }
        o.put("packet_encoding", "xudp")
        return Node(tag, j.optString("ps").ifEmpty { server }, "vmess", server, port, false, o)
    }

    private fun parseSs(link: String, tag: String): Node? {
        val body = link.substringAfter("://")
        val name = dec(body.substringAfter('#', "")).ifEmpty { tag }
        val main = body.substringBefore('#').substringBefore('?')
        val userInfo: String
        val hostPort: String
        if (main.contains('@')) {
            val raw = main.substringBeforeLast('@')
            userInfo = try { b64(raw) } catch (_: Exception) { dec(raw) }
            hostPort = main.substringAfterLast('@')
        } else {
            val full = b64(main)
            userInfo = full.substringBeforeLast('@')
            hostPort = full.substringAfterLast('@')
        }
        val server = hostPort.substringBeforeLast(':').trim('[', ']')
        val port = hostPort.substringAfterLast(':').toIntOrNull() ?: return null
        val o = JSONObject().put("tag", tag).put("type", "shadowsocks").put("server", server).put("server_port", port)
            .put("method", userInfo.substringBefore(':')).put("password", userInfo.substringAfter(':'))
        return Node(tag, name, "ss", server, port, true, o)
    }
}
