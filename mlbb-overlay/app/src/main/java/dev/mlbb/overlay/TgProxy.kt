package dev.mlbb.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Прокси для Telegram (MTProto) на крайний случай: когда Telegram не открывается ни напрямую, ни по
 * IPv6, ни через сервер подписки. Это штатная возможность самого Telegram — он умеет ходить через
 * такой прокси по ссылке tg://proxy. Приложение не вмешивается в его трафик: оно лишь берёт
 * публичный список прокси с GitHub, проверяет, какие из них живы (обычное TCP-подключение), и
 * отдаёт рабочий Telegram — дальше Telegram подключается сам.
 *
 * Прокси видят только зашифрованный поток до серверов Telegram, переписку они прочитать не могут,
 * но держат их посторонние люди — поэтому это именно резерв, а не основной путь.
 */
object TgProxy {
    /** Открытые списки MTProto-прокси (ссылки вида https://t.me/proxy?server=…&port=…&secret=…) */
    private val SOURCES = listOf(
        "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt",
        "https://raw.githubusercontent.com/Grim1313/mtproto-for-telegram/master/all_proxies.txt",
    )

    data class Proxy(val server: String, val port: Int, val secret: String) {
        /** Ссылка, по которой Telegram сам подключается к прокси */
        fun link() = "tg://proxy?server=$server&port=$port&secret=$secret"
    }

    private fun parse(line: String): Proxy? {
        val u = try { Uri.parse(line.trim()) } catch (_: Exception) { return null }
        val server = u.getQueryParameter("server")?.takeIf { it.isNotBlank() } ?: return null
        val port = u.getQueryParameter("port")?.toIntOrNull() ?: return null
        val secret = u.getQueryParameter("secret")?.takeIf { it.isNotBlank() } ?: return null
        return Proxy(server, port, secret)
    }

    private fun fetch(ctx: Context): List<Proxy> {
        val net = ServerTester.underlying(ctx)
        val out = LinkedHashSet<Proxy>()
        for (url in SOURCES) {
            try {
                val c = (net?.openConnection(java.net.URL(url)) ?: java.net.URL(url).openConnection())
                    as java.net.HttpURLConnection
                c.connectTimeout = 6000
                c.readTimeout = 6000
                c.setRequestProperty("Connection", "close")
                c.inputStream.bufferedReader().useLines { lines -> lines.forEach { parse(it)?.let(out::add) } }
            } catch (_: Exception) {}
            if (out.size > 60) break
        }
        return out.toList()
    }

    /** Жив ли прокси — обычное TCP-подключение к его адресу через текущую сеть */
    private fun alive(ctx: Context, p: Proxy): Boolean = try {
        val net = ServerTester.underlying(ctx)
        Socket().use { s ->
            try { net?.bindSocket(s) } catch (_: Exception) {}
            s.connect(InetSocketAddress(p.server, p.port), 2000)
            true
        }
    } catch (_: Exception) { false }

    @Volatile private var searching = false

    /**
     * Найти живой прокси (в фоне) и открыть его в Telegram. progress — строки статуса для экрана.
     * onDone(true) — Telegram открыт с прокси; onDone(false) — рабочего не нашлось или нет сети.
     */
    fun findAndOpen(ctx: Context, progress: (String) -> Unit, onDone: (Boolean) -> Unit) {
        if (searching) { onDone(false); return }
        searching = true
        Thread {
            try {
                progress("Беру список прокси…")
                val list = fetch(ctx).shuffled()
                if (list.isEmpty()) { progress(""); onDone(false); return@Thread }
                progress("Проверяю прокси: 0 из ${minOf(list.size, 40)}…")
                val checked = java.util.concurrent.atomic.AtomicInteger()
                val working = ConcurrentHashMap.newKeySet<Proxy>()
                val take = list.take(40)
                val pool = Executors.newFixedThreadPool(12)
                for (p in take) pool.execute {
                    if (alive(ctx, p)) working.add(p)
                    progress("Проверяю прокси: ${checked.incrementAndGet()} из ${take.size}…")
                }
                pool.shutdown()
                pool.awaitTermination(25, TimeUnit.SECONDS)
                val best = working.firstOrNull()
                progress("")
                if (best == null) { onDone(false); return@Thread }
                AppLog.conn("прокси для Telegram: рабочий ${best.server}:${best.port} — открываю в Telegram")
                open(ctx, best)
                onDone(true)
            } finally {
                searching = false
            }
        }.start()
    }

    /** Открыть ссылку прокси — Telegram перехватит её и предложит подключиться */
    private fun open(ctx: Context, p: Proxy) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.link()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLog.err("не удалось открыть прокси в Telegram", e)
        }
    }
}
