package dev.mlbb.overlay

import java.io.File

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Постоянная история событий Fast VPN. Хранит структурированные события отдельно
 * от текстового applog.txt: переживает перезапуск процесса и телефона.
 */
object HistoryStore {
    private const val DB_NAME = "diagnostics.db"
    private const val DB_VERSION = 1
    private const val KEEP_DAYS = 7L
    private const val MAX_ESTIMATED_BYTES = 100L * 1024 * 1024
    private const val TRIM_TO_BYTES = 80L * 1024 * 1024
    @Volatile private var helper: Helper? = null
    private var lastPruneAt = 0L

    data class Event(val id: Long, val timeMs: Long, val category: String, val message: String)

    @Synchronized
    fun init(context: Context) {
        if (helper == null) helper = Helper(context.applicationContext)
        prune()
    }

    @Synchronized
    fun record(timeMs: Long, category: String, message: String) {
        val h = helper ?: return
        try {
            val values = ContentValues().apply {
                put("time_ms", timeMs)
                put("category", category)
                put("message", message)
            }
            h.writableDatabase.insert("events", null, values)
            if (timeMs - lastPruneAt >= 60_000L) {
                lastPruneAt = timeMs
                prune()
            }
        } catch (_: Exception) {
            // Диагностика никогда не должна мешать работе VPN.
        }
    }

    @Synchronized
    fun recent(limit: Int = 500): List<Event> {
        val db = helper?.readableDatabase ?: return emptyList()
        val result = ArrayList<Event>()
        try {
            db.rawQuery(
                "SELECT id,time_ms,category,message FROM events ORDER BY time_ms DESC,id DESC LIMIT ?",
                arrayOf(limit.coerceIn(1, 5000).toString())
            ).use { c ->
                while (c.moveToNext()) result.add(Event(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3)))
            }
        } catch (_: Exception) {}
        return result
    }

    /** Полная хронология за последние семь дней для ручной отправки. */
    @Synchronized
    fun exportText(maxChars: Int = 12_000_000): String {
        val db = helper?.readableDatabase ?: return ""
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 60L * 60L * 1000L
        val out = StringBuilder()
        try {
            db.rawQuery(
                "SELECT time_ms,category,message FROM events WHERE time_ms>=? ORDER BY time_ms ASC,id ASC",
                arrayOf(cutoff.toString())
            ).use { c ->
                while (c.moveToNext() && out.length < maxChars) {
                    val line = "${c.getLong(0)} [${c.getString(1)}] ${c.getString(2)}\n"
                    val remaining = maxChars - out.length
                    out.append(if (line.length <= remaining) line else line.take(remaining))
                }
            }
        } catch (_: Exception) {}
        return out.toString()
    }

    @Synchronized
    fun count(): Long = try {
        helper?.readableDatabase?.rawQuery("SELECT COUNT(*) FROM events", null)?.use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        } ?: 0L
    } catch (_: Exception) { 0L }

    @Synchronized
    fun databaseFile(context: Context): File = context.getDatabasePath(DB_NAME)

    @Synchronized
    fun checkpoint() {
        try { helper?.writableDatabase?.rawQuery("PRAGMA wal_checkpoint(FULL)", null)?.close() } catch (_: Exception) {}
    }

    @Synchronized
    fun clear() {
        try { helper?.writableDatabase?.delete("events", null, null) } catch (_: Exception) {}
    }

    @Synchronized
    private fun prune() {
        val db = helper?.writableDatabase ?: return
        try {
            lastPruneAt = System.currentTimeMillis()
            val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 60L * 60L * 1000L
            db.delete("events", "time_ms < ?", arrayOf(cutoff.toString()))
            var bytes = estimatedBytes(db)
            while (bytes > MAX_ESTIMATED_BYTES) {
                db.execSQL(
                    "DELETE FROM events WHERE id IN (SELECT id FROM events ORDER BY time_ms ASC,id ASC LIMIT 1000)"
                )
                val next = estimatedBytes(db)
                if (next >= bytes) break
                bytes = next
                if (bytes <= TRIM_TO_BYTES) break
            }
        } catch (_: Exception) {}
    }

    private fun estimatedBytes(db: SQLiteDatabase): Long = try {
        db.rawQuery("SELECT COALESCE(SUM(LENGTH(message)+LENGTH(category)+32),0) FROM events", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
    } catch (_: Exception) { 0L }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE events (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "time_ms INTEGER NOT NULL," +
                    "category TEXT NOT NULL," +
                    "message TEXT NOT NULL)"
            )
            db.execSQL("CREATE INDEX events_time_idx ON events(time_ms DESC,id DESC)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Versioned migrations are added here; never discard diagnostic history silently.
        }
    }
}
