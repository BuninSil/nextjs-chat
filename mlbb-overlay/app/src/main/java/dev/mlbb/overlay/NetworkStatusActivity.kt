package dev.mlbb.overlay

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class NetworkStatusActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var connectionValue: TextView
    private lateinit var networkValue: TextView
    private lateinit var serverValue: TextView
    private lateinit var pingValue: TextView
    private lateinit var jitterValue: TextView
    private lateinit var downloadValue: TextView
    private lateinit var uploadValue: TextView
    private lateinit var ipValue: TextView

    private val ticker = object : Runnable {
        override fun run() {
            updateValues()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppSettings.load(this)
        setTheme(Ui.themeRes())
        super.onCreate(savedInstanceState)
        Ui.applyWindow(this)
        supportActionBar?.hide()

        val d = { v: Float -> Ui.dp(this, v) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(18f), d(18f), d(18f), d(18f))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.text(this, "Состояние сети", 23f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Ui.text(this, "‹", 30f, Ui.TEXT).apply {
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(d(40f), d(44f)))
        root.addView(header)
        root.addView(Ui.text(this, "Текущие показатели подключения", 13f, Ui.MUTED).apply {
            setPadding(0, d(3f), 0, d(16f))
        })

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun section(name: String): LinearLayout {
            val card = Ui.card(this).apply { setPadding(d(16f), d(15f), d(16f), d(15f)) }
            card.addView(Ui.text(this, name, 14f, Ui.MUTED, bold = true))
            body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(10f) })
            return card
        }
        fun line(card: LinearLayout, label: String): TextView {
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, d(9f), 0, d(2f))
            }
            row.addView(Ui.text(this, label, 14f, Ui.SUB), LinearLayout.LayoutParams(0, -2, 1f))
            val value = Ui.text(this, "—", 15f, Ui.TEXT, bold = true)
            row.addView(value)
            card.addView(row)
            return value
        }

        val status = section("ПОДКЛЮЧЕНИЕ")
        connectionValue = line(status, "VPN")
        networkValue = line(status, "Сеть телефона")
        serverValue = line(status, "Сервер")

        val latency = section("ЗАДЕРЖКА")
        pingValue = line(latency, "Пинг до сервера")
        jitterValue = line(latency, "Разброс")

        val traffic = section("ТЕКУЩАЯ СКОРОСТЬ")
        downloadValue = line(traffic, "↓ Загрузка")
        uploadValue = line(traffic, "↑ Отдача")

        val ip = section("ВНЕШНИЙ IP")
        ipValue = line(ip, "Адрес и страна")
        ip.addView(Ui.text(this, "Показатели обновляются по мере поступления реальных данных. История не сохраняется.", 12f, Ui.MUTED).apply {
            setPadding(0, d(8f), 0, 0)
        })

        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        if (MyIp.stale()) MyIp.refresh { runOnUiThread { if (!isFinishing) updateValues() } }
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun formatSpeed(bytesPerSecond: Double): String {
        if (bytesPerSecond <= 0.0) return "0 КБ/с"
        val kb = bytesPerSecond / 1024.0
        return if (kb >= 1024.0) String.format(java.util.Locale.US, "%.2f МБ/с", kb / 1024.0)
            else String.format(java.util.Locale.US, "%.0f КБ/с", kb)
    }

    private fun networkType(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "Нет подключения"
        val caps = cm.getNetworkCapabilities(network) ?: return "Неизвестно"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобильная сеть"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "Другая сеть"
        }
    }

    private fun updateValues() {
        if (!::connectionValue.isInitialized) return
        val running = BoxVpnService.isRunning
        connectionValue.text = if (running) "Подключён" else if (BoxVpnService.isStarting) "Подключение…" else "Отключён"
        networkValue.text = networkType()
        val node = Subscription.usable(this).firstOrNull { it.tag == AppSettings.selectedTag }
        val result = node?.let { ServerTester.results[it.tag] }
        serverValue.text = node?.let { Ui.cleanName(it) } ?: if (AppSettings.selectedTag == XrayCore.BYPASS) "Обход блокировок" else "—"
        pingValue.text = result?.let { "${it.medianMs} мс" } ?: "Нет замера"
        jitterValue.text = result?.let { "${it.jitterMs} мс" } ?: "Нет замера"
        downloadValue.text = if (running) formatSpeed(BoxVpnService.downBps) else "—"
        uploadValue.text = if (running) formatSpeed(BoxVpnService.upBps) else "—"
        ipValue.text = MyIp.line().ifBlank { "Нет данных" }
    }
}
