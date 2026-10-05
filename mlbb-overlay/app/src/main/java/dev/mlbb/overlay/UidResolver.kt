package dev.mlbb.overlay

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress

/** Определяет, какому приложению (uid) принадлежит соединение из tun. */
class UidResolver(ctx: Context) {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)

    fun uid(proto: Int, srcIp: String, srcPort: Int, dstIp: String, dstPort: Int): Int {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                // Доступно активному VPN-приложению, то есть нам
                cm.getConnectionOwnerUid(
                    proto,
                    InetSocketAddress(InetAddress.getByName(srcIp), srcPort),
                    InetSocketAddress(InetAddress.getByName(dstIp), dstPort)
                )
            } else {
                fromProcNet(proto, srcPort)
            }
        } catch (e: Exception) {
            Process.INVALID_UID
        }
    }

    /** Android 8–9: /proc/net ещё читается обычным приложением. */
    private fun fromProcNet(proto: Int, srcPort: Int): Int {
        val base = if (proto == ConnTracker.PROTO_TCP) "tcp" else "udp"
        val portHex = String.format("%04X", srcPort)
        for (name in listOf(base, base + "6")) {
            val f = File("/proc/net/$name")
            if (!f.canRead()) continue
            f.useLines { lines ->
                for (line in lines.drop(1)) {
                    val cols = line.trim().split(Regex("\\s+"))
                    if (cols.size < 8) continue
                    if (cols[1].substringAfterLast(':') == portHex) return cols[7].toIntOrNull() ?: -1
                }
            }
        }
        return Process.INVALID_UID
    }
}
