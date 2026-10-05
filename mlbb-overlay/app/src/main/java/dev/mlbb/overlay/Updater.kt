package dev.mlbb.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File

/**
 * Автообновление по GitHub Releases. CI публикует релиз с тегом mlbb-v1.0.<versionCode>
 * и ассетом mlbb-overlay.apk в публичном репозитории BuildConfig.UPDATE_REPO.
 */
object Updater {
    /** apkSize — размер APK из релиза: по нему узнаём, что файл уже скачан целиком. */
    data class Release(val versionCode: Int, val tag: String, val notes: String, val apkUrl: String, val apkSize: Long = -1)

    /**
     * Возвращает свежий релиз, если он новее установленной версии, иначе null.
     * В описание попадают изменения всех версий, вышедших после установленной, —
     * чтобы при прыжке через несколько версий было видно всё, что поменялось.
     */
    fun check(): Release? {
        val c = Net.open("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=40")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "mlbb-overlay/${BuildConfig.VERSION_NAME}")
        when (c.responseCode) {
            200 -> {}
            404 -> throw RuntimeException("релизов пока нет")
            403 -> throw RuntimeException("GitHub временно ограничил запросы, попробуй позже")
            else -> throw RuntimeException("GitHub HTTP ${c.responseCode}")
        }
        val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })

        class Item(val code: Int, val tag: String, val sha: String, val body: String, val apk: String, val size: Long)
        val newer = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val tag = r.optString("tag_name")
            if (!tag.startsWith("mlbb-v")) continue
            val code = tag.substringAfterLast('.').toIntOrNull() ?: continue
            if (code <= BuildConfig.VERSION_CODE) continue
            val sha = r.optString("target_commitish")
            // Тот же код, что уже установлен (собран повторно) — не обновление
            if (BuildConfig.GIT_SHA.isNotEmpty() && sha == BuildConfig.GIT_SHA) continue
            val assets = r.optJSONArray("assets") ?: continue
            var apk = ""
            var size = -1L
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                if (a.getString("name").endsWith(".apk")) {
                    apk = a.getString("browser_download_url")
                    size = a.optLong("size", -1)
                }
            }
            if (apk.isEmpty()) continue
            newer.add(Item(code, tag, sha, r.optString("body", ""), apk, size))
        }
        if (newer.isEmpty()) return null
        // Один коммит часто собирается дважды (ветка и main) — оставляем по одному релизу на коммит
        val versions = newer.sortedByDescending { it.code }.distinctBy { it.sha.ifEmpty { it.tag } }
        val latest = versions.first()
        val notes = if (versions.size == 1) cleanNotes(latest.body)
        else versions.joinToString("\n\n") { v ->
            "Версия ${v.tag.removePrefix("mlbb-v")}\n" + cleanNotes(v.body).ifBlank { "Исправления и улучшения." }
        }
        return Release(latest.code, latest.tag, notes, latest.apk, latest.size)
    }

    /**
     * Приводит описание релиза (текст коммита) к читаемому виду: убирает служебные строки
     * и склеивает строки, перенесённые посреди фразы; пункты «- …» превращает в «• …».
     */
    private fun cleanNotes(body: String): String {
        val out = ArrayList<StringBuilder>()
        var paragraphBreak = true
        for (raw in body.lines()) {
            val l = raw.trim()
            if (l.startsWith("Co-Authored-By:", true) || l.startsWith("Claude-Session:", true) ||
                l.startsWith("Signed-off-by:", true)
            ) continue
            when {
                l.isEmpty() -> paragraphBreak = true
                l.startsWith("- ") || l.startsWith("* ") || l.startsWith("• ") -> {
                    out.add(StringBuilder("• ").append(l.substring(2).trim()))
                    paragraphBreak = false
                }
                paragraphBreak || out.isEmpty() -> {
                    if (out.isNotEmpty()) out.add(StringBuilder())
                    out.add(StringBuilder(l))
                    paragraphBreak = false
                }
                else -> out.last().append(' ').append(l)
            }
        }
        return out.joinToString("\n") { it.toString() }.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "updates").apply { mkdirs() }

    /** Удаляет скачанные APK, которые уже не нужны (версия установлена или устарела). */
    fun cleanup(ctx: Context, keepCode: Int = -1) {
        dir(ctx).listFiles()?.forEach { f ->
            val code = f.name.removePrefix("mlbb-overlay-").substringBefore('.').toIntOrNull()
            if (code == null || code != keepCode) f.delete()
        }
    }

    /**
     * Скачивает APK. Если эта версия уже скачана целиком (размер совпал с релизом) — берёт её,
     * не качая заново. Качает во временный файл и переименовывает только в конце,
     * чтобы оборванная загрузка не считалась готовой.
     */
    fun download(ctx: Context, rel: Release, progress: (String) -> Unit): File {
        val out = File(dir(ctx), "mlbb-overlay-${rel.versionCode}.apk")
        cleanup(ctx, keepCode = rel.versionCode)
        if (out.exists() && rel.apkSize > 0 && out.length() == rel.apkSize) return out
        out.delete()
        val c = Net.open(rel.apkUrl)
        c.instanceFollowRedirects = true
        if (c.responseCode != 200) throw RuntimeException("скачивание: HTTP ${c.responseCode}")
        val total = c.contentLengthLong
        val part = File(dir(ctx), "mlbb-overlay-${rel.versionCode}.part")
        c.inputStream.use { input ->
            part.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - last > 300) {
                        last = now
                        progress(if (total > 0) "Обновление: ${done * 100 / total}%" else "Обновление: ${done / 1024} КБ")
                    }
                }
            }
        }
        if (total > 0 && part.length() != total) {
            part.delete()
            throw RuntimeException("загрузка оборвалась, попробуй ещё раз")
        }
        if (!part.renameTo(out)) throw RuntimeException("не удалось сохранить файл обновления")
        return out
    }

    /** true — установщик открыт; false — сначала нужно разрешить установку из этого приложения. */
    fun install(ctx: Context, apk: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
