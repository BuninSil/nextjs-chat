package dev.mlbb.overlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Перенос на новый телефон: все настройки, подписка, избранные/скрытые серверы и страны выхода —
 * одним файлом. Сохранить в файл или отправить себе, на новом телефоне — «Загрузить из файла».
 */
object Backup {
    private const val FORMAT = "fast-vpn-backup"

    /** Что относится к этому телефону и переносить нельзя (или бессмысленно). */
    private val SKIP = setOf(
        "hwid", "seenVersion", "rankAt", "rankNet", "rankGame", "trafDay", "trafBytes",
        "updNotes", "updNotesFor", "lastCrash", "subWarnDay", "autoSuppressNet", "v2", "subAt", "notif_asked",
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun subFile(ctx: Context) = File(ctx.filesDir, "subscription.txt")
    private fun infoFile(ctx: Context) = File(ctx.filesDir, "subscription-info.txt")

    fun build(ctx: Context): String {
        AppSettings.save(ctx)
        val p = JSONObject()
        for ((k, v) in prefs(ctx).all) {
            if (k in SKIP || v == null) continue
            val e = JSONObject()
            when (v) {
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is String -> e.put("t", "s").put("v", v)
                is Set<*> -> e.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            p.put(k, e)
        }
        val o = JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("app", BuildConfig.VERSION_NAME)
            .put("created", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
            .put("prefs", p)
        subFile(ctx).takeIf { it.exists() }?.let { o.put("subscription", it.readText()) }
        infoFile(ctx).takeIf { it.exists() }?.let { o.put("subInfo", it.readText()) }
        return o.toString(1)
    }

    private fun fileName() = "fast-vpn-settings-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".json"

    /** Записать в файл, который выбрал пользователь (CreateDocument). */
    fun saveTo(ctx: Context, uri: Uri) {
        val text = build(ctx)
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
        AppLog.ui("настройки сохранены в файл (${text.length} байт)")
    }

    fun suggestedName() = fileName()

    /** Отправить файл себе — в Telegram «Избранное», на почту, в облако. */
    fun share(a: Activity) {
        val dir = File(a.cacheDir, "backup").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, fileName()).apply { writeText(build(a)) }
        AppLog.ui("отправка файла настроек")
        val uri = FileProvider.getUriForFile(a, "${a.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Fast VPN — настройки")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(Intent.createChooser(send, "Отправить себе файл настроек"))
    }

    class Parsed(val json: JSONObject, val created: String, val app: String, val servers: Int)

    /** Прочитать и проверить файл. Бросает исключение с понятным текстом, если это не наш файл. */
    fun read(ctx: Context, uri: Uri): Parsed {
        val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }.toString(Charsets.UTF_8)
        val o = try { JSONObject(text) } catch (_: Exception) { throw RuntimeException("Это не файл настроек Fast VPN") }
        if (o.optString("format") != FORMAT) throw RuntimeException("Это не файл настроек Fast VPN")
        if (o.optInt("version") > 1) throw RuntimeException("Файл из более новой версии приложения — сначала обнови Fast VPN")
        val servers = o.optString("subscription").takeIf { it.isNotBlank() }
            ?.let { try { Subscription.parseAll(it).size } catch (_: Exception) { 0 } } ?: 0
        return Parsed(o, o.optString("created"), o.optString("app"), servers)
    }

    /** Применить: VPN выключается, настройки и подписка заменяются. */
    fun apply(ctx: Context, b: Parsed) {
        if (BoxVpnService.isRunning || BoxVpnService.isStarting) BoxVpnService.stop(ctx, manual = false)
        val o = b.json
        val p = o.getJSONObject("prefs")
        val ed = prefs(ctx).edit()
        // Всё, что переносится, заменяем целиком; то, что относится к этому телефону, не трогаем
        for (k in prefs(ctx).all.keys) if (k !in SKIP) ed.remove(k)
        for (k in p.keys()) {
            if (k in SKIP) continue
            val e = p.getJSONObject(k)
            when (e.optString("t")) {
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "s" -> ed.putString(k, e.getString("v"))
                "set" -> {
                    val a = e.getJSONArray("v")
                    ed.putStringSet(k, HashSet((0 until a.length()).map { a.getString(it) }))
                }
            }
        }
        // Страны выхода и замеры привязаны к серверам — перепроверим на новом месте
        ed.remove("rankAt")
        ed.commit()

        o.optString("subscription").takeIf { it.isNotBlank() }?.let { subFile(ctx).writeText(it) } ?: subFile(ctx).delete()
        o.optString("subInfo").takeIf { it.isNotBlank() }?.let { infoFile(ctx).writeText(it) } ?: infoFile(ctx).delete()

        AppSettings.load(ctx)
        Subscription.reload(ctx)
        ServerTester.reload(ctx)
        AutoConnect.arm(ctx)
        AutoUpdate.schedule(ctx)
        Widget.update(ctx)
        AppLog.ui("настройки загружены из файла (от ${b.created}, версия ${b.app}, серверов ${b.servers})")
    }
}
