package dev.mlbb.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

/**
 * Автообновление по GitHub Releases. CI публикует релиз с тегом mlbb-v1.0.<versionCode>
 * и ассетом mlbb-overlay.apk в публичном репозитории BuildConfig.UPDATE_REPO.
 */
object Updater {
    data class Release(val versionCode: Int, val tag: String, val notes: String, val apkUrl: String)

    /** Возвращает свежий релиз, если он новее установленной версии, иначе null. */
    fun check(): Release? {
        val c = Net.open("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "mlbb-overlay/${BuildConfig.VERSION_NAME}")
        when (c.responseCode) {
            200 -> {}
            404 -> throw RuntimeException("релизов пока нет")
            403 -> throw RuntimeException("GitHub временно ограничил запросы, попробуй позже")
            else -> throw RuntimeException("GitHub HTTP ${c.responseCode}")
        }
        val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val tag = json.getString("tag_name")
        val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
        if (code <= BuildConfig.VERSION_CODE) return null
        val assets = json.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) {
                return Release(code, tag, cleanNotes(json.optString("body", "")), a.getString("browser_download_url"))
            }
        }
        return null
    }

    /** Убирает из описания релиза служебные строки коммита (соавторы, ссылки на сессии). */
    private fun cleanNotes(body: String): String =
        body.lines()
            .filterNot { line ->
                val l = line.trim()
                l.startsWith("Co-Authored-By:", ignoreCase = true) ||
                    l.startsWith("Claude-Session:", ignoreCase = true) ||
                    l.startsWith("Signed-off-by:", ignoreCase = true)
            }
            .joinToString("\n")
            .trim()

    fun download(ctx: Context, rel: Release, progress: (String) -> Unit): File {
        val c = Net.open(rel.apkUrl)
        c.instanceFollowRedirects = true
        if (c.responseCode != 200) throw RuntimeException("скачивание: HTTP ${c.responseCode}")
        val total = c.contentLengthLong
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "mlbb-overlay-${rel.versionCode}.apk")
        c.inputStream.use { input ->
            out.outputStream().use { o ->
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
