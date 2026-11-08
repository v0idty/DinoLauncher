package com.dino.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ResolveInfo
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.*
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.*
import android.widget.*
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {
    class App(val pkg: String, val orig: String, val icon: Drawable)

    lateinit var p: SharedPreferences
    var apps = listOf<App>()
    var query = ""
    var drawer = false
    var wall: Drawable? = null
    var wallMode = -1
    var x0 = 0f
    var y0 = 0f
    val DLG = android.R.style.Theme_DeviceDefault_Dialog_Alert
    val LT = 0xFFE6E1E5.toInt()

    fun gi(k: String, d: Int) = p.getInt(k, d)
    fun gb(k: String, d: Boolean) = p.getBoolean(k, d)
    fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    fun label(a: App) = p.getString("n_" + a.pkg, null) ?: a.orig
    fun lst(k: String): List<String> = (p.getString(k, "") ?: "").split(",").filter { it.isNotEmpty() }
    fun put(k: String, l: List<String>) = p.edit().putString(k, l.joinToString(",")).apply()
    val tc get() = gi("tc", Color.WHITE)
    val ac get() = gi("ac", 0xFF8AB4F8.toInt())

    fun txt(t: String, s: Float) = TextView(this).apply {
        text = t; textSize = s; setTextColor(tc); setPadding(dp(10), dp(4), dp(10), dp(4))
    }
    fun pill(c: Int, r: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        p = getSharedPreferences("cfg", 0)
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        drawer = false; query = ""
    }

    override fun onResume() {
        super.onResume()
        load(); build()
    }

    override fun onBackPressed() {
        if (drawer) { drawer = false; query = ""; build() }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) { x0 = ev.x; y0 = ev.y }
        val r = super.dispatchTouchEvent(ev)
        if (!drawer && ev.action == MotionEvent.ACTION_UP && y0 - ev.y > dp(120) && Math.abs(ev.x - x0) < y0 - ev.y) {
            drawer = true; build()
        }
        return r
    }

    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        super.onActivityResult(r, c, d)
        val u = d?.data ?: return
        if (r != 7 || c != RESULT_OK) return
        try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
            var s = 1
            while (o.outWidth / s > 2000) s *= 2
            val b = contentResolver.openInputStream(u)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
            }
            if (b != null) {
                FileOutputStream(File(filesDir, "wall.jpg")).use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                p.edit().putInt("wm", 2).apply(); wallMode = -1
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось загрузить фото", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- данные ----------
    fun icon(ri: ResolveInfo): Drawable {
        if (gb("oi", true)) try {
            val ai = ri.activityInfo
            val id = if (ai.icon != 0) ai.icon else ai.applicationInfo.icon
            if (id != 0) {
                val d = packageManager.getResourcesForApplication(ai.applicationInfo).getDrawableForDensity(id, 480, null)
                if (d != null) return d
            }
        } catch (e: Exception) {}
        return ri.loadIcon(packageManager)
    }

    fun load() {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val hidden = p.getStringSet("hidden", emptySet())!!
        apps = pm.queryIntentActivities(i, 0)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName !in hidden }
            .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString(), icon(it)) }
    }

    fun wallpaper(): Drawable? {
        val m = gi("wm", 0)
        if (m != wallMode) {
            wallMode = m
            wall = try {
                when (m) {
                    0 -> resources.getIdentifier("default_wall", "drawable", packageName)
                        .let { if (it != 0) resources.getDrawable(it, null) else null }
                    2 -> File(filesDir, "wall.jpg").takeIf { it.exists() }
                        ?.let { BitmapDrawable(resources, BitmapFactory.decodeFile(it.path)) }
                    else -> null
                }
            } catch (e: Exception) { null }
        }
        return wall
    }

    fun launch(a: App) {
        packageManager.getLaunchIntentForPackage(a.pkg)?.let { startActivity(it) }
    }

    // ---------- экраны ----------
    fun build() {
        val fr = FrameLayout(this)
        val w = wallpaper()
        if (w == null && gi("wm", 0) != 1) fr.setBackgroundColor(gi("wc", 0xFF1B1B1F.toInt()))
        if (w != null) fr.addView(ImageView(this).apply {
            setImageDrawable(w); scaleType = ImageView.ScaleType.CENTER_CROP
        }, FrameLayout.LayoutParams(-1, -1))
        fr.addView(View(this).apply { setBackgroundColor(gi("bg", 0x33000000)) }, FrameLayout.LayoutParams(-1, -1))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(40), dp(12), dp(28))
        }
        fr.addView(col, FrameLayout.LayoutParams(-1, -1))
        if (drawer) buildDrawer(col) else buildHome(col)
        setContentView(fr)
        fill(col)
    }

    fun newGrid(n: Int) = GridView(this).apply {
        tag = "grid"; numColumns = n; verticalSpacing = dp(gi("gap", 6))
        setPadding(0, dp(8), 0, 0); clipToPadding = false
    }

    fun buildHome(col: LinearLayout) {
        val top = LinearLayout(this)
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        if (gb("clock", true)) {
            c.addView(TextClock(this).apply {
                format24Hour = "HH:mm"; format12Hour = "HH:mm"; textSize = gi("cs", 56).toFloat(); setTextColor(tc)
            })
            c.addView(TextClock(this).apply {
                format24Hour = "EEEE, d MMMM"; format12Hour = "EEEE, d MMMM"; textSize = 16f; setTextColor(tc)
            })
        }
        top.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(txt("⚙", 28f).apply { setOnClickListener { settings() } })
        col.addView(top)
        if (lst("desk").isEmpty()) col.addView(
            txt("Пусто. Смахни вверх, зажми приложение и выбери «На главный стол»", 15f).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, 0, 1f)
        ) else col.addView(newGrid(gi("hc", 4)), LinearLayout.LayoutParams(-1, 0, 1f))
        if (gb("dock", true)) col.addView(LinearLayout(this).apply {
            tag = "dock"; gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(8))
            background = pill(gi("dockc", 0x33FFFFFF), gi("dockr", 28))
        }, LinearLayout.LayoutParams(-1, -2))
        col.addView(txt("⌃  Все приложения", 14f).apply {
            gravity = Gravity.CENTER; setOnClickListener { drawer = true; build() }
        }, LinearLayout.LayoutParams(-1, -2))
    }

    fun buildDrawer(col: LinearLayout) {
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(txt("⌄", 30f).apply { setOnClickListener { drawer = false; query = ""; build() } })
        if (gb("search", true)) top.addView(EditText(this).apply {
            hint = "Поиск"; setHintTextColor(tc and 0x88FFFFFF.toInt()); setTextColor(tc); setSingleLine()
            background = pill(gi("pc", 0x33FFFFFF), 24)
            setPadding(dp(16), dp(8), dp(16), dp(8)); setText(query); setSelection(query.length)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if (s.toString() != query) { query = s.toString(); fill(col) }
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        else top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(txt("⚙", 28f).apply { setOnClickListener { settings() } })
        col.addView(top)
        col.addView(newGrid(gi("cols", 4)), LinearLayout.LayoutParams(-1, 0, 1f))
    }

    fun fill(root: View) {
        val list = if (drawer)
            apps.filter { label(it).contains(query, true) }.sortedBy { label(it).lowercase() }
                .let { if (gb("rev", false)) it.reversed() else it }
        else lst("desk").mapNotNull { pk -> apps.find { it.pkg == pk } }
        root.findViewWithTag<GridView>("grid")?.adapter = object : BaseAdapter() {
            override fun getCount() = list.size
            override fun getItem(i: Int) = list[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, v: View?, parent: ViewGroup) = cell(list[i], true)
        }
        root.findViewWithTag<LinearLayout>("dock")?.apply {
            removeAllViews()
            lst("dock").mapNotNull { pk -> apps.find { it.pkg == pk } }.forEach {
                addView(cell(it, gb("dlbl", false)), LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
    }

    fun cell(a: App, showLabel: Boolean): View {
        val s = dp(gi("is", 60))
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(8))
            addView(ImageView(context).apply {
                setImageDrawable(a.icon)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, v.width * gi("rad", 24) / 100f)
                    }
                }
                clipToOutline = true
            }, LinearLayout.LayoutParams(s, s))
            if (showLabel && gb("lbl", true)) addView(TextView(context).apply {
                text = label(a); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setTextColor(tc); textSize = gi("ts", 12).toFloat()
                gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
            })
            setOnClickListener { launch(a) }
            setOnLongClickListener { menu(a); true }
        }
    }

    fun toggle(k: String, pkg: String, max: Int) {
        val l = lst(k).toMutableList()
        if (pkg in l) l.remove(pkg)
        else if (l.size < max) l.add(pkg)
        else Toast.makeText(this, "Максимум $max", Toast.LENGTH_SHORT).show()
        put(k, l); build()
    }

    fun menu(a: App) {
        val items = arrayOf(
            "Удалить", "Информация о приложении", "Переименовать", "Скрыть",
            if (a.pkg in lst("dock")) "Убрать из дока" else "В док",
            if (a.pkg in lst("desk")) "Убрать с главного стола" else "На главный стол"
        )
        AlertDialog.Builder(this, DLG).setTitle(label(a)).setItems(items) { _, w ->
            when (w) {
                0 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)))
                1 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)))
                2 -> rename(a)
                3 -> {
                    val h = p.getStringSet("hidden", emptySet())!!.toMutableSet().apply { add(a.pkg) }
                    p.edit().putStringSet("hidden", h).apply(); load(); build()
                }
                4 -> toggle("dock", a.pkg, 5)
                5 -> toggle("desk", a.pkg, 24)
            }
        }.show()
    }

    fun rename(a: App) {
        val e = EditText(this).apply { setText(label(a)); setTextColor(LT) }
        AlertDialog.Builder(this, DLG).setTitle("Новое имя").setView(e)
            .setPositiveButton("OK") { _, _ ->
                val t = e.text.toString().trim()
                p.edit().apply { if (t.isEmpty()) remove("n_" + a.pkg) else putString("n_" + a.pkg, t) }.apply()
                build()
            }.setNegativeButton("Отмена", null).show()
    }

    // ---------- настройки ----------
    fun LinearLayout.hdr(t: String) = addView(TextView(context).apply {
        text = t; setTextColor(ac); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(22), 0, dp(4))
    })

    fun LinearLayout.btn(t: String, a: () -> Unit) = addView(Button(context).apply {
        text = t; setOnClickListener { a() }
    })

    fun LinearLayout.seek(t: String, k: String, mn: Int, mx: Int, d: Int) {
        val tv = TextView(context).apply { text = "$t: ${gi(k, d)}"; setTextColor(LT); setPadding(0, dp(10), 0, 0) }
        addView(tv)
        addView(SeekBar(context).apply {
            max = mx - mn; progress = gi(k, d) - mn
            progressTintList = ColorStateList.valueOf(ac); thumbTintList = ColorStateList.valueOf(ac)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                    if (u) { p.edit().putInt(k, v + mn).apply(); tv.text = "$t: ${v + mn}"; build() }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
    }

    fun LinearLayout.sw(t: String, k: String, d: Boolean, reload: Boolean = false) {
        addView(Switch(context).apply {
            text = t; setTextColor(LT); isChecked = gb(k, d); setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, c ->
                p.edit().putBoolean(k, c).apply(); if (reload) load(); build()
            }
        })
    }

    fun LinearLayout.color(t: String, k: String, d: Int) {
        addView(TextView(context).apply { text = "$t (#AARRGGBB)"; setTextColor(LT); setPadding(0, dp(10), 0, 0) })
        addView(EditText(context).apply {
            setText(String.format("#%08X", gi(k, d))); setSingleLine(); setTextColor(LT)
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
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(10), dp(22), dp(10)) }
        val names = arrayOf("Мои обои (встроенные)", "Системные обои", "Фото из галереи", "Один цвет")
        l.hdr("ОБОИ")
        l.btn("Источник обоев") {
            AlertDialog.Builder(this, DLG).setItems(names) { _, w ->
                p.edit().putInt("wm", w).apply(); build()
            }.show()
        }
        l.btn("Выбрать фото из галереи") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 7)
        }
        l.color("Затемнение обоев", "bg", 0x33000000)
        l.color("Цвет фона (режим «цвет»)", "wc", 0xFF1B1B1F.toInt())
        l.hdr("ИКОНКИ")
        l.sw("Оригинальные иконки (без темы системы)", "oi", true, true)
        l.seek("Размер иконок", "is", 32, 96, 60)
        l.seek("Скругление иконок, %", "rad", 0, 50, 24)
        l.hdr("СЕТКА")
        l.seek("Колонок в списке", "cols", 3, 7, 4)
        l.seek("Колонок на столе", "hc", 3, 7, 4)
        l.seek("Отступ между рядами", "gap", 0, 40, 6)
        l.hdr("ТЕКСТ")
        l.sw("Подписи под иконками", "lbl", true)
        l.seek("Размер подписей", "ts", 8, 20, 12)
        l.color("Цвет текста", "tc", Color.WHITE)
        l.hdr("ГЛАВНЫЙ СТОЛ")
        l.sw("Часы и дата", "clock", true)
        l.seek("Размер часов", "cs", 24, 96, 56)
        l.sw("Док", "dock", true)
        l.sw("Подписи в доке", "dlbl", false)
        l.seek("Скругление дока", "dockr", 0, 50, 28)
        l.color("Цвет дока", "dockc", 0x33FFFFFF)
        l.hdr("СПИСОК ПРИЛОЖЕНИЙ")
        l.sw("Поиск", "search", true)
        l.color("Цвет поиска", "pc", 0x33FFFFFF)
        l.sw("Сортировка Я→А", "rev", false)
        l.hdr("ПРОЧЕЕ")
        l.color("Акцентный цвет", "ac", 0xFF8AB4F8.toInt())
        l.btn("Показать скрытые приложения") { p.edit().remove("hidden").apply(); load(); build() }
        l.btn("Сбросить всё") { p.edit().clear().apply(); wallMode = -1; load(); build() }
        val d = AlertDialog.Builder(this, DLG).setTitle("Настройки")
            .setView(ScrollView(this).apply { addView(l) })
            .setPositiveButton("Готово", null).create()
        d.show()
        d.window?.setBackgroundDrawable(pill(0xFF1E1E24.toInt(), 28))
    }
}
