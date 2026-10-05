package dev.mlbb.overlay

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Источник данных без своего VPN: через Shizuku читаем таблицу сокетов ядра
 * (/proc/net/udp, tcp…) и берём сокеты игры по её uid. Работает с любым
 * VPN-клиентом и без VPN — сеть мы не трогаем.
 */
object ShizukuSource {
    const val PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 4242

    data class Sock(
        val udp: Boolean,
        val localPort: Int,
        val remoteIp: String,
        val remotePort: Int,
        val inode: Long,
    )

    fun running(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun granted(): Boolean = running() && try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun requestPermission(onResult: (Boolean) -> Unit) {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(REQUEST_CODE)
    }

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ProcReaderService::class.java.name))
            .daemon(false)
            .processNameSuffix("procreader")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    @Volatile
    private var reader: IProcReader? = null
    private var connection: ServiceConnection? = null

    /** Поднимает сервис-читалку в процессе Shizuku. Вызывать НЕ с главного потока. */
    fun bind(): IProcReader? {
        reader?.let { if (it.asBinder().pingBinder()) return it }
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service != null && service.pingBinder()) reader = IProcReader.Stub.asInterface(service)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                reader = null
            }
        }
        connection = conn
        Handler(Looper.getMainLooper()).post {
            try {
                Shizuku.bindUserService(args, conn)
            } catch (_: Throwable) {
                latch.countDown()
            }
        }
        latch.await(10, TimeUnit.SECONDS)
        return reader
    }

    fun unbind() {
        val conn = connection ?: return
        connection = null
        reader = null
        Handler(Looper.getMainLooper()).post {
            try {
                Shizuku.unbindUserService(args, conn, true)
            } catch (_: Throwable) {
            }
        }
    }

    /** Сокеты приложения с данным uid, у которых есть удалённый адрес. */
    fun sockets(r: IProcReader, uid: Int): List<Sock> {
        val out = ArrayList<Sock>()
        for (name in listOf("udp", "udp6", "tcp", "tcp6")) {
            val text = r.readProcNet(name)
            if (text.startsWith("ERROR")) throw RuntimeException("не читается /proc/net/$name: $text")
            val udp = name.startsWith("udp")
            for (line in text.lineSequence().drop(1)) {
                val c = line.trim().split(Regex("\\s+"))
                if (c.size < 10) continue
                if (c[7].toIntOrNull() != uid) continue
                // TCP: только установленные соединения (state 01)
                if (!udp && c[3] != "01") continue
                val rem = c[2]
                val remotePort = rem.substringAfterLast(':').toIntOrNull(16) ?: continue
                if (remotePort == 0) continue
                val remoteIp = hexToIp(rem.substringBeforeLast(':')) ?: continue
                if (remoteIp == "0.0.0.0" || remoteIp == "::") continue
                val localPort = c[1].substringAfterLast(':').toIntOrNull(16) ?: 0
                out.add(Sock(udp, localPort, remoteIp, remotePort, c[9].toLongOrNull() ?: 0))
            }
        }
        return out
    }

    /** Адрес из /proc/net: 32-битные слова в порядке байт хоста (little-endian). */
    private fun hexToIp(hex: String): String? {
        val bytes = when (hex.length) {
            8 -> wordBytes(hex)
            32 -> (0 until 4).flatMap { wordBytes(hex.substring(it * 8, it * 8 + 8)).toList() }.toByteArray()
            else -> return null
        }
        if (bytes.size == 16) {
            val mapped = (0 until 10).all { bytes[it].toInt() == 0 } &&
                bytes[10].toInt() == -1 && bytes[11].toInt() == -1
            if (mapped) return bytes.copyOfRange(12, 16).joinToString(".") { (it.toInt() and 0xFF).toString() }
            if (bytes.all { it.toInt() == 0 }) return "::"
            return java.net.InetAddress.getByAddress(bytes).hostAddress
        }
        return bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
    }

    private fun wordBytes(word: String): ByteArray {
        val v = word.toLong(16)
        return byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
        )
    }
}
