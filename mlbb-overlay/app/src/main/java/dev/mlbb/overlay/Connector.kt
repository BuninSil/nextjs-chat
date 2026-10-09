package dev.mlbb.overlay

import android.content.Context
import android.net.VpnService

/**
 * Подключение встроенного VPN — одно для кнопки на главном экране, плитки в шторке, виджета
 * и автоподключения.
 *
 * Без ожидания: сразу к последнему удачному серверу и быстрая проверка, что он отвечает.
 * Полный подбор — только если сервер не отвечает, сменилась сеть или с прошлого прошло много
 * времени, и уже при работающем VPN. В игровом режиме сервер выбирается один раз и не меняется.
 *
 * Пока VPN работает в обычном режиме, сторож раз в полминуты проверяет сервер и, если тот умер,
 * сам переключает на рабочий.
 */
object Connector {
    /** Идёт подключение или подбор сервера */
    @Volatile var busy = false
    /** Что сейчас происходит — для строки статуса и виджета */
    @Volatile var busyText = ""

    private fun nodeName(ctx: Context, tag: String) =
        Subscription.usable(ctx).firstOrNull { it.tag == tag }?.let { Ui.cleanName(it) } ?: ""

    /** Разрешение на VPN уже есть — можно подключаться без открытия приложения. */
    fun canStart(ctx: Context) = VpnService.prepare(ctx) == null && Subscription.usable(ctx).isNotEmpty()

    /**
     * Подключить в фоне. onReady(сообщение) — когда интернет через VPN уже есть
     * (сообщение для тоста: какой сервер, или почему не вышло; null — промолчать).
     */
    fun connect(ctx: Context, onReady: (String?) -> Unit = {}) {
        if (busy) { AppLog.conn("подключение уже идёт — повторное нажатие пропущено"); return }
        AppLog.conn("подключаю: сервер «${AppLog.name(ctx, AppSettings.selectedTag)}», автовыбор ${AppSettings.autoSelect}, " +
            "игровой режим ${AppSettings.gameMode}, сеть ${ServerTester.netKey(ctx)}")
        busy = true
        busyText = "Подключаюсь…"
        Widget.update(ctx)
        Thread {
            try {
                BoxVpnService.lastError = null
                BoxVpnService.start(ctx)
                afterStart(ctx, onReady)
            } finally {
                busy = false
                Widget.update(ctx)
            }
        }.start()
    }

    /** Включить или выключить — для плитки и виджета. false — нужно открыть приложение (нет разрешения/подписки). */
    fun toggle(ctx: Context): Boolean {
        AppLog.add("TILE", "плитка/виджет: нажали (VPN ${if (BoxVpnService.isRunning) "включён" else "выключен"})")
        if (BoxVpnService.isRunning || BoxVpnService.isStarting) {
            BoxVpnService.stop(ctx)
            return true
        }
        if (!canStart(ctx)) { AppLog.add("TILE", "нет разрешения на VPN или подписки — открываю приложение"); return false }
        AppSettings.load(ctx)
        connect(ctx)
        return true
    }

    /** Ждёт, пока VPN поднимется (до 20 с). */
    fun waitUp(): Boolean {
        Thread.sleep(300)
        val deadline = System.currentTimeMillis() + 20_000
        while ((BoxVpnService.isStarting || !BoxVpnService.isRunning) && BoxVpnService.lastError == null &&
            System.currentTimeMillis() < deadline
        ) Thread.sleep(200)
        if (!BoxVpnService.isRunning && BoxVpnService.lastError == null) {
            BoxVpnService.lastError = "VPN не подключился за 20 секунд"
        }
        return BoxVpnService.isRunning
    }

    /** Запустить VPN и дождаться (для служебных задач вроде обновления подписки). */
    fun startAndWait(ctx: Context): Boolean {
        BoxVpnService.lastError = null
        BoxVpnService.start(ctx)
        return waitUp()
    }

    private fun afterStart(ctx: Context, onReady: (String?) -> Unit) {
        val t0 = System.currentTimeMillis()
        if (!waitUp()) { AppLog.conn("VPN не поднялся: ${BoxVpnService.lastError}"); return }
        AppLog.conn("VPN поднялся за ${System.currentTimeMillis() - t0} мс")
        val game = AppSettings.gameMode
        val tag = AppSettings.selectedTag
        busyText = "Проверяю сервер…"
        Widget.update(ctx)
        val alive = ServerTester.alive(tag)
        val goodForGame = !game || (alive && ServerTester.exitOkForGame(ctx, tag))
        val stale = ServerTester.rankingStale(ctx)
        AppLog.conn("проверка сервера «${nodeName(ctx, tag)}»: отвечает $alive" +
            (if (game) ", выход в России $goodForGame" else "") + ", подбор устарел $stale")
        fun current() = "Сервер: ${nodeName(ctx, AppSettings.selectedTag)}"

        when {
            !AppSettings.autoSelect ->
                // Сервер выбран вручную — не трогаем, только предупреждаем, если он не отвечает
                onReady(if (alive) current() else "Выбранный сервер не отвечает — выбери другой в «Серверах» или включи автовыбор")
            game && alive && goodForGame ->
                // Игровой режим: сервер выбирается один раз и дальше не меняется — иначе пинг скакал бы
                onReady(current())
            alive && !stale -> onReady(current())
            alive && goodForGame -> {
                // Работает, но подбор устарел: пользуемся сразу, лучший ищем в фоне
                onReady(null)
                rank(ctx, game) {}
            }
            else ->
                // Сервер не отвечает (или в игре выход не в России) — сначала любой рабочий
                rank(ctx, game) { onReady(current()) }
        }
        AppLog.conn("готово за ${System.currentTimeMillis() - t0} мс, сервер «${nodeName(ctx, AppSettings.selectedTag)}»")
        if (!game) startWatchdog(ctx)
    }

    /** Полный подбор при работающем VPN; прогресс — в busyText. Прерывается, если VPN выключили. */
    private fun rank(ctx: Context, game: Boolean, onWorking: () -> Unit) {
        if (!BoxVpnService.isRunning) return
        AppLog.conn("полный подбор сервера (${if (game) "игровой режим" else "обычный режим"})")
        busyText = "Меряю пинг до серверов…"
        val ranked = ServerTester.measure(ctx) { done, total -> busyText = "Меряю пинг: $done из $total" }
        if (!BoxVpnService.isRunning) return
        if (ranked.isEmpty()) {
            onWorking()
            return
        }
        // Сначала — первый рабочий (в игре — с выходом в России): интернет есть уже через секунды
        busyText = if (game) "Ищу сервер с выходом в России…" else "Проверяю сервер…"
        ServerTester.pickWorking(ctx, ranked)
        onWorking()
        Widget.update(ctx)
        // Обычный режим: дальше в фоне — самый быстрый по скорости
        if (!game && BoxVpnService.isRunning) {
            ServerTester.pickFastest(ctx, ranked) { done, total -> busyText = "Проверяю скорость: $done из $total" }
        }
        if (BoxVpnService.isRunning) ServerTester.markRanked(ctx)
    }

    // ------------------------------ сторож ------------------------------

    @Volatile private var watchdog: Thread? = null

    /** Как часто искать сервер быстрее, пока VPN работает (обычный режим, автовыбор) */
    private const val RERANK_MS = 30 * 60 * 1000L

    /**
     * Обычный режим: раз в 30 секунд проверяет, что сервер отвечает. Два провала подряд —
     * тихо переключает на первый рабочий. В игровом режиме не запускается.
     */
    private fun startWatchdog(ctx: Context) {
        if (watchdog?.isAlive == true) return
        watchdog = Thread {
            var fails = 0
            var lastRank = System.currentTimeMillis()
            while (BoxVpnService.isRunning && !AppSettings.gameMode && AppSettings.autoSelect) {
                try { Thread.sleep(30_000) } catch (_: InterruptedException) { break }
                if (!BoxVpnService.isRunning || busy) continue
                // Раз в 30 минут — нет ли сервера быстрее. Не во время скачивания или видео
                // (больше ~1,5 МБ/с): на время замера новые соединения идут через проверяемые серверы
                if (System.currentTimeMillis() - lastRank > RERANK_MS && BoxVpnService.downBps < 1_500_000 &&
                    ServerTester.netKey(ctx) != "none"
                ) {
                    lastRank = System.currentTimeMillis()
                    val before = AppSettings.selectedTag
                    busy = true
                    try {
                        busyText = "Ищу сервер побыстрее…"
                        Widget.update(ctx)
                        AppLog.conn("плановая проверка: нет ли сервера быстрее")
                        val ranked = ServerTester.measure(ctx) { _, _ -> }
                        if (ranked.isNotEmpty() && BoxVpnService.isRunning) {
                            ServerTester.pickFastest(ctx, ranked)
                            ServerTester.markRanked(ctx)
                            if (AppSettings.selectedTag != before) {
                                AppLog.conn("плановая проверка: переключил на «${nodeName(ctx, AppSettings.selectedTag)}»")
                            }
                        }
                    } finally {
                        busy = false
                        Widget.update(ctx)
                    }
                    continue
                }
                if (ServerTester.alive(AppSettings.selectedTag)) { fails = 0; continue }
                // Без сети менять сервер бесполезно
                if (ServerTester.netKey(ctx) == "none") continue
                AppLog.conn("сторож: сервер «${nodeName(ctx, AppSettings.selectedTag)}» не ответил (${fails + 1}-й раз)")
                if (++fails < 2) continue
                fails = 0
                busy = true
                try {
                    busyText = "Сервер не отвечает — ищу рабочий…"
                    Widget.update(ctx)
                    val ranked = ServerTester.measure(ctx) { _, _ -> }
                    if (ranked.isNotEmpty() && BoxVpnService.isRunning) {
                        ServerTester.pickWorking(ctx, ranked)
                        VpnLog.add("автопереподключение: ${nodeName(ctx, AppSettings.selectedTag)}")
                    }
                } finally {
                    busy = false
                    Widget.update(ctx)
                }
            }
        }.apply { isDaemon = true; start() }
    }
}
