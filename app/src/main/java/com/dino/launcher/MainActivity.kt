package com.dino.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    class App(val pkg: String, val orig: String, val icon: Drawable)

    lateinit var p: SharedPreferences
    var apps = listOf<App>()
    var query = ""

    fun gi(k: String, d: Int) = p.getInt(k, d)
    fun gb(k: String, d: Boolean) = p.getBoolean(k, d)
    fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    fun label(a: App) = p.getString("n_" + a.pkg, null) ?: a.orig
    fun dock(): List<String> = (p.getString("dock", "") ?: "").split(",").filter { it.isNotEmpty() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        p = getSharedPreferences("cfg", 0)
    }

    override fun onResume() {
        super.onResume()
        load(); build()
    }

    override fun onBackPressed() {}

    fun load() {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val hidden = p.getStringSet("hidden", emptySet())!!
        apps = pm.queryIntentActivities(i, 0)
            .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
            .distinctBy { it.pkg }
            .filter { it.pkg !in hidden }
    }

    fun launch(a: App) {
        packageManager.getLaunchIntentForPackage(a.pkg)?.let { startActivity(it) }
    }

    fun build() {
        val tc = gi("tc", Color.WHITE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(gi("bg", 0x55000000))
            setPadding(dp(8), dp(36), dp(8), dp(8))
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        if (gb("clock", true)) top.addView(TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"
            textSize = gi("cs", 40).toFloat(); setTextColor(tc)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        else top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(TextView(this).apply {
            text = "⚙"; textSize = 28f; setTextColor(tc); setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { settings() }
        })
        root.addView(top)

        if (gb("search", true)) root.addView(EditText(this).apply {
            hint = "Поиск"; setHintTextColor(tc and 0x88FFFFFF.toInt()); setTextColor(tc)
            setSingleLine(); setText(query); setSelection(query.length)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if (s.toString() != query) { query = s.toString(); fill(root) }
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })

        val grid = GridView(this).apply {
            tag = "grid"; numColumns = gi("cols", 4); selector = null
            verticalSpacing = dp(gi("gap", 4)); setPadding(0, dp(8), 0, 0)
            clipToPadding = false
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))

        if (gb("dock", true)) {
            val d = LinearLayout(this).apply {
                tag = "dock"; gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(8))
                background = GradientDrawable().apply {
                    setColor(gi("dockc", 0x33FFFFFF)); cornerRadius = dp(gi("dockr", 24)).toFloat()
                }
            }
            root.addView(d, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(root)
        fill(root)
    }

    fun fill(root: View) {
        val list = apps.filter { label(it).contains(query, true) }
            .sortedBy { label(it).lowercase() }.let { if (gb("rev", false)) it.reversed() else it }
        root.findViewWithTag<GridView>("grid").apply {
            adapter = object : BaseAdapter() {
                override fun getCount() = list.size
                override fun getItem(i: Int) = list[i]
                override fun getItemId(i: Int) = i.toLong()
                override fun getView(i: Int, v: View?, parent: ViewGroup) = cell(list[i], true)
            }
        }
        root.findViewWithTag<LinearLayout>("dock")?.apply {
            removeAllViews()
            dock().mapNotNull { pk -> apps.find { it.pkg == pk } }.forEach {
                addView(cell(it, gb("dlbl", false)), LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
    }

    fun cell(a: App, showLabel: Boolean): View {
        val s = dp(gi("is", 56))
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(8))
            addView(ImageView(context).apply {
                setImageDrawable(a.icon)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, v.width * gi("rad", 22) / 100f)
                    }
                }
                clipToOutline = true
            }, LinearLayout.LayoutParams(s, s))
            if (showLabel && gb("lbl", true)) addView(TextView(context).apply {
                text = label(a); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setTextColor(gi("tc", Color.WHITE)); textSize = gi("ts", 12).toFloat()
                gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
            })
            setOnClickListener { launch(a) }
            setOnLongClickListener { menu(a); true }
        }
    }

    fun menu(a: App) {
        val inDock = a.pkg in dock()
        val items = arrayOf(
            "Удалить", "Информация о приложении", "Переименовать", "Скрыть",
            if (inDock) "Убрать из дока" else "В док"
        )
        AlertDialog.Builder(this).setTitle(label(a)).setItems(items) { _, w ->
            when (w) {
                0 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)))
                1 -> startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg))
                )
                2 -> rename(a)
                3 -> {
                    val h = p.getStringSet("hidden", emptySet())!!.toMutableSet().apply { add(a.pkg) }
                    p.edit().putStringSet("hidden", h).apply(); load(); build()
                }
                4 -> {
                    val d = dock().toMutableList()
                    if (inDock) d.remove(a.pkg) else if (d.size < 5) d.add(a.pkg)
                    else Toast.makeText(this, "В доке максимум 5", Toast.LENGTH_SHORT).show()
                    p.edit().putString("dock", d.joinToString(",")).apply(); build()
                }
            }
        }.show()
    }

    fun rename(a: App) {
        val e = EditText(this).apply { setText(label(a)) }
        AlertDialog.Builder(this).setTitle("Новое имя").setView(e)
            .setPositiveButton("OK") { _, _ ->
                val t = e.text.toString().trim()
                p.edit().apply { if (t.isEmpty()) remove("n_" + a.pkg) else putString("n_" + a.pkg, t) }.apply()
                build()
            }.setNegativeButton("Отмена", null).show()
    }

    // ---------- Настройки ----------
    fun LinearLayout.seek(t: String, k: String, mn: Int, mx: Int, d: Int) {
        val tv = TextView(context).apply { text = "$t: ${gi(k, d)}"; setPadding(0, dp(12), 0, 0) }
        addView(tv)
        addView(SeekBar(context).apply {
            max = mx - mn; progress = gi(k, d) - mn
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                    if (u) { p.edit().putInt(k, v + mn).apply(); tv.text = "$t: ${v + mn}"; build() }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
    }

    fun LinearLayout.sw(t: String, k: String, d: Boolean) {
        addView(Switch(context).apply {
            text = t; isChecked = gb(k, d); setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, c -> p.edit().putBoolean(k, c).apply(); build() }
        })
    }

    fun LinearLayout.color(t: String, k: String, d: Int) {
        addView(TextView(context).apply { text = "$t (#AARRGGBB)"; setPadding(0, dp(12), 0, 0) })
        addView(EditText(context).apply {
            setText(String.format("#%08X", gi(k, d))); setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    try { p.edit().putInt(k, Color.parseColor(s.toString())).apply(); build() } catch (e: Exception) {}
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })
    }

    fun settings() {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        l.seek("Колонок", "cols", 2, 8, 4)
        l.seek("Размер иконок (dp)", "is", 32, 96, 56)
        l.seek("Скругление иконок (%)", "rad", 0, 50, 22)
        l.seek("Размер подписей", "ts", 8, 20, 12)
        l.seek("Отступ между рядами", "gap", 0, 40, 4)
        l.seek("Размер часов", "cs", 16, 80, 40)
        l.seek("Скругление дока", "dockr", 0, 50, 24)
        l.sw("Показывать подписи", "lbl", true)
        l.sw("Подписи в доке", "dlbl", false)
        l.sw("Часы", "clock", true)
        l.sw("Поиск", "search", true)
        l.sw("Док", "dock", true)
        l.sw("Сортировка Я→А", "rev", false)
        l.color("Цвет фона (с прозрачностью)", "bg", 0x55000000)
        l.color("Цвет текста", "tc", Color.WHITE)
        l.color("Цвет дока", "dockc", 0x33FFFFFF)
        l.addView(Button(this).apply {
            text = "Показать скрытые приложения"
            setOnClickListener { p.edit().remove("hidden").apply(); load(); build() }
        })
        l.addView(Button(this).apply {
            text = "Сбросить всё"
            setOnClickListener { p.edit().clear().apply(); load(); build() }
        })
        AlertDialog.Builder(this).setTitle("Настройки").setView(ScrollView(this).apply { addView(l) })
            .setPositiveButton("Готово", null).show()
    }
}
