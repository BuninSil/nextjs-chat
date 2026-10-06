package dev.mlbb.overlay

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Какие приложения идут через VPN: все, только выбранные или все, кроме выбранных. */
class AppsActivity : AppCompatActivity() {
    private data class AppItem(val pkg: String, val label: String)

    private var all: List<AppItem> = emptyList()
    private var filter = ""
    private val icons = HashMap<String, Drawable?>()
    private val adapter = Adapter()
    private val modeCards = HashMap<Int, LinearLayout>()
    private lateinit var hint: TextView

    private fun d(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        AppSettings.load(this)
        setTheme(Ui.themeRes())
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        Ui.applyWindow(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16f), d(20f), d(16f), 0)
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.backButton(this) { finish() })
        top.addView(Ui.text(this, "Приложения через VPN", 22f, bold = true))
        root.addView(top)
        root.addView(Ui.space(this, 12f))

        // Режим
        root.addView(modeCard(AppSettings.APPS_ALL, "Все приложения", "Через VPN идёт весь телефон"))
        root.addView(Ui.space(this, 8f))
        root.addView(modeCard(AppSettings.APPS_ONLY, "Только выбранные", "Через VPN — только отмеченные ниже, остальные напрямую"))
        root.addView(Ui.space(this, 8f))
        root.addView(modeCard(AppSettings.APPS_EXCEPT, "Все, кроме выбранных", "Отмеченные ниже идут напрямую — например, банк"))
        highlight()

        hint = Ui.text(this, "", 12f, Ui.MUTED).apply { setPadding(d(4f), d(10f), d(4f), d(6f)) }
        root.addView(hint)
        highlight()
        root.addView(EditText(this).apply {
            this.hint = "Поиск приложения"
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.HINT)
            textSize = 15f
            background = Ui.rounded(Ui.INPUT, d(12f).toFloat())
            setPadding(d(12f), d(10f), d(12f), d(10f))
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { filter = s?.toString()?.trim() ?: ""; adapter.reload() }
            })
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(Ui.space(this, 8f))

        val list = RecyclerView(this)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        // Список приложений с иконкой на рабочем столе — в фоне, их бывает много
        Thread {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val apps = pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .filter { it.first != packageName }
                .distinctBy { it.first }
                .map { AppItem(it.first, it.second) }
                .sortedBy { it.label.lowercase() }
            runOnUiThread { all = apps; adapter.reload() }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        AppSettings.save(this)
    }

    private fun modeCard(mode: Int, title: String, sub: String): LinearLayout {
        val c = Ui.card(this)
        c.addView(Ui.text(this, title, 15f, bold = true))
        c.addView(Ui.text(this, sub, 12f, Ui.MUTED).apply { setPadding(0, d(3f), 0, 0) })
        c.setOnClickListener {
            AppSettings.appsMode = mode
            AppSettings.save(this)
            highlight()
            adapter.reload()
        }
        modeCards[mode] = c
        return c
    }

    private fun highlight() {
        for ((mode, card) in modeCards) {
            val sel = mode == AppSettings.appsMode
            card.background = Ui.rounded(if (sel) Ui.SEL else Ui.CARD, d(16f).toFloat()).apply {
                if (sel) setStroke(d(2f), Ui.GREEN)
            }
        }
        if (!::hint.isInitialized) return
        hint.text = when (AppSettings.appsMode) {
            AppSettings.APPS_ONLY -> "Отметь приложения, которые пойдут через VPN. Выбрано: ${AppSettings.appsList.size}"
            AppSettings.APPS_EXCEPT -> "Отметь приложения, которые пойдут мимо VPN. Выбрано: ${AppSettings.appsList.size}"
            else -> "Сейчас через VPN идут все приложения. Выбери режим выше, чтобы отметить нужные."
        }
        if (BoxVpnService.isRunning) hint.append("\nПрименится после переподключения VPN.")
    }

    private class Holder(v: LinearLayout) : RecyclerView.ViewHolder(v) {
        val root = v
        lateinit var icon: ImageView
        lateinit var name: TextView
        lateinit var check: CheckBox
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        private var items: List<AppItem> = emptyList()

        fun reload() {
            val q = filter.lowercase()
            // Отмеченные — сверху
            items = all.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q) }
                .sortedBy { if (it.pkg in AppSettings.appsList) 0 else 1 }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(parent.context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(d(12f), d(10f), d(8f), d(10f))
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = d(6f) }
                background = Ui.rounded(Ui.CARD, d(12f).toFloat())
            }
            val h = Holder(row)
            h.icon = ImageView(parent.context)
            row.addView(h.icon, LinearLayout.LayoutParams(d(36f), d(36f)))
            h.name = Ui.text(parent.context, "", 14f).apply { setPadding(d(12f), 0, d(8f), 0) }
            row.addView(h.name, LinearLayout.LayoutParams(0, -2, 1f))
            h.check = CheckBox(parent.context).apply {
                buttonTintList = android.content.res.ColorStateList.valueOf(Ui.GREEN)
                isClickable = false
            }
            row.addView(h.check)
            return h
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: Holder, pos: Int) {
            val a = items[pos]
            h.name.text = a.label
            val icon = icons.getOrPut(a.pkg) {
                try { packageManager.getApplicationIcon(a.pkg) } catch (_: PackageManager.NameNotFoundException) { null }
            }
            h.icon.setImageDrawable(icon)
            val active = AppSettings.appsMode != AppSettings.APPS_ALL
            h.check.isChecked = a.pkg in AppSettings.appsList
            h.check.isEnabled = active
            h.root.alpha = if (active) 1f else 0.5f
            h.root.setOnClickListener {
                if (!active) return@setOnClickListener
                val set = AppSettings.appsList.toMutableSet()
                if (!set.remove(a.pkg)) set.add(a.pkg)
                AppSettings.appsList = set
                AppSettings.save(this@AppsActivity)
                h.check.isChecked = a.pkg in set
                highlight()
            }
        }
    }
}
