package dev.mlbb.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Автообновление по GitHub Releases. CI публикует релиз с тегом mlbb-v1.0.<versionCode>
 * и ассетом mlbb-overlay.apk. Для приватного репозитория нужен токен с правом чтения.
 */
object Updater {
    data class Release(val versionCode: Int, val tag: String, val notes: String, val apiAssetUrl: String, val browserUrl: String)

    private fun open(url: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.setRequestProperty("User-Agent", "mlbb-overlay/${BuildConfig.VERSION_NAME}")
        val token = AppSettings.updateToken.trim()
        if (token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    /** Возвращает свежий релиз, если он новее установленной версии, иначе null. */
    fun check(): Release? {
        val repo = AppSettings.updateRepo.trim()
        val c = open("https://api.github.com/repos/$repo/releases/latest", "application/vnd.github+json")
        when (c.responseCode) {
            200 -> {}
            404 -> throw RuntimeException(
                if (AppSettings.updateToken.isBlank()) "релизы не найдены (репозиторий приватный? укажи токен)"
                else "релизы не найдены"
            )
            401, 403 -> throw RuntimeException("GitHub отказал (${c.responseCode}): проверь токен или лимит запросов")
            else -> throw RuntimeException("GitHub HTTP ${c.responseCode}")
        }
        val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val tag = json.getString("tag_name")
        val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
        val assets = json.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) {
                val rel = Release(code, tag, json.optString("body", ""), a.getString("url"), a.getString("browser_download_url"))
                return if (code > BuildConfig.VERSION_CODE) rel else null
            }
        }
        return null
    }

    fun download(ctx: Context, rel: Release, progress: (String) -> Unit): File {
        // Через API-URL ассета работает и для приватных репо (с токеном), и для публичных
        val c = open(rel.apiAssetUrl, "application/octet-stream")
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
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
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
