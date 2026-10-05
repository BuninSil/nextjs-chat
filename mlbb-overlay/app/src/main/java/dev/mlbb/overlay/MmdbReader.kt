package dev.mlbb.overlay

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets

/**
 * Минимальный ридер формата MaxMind DB (.mmdb), в котором DB-IP раздаёт
 * свои Lite-базы. Без зависимостей, файл мапится в память.
 * Спецификация: https://maxmind.github.io/MaxMind-DB/
 */
class MmdbReader(file: File) {
    private val buf: ByteBuffer
    private val nodeCount: Long
    private val recordSize: Int
    private val ipVersion: Int
    private val treeSize: Long
    private val dataStart: Int
    private var ipv4Start = -1L

    val metadata: Map<*, *>

    init {
        buf = RandomAccessFile(file, "r").use { raf ->
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
        }
        val metaStart = findMetadataStart()
        metadata = Decoder(metaStart, metaStart).decode() as Map<*, *>
        nodeCount = (metadata["node_count"] as Number).toLong()
        recordSize = (metadata["record_size"] as Number).toInt()
        ipVersion = (metadata["ip_version"] as Number).toInt()
        require(recordSize == 24 || recordSize == 28 || recordSize == 32) { "Bad record size $recordSize" }
        treeSize = (recordSize * 2 / 8) * nodeCount
        dataStart = (treeSize + 16).toInt()
    }

    private fun findMetadataStart(): Int {
        val marker = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte()) +
            "MaxMind.com".toByteArray(StandardCharsets.US_ASCII)
        val limit = maxOf(0, buf.capacity() - 128 * 1024)
        var i = buf.capacity() - marker.size
        while (i >= limit) {
            var ok = true
            for (j in marker.indices) {
                if (buf.get(i + j) != marker[j]) { ok = false; break }
            }
            if (ok) return i + marker.size
            i--
        }
        throw IllegalArgumentException("Not a MaxMind DB file")
    }

    fun lookup(address: InetAddress): Any? {
        val bytes = address.address
        var node = if (bytes.size == 4 && ipVersion == 6) ipv4StartNode() else 0L
        if (bytes.size == 16 && ipVersion == 4) return null

        for (i in 0 until bytes.size * 8) {
            if (node >= nodeCount) break
            val bit = (bytes[i shr 3].toInt() shr (7 - (i and 7))) and 1
            node = readRecord(node, bit)
        }
        if (node == nodeCount) return null
        if (node < nodeCount) return null
        val offset = (node - nodeCount - 16).toInt()
        return Decoder(dataStart, dataStart + offset).decode()
    }

    private fun ipv4StartNode(): Long {
        if (ipv4Start >= 0) return ipv4Start
        var node = 0L
        var i = 0
        while (i < 96 && node < nodeCount) {
            node = readRecord(node, 0)
            i++
        }
        ipv4Start = node
        return node
    }

    private fun u8(pos: Long) = buf.get(pos.toInt()).toInt() and 0xFF

    private fun readRecord(node: Long, side: Int): Long {
        return when (recordSize) {
            24 -> {
                val p = node * 6 + side * 3
                (u8(p).toLong() shl 16) or (u8(p + 1).toLong() shl 8) or u8(p + 2).toLong()
            }
            28 -> {
                val p = node * 7
                if (side == 0) {
                    ((u8(p + 3).toLong() and 0xF0) shl 20) or (u8(p).toLong() shl 16) or
                        (u8(p + 1).toLong() shl 8) or u8(p + 2).toLong()
                } else {
                    ((u8(p + 3).toLong() and 0x0F) shl 24) or (u8(p + 4).toLong() shl 16) or
                        (u8(p + 5).toLong() shl 8) or u8(p + 6).toLong()
                }
            }
            else -> {
                val p = node * 8 + side * 4
                (u8(p).toLong() shl 24) or (u8(p + 1).toLong() shl 16) or
                    (u8(p + 2).toLong() shl 8) or u8(p + 3).toLong()
            }
        }
    }

    /** Декодер секции данных. [base] — начало секции, указатели считаются от него. */
    private inner class Decoder(private val base: Int, private var pos: Int) {
        private fun next(): Int = buf.get(pos++).toInt() and 0xFF

        private fun readUInt(size: Int): Long {
            var v = 0L
            repeat(size) { v = (v shl 8) or next().toLong() }
            return v
        }

        fun decode(): Any? {
            val ctrl = next()
            var type = ctrl ushr 5

            if (type == 1) { // pointer
                val ss = (ctrl ushr 3) and 0x3
                val vvv = (ctrl and 0x7).toLong()
                val ptr = when (ss) {
                    0 -> (vvv shl 8) or readUInt(1)
                    1 -> ((vvv shl 16) or readUInt(2)) + 2048
                    2 -> ((vvv shl 24) or readUInt(3)) + 526336
                    else -> readUInt(4)
                }
                return Decoder(base, base + ptr.toInt()).decode()
            }

            if (type == 0) type = 7 + next()

            var size = ctrl and 0x1F
            size = when (size) {
                29 -> 29 + next()
                30 -> 285 + readUInt(2).toInt()
                31 -> 65821 + readUInt(3).toInt()
                else -> size
            }

            return when (type) {
                2 -> { // utf8 string
                    val arr = ByteArray(size)
                    for (k in 0 until size) arr[k] = buf.get(pos + k)
                    pos += size
                    String(arr, StandardCharsets.UTF_8)
                }
                3 -> java.lang.Double.longBitsToDouble(readUInt(8))
                4 -> { pos += size; null } // bytes — не нужны
                5, 6, 9 -> readUInt(size)
                8 -> readUInt(size).toInt()
                10 -> { pos += size; null } // uint128 — не нужен
                7 -> {
                    val m = HashMap<String, Any?>(size * 2)
                    repeat(size) {
                        val k = decode() as String
                        m[k] = decode()
                    }
                    m
                }
                11 -> List(size) { decode() }
                14 -> size != 0
                15 -> java.lang.Float.intBitsToFloat(readUInt(4).toInt())
                else -> throw IllegalStateException("Unknown MMDB type $type")
            }
        }
    }
}
