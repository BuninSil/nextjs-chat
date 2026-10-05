package dev.mlbb.overlay

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

/**
 * Второе ядро — Xray — для серверов с транспортом XHTTP, которого нет в sing-box.
 * Запускается отдельной программой (libxray.so из jniLibs, собран CI из исходников Xray-core).
 * На каждый XHTTP-сервер — свой локальный SOCKS-вход; sing-box отдаёт туда трафик этого сервера.
 * Наше приложение исключено из туннеля, поэтому соединения Xray идут напрямую, без петли.
 */
object XrayCore {
    private const val TAG = "Xray"
    /** Транспорты, которые умеет только Xray */
    val TRANSPORTS = setOf("xhttp", "splithttp")

    private var process: Process? = null

    fun binary(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libxray.so")
    fun available(ctx: Context) = binary(ctx).exists()

    private fun dec(s: String?) = if (s == null) "" else URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

    /** Ссылка vless:// или trojan:// с XHTTP -> outbound Xray. */
    fun outbound(link: String, tag: String): JSONObject? {
        val uri = Uri.parse(link)
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val user = dec(uri.encodedUserInfo)
        val q: (String) -> String? = { uri.getQueryParameter(it)?.takeIf { v -> v.isNotEmpty() } }

        val out = JSONObject().put("tag", tag)
        when (scheme) {
            "vless" -> out.put("protocol", "vless").put("settings", JSONObject().put("vnext", JSONArray().put(
                JSONObject().put("address", host).put("port", port).put("users", JSONArray().put(
                    JSONObject().put("id", user).put("encryption", q("encryption") ?: "none").apply {
                        q("flow")?.let { put("flow", it) }
                    }
                ))
            )))
            "trojan" -> out.put("protocol", "trojan").put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", host).put("port", port).put("password", user)
            )))
            else -> return null
        }

        val security = q("security") ?: if (scheme == "trojan") "tls" else "none"
        val stream = JSONObject().put("network", "xhttp").put("security", security)
        val sni = q("sni") ?: q("peer") ?: host
        when (security) {
            "reality" -> stream.put("realitySettings", JSONObject()
                .put("serverName", sni)
                .put("fingerprint", q("fp") ?: "chrome")
                .put("publicKey", q("pbk") ?: "")
                .put("shortId", q("sid") ?: "")
                .put("spiderX", q("spx") ?: ""))
            "tls" -> stream.put("tlsSettings", JSONObject()
                .put("serverName", sni)
                .put("fingerprint", q("fp") ?: "chrome")
                .apply {
                    q("alpn")?.let { put("alpn", JSONArray(it.split(','))) }
                    if (q("allowInsecure") == "1" || q("insecure") == "1") put("allowInsecure", true)
                })
        }
        val xhttp = JSONObject().put("path", q("path") ?: "/").put("mode", q("mode") ?: "auto")
        q("host")?.let { xhttp.put("host", it) }
        q("extra")?.let { e -> try { xhttp.put("extra", JSONObject(e)) } catch (_: Exception) {} }
        stream.put("xhttpSettings", xhttp)
        out.put("streamSettings", stream)
        return out
    }

    /** Запускает Xray для XHTTP-серверов: tag -> (ссылка, локальный порт). */
    @Synchronized
    fun start(ctx: Context, nodes: Map<String, Pair<String, Int>>) {
        stop()
        if (nodes.isEmpty() || !available(ctx)) return
        val inbounds = JSONArray()
        val outbounds = JSONArray()
        val rules = JSONArray()
        for ((tag, v) in nodes) {
            val ob = outbound(v.first, tag) ?: continue
            outbounds.put(ob)
            inbounds.put(JSONObject().put("tag", "in-$tag").put("listen", "127.0.0.1").put("port", v.second)
                .put("protocol", "socks").put("settings", JSONObject().put("udp", true).put("auth", "noauth")))
            rules.put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put("in-$tag")).put("outboundTag", tag))
        }
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        val config = JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("routing", JSONObject().put("rules", rules))
        val file = File(ctx.filesDir, "xray.json").apply { writeText(config.toString()) }

        val p = ProcessBuilder(binary(ctx).path, "run", "-c", file.path)
            .directory(ctx.filesDir)
            .redirectErrorStream(true)
            .start()
        process = p
        Thread({
            try {
                p.inputStream.bufferedReader().forEachLine {
                    Log.i(TAG, it)
                    VpnLog.add("xray: $it")
                }
            } catch (_: Exception) {
            }
        }, "xray-log").start()
    }

    @Synchronized
    fun stop() {
        process?.let {
            try { it.destroy() } catch (_: Exception) {}
        }
        process = null
    }
}
