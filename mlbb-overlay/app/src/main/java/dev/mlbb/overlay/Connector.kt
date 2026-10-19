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
    fun canStart(ctx: Context) = VpnService.prepare(ctx) == null &&
        (Subscription.usable(ctx).isNotEmpty() || (AppSettings.selectedTag == XrayCore.BYPASS && XrayCore.available(ctx)))

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
        if (AppSettings.selectedTag == XrayCore.BYPASS) {
            if (checkBypass(ctx)) {
                onReady("Обход блокировок работает: YouTube открывается без сервера")
                // Гибрид: Telegram и прочее, что обход не пробивает, — через рабочий сервер подписки
                busyText = "Подбираю сервер для Telegram…"
                Widget.update(ctx)
                try { ServerTester.pickAbroadForBypass(ctx) } catch (e: Exception) { AppLog.err("сервер для Telegram в обходе", e) }
                startWatchdog(ctx)
            } else {
                fallbackFromBypass(ctx, onReady)
            }
            return
        }
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
            else -> {
                // Автопилот: в этой сети есть проверенный лидер — сразу к нему, без перебора
                val lead = if (game) null else Autopilot.leader(ctx,
                    Subscription.usable(ctx).filter { !ServerTester.isSeparator(it) && !ServerTester.exitsRussia(it) && it.tag != tag })
                if (lead != null && ServerTester.alive(lead.tag)) {
                    AppLog.conn("автопилот: сразу «${Ui.cleanName(lead)}» — лучший в ${Autopilot.networkLabel(ctx)} по опыту")
                    ServerTester.use(ctx, lead.tag)
                    Autopilot.record(ctx, lead, ok = true)
                    onReady(current())
                    rank(ctx, game) {}
                } else {
                    // Сервер не отвечает (или в игре выход не в России) — сначала любой рабочий
                    rank(ctx, game) { onReady(current()) }
                }
            }
        }
        AppLog.conn("готово за ${System.currentTimeMillis() - t0} мс, сервер «${nodeName(ctx, AppSettings.selectedTag)}»")
        if (!game) startWatchdog(ctx)
    }

    /** Сайт, по которому проверяем обход: YouTube — главное, что режут */
    private const val BYPASS_CHECK = BypassLab.CHECK

    /**
     * Обход блокировок без сервера: проверяем, открывается ли YouTube. Не открывается — перебираем
     * способы (у разных провайдеров работают разные), рабочий запоминаем.
     */
    private fun checkBypass(ctx: Context): Boolean {
        if (BoxVpnService.ports == null) return false
        // Фоновая лаборатория ещё ищет — дождёмся (иначе решили бы, что обход не работает)
        var waited = 0
        while (BypassLab.running && waited < 120_000) { busyText = "Лаборатория обхода заканчивает поиск…"; Thread.sleep(500); waited += 500 }
        // Лучшее известное для этой сети и заготовки; долгий поиск — потом, в фоне, уже на сервере
        val g = BypassLab.search(ctx, maxTries = 5, improve = false) { busyText = it; Widget.update(ctx) }
        return g != null
    }

    /**
     * Обход не работает, а пользователь хотел обход: пока он на сервере, лаборатория в фоне ищет
     * новый способ. Нашла — возвращаемся на обход и говорим об этом.
     */
    private fun bypassLabInBackground(ctx: Context) {
        if (!XrayCore.available(ctx) || AppSettings.gameMode || BypassLab.running) return
        // Xray обслуживает и XHTTP-серверы: если выбран такой — перезапуски оборвут соединения
        val sel = Subscription.usable(ctx).firstOrNull { it.tag == AppSettings.selectedTag }
        if (sel?.xrayLink != null) { AppLog.conn("лаборатория обхода: выбран XHTTP-сервер — не запускаю, чтобы не рвать соединения"); return }
        val net = Autopilot.networkKey(ctx)
        val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val last = p.getLong("labAt:$net", 0)
        if (System.currentTimeMillis() - last < 30 * 60_000L) return
        p.edit().putLong("labAt:$net", System.currentTimeMillis()).apply()
        Thread {
            val g = BypassLab.search(ctx, maxTries = 18)
            if (g == null || !BoxVpnService.isRunning) return@Thread
            if (!p.getBoolean("bypassWanted", false) || AppSettings.selectedTag == XrayCore.BYPASS) return@Thread
            val ports = BoxVpnService.ports ?: return@Thread
            if (!ServerTester.selectMain(ports, XrayCore.BYPASS)) return@Thread
            AppSettings.selectedTag = XrayCore.BYPASS
            AppSettings.autoSelect = false
            AppSettings.save(ctx)
            p.edit().putBoolean("bypassWanted", false).apply()
            AppLog.conn("лаборатория обхода: нашла способ — возвращаюсь на обход без сервера")
            notifyBypass(ctx, "Обход блокировок снова работает",
                "Нашёл новый способ обхода для ${Autopilot.networkLabel(ctx)} — VPN снова работает без сервера")
            Widget.update(ctx)
        }.start()
    }

    /**
     * Обход перестал работать (сбой или провайдер стал резать сильнее): сами переходим на самый
     * быстрый сервер подписки — тем же подбором, что и обычно, — и сообщаем уведомлением.
     */
    private fun fallbackFromBypass(ctx: Context, onReady: (String?) -> Unit) {
        if (Subscription.usable(ctx).none { !ServerTester.isSeparator(it) }) {
            AppLog.conn("обход не работает, а серверов в подписке нет — переключать не на что")
            notifyBypass(ctx, "Обход блокировок не работает", "Провайдер режет и так. Добавь подписку — VPN сможет переключаться на серверы сам")
            onReady("Обход блокировок не работает — добавь подписку, чтобы переключаться на серверы")
            return
        }
        AppLog.conn("обход блокировок не работает — переключаюсь на самый быстрый сервер")
        // Пользователь хотел обход — лаборатория поищет новый способ и вернёт его
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("bypassWanted", true).apply()
        // Автовыбор — как был до включения обхода
        AppSettings.autoSelect = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("beforeBypassAuto", true)
        AppSettings.save(ctx)
        var switched = false
        rank(ctx, AppSettings.gameMode) {
            // Рабочий сервер уже выбран — сообщаем сразу, не дожидаясь замера скорости
            val name = nodeName(ctx, AppSettings.selectedTag)
            switched = AppSettings.selectedTag != XrayCore.BYPASS && name.isNotEmpty()
            if (switched) {
                onReady("Обход не работает — переключился на «$name»")
                notifyBypass(ctx, "Обход блокировок сейчас не работает",
                    "Из-за сбоев у провайдера VPN переключился на сервер «$name» — самый быстрый из рабочих. " +
                        "Пока ищу новый способ обхода — найду, верну")
            }
        }
        if (switched) {
            if (!AppSettings.gameMode) startWatchdog(ctx)
            bypassLabInBackground(ctx)
        } else if (BoxVpnService.isRunning) {
            // (VPN выключили посреди подбора — молчим)
            notifyBypass(ctx, "Обход блокировок не работает", "И ни один сервер подписки сейчас не ответил — проверь интернет")
            onReady("Обход не работает, и рабочих серверов не нашлось")
        }
    }

    private const val ALERT_CHANNEL = "alerts"

    /** Всплывающее уведомление (важность высокая — появляется поверх экрана). */
    private fun notifyBypass(ctx: Context, title: String, text: String) {
        try {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(android.app.NotificationChannel(ALERT_CHANNEL, "Важные события VPN",
                android.app.NotificationManager.IMPORTANCE_HIGH))
            val open = android.app.PendingIntent.getActivity(ctx, 7,
                android.content.Intent(ctx, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
            val n = android.app.Notification.Builder(ctx, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_mono)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(7301, n)
            AppLog.conn("уведомление: $title — $text")
        } catch (e: Exception) {
            AppLog.err("уведомление об обходе", e)
        }
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
            var bypassFails = 0
            var abroadFails = 0
            while (BoxVpnService.isRunning && !AppSettings.gameMode &&
                (AppSettings.autoSelect || AppSettings.selectedTag == XrayCore.BYPASS)
            ) {
                try { Thread.sleep(30_000) } catch (_: InterruptedException) { break }
                if (!BoxVpnService.isRunning || busy) continue
                // Обход без сервера: открывается ли YouTube. Два провала подряд (минута) — на сервер
                if (AppSettings.selectedTag == XrayCore.BYPASS) {
                    if (ServerTester.netKey(ctx) == "none") continue
                    val p = BoxVpnService.ports ?: continue
                    if (ClashApi.delay(p.api, p.secret, XrayCore.BYPASS, BYPASS_CHECK, 5000) != null) {
                        bypassFails = 0
                        // Гибрид: жив ли сервер, через который идут Telegram и прочее. Два провала — другой
                        val abroad = ClashApi.now(p.api, p.secret, "abroad")
                        if (abroad == null || abroad == XrayCore.BYPASS) continue
                        if (ServerTester.alive(abroad)) { abroadFails = 0; continue }
                        Subscription.usable(ctx).firstOrNull { it.tag == abroad }?.let { Autopilot.record(ctx, it, ok = false) }
                        AppLog.conn("сторож: сервер для Telegram «${nodeName(ctx, abroad)}» не ответил (${abroadFails + 1}-й раз)")
                        if (++abroadFails < 2) continue
                        abroadFails = 0
                        busy = true
                        try {
                            busyText = "Сервер для Telegram не отвечает — ищу другой…"
                            Widget.update(ctx)
                            ServerTester.pickAbroadForBypass(ctx)
                        } finally {
                            busy = false
                            busyText = ""
                            Widget.update(ctx)
                        }
                        continue
                    }
                    AppLog.conn("сторож: обход блокировок не открыл YouTube (${bypassFails + 1}-й раз)")
                    if (++bypassFails < 2) continue
                    bypassFails = 0
                    busy = true
                    try {
                        // Может, провайдер сменил тактику — сначала другие способы обхода, потом сервер
                        if (!checkBypass(ctx)) fallbackFromBypass(ctx) {}
                    } finally {
                        busy = false
                        busyText = ""
                        Widget.update(ctx)
                    }
                    continue
                }
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
                // Обход не работал, а пользователь его хочет — лаборатория иногда пробует найти новый способ
                if (ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("bypassWanted", false)) bypassLabInBackground(ctx)
                if (ServerTester.alive(AppSettings.selectedTag)) { fails = 0; continue }
                Subscription.usable(ctx).firstOrNull { it.tag == AppSettings.selectedTag }?.let { Autopilot.record(ctx, it, ok = false) }
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
