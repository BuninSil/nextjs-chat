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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var dbStatus: TextView
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
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
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
