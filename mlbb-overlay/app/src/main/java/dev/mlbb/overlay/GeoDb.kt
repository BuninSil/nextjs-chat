package dev.mlbb.overlay

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.URL
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/** Офлайн-геолокация по DB-IP IP to City Lite (CC BY 4.0, https://db-ip.com). */
object GeoDb {
    private const val TAG = "GeoDb"
    private const val FILE_NAME = "dbip-city-lite.mmdb"

    data class Geo(val countryCode: String, val country: String, val city: String)

    @Volatile
    private var reader: MmdbReader? = null
    private val cache = ConcurrentHashMap<String, Geo>()
    private val NONE = Geo("", "", "")

    fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    val isLoaded get() = reader != null

    @Synchronized
    fun load(ctx: Context): Boolean {
        if (reader != null) return true
        val f = file(ctx)
        if (!f.exists()) return false
        return try {
            reader = MmdbReader(f)
            cache.clear()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open $f", e)
            false
        }
    }

    fun description(): String {
        val r = reader ?: return "не загружена"
        val built = (r.metadata["build_epoch"] as? Number)?.toLong()?.let {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(it * 1000))
        } ?: "?"
        return "${r.metadata["database_type"]} от $built"
    }

    fun lookup(ip: String): Geo? {
        val r = reader ?: return null
        cache[ip]?.let { return if (it === NONE) null else it }
        val geo = try {
            parse(r.lookup(InetAddress.getByName(ip)))
        } catch (e: Exception) {
            Log.w(TAG, "lookup $ip failed", e)
            null
        }
        cache[ip] = geo ?: NONE
        return geo
    }

    private fun name(node: Any?): String {
        val names = (node as? Map<*, *>)?.get("names") as? Map<*, *> ?: return ""
        return (names["ru"] ?: names["en"] ?: names.values.firstOrNull())?.toString() ?: ""
    }

    private fun parse(rec: Any?): Geo? {
        val m = rec as? Map<*, *> ?: return null
        val country = m["country"] as? Map<*, *>
        val code = country?.get("iso_code")?.toString() ?: return null
        return Geo(code, name(country), name(m["city"]))
    }

    fun flag(code: String): String {
        if (code.length != 2) return "🏳"
        val up = code.uppercase()
        return String(Character.toChars(0x1F1E6 + (up[0] - 'A'))) +
            String(Character.toChars(0x1F1E6 + (up[1] - 'A')))
    }

    /** Скачивает свежую базу с db-ip.com (пробует текущий и прошлый месяц). */
    fun download(ctx: Context, progress: (String) -> Unit) {
        val cal = Calendar.getInstance()
        var lastError: Exception? = null
        repeat(2) {
            val name = String.format(
                java.util.Locale.US, "dbip-city-lite-%04d-%02d.mmdb.gz",
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1
            )
            val url = URL("https://download.db-ip.com/free/$name")
            try {
                progress("Качаю $name…")
                val conn = Net.open(url.toString())
                if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                conn.inputStream.use { install(ctx, it, gzipped = true, total = total, progress = progress) }
                return
            } catch (e: Exception) {
                Log.w(TAG, "download $url failed", e)
                lastError = e
                cal.add(Calendar.MONTH, -1)
            }
        }
        throw lastError ?: RuntimeException("download failed")
    }

    /** Ставит базу из потока (.mmdb или .mmdb.gz — определяется по сигнатуре, если gzipped == null). */
    fun install(ctx: Context, input: InputStream, gzipped: Boolean? = null, total: Long = -1, progress: (String) -> Unit = {}) {
        val bin = BufferedInputStream(input, 64 * 1024)
        val isGz = gzipped ?: run {
            bin.mark(2)
            val a = bin.read()
            val b = bin.read()
            bin.reset()
            a == 0x1f && b == 0x8b
        }
        val counting = CountingStream(bin)
        val src: InputStream = if (isGz) GZIPInputStream(counting, 64 * 1024) else counting
        val tmp = File(ctx.filesDir, "$FILE_NAME.tmp")
        tmp.outputStream().use { out ->
            val chunk = ByteArray(64 * 1024)
            var lastReport = 0L
            while (true) {
                val n = src.read(chunk)
                if (n < 0) break
                out.write(chunk, 0, n)
                val now = System.currentTimeMillis()
                if (now - lastReport > 500) {
                    lastReport = now
                    val mb = counting.count / 1_048_576.0
                    progress(if (total > 0) String.format("%.1f / %.1f МБ", mb, total / 1_048_576.0) else String.format("%.1f МБ", mb))
                }
            }
        }
        MmdbReader(tmp) // валидация: бросит исключение, если файл битый
        synchronized(this) {
            reader = null
            val dst = file(ctx)
            dst.delete()
            if (!tmp.renameTo(dst)) throw RuntimeException("rename failed")
        }
        load(ctx)
        progress("База установлена: ${description()}")
    }

    private class CountingStream(private val inner: InputStream) : InputStream() {
        var count = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            inner.read(b, off, len).also { if (it > 0) count += it }
        override fun close() = inner.close()
    }
}
