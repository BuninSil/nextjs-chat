package dev.mlbb.overlay

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = ConnAdapter()
    private lateinit var summary: TextView

    private val exportCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) export(uri)
    }

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        title = "Лог соединений"
        summary = findViewById(R.id.summary)
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<Button>(R.id.btnExport).setOnClickListener {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            exportCsv.launch("mlbb-connections-$stamp.csv")
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            ConnTracker.clear()
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    private fun refresh() {
        val items = ConnTracker.snapshot().sortedByDescending { it.firstSeenMs }
        val battleKey = ConnTracker.battleServer()?.conn?.key
        val active = items.count { !it.closed }
        summary.text = "Всего: ${items.size}, активных: $active"
        adapter.submit(items, battleKey)
    }

    private fun export(uri: Uri) {
        val items = ConnTracker.snapshot().sortedBy { it.firstSeenMs }
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        try {
            contentResolver.openOutputStream(uri)!!.bufferedWriter().use { w ->
                w.write("first_packet,last_packet,protocol,src_port,dst_ip,dst_port,bytes_out,bytes_in,packets_out,packets_in,tcp_handshake_rtt_ms,country_code,country,city,status\n")
                for (c in items) {
                    val geo = GeoDb.lookup(c.dstIp)
                    val row = listOf(
                        iso.format(Date(c.firstSeenMs)), iso.format(Date(c.lastSeenMs)),
                        ConnTracker.protoName(c.proto), c.srcPort.toString(), c.dstIp, c.dstPort.toString(),
                        c.bytesOut.toString(), c.bytesIn.toString(), c.pktsOut.toString(), c.pktsIn.toString(),
                        if (c.hsRttMs >= 0) c.hsRttMs.toString() else "",
                        geo?.countryCode ?: "", geo?.country ?: "", geo?.city ?: "",
                        if (c.closed) "closed" else "active",
                    )
                    w.write(row.joinToString(",") { csv(it) })
                    w.write("\n")
                }
            }
            Toast.makeText(this, "Сохранено строк: ${items.size}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun csv(s: String) =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val line1: TextView = v.findViewById(R.id.line1)
        val line2: TextView = v.findViewById(R.id.line2)
    }

    private class ConnAdapter : RecyclerView.Adapter<Holder>() {
        private var items: List<ConnTracker.Conn> = emptyList()
        private var battleKey: Long? = null
        private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

        fun submit(list: List<ConnTracker.Conn>, battle: Long?) {
            items = list
            battleKey = battle
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_conn, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: Holder, pos: Int) {
            val c = items[pos]
            val geo = GeoDb.lookup(c.dstIp)
            val place = geo?.let { "${GeoDb.flag(it.countryCode)} ${it.city.ifEmpty { it.country }}" } ?: ""
            val isBattle = c.key == battleKey
            h.line1.text = "${time.format(Date(c.firstSeenMs))}  ${ConnTracker.protoName(c.proto)}  ${c.dstIp}:${c.dstPort}  $place" +
                if (isBattle) "  · бой" else ""
            h.line1.setTypeface(null, if (isBattle) Typeface.BOLD else Typeface.NORMAL)
            val rtt = if (c.hsRttMs >= 0) " · hs ${c.hsRttMs} ms" else ""
            h.line2.text = "↑ ${fmt(c.bytesOut)} (${c.pktsOut})  ↓ ${fmt(c.bytesIn)} (${c.pktsIn})$rtt · " +
                if (c.closed) "закрыто" else "активно"
        }

        private fun fmt(b: Long) = when {
            b >= 1_048_576 -> String.format(Locale.US, "%.1f MB", b / 1_048_576.0)
            b >= 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
            else -> "$b B"
        }
    }
}
