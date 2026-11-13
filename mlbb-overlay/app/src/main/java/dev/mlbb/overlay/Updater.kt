package dev.mlbb.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File

/**
 * Автообновление с собственного сайта Fast VPN. GitHub используется только для сборки APK.
 */
object Updater {
    /** apkSize — размер APK из релиза: по нему узнаём, что файл уже скачан целиком. */
    data class Release(val versionCode: Int, val tag: String, val notes: String, val apkUrl: String, val apkSize: Long = -1, val apkSha256: String = "")

    /**
     * Возвращает свежий релиз, если он новее установленной версии, иначе null.
     * В описание попадают изменения всех версий, вышедших после установленной, —
     * чтобы при прыжке через несколько версий было видно всё, что поменялось.
     */
    fun check(): Release? {
        val c = Net.open("http://vladisl140.temp.swtest.ru/api/version.php")
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "FastVPN-Android/${BuildConfig.VERSION_NAME}")
        if (c.responseCode != 200) throw RuntimeException("сервер обновлений HTTP ${c.responseCode}")
        val json = org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        if (!json.optBoolean("available", false)) return null
        val version = json.optString("version")
        val code = json.optInt("versionCode", 0)
        if (!version.matches(Regex("""1\.0\.\d{1,8}""")) || code <= BuildConfig.VERSION_CODE) return null
        val sha = json.optString("sha256").lowercase()
        if (!sha.matches(Regex("""[a-f0-9]{64}"""))) throw RuntimeException("сервер вернул некорректную контрольную сумму APK")
        val size = json.optLong("size", -1L)
        val notes = json.optString("notes").ifBlank { "Исправления и улучшения." }
        return Release(code, "mlbb-v$version", notes, "http://vladisl140.temp.swtest.ru/download.php", size, sha)
    }

    /**
     * Приводит описание релиза (текст коммита) к читаемому виду: убирает служебные строки
     * и склеивает строки, перенесённые посреди фразы; пункты «- …» превращает в «• …».
     */
    /** Заголовок релиза одной строкой: «Перенос настроек, анимация подключения». */
    private fun shortNote(body: String): String =
        cleanNotes(body).lineSequence().map { it.trim().removePrefix("• ") }.firstOrNull { it.isNotBlank() }
            ?.trimEnd('.') ?: ""

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
        // Старую загруженную сборку не удаляем, пока новая не скачана и не проверена.
        if (out.exists() && rel.apkSize > 0 && out.length() == rel.apkSize &&
            (rel.apkSha256.isBlank() || sha256(out).equals(rel.apkSha256, ignoreCase = true))) {
            cleanup(ctx, keepCode = rel.versionCode)
            return out
        }
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
        if (rel.apkSha256.isNotBlank() && !sha256(part).equals(rel.apkSha256, ignoreCase = true)) {
            part.delete()
            throw RuntimeException("контрольная сумма APK не совпала — файл удалён")
        }
        if (!part.renameTo(out)) throw RuntimeException("не удалось сохранить файл обновления")
        // Только после успешной загрузки и SHA-256 удаляем предыдущие скачанные APK.
        cleanup(ctx, keepCode = rel.versionCode)
        return out
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
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
