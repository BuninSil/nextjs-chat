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
                TelegramRoute.preset(ctx)
                Thread { try { TelegramRoute.apply(ctx) } catch (_: Exception) {} }.start()
                startWatchdog(ctx)
            } else if (!ServerTester.internetUp(ctx)) {
                // Связи нет вообще — обход тут ни при чём; сторож проверит снова, когда сеть появится
                AppLog.conn("обход: интернета сейчас нет — не переключаюсь, жду сеть")
                onReady("Нет интернета — проверю обход, когда сеть появится")
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
            alive && !stale -> {
                // Сервер свежий и живой — подключаемся к нему сразу, но не вслепую: в фоне сверяем
                // с лучшими кандидатами по честной задержке и переключаемся, если кто-то стабильно быстрее
                onReady(current())
                if (!game && AppSettings.autoSelect) Thread { try { quickRefine(ctx) } catch (_: Exception) {} }.start()
            }
            alive && goodForGame -> {
                // Работает, но подбор устарел: пользуемся сразу, лучший ищем в фоне
                onReady(null)
                rank(ctx, game) {}
            }
            // (только обычный режим: в игровом сторожа нет, там подбор как раньше)
            !game && !alive && !ServerTester.internetUp(ctx) -> {
                // Связи нет вообще — подбор ничего не даст (все «не ответят»). Сторож подберёт, когда сеть вернётся
                AppLog.conn("интернета сейчас нет — подбор отложен до появления сети")
                onReady("Нет интернета — подключусь к рабочему серверу, когда сеть появится")
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
                    // Без «первого рабочего» — он увёл бы с лидера; только сверка скорости
                    refine(ctx)
                } else {
                    // Сервер не отвечает (или в игре выход не в России) — сначала любой рабочий
                    rank(ctx, game) { onReady(current()) }
                }
            }
        }
        AppLog.conn("готово за ${System.currentTimeMillis() - t0} мс, сервер «${nodeName(ctx, AppSettings.selectedTag)}»")
        if (!game) {
            // Telegram: если в этой сети он открыт напрямую — пустим без сервера (в фоне, не задерживая)
            TelegramRoute.preset(ctx)
            Thread { try { TelegramRoute.apply(ctx) } catch (_: Exception) {} }.start()
            // Диагностика: проходит ли cloudflared (7844) напрямую в этой сети — чтобы видеть, режут ли его
            Thread {
                try {
                    if (!ServerTester.directReach(ctx, "198.41.192.7", 7844))
                        AppLog.conn("cloudflared: порт 7844 напрямую не прошёл (если не пользуешься WARP/cloudflared — неважно)")
                } catch (_: Exception) {}
            }.start()
            // Частный DNS (DoT) в системе мешает нашему DNS — мы его в туннеле отбиваем (порт 853),
            // Android откатывается на обычный DNS. Пишем в журнал, чтобы это было видно в отчёте.
            try {
                val mode = android.provider.Settings.Global.getString(ctx.contentResolver, "private_dns_mode")
                if (mode != null && mode != "off")
                    AppLog.conn("частный DNS Android включён ($mode) — в туннеле отключён (853 отбивается), работает наш DNS")
            } catch (_: Exception) {}
            startWatchdog(ctx)
        }
    }

    /** Сайт, по которому проверяем обход: YouTube — главное, что режут */
    private const val BYPASS_CHECK = BypassLab.CHECK

    /**
     * Открывается ли YouTube через обход. Один медленный ответ — ещё не провал (по журналам
     * обход работал, а проверка раз в несколько минут не укладывалась в 5 секунд): переспрашиваем.
     */
    private fun bypassOk(p: BoxConfig.Ports): Boolean {
        if (ClashApi.delay(p.api, p.secret, XrayCore.BYPASS, BYPASS_CHECK, 5000) != null) return true
        try { Thread.sleep(2000) } catch (_: InterruptedException) { return false }
        return ClashApi.delay(p.api, p.secret, XrayCore.BYPASS, BYPASS_CHECK, 8000) != null
    }

    /**
     * Обход блокировок без сервера: проверяем, открывается ли YouTube. Не открывается — перебираем
     * способы (у разных провайдеров работают разные), рабочий запоминаем.
     */
    private fun checkBypass(ctx: Context): Boolean {
        if (BoxVpnService.ports == null) return false
        // Лучшее известное для этой сети и заготовки; долгий поиск — потом, в фоне, уже на сервере
        // wait: фоновая лаборатория ещё ищет — дождёмся её (иначе решили бы, что обход не работает)
        val g = BypassLab.search(ctx, maxTries = 5, improve = false, wait = true) { busyText = it; Widget.update(ctx) }
        return g != null
    }

    /**
     * Обход не работает, а пользователь хотел обход: пока он на сервере, лаборатория в фоне ищет
     * новый способ. Нашла — возвращаемся на обход и говорим об этом.
     */
    @Volatile private var xhttpSkipLogged = 0L

    private fun bypassLabInBackground(ctx: Context) {
        if (!XrayCore.available(ctx) || AppSettings.gameMode || BypassLab.running) return
        // Xray обслуживает и XHTTP-серверы: если выбран такой — перезапуски оборвут соединения
        val nodes = Subscription.usable(ctx)
        val abroad = BoxVpnService.ports?.let { ClashApi.now(it.api, it.secret, "abroad") }
        val viaXray = listOf(AppSettings.selectedTag, abroad).any { t -> nodes.firstOrNull { it.tag == t }?.xrayLink != null }
        if (viaXray) {
            if (System.currentTimeMillis() - xhttpSkipLogged > 10 * 60_000L) {
                xhttpSkipLogged = System.currentTimeMillis()
                AppLog.conn("лаборатория обхода: трафик идёт через XHTTP-сервер — не запускаю, чтобы не рвать соединения")
            }
            return
        }
        val net = Autopilot.networkKey(ctx)
        val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val last = p.getLong("labAt:$net", 0)
        if (System.currentTimeMillis() - last < 30 * 60_000L) return
        p.edit().putLong("labAt:$net", System.currentTimeMillis()).apply()
        Thread {
            val g = BypassLab.search(ctx, maxTries = 18)
            if (g == null || !BoxVpnService.isRunning) return@Thread
            if (!p.getBoolean("bypassWanted", false) || AppSettings.selectedTag == XrayCore.BYPASS) return@Thread
            AppSettings.selectedTag = XrayCore.BYPASS
            AppSettings.autoSelect = false
            AppSettings.save(ctx)
            p.edit().putBoolean("bypassWanted", false).apply()
            AppLog.conn("лаборатория обхода: нашла способ — переподключаюсь на обход без сервера")
            // Переподключение, а не просто смена сервера: правила маршрутизации для обхода
            // (гибрид, всё прочее — через обход) собираются только при запуске ядра
            BoxVpnService.stop(ctx, manual = false)
            Thread.sleep(1500)
            connect(ctx)
            notifyBypass(ctx, "Обход блокировок снова работает",
                "Нашёл новый способ обхода для ${Autopilot.networkLabel(ctx)} — VPN снова работает без сервера")
        }.start()
    }

    /**
     * Обход перестал работать (сбой или провайдер стал резать сильнее): сами переходим на самый
     * быстрый сервер подписки — тем же подбором, что и обычно, — и сообщаем уведомлением.
     */
    private fun fallbackFromBypass(ctx: Context, onReady: (String?) -> Unit) {
        // VPN выключили, пока проверяли обход, — это не «обход не работает»
        if (!BoxVpnService.isRunning) return
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
        } else if (BoxVpnService.isRunning && ServerTester.internetUp(ctx)) {
            // (VPN выключили посреди подбора или пропала связь — молчим)
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

    /**
     * Быстрая сверка при подключении: не переиспользуем старый сервер вслепую, а сравниваем его с
     * несколькими лучшими по опыту кандидатами по честной задержке (в фоне, не задерживая запуск).
     */
    private fun quickRefine(ctx: Context) {
        if (!BoxVpnService.isRunning || AppSettings.gameMode || !AppSettings.autoSelect) return
        val byTag = Subscription.usable(ctx)
            .filterNot { ServerTester.isSeparator(it) || Subscription.isReserve(it) || ServerTester.exitsRussia(it) }
            .associateBy { it.tag }
        if (byTag.isEmpty()) return
        val order = Autopilot.order(ctx, byTag.keys.toList(), byTag) { ServerTester.results[it]?.medianMs }
        // firstWins: при ручном подключении переключаемся с первого же замера, если кандидат на 20%+
        // быстрее — иначе второй замер был бы только на плановой проверке через полчаса, и «выключил-включил»
        // держалось бы за старый сервер
        ServerTester.pickFastest(ctx, order, candidates = 4, firstWins = true)
    }

    /** Сверка скорости без смены рабочего сервера на «первый попавшийся»: меняет, только если другой заметно быстрее. */
    private fun refine(ctx: Context) {
        if (!BoxVpnService.isRunning) return
        busyText = "Меряю пинг до серверов…"
        val ranked = ServerTester.measure(ctx) { done, total -> busyText = "Меряю пинг: $done из $total" }
        if (ranked.isEmpty() || !BoxVpnService.isRunning) return
        // Это путь подключения (лидер) — переключаемся с первого замера, не ждём второй
        ServerTester.pickFastest(ctx, ranked, firstWins = true) { done, total -> busyText = "Проверяю скорость: $done из $total" }
        if (BoxVpnService.isRunning) ServerTester.markRanked(ctx)
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
        // Ни один сервер подписки не ответил, а резерв включён — крайний вариант: бесплатный сервер
        if (!game && !ServerTester.alive(AppSettings.selectedTag) && AppSettings.freeReserve && ServerTester.internetUp(ctx)) {
            busyText = "Серверы не отвечают — пробую резервные…"
            Widget.update(ctx)
            ServerTester.pickReserve(ctx)?.let {
                notifyBypass(ctx, "Включён резервный сервер",
                    "Серверы подписки сейчас не отвечают — подключил бесплатный публичный сервер. Как только подписка заработает, вернусь на неё.")
            }
        }
        onWorking()
        Widget.update(ctx)
        // Обычный режим: дальше — самый быстрый по задержке (путь подключения, с первого замера)
        if (!game && BoxVpnService.isRunning) {
            ServerTester.pickFastest(ctx, ranked, firstWins = true) { done, total -> busyText = "Проверяю скорость: $done из $total" }
        }
        if (BoxVpnService.isRunning) ServerTester.markRanked(ctx)
    }

    // ------------------------------ сторож ------------------------------

    @Volatile private var watchdog: Thread? = null

    /** Когда последний раз сменилась сеть (переезд, вышка, Wi-Fi ↔ мобильная) и не ждёт ли сторож */
    @Volatile private var netChangedAt = 0L
    @Volatile private var netKick = false

    /**
     * Сеть сменилась: в дороге каждый город и область — свой интернет и свои блокировки.
     * Сторож проверяет обход и сервер сразу (а не через полминуты) и пару минут — чаще.
     */
    fun onNetworkChanged(ctx: Context, why: String) {
        Autopilot.invalidate()
        if (!BoxVpnService.isRunning || AppSettings.gameMode) return
        netChangedAt = System.currentTimeMillis()
        netKick = true
        AppLog.conn("сторож: $why — проверю связь")
    }

    /** Мгновенное переключение на горячий запасной; полный подбор (новый запасной) — через пару минут. */
    private fun switchToStandby(ctx: Context, tag: String, why: String) {
        ServerTester.standby = null
        if (!ServerTester.use(ctx, tag)) return
        AppLog.conn("сторож: $why — сразу на запасной «${nodeName(ctx, tag)}»")
        VpnLog.add("на запасной сервер: ${nodeName(ctx, tag)}")
        Subscription.usable(ctx).firstOrNull { it.tag == tag }?.let { Autopilot.record(ctx, it, ok = true) }
        Widget.update(ctx)
        // Звонки и игры шли через упавший? — пусть идут через новый, пока подбор не найдёт лучший
        ServerTester.lowTag?.let { low -> if (ServerTester.delayMs(low) == null) { ServerTester.lowTag = null; ServerTester.applyAbroad(ctx) } }
        rerankSoon = true
    }

    /** Сервер для звонков и игр жив? Нет — звонки через выбранный, пока подбор не найдёт другой. */
    private fun checkLowPing(ctx: Context) {
        val low = ServerTester.lowTag ?: return
        if (low == AppSettings.selectedTag || ServerTester.delayMs(low) != null) return
        AppLog.conn("сторож: сервер для звонков и игр «${nodeName(ctx, low)}» не отвечает — звонки через выбранный")
        ServerTester.lowTag = null
        ServerTester.applyAbroad(ctx)
    }

    /** Переключились на запасной — сторож запустит подбор через пару минут (найти новый запасной) */
    @Volatile private var rerankSoon = false

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
            var okRecordedAt = 0L
            var lastNet = Autopilot.networkKey(ctx)
            var earlyRankAt = 0L
            // Качество выбранного сервера. База — медиана ХОРОШИХ замеров за ~30 мин (плохие в неё не
            // попадают, иначе при плавной деградации база ползёт вверх и сторож не срабатывает). Окно —
            // последние 5 замеров; переключаемся, если 3 из 5 плохих ИЛИ 2 подряд. Таймаут — тоже плохой.
            var qTag = ""
            var qBase = 0.0
            var qFloor = 0.0                             // пол базы — медиана 3 замеров при выборе сервера
            val qGood = ArrayDeque<Pair<Long, Int>>()   // (время, мс) хороших замеров
            val qWin = ArrayDeque<Pair<Int, Boolean>>()  // последние 5: (мс, плохой ли)
            var qStuck = 0                               // сработок подряд, когда переключиться не на что
            val qSwitches = ArrayDeque<Long>()           // времена переключений сторожа за ~15 мин
            var qPauseUntil = 0L                         // пауза переключений по качеству (нестабильная сеть)
            var qSwitchAt = 0L
            var standbyAt = 0L
            val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            // Работает и при ручном выборе сервера, если обход ждёт возвращения (bypassWanted)
            // isStarting — ядро перезапускается внутри сервиса (обновился список серверов): не выходим
            while ((BoxVpnService.isRunning || BoxVpnService.isStarting) && !AppSettings.gameMode &&
                (AppSettings.autoSelect || AppSettings.selectedTag == XrayCore.BYPASS || prefs.getBoolean("bypassWanted", false))
            ) {
                // Обычно — раз в 30 секунд; после смены сети — раз в 10 секунд две минуты,
                // а о самой смене узнаём сразу (секунды на то, чтобы новая сеть поднялась)
                try {
                    val fast = System.currentTimeMillis() - netChangedAt < 120_000
                    val until = System.currentTimeMillis() + if (fast) 10_000 else 30_000
                    while (System.currentTimeMillis() < until && !netKick) Thread.sleep(500)
                    if (netKick) {
                        netKick = false
                        Thread.sleep(4000)
                    }
                } catch (_: InterruptedException) { break }
                if (!BoxVpnService.isRunning || busy) continue
                // Идёт тест скорости пользователя — не трогаем сервер, иначе рестарт ядра испортит замер
                if (SpeedTest.active) continue
                val net = Autopilot.networkKey(ctx)
                // «cell:оператор» → «cell:оператор:город» — тот же оператор, просто узнали регион
                if (net.startsWith("$lastNet:") || lastNet.startsWith("$net:")) lastNet = net
                if (net.isNotEmpty() && net != lastNet) {
                    AppLog.conn("сторож: теперь ${Autopilot.networkLabel(ctx, net)}")
                    lastNet = net
                    fails = 0
                    bypassFails = 0
                    abroadFails = 0
                    // Другая сеть — свой пинг: окно и базу качества обнуляем (reseed на следующем замере)
                    qTag = ""
                    // Для этой сети (или оператора) уже знаем свой способ обхода — сразу на него:
                    // соединения при смене сети всё равно оборвались, перезапуск Xray ничего не испортит
                    if (AppSettings.selectedTag == XrayCore.BYPASS && !BypassLab.running) {
                        val best = Autopilot.bypassBest(ctx)?.params()
                        if (best != null && best != XrayCore.bypassParams) {
                            AppLog.conn("сторож: способ обхода, запомненный для этой сети, — ${Autopilot.bypassBest(ctx)}")
                            XrayCore.setBypassParams(ctx, best)
                            continue
                        }
                    }
                    Thread { try { TelegramRoute.apply(ctx) } catch (_: Exception) {} }.start()
                    // Новая сеть часто убивает прежний сервер (на мобильной живы только «Обход»-сервера).
                    // Если выбранный сервер на ней уже не отвечает, а интернет есть — подбираем сразу,
                    // не дожидаясь плановой проверки (раньше 2 минуты висели сплошные refused).
                    if (AppSettings.autoSelect && AppSettings.selectedTag != XrayCore.BYPASS &&
                        !ServerTester.alive(AppSettings.selectedTag) && ServerTester.internetUp(ctx)
                    ) {
                        AppLog.conn("сторож: на новой сети «${nodeName(ctx, AppSettings.selectedTag)}» не отвечает — подбираю сразу")
                        lastRank = 0L
                    } else if (System.currentTimeMillis() - earlyRankAt > 10 * 60_000L) {
                        // Прежний жив — просто перепроверим, нет ли рядом быстрее, через пару минут
                        earlyRankAt = System.currentTimeMillis()
                        lastRank = minOf(lastRank, System.currentTimeMillis() - RERANK_MS + 2 * 60_000L)
                    }
                }
                // Обход без сервера: открывается ли YouTube. Два провала подряд (минута) — на сервер
                if (AppSettings.selectedTag == XrayCore.BYPASS) {
                    if (ServerTester.netKey(ctx) == "none") continue
                    val p = BoxVpnService.ports ?: continue
                    if (bypassOk(p)) {
                        bypassFails = 0
                        // Гибрид: жив ли сервер, через который идут Telegram и прочее. Два провала — другой
                        val abroad = ClashApi.now(p.api, p.secret, "abroad")
                        if (abroad == null || abroad == XrayCore.BYPASS) continue
                        if (ServerTester.alive(abroad)) {
                            abroadFails = 0
                            if (System.currentTimeMillis() - okRecordedAt > 10 * 60_000L) {
                                okRecordedAt = System.currentTimeMillis()
                                Subscription.usable(ctx).firstOrNull { it.tag == abroad }?.let { Autopilot.record(ctx, it, ok = true) }
                            }
                            continue
                        }
                        if (!ServerTester.internetUp(ctx)) { abroadFails = 0; continue }
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
                    if (!ServerTester.internetUp(ctx)) { bypassFails = 0; continue } // связи нет — ждём
                    AppLog.conn("сторож: обход блокировок не открыл YouTube (${bypassFails + 1}-й раз)")
                    if (++bypassFails < 2) continue
                    bypassFails = 0
                    busy = true
                    try {
                        // Может, провайдер сменил тактику — сначала другие способы обхода, потом сервер
                        if (!checkBypass(ctx) && ServerTester.internetUp(ctx)) fallbackFromBypass(ctx) {}
                    } finally {
                        busy = false
                        busyText = ""
                        Widget.update(ctx)
                    }
                    continue
                }
                // Обход не работал, а пользователь его хочет — лаборатория иногда пробует найти новый способ
                if (prefs.getBoolean("bypassWanted", false)) bypassLabInBackground(ctx)
                // Сервер выбран вручную — сами его не меняем
                if (!AppSettings.autoSelect) continue
                if (rerankSoon) {
                    rerankSoon = false
                    lastRank = minOf(lastRank, System.currentTimeMillis() - RERANK_MS + 2 * 60_000L)
                }
                // Сейчас на бесплатном резерве — чаще проверяем, не ожила ли подписка, чтобы вернуться
                if (Subscription.usable(ctx).firstOrNull { it.tag == AppSettings.selectedTag }?.let { Subscription.isReserve(it) } == true &&
                    System.currentTimeMillis() - lastRank > 2 * 60_000L
                ) {
                    lastRank = 0L
                }
                // Раз в 30 минут — нет ли сервера быстрее. Не во время скачивания или видео
                // (больше ~1,5 МБ/с): на время замера новые соединения идут через проверяемые серверы
                if (System.currentTimeMillis() - lastRank > RERANK_MS && BoxVpnService.downBps < 1_500_000 &&
                    ServerTester.netKey(ctx) != "none" && System.currentTimeMillis() - BoxVpnService.upAt > 60_000L
                ) {
                    lastRank = System.currentTimeMillis()
                    val before = AppSettings.selectedTag
                    busy = true
                    try {
                        busyText = "Ищу сервер побыстрее…"
                        Widget.update(ctx)
                        AppLog.conn("плановая проверка: нет ли сервера быстрее")
                        // Не пингуем все 70 на платной мобилке: берём порядок по опыту (автопилот),
                        // pickFastest сам свежо замерит только топ-кандидатов. Полный прогон — при смене
                        // сети и когда рабочих не осталось (ветки ниже).
                        val byTag = Subscription.usable(ctx)
                            .filterNot { ServerTester.isSeparator(it) || Subscription.isReserve(it) || ServerTester.exitsRussia(it) }
                            .associateBy { it.tag }
                        val ranked = Autopilot.order(ctx, byTag.keys.toList(), byTag) { ServerTester.results[it]?.medianMs }
                        if (ranked.isNotEmpty() && BoxVpnService.isRunning) {
                            ServerTester.pickFastest(ctx, ranked, candidates = 8)
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
                val sel = AppSettings.selectedTag
                // Один медленный ответ — ещё не провал: переспрашиваем с запасом
                val ms = ServerTester.delayMs(sel) ?: run {
                    try { Thread.sleep(2000) } catch (_: InterruptedException) { return@Thread }
                    ServerTester.delayMs(sel, 6000)
                }
                if (ms != null) {
                    fails = 0
                    // Успех тоже опыт (не чаще раза в 10 минут — чтобы не раздувать)
                    if (System.currentTimeMillis() - okRecordedAt > 10 * 60_000L) {
                        okRecordedAt = System.currentTimeMillis()
                        Subscription.usable(ctx).firstOrNull { it.tag == sel }?.let { Autopilot.record(ctx, it, ok = true) }
                    }
                    // Новый сервер — чистим окно и базу; базу берём из замера при выборе (честная задержка)
                    val tNow = System.currentTimeMillis()
                    if (qTag != sel) {
                        qTag = sel; qGood.clear(); qWin.clear(); qStuck = 0
                        // База — медиана 3 свежих замеров (не один «удачный»); она же пол базы
                        qBase = (ServerTester.baseline3(sel) ?: ServerTester.results[sel]?.medianMs ?: ms).toDouble()
                        qFloor = qBase
                    }
                    // Во время скачивания/видео (>1,5 МБ/с) пинг растёт сам — такой замер не учитываем вовсе
                    if (BoxVpnService.downBps < 1_500_000) {
                        // Плохо = в 2+ раза или на 150 мс хуже базы, либо просто выше 800 мс (абсолютный потолок)
                        val slow = ms > maxOf(qBase * 2, qBase + 150) || ms > 800
                        qWin.addLast(ms to slow)
                        while (qWin.size > 5) qWin.removeFirst()
                        if (!slow) {
                            // Хороший замер — в базу (медиана хороших за 30 мин); плохие в базу не идут.
                            // База не опускается ниже пола (медианы 3 замеров при выборе), иначе случайные
                            // сверхнизкие пинги занижали бы базу и всё казалось бы «медленным».
                            qGood.addLast(tNow to ms)
                            while (qGood.isNotEmpty() && tNow - qGood.first().first > 30 * 60_000L) qGood.removeFirst()
                            val gm = qGood.map { it.second }.sorted().let { if (it.isEmpty()) ms.toDouble() else it[it.size / 2].toDouble() }
                            qBase = maxOf(gm, qFloor)
                        }
                        val bad = qWin.count { it.second }
                        val twoInRow = qWin.size >= 2 && qWin.last().second && qWin.elementAt(qWin.size - 2).second
                        // И не дёргаем сервер в первую минуту после подключения (ему надо прогреться)
                        val trigger = (bad >= 3 || twoInRow) && tNow > qPauseUntil && tNow - qSwitchAt > 3 * 60_000L &&
                            tNow - BoxVpnService.upAt > 60_000L
                        if (slow) {
                            val badList = qWin.filter { it.second }.joinToString(", ") { "${it.first}" }
                            AppLog.conn("сторож: «${nodeName(ctx, sel)}» тормозит — $ms мс при базе ~${qBase.toInt()} (плохих $bad из ${qWin.size}: $badList)" +
                                if (trigger) " → проверяю кандидатов" else "")
                        }
                        if (trigger) {
                            // Просела ли вся сеть? Меряем свежим real delay запасной и 2 лучших кандидата
                            // и переключаемся, только если кандидат быстрее текущего хотя бы на 30%.
                            val foreign = Subscription.usable(ctx)
                                .filterNot { ServerTester.isSeparator(it) || Subscription.isReserve(it) || ServerTester.exitsRussia(it) || it.tag == sel }
                            val order = Autopilot.order(ctx, foreign.map { it.tag }, foreign.associateBy { it.tag }) { ServerTester.results[it]?.medianMs }
                            val cands = (listOfNotNull(ServerTester.standby.takeIf { it != sel }) + order).distinct().take(3)
                            val cd = cands.associateWith { ServerTester.delayMs(it) }  // по одному свежему замеру
                            val bestEntry = cd.entries.filter { it.value != null }.minByOrNull { it.value!! }
                            val bestTag = bestEntry?.key
                            val bestMs = bestEntry?.value ?: Int.MAX_VALUE
                            if (bestTag != null && bestMs < ms * 0.7) {
                                qSwitchAt = tNow; qStuck = 0; qWin.clear()
                                qSwitches.addLast(tNow)
                                while (qSwitches.isNotEmpty() && tNow - qSwitches.first() > 15 * 60_000L) qSwitches.removeFirst()
                                if (qSwitches.size >= 3) {
                                    qPauseUntil = tNow + 10 * 60_000L
                                    AppLog.conn("сторож: частые переключения (${qSwitches.size} за 15 мин) — сеть нестабильна, пауза 10 мин")
                                }
                                if (bestTag == ServerTester.standby) switchToStandby(ctx, bestTag, "тормозит ($ms мс), запасной $bestMs мс")
                                else {
                                    ServerTester.use(ctx, bestTag)
                                    AppLog.conn("сторож: «${nodeName(ctx, sel)}» тормозит ($ms мс) → «${nodeName(ctx, bestTag)}» ($bestMs мс)")
                                    VpnLog.add("смена по качеству: ${nodeName(ctx, bestTag)}")
                                    rerankSoon = true
                                }
                            } else {
                                // Кандидаты не лучше — просела вся сеть, дёргаться бессмысленно, остаёмся
                                qStuck++
                                AppLog.conn("сторож: тормозит «${nodeName(ctx, sel)}» ($ms мс), но кандидаты не лучше (" +
                                    cd.entries.joinToString(", ") { "${nodeName(ctx, it.key)} ${it.value ?: "—"} мс" } + ") — остаюсь")
                                if (qStuck >= 3) {
                                    // Замкнутый круг: пересчитываем базу по окну как есть, чтобы отлипнуть
                                    qBase = qWin.map { it.first }.sorted().let { if (it.isEmpty()) qBase else it[it.size / 2].toDouble() }
                                    qFloor = minOf(qFloor, qBase)
                                    qStuck = 0; qWin.clear()
                                    AppLog.conn("сторож: кандидаты давно не лучше — пересчитал базу по факту: ~${qBase.toInt()} мс")
                                }
                            }
                            continue
                        }
                    }
                    // Раз в 5 минут: жив ли запасной, сервер для звонков и сервер для Telegram/YouTube,
                    // и одной строкой — что сторож жив (сервер, база, окно), чтобы это было видно в отчёте
                    if (System.currentTimeMillis() - standbyAt > 5 * 60_000L) {
                        standbyAt = System.currentTimeMillis()
                        ServerTester.refreshStandby(ctx)
                        checkLowPing(ctx)
                        ServerTester.ensureAbroadAlive(ctx)
                        AppLog.conn("сторож жив: «${nodeName(ctx, sel)}» $ms мс, база ~${qBase.toInt()} мс, окно [" +
                            qWin.joinToString(" ") { if (it.second) "✗${it.first}" else "ok" } + "]" +
                            (ServerTester.standby?.let { ", запасной «${nodeName(ctx, it)}»" } ?: ""))
                    }
                    continue
                }
                // Сервер не ответил. Проверяем интернет напрямую, мимо туннеля: если он есть —
                // это мёртвый сервер (а не «пропала связь»), и тянуть незачем.
                val netUp = ServerTester.internetUp(ctx)
                if (!netUp) { fails = 0; continue }
                Subscription.usable(ctx).firstOrNull { it.tag == sel }?.let { Autopilot.record(ctx, it, ok = false) }
                AppLog.conn("сторож: «${nodeName(ctx, sel)}» не ответил, а интернет есть — сервер мёртв, меняю")
                // Есть живой запасной — переключаемся сразу, за секунду, без полного подбора
                val sb = ServerTester.standby
                if (sb != null && sb != sel && ServerTester.delayMs(sb) != null) {
                    fails = 0
                    switchToStandby(ctx, sb, "«${nodeName(ctx, sel)}» не отвечает")
                    continue
                }
                // Интернет подтверждён, сервер мёртв, запасного нет — полный подбор сразу, без «второго раза»
                fails = 0
                busy = true
                try {
                    busyText = "Сервер не отвечает — ищу рабочий…"
                    Widget.update(ctx)
                    val ranked = ServerTester.measure(ctx) { _, _ -> }
                    if (ranked.isNotEmpty() && BoxVpnService.isRunning) {
                        ServerTester.pickWorking(ctx, ranked)
                        VpnLog.add("автопереподключение: ${nodeName(ctx, AppSettings.selectedTag)}")
                        // Новый запасной и сервер для звонков — подбором по скорости через пару минут
                        lastRank = minOf(lastRank, System.currentTimeMillis() - RERANK_MS + 2 * 60_000L)
                    }
                    // Ни один сервер подписки не ожил, а резерв включён — крайний вариант
                    if (BoxVpnService.isRunning && !ServerTester.alive(AppSettings.selectedTag) &&
                        AppSettings.freeReserve && ServerTester.internetUp(ctx)
                    ) {
                        busyText = "Серверы не отвечают — пробую резервные…"
                        Widget.update(ctx)
                        ServerTester.pickReserve(ctx)?.let {
                            VpnLog.add("резервный сервер: ${nodeName(ctx, AppSettings.selectedTag)}")
                            notifyBypass(ctx, "Включён резервный сервер",
                                "Серверы подписки сейчас не отвечают — подключил бесплатный публичный сервер. Как только подписка заработает, вернусь на неё.")
                        }
                    }
                } finally {
                    busy = false
                    Widget.update(ctx)
                }
            }
        }.apply { isDaemon = true; start() }
    }
}
