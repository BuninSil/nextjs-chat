package dev.mlbb.overlay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var statusBig: TextView
    private var shownError: String? = null
    private lateinit var dbStatus: TextView
    private lateinit var updateStatus: TextView
    @Volatile
    private var updateBusy = false
    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var dbBusy = false

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) startService()
        else showInfo("Нужно разрешение", "Без разрешения на VPN приложение не видит трафик игры. Нажми СТАРТ и согласись.")
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        startFlow()
    }

    private val importDb = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importDatabase(uri)
    }

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        statusBig = findViewById(R.id.statusBig)
        dbStatus = findViewById(R.id.dbStatus)
        updateStatus = findViewById(R.id.updateStatus)
        findViewById<TextView>(R.id.author).text =
            "Автор: ${BuildConfig.AUTHOR} · версия ${BuildConfig.VERSION_NAME}"
        updateStatus.text = "Версия ${BuildConfig.VERSION_NAME}"
        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener { checkUpdate(manual = true) }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettings() }

        findViewById<Button>(R.id.btnStart).setOnClickListener { startFlow() }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            CaptureVpnService.stop(this)
            MonitorService.stop(this)
        }
        findViewById<Button>(R.id.btnLog).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        findViewById<Button>(R.id.btnDownloadDb).setOnClickListener { downloadDatabase() }
        findViewById<Button>(R.id.btnImportDb).setOnClickListener {
            importDb.launch(arrayOf("*/*"))
        }

        Thread { GeoDb.load(applicationContext) }.start()

        AppSettings.load(this)
        val modeGroup = findViewById<RadioGroup>(R.id.modeGroup)
        modeGroup.check(if (AppSettings.mode == AppSettings.MODE_DIRECT) R.id.modeDirect else R.id.modeChain)
        modeGroup.setOnCheckedChangeListener { _, id ->
            AppSettings.mode = if (id == R.id.modeChain) AppSettings.MODE_API else AppSettings.MODE_DIRECT
            AppSettings.save(this)
            if (anyRunning()) toast("Режим поменяется после СТОП → СТАРТ")
        }

        if (savedInstanceState == null && AppSettings.autoCheckUpdates) checkUpdate(manual = false)
    }

    private fun showInfo(title: String, msg: String, action: Pair<String, () -> Unit>? = null) {
        if (isFinishing) return
        val b = AlertDialog.Builder(this).setTitle(title).setMessage(msg)
        if (action != null) {
            b.setPositiveButton(action.first) { _, _ -> action.second() }
            b.setNegativeButton("Отмена", null)
        } else {
            b.setPositiveButton("Понятно", null)
        }
        b.show()
    }

    private fun checkUpdate(manual: Boolean) {
        if (updateBusy) return
        updateBusy = true
        updateStatus.text = "Проверяю обновления…"
        Thread {
            try {
                val rel = Updater.check()
                runOnUiThread {
                    if (rel == null) {
                        updateStatus.text = "Версия ${BuildConfig.VERSION_NAME} — последняя"
                    } else {
                        updateStatus.text = "Доступна ${rel.tag}"
                        if (!isFinishing) askInstall(rel)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateStatus.text = "Обновления: ${e.message}"
                    if (manual) toast("Не удалось проверить: ${e.message}")
                }
            } finally {
                updateBusy = false
            }
        }.start()
    }

    private fun askInstall(rel: Updater.Release) {
        AlertDialog.Builder(this)
            .setTitle("Обновление ${rel.tag}")
            .setMessage(rel.notes.ifBlank { "Новая сборка доступна." }.take(1500))
            .setPositiveButton("Скачать и установить") { _, _ -> downloadUpdate(rel) }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun downloadUpdate(rel: Updater.Release) {
        if (updateBusy) return
        updateBusy = true
        Thread {
            try {
                val apk = Updater.download(applicationContext, rel) { msg -> runOnUiThread { updateStatus.text = msg } }
                runOnUiThread {
                    updateStatus.text = "Скачано, открываю установщик"
                    if (!Updater.install(this, apk)) {
                        toast("Разреши установку из этого приложения и нажми «Проверить обновления» ещё раз")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { updateStatus.text = "Ошибка обновления: ${e.message}" }
            } finally {
                updateBusy = false
            }
        }.start()
    }

    private fun showSettings() {
        AppSettings.load(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        fun label(t: String) = TextView(this).apply {
            text = t
            setPadding(0, pad, 0, 0)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val chainHint = TextView(this).apply {
            alpha = 0.7f
            textSize = 12f
            text = "Для режима «С моим VPN». Порт и клиент находятся сами при нажатии СТАРТ — " +
                "трогай, только если автоматика не справилась."
        }
        val host = EditText(this).apply {
            hint = "SOCKS5 IP (127.0.0.1)"
            setText(AppSettings.socksHost)
        }
        val port = EditText(this).apply {
            hint = "порт"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(AppSettings.socksPort.toString())
        }
        val user = EditText(this).apply {
            hint = "логин (если есть)"
            setText(AppSettings.socksUser)
        }
        val pass = EditText(this).apply {
            hint = "пароль (если есть)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(AppSettings.socksPass)
        }
        var clientPkg = AppSettings.clientPackage
        val client = Button(this)
        fun updateClient() {
            client.text = "VPN-клиент: " + (if (clientPkg.isBlank()) "не выбран" else appLabel(clientPkg))
        }
        updateClient()
        client.setOnClickListener {
            val apps = vpnClients()
            if (apps.isEmpty()) {
                toast("Не нашёл установленных VPN-клиентов")
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("Какой VPN-клиент используешь")
                .setItems(apps.map { it.second }.toTypedArray()) { _, i ->
                    clientPkg = apps[i].first
                    updateClient()
                }
                .show()
        }

        val alert = CheckBox(this).apply {
            text = "Вибрировать, если сервер не из списка стран"
            isChecked = AppSettings.alertEnabled
        }
        val countries = EditText(this).apply {
            hint = "страны через запятую, например RU,BY,KZ"
            setText(AppSettings.allowedCountries.joinToString(","))
        }
        val auto = CheckBox(this).apply {
            text = "Проверять обновления при запуске"
            isChecked = AppSettings.autoCheckUpdates
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(label("Прокси VPN-клиента"))
            addView(chainHint)
            addView(host)
            addView(port)
            addView(user)
            addView(pass)
            addView(client)
            addView(label("Предупреждение"))
            addView(alert)
            addView(countries)
            addView(label("Обновления"))
            addView(auto)
        }
        AlertDialog.Builder(this)
            .setTitle("Настройки")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Сохранить") { _, _ ->
                AppSettings.socksHost = host.text.toString().trim().ifEmpty { "127.0.0.1" }
                AppSettings.socksPort = port.text.toString().toIntOrNull()?.takeIf { it in 1..65535 } ?: 10808
                AppSettings.socksUser = user.text.toString()
                AppSettings.socksPass = pass.text.toString()
                AppSettings.clientPackage = clientPkg
                AppSettings.alertEnabled = alert.isChecked
                AppSettings.allowedCountries = AppSettings.parseCountries(countries.text.toString())
                AppSettings.autoCheckUpdates = auto.isChecked
                AppSettings.save(this)
                if (CaptureVpnService.isRunning) toast("Применится после СТОП → СТАРТ")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Приложения с VpnService — кандидаты в VPN-клиенты. */
    private fun vpnClients(): List<Pair<String, String>> {
        val pm = packageManager
        @Suppress("DEPRECATION")
        val services = pm.queryIntentServices(Intent("android.net.VpnService"), 0)
        return services.map { it.serviceInfo.packageName }
            .filter { it != packageName }
            .distinct()
            .map { it to appLabel(it) }
            .sortedBy { it.second.lowercase() }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    /** СТАРТ: проверяем всё по шагам и на каждой проблеме объясняем, что нажать. */
    private fun startFlow() {
        CaptureVpnService.lastError = null
        MonitorService.lastError = null
        shownError = null
        AppSettings.load(this)

        if (!isGameInstalled()) {
            showInfo("Игра не найдена", "Mobile Legends (${CaptureVpnService.GAME_PACKAGE}) не установлена на этом телефоне.")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("notif_asked", false)
        ) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notif_asked", true).apply()
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            showInfo(
                "Шаг 1: разреши оверлей",
                "Чтобы плашка показывалась поверх игры, найди в списке «MLBB Server» и включи «Поверх других окон». Потом вернись и снова нажми СТАРТ.",
                "Открыть настройки" to {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            )
            return
        }
        when (AppSettings.mode) {
            AppSettings.MODE_API -> startMonitorFlow()
            AppSettings.MODE_CHAIN -> prepareChain { launchVpn() }
            else -> launchVpn()
        }
    }

    private fun anyRunning() = CaptureVpnService.isRunning || MonitorService.isRunning

    /**
     * Режим «С моим VPN»: свой VPN не поднимаем. Список соединений берём у
     * VPN-клиента (Clash API), а если запущен Shizuku — из таблицы сокетов.
     */
    private fun startMonitorFlow() {
        CaptureVpnService.stop(this)
        if (ShizukuSource.granted()) {
            MonitorService.start(this)
            return
        }
        val progress = AlertDialog.Builder(this)
            .setTitle("Подключаюсь к VPN-клиенту")
            .setMessage("Секунду…")
            .setCancelable(false)
            .show()
        Thread {
            val found = ClashApi.find(AppSettings.apiPort, AppSettings.apiSecret) { msg ->
                runOnUiThread { progress.setMessage(msg) }
            }
            runOnUiThread {
                progress.dismiss()
                when (found?.second) {
                    ClashApi.Probe.OK -> {
                        AppSettings.apiPort = found.first
                        AppSettings.save(this)
                        MonitorService.start(this)
                        toast("Подключился к VPN-клиенту, порт ${found.first}")
                    }
                    ClashApi.Probe.NEED_SECRET -> askSecret(found.first)
                    else -> showInfo(
                        "VPN-клиент не отдаёт соединения",
                        "Не нашёл у твоего VPN-клиента Clash API — через него приложение узнаёт сервер игры, не включая свой VPN.\n\n" +
                            "• Проверь, что VPN подключён.\n" +
                            "• Работает с клиентами на sing-box и mihomo: Karing, Hiddify, NekoBox, FlClash, Clash Meta. " +
                            "Если в настройках клиента есть «Clash API», «External controller», «Контроллер» или «Dashboard» — включи.\n" +
                            "• С v2rayNG, Happ, v2RayTun так не получится: они список соединений никому не отдают."
                    )
                }
            }
        }.start()
    }

    private fun askSecret(port: Int) {
        val input = EditText(this).apply { hint = "секрет (secret)" }
        AlertDialog.Builder(this)
            .setTitle("Нужен пароль от Clash API")
            .setMessage("VPN-клиент на порту $port просит секрет. Он есть в настройках клиента рядом с «Clash API» / «External controller» (поле secret).")
            .setView(input)
            .setPositiveButton("Готово") { _, _ ->
                AppSettings.apiPort = port
                AppSettings.apiSecret = input.text.toString().trim()
                AppSettings.save(this)
                startMonitorFlow()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun launchVpn() {
        val prep = VpnService.prepare(this)
        if (prep != null) vpnPermission.launch(prep) else startService()
    }

    private fun startService() {
        CaptureVpnService.start(this)
        if (AppSettings.chainEnabled) {
            // Некоторые клиенты гасят прокси, когда у них отбирают VPN — ловим это
            val port = AppSettings.socksPort
            handler.postDelayed({
                Thread {
                    if (CaptureVpnService.isRunning && !ProxyDetector.isSocks5(port, AppSettings.socksHost)) {
                        runOnUiThread {
                            CaptureVpnService.stop(this)
                            showInfo(
                                "VPN-клиент выключился",
                                "${appLabel(AppSettings.clientPackage)} перестал отвечать, когда включился наш VPN. " +
                                    "Значит, в нём включён режим VPN/TUN.\n\n" + clientInstructions()
                            )
                        }
                    }
                }.start()
            }, 3000)
        }
    }

    private fun clientInstructions(): String {
        val name = appLabel(AppSettings.clientPackage).ifBlank { "свой VPN-клиент" }
        return "1. Открой $name.\n" +
            "2. В настройках переключи режим с «VPN»/«TUN» на «Прокси» (в Karing — выключи TUN; " +
            "в v2rayNG — режим «Только прокси»; в Hiddify — «Прокси»).\n" +
            "3. Подключись (нажми большую кнопку включения).\n" +
            "4. Вернись сюда и нажми СТАРТ."
    }

    private fun isInstalled(pkg: String) = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** Режим «с моим VPN»: находим клиент, его порт и проверяем UDP. */
    private fun prepareChain(onReady: () -> Unit) {
        if (AppSettings.clientPackage.isBlank() || !isInstalled(AppSettings.clientPackage)) {
            val apps = vpnClients()
            when {
                apps.isEmpty() -> {
                    showInfo("VPN-клиент не найден", "Не вижу на телефоне ни одного VPN-приложения. Установи свой клиент (Karing, v2rayNG, Hiddify…) или выбери режим «Без VPN».")
                    return
                }
                apps.size == 1 -> {
                    AppSettings.clientPackage = apps[0].first
                    AppSettings.save(this)
                }
                else -> {
                    AlertDialog.Builder(this)
                        .setTitle("Каким VPN пользуешься?")
                        .setItems(apps.map { it.second }.toTypedArray()) { _, i ->
                            AppSettings.clientPackage = apps[i].first
                            AppSettings.save(this)
                            prepareChain(onReady)
                        }
                        .show()
                    return
                }
            }
        }

        // Свой адрес или логин — значит человек всё настроил сам, не лезем
        if (AppSettings.socksHost != "127.0.0.1" || AppSettings.socksUser.isNotEmpty()) {
            onReady()
            return
        }

        val progress = AlertDialog.Builder(this)
            .setTitle("Ищу прокси ${appLabel(AppSettings.clientPackage)}")
            .setMessage("Секунду…")
            .setCancelable(false)
            .show()
        Thread {
            val port = ProxyDetector.findPort(AppSettings.socksPort) { msg ->
                runOnUiThread { progress.setMessage(msg) }
            }
            val udp = port != null && run {
                runOnUiThread { progress.setMessage("Прокси на порту $port. Проверяю, проходит ли UDP…") }
                ProxyDetector.udpWorks(port)
            }
            runOnUiThread {
                progress.dismiss()
                if (port == null) {
                    showInfo(
                        "Прокси не найден",
                        "Не вижу локального прокси VPN-клиента. Скорее всего, он выключен или работает в режиме VPN.\n\n" +
                            clientInstructions(),
                        "Открыть ${appLabel(AppSettings.clientPackage)}" to { openClient() }
                    )
                    return@runOnUiThread
                }
                AppSettings.socksHost = "127.0.0.1"
                AppSettings.socksPort = port
                AppSettings.save(this)
                if (udp) {
                    toast("Прокси найден: порт $port, UDP работает")
                    onReady()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("UDP через прокси не проходит")
                        .setMessage(
                            "Прокси найден (порт $port), но UDP через него не идёт. Без UDP игра скорее всего не подключится к матчу.\n\n" +
                                "В настройках ${appLabel(AppSettings.clientPackage)} поищи «UDP» и включи его для локального прокси, " +
                                "либо смени сервер/протокол на поддерживающий UDP."
                        )
                        .setPositiveButton("Всё равно запустить") { _, _ -> onReady() }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            }
        }.start()
    }

    private fun openClient() {
        packageManager.getLaunchIntentForPackage(AppSettings.clientPackage)?.let { startActivity(it) }
    }

    private fun isGameInstalled() = try {
        packageManager.getPackageInfo(CaptureVpnService.GAME_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun refresh() {
        val err = CaptureVpnService.lastError ?: MonitorService.lastError
        when {
            anyRunning() -> {
                statusBig.text = "● РАБОТАЕТ — запускай игру"
                statusBig.setTextColor(0xFF2E7D32.toInt())
            }
            err != null -> {
                statusBig.text = "✖ НЕ ЗАПУСТИЛОСЬ"
                statusBig.setTextColor(0xFFC62828.toInt())
            }
            else -> {
                statusBig.text = "○ ВЫКЛЮЧЕНО — жми СТАРТ"
                statusBig.setTextColor(0xFF9E9E9E.toInt())
            }
        }
        if (err != null && err != shownError) {
            shownError = err
            showInfo("Не запустилось", err + if (AppSettings.mode == AppSettings.MODE_CHAIN) "\n\n" + clientInstructions() else "")
        }

        val sb = StringBuilder()
        sb.append("Игра: ").append(if (isGameInstalled()) "установлена" else "НЕ установлена").append('\n')
        sb.append("Оверлей: ").append(if (Settings.canDrawOverlays(this)) "разрешён" else "нет разрешения").append('\n')
        sb.append("Режим: ").append(
            when (AppSettings.mode) {
                AppSettings.MODE_API -> "с твоим VPN, свой VPN не включаю" +
                    (if (MonitorService.isRunning) " (источник: ${MonitorService.sourceName})" else "")
                AppSettings.MODE_CHAIN -> "цепочка через SOCKS5 ${AppSettings.socksHost}:${AppSettings.socksPort}"
                else -> "без стороннего VPN, игра напрямую"
            }
        ).append('\n')

        val battle = ConnTracker.battleServer()
        if (battle != null) {
            val geo = GeoDb.lookup(battle.conn.dstIp)
            val ping = (CaptureVpnService.pinger?.last ?: MonitorService.lastPing)?.takeIf { it.ip == battle.conn.dstIp }
            sb.append("\nБоевой сервер: ${battle.conn.dstIp}:${battle.conn.dstPort}\n")
            if (geo != null) sb.append("${GeoDb.flag(geo.countryCode)} ${geo.country}, ${geo.city}\n")
            if (MonitorService.isRunning && ConnTracker.metricBytes) sb.append("Трафик за 10 с: ${battle.pktsLast10s / 1024} КБ\n")
            else if (!MonitorService.isRunning) sb.append("Пакетов за 10 с: ${battle.pktsLast10s}\n")
            if (ping != null && ping.ms >= 0) sb.append("Пинг: ${ping.ms} ms (${ping.method})\n")
        } else {
            sb.append("\nБоевой сервер: пока нет UDP-трафика\n")
        }
        status.text = sb.toString()
        if (!dbBusy) dbStatus.text = "База DB-IP: ${GeoDb.description()}"
    }

    private fun downloadDatabase() {
        if (dbBusy) return
        dbBusy = true
        Thread {
            try {
                GeoDb.download(applicationContext) { msg -> runOnUiThread { dbStatus.text = msg } }
            } catch (e: Exception) {
                runOnUiThread { dbStatus.text = "Не скачалось: ${e.message}\nМожно скачать .mmdb.gz вручную с db-ip.com и импортировать." }
            } finally {
                dbBusy = false
            }
        }.start()
    }

    private fun importDatabase(uri: Uri) {
        if (dbBusy) return
        dbBusy = true
        Thread {
            try {
                contentResolver.openInputStream(uri)!!.use { input ->
                    GeoDb.install(applicationContext, input) { msg -> runOnUiThread { dbStatus.text = msg } }
                }
            } catch (e: Exception) {
                runOnUiThread { dbStatus.text = "Импорт не удался: ${e.message}" }
            } finally {
                dbBusy = false
            }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
