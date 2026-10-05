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
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var dbStatus: TextView
    private lateinit var updateStatus: TextView
    @Volatile
    private var updateBusy = false
    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var dbBusy = false

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) CaptureVpnService.start(this)
        else toast("Без разрешения на VPN ничего не выйдет")
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
        dbStatus = findViewById(R.id.dbStatus)
        updateStatus = findViewById(R.id.updateStatus)
        findViewById<TextView>(R.id.author).text =
            "Автор: ${BuildConfig.AUTHOR} · версия ${BuildConfig.VERSION_NAME}"
        updateStatus.text = "Версия ${BuildConfig.VERSION_NAME}"
        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener { checkUpdate(manual = true) }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettings() }

        findViewById<Button>(R.id.btnStart).setOnClickListener { startFlow() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { CaptureVpnService.stop(this) }
        findViewById<Button>(R.id.btnLog).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        findViewById<Button>(R.id.btnDownloadDb).setOnClickListener { downloadDatabase() }
        findViewById<Button>(R.id.btnImportDb).setOnClickListener {
            importDb.launch(arrayOf("*/*"))
        }

        Thread { GeoDb.load(applicationContext) }.start()

        AppSettings.load(this)
        if (savedInstanceState == null && AppSettings.autoCheckUpdates) checkUpdate(manual = false)
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

        val chain = CheckBox(this).apply {
            text = "Через сторонний VPN (SOCKS5)"
            isChecked = AppSettings.chainEnabled
        }
        val hint = TextView(this).apply {
            alpha = 0.7f
            textSize = 12f
            text = "В клиенте (v2rayNG, Hiddify и т.п.) включи режим «только прокси» и UDP " +
                "для SOCKS-входа. Весь трафик телефона пойдёт через него, игра — тоже."
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
            addView(label("Режим VPN"))
            addView(chain)
            addView(hint)
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
                AppSettings.chainEnabled = chain.isChecked
                AppSettings.socksHost = host.text.toString().trim().ifEmpty { "127.0.0.1" }
                AppSettings.socksPort = port.text.toString().toIntOrNull()?.takeIf { it in 1..65535 } ?: 10808
                AppSettings.socksUser = user.text.toString()
                AppSettings.socksPass = pass.text.toString()
                AppSettings.clientPackage = clientPkg
                AppSettings.alertEnabled = alert.isChecked
                AppSettings.allowedCountries = AppSettings.parseCountries(countries.text.toString())
                AppSettings.autoCheckUpdates = auto.isChecked
                AppSettings.save(this)
                if (CaptureVpnService.isRunning) toast("Режим VPN применится после перезапуска (Стоп → Старт)")
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

    private fun startFlow() {
        if (!isGameInstalled()) {
            toast("Mobile Legends (${CaptureVpnService.GAME_PACKAGE}) не найдена на устройстве")
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
            toast("Разреши показ поверх других окон и нажми «Старт» ещё раз")
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        val prep = VpnService.prepare(this)
        if (prep != null) vpnPermission.launch(prep) else CaptureVpnService.start(this)
    }

    private fun isGameInstalled() = try {
        packageManager.getPackageInfo(CaptureVpnService.GAME_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun refresh() {
        val sb = StringBuilder()
        sb.append(if (CaptureVpnService.isRunning) "● Захват работает" else "○ Захват остановлен").append('\n')
        CaptureVpnService.lastError?.let { sb.append("Ошибка: ").append(it).append('\n') }
        sb.append("Игра: ").append(if (isGameInstalled()) "установлена" else "НЕ установлена").append('\n')
        sb.append("Оверлей: ").append(if (Settings.canDrawOverlays(this)) "разрешён" else "нет разрешения").append('\n')
        sb.append("Режим: ").append(
            if (AppSettings.chainEnabled) "через ${appLabel(AppSettings.clientPackage)} (SOCKS5 ${AppSettings.socksHost}:${AppSettings.socksPort})"
            else "только игра, напрямую"
        ).append('\n')

        val battle = ConnTracker.battleServer()
        if (battle != null) {
            val geo = GeoDb.lookup(battle.conn.dstIp)
            val ping = CaptureVpnService.pinger?.last?.takeIf { it.ip == battle.conn.dstIp }
            sb.append("\nБоевой сервер: ${battle.conn.dstIp}:${battle.conn.dstPort}\n")
            if (geo != null) sb.append("${GeoDb.flag(geo.countryCode)} ${geo.country}, ${geo.city}\n")
            sb.append("Пакетов за 10 с: ${battle.pktsLast10s}\n")
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
