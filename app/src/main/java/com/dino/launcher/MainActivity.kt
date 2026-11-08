package com.dino.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {
    class App(val pkg: String, val orig: String, val raw: Drawable) { var bmp: Bitmap? = null }

    lateinit var p: SharedPreferences
    lateinit var home: View
    lateinit var drw: View
    var apps = listOf<App>()
    var query = ""
    var prog = 0f
    var base = 0f
    var drag = false
    var x0 = 0f
    var y0 = 0f
    var vt: VelocityTracker? = null
    var anim: ValueAnimator? = null
    var wall: Drawable? = null
    var wallMode = -1
    var needBake = false
    var drawerGrid: GridView? = null
    var deskGrid: GridView? = null
    var dockBox: LinearLayout? = null
    var searchBox: EditText? = null
    val ui = Handler(Looper.getMainLooper())
    val pool = Executors.newSingleThreadExecutor()
    val DLG = android.R.style.Theme_DeviceDefault_Dialog_Alert
    val LT = 0xFFE6E1E5.toInt()
    val applyRun = Runnable { if (needBake) { needBake = false; rebake() } else rebuild() }
    val rcv = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { reload() }
    }

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

    // ---------- жизненный цикл ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        p = getSharedPreferences("cfg", 0)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rcv, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(rcv, f)
        rebuild(); reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(rcv) } catch (e: Exception) {}
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        if (prog > 0f) go(false)
    }

    override fun onBackPressed() {
        if (prog > 0.5f) go(false)
    }

    // ---------- жесты: список едет за пальцем ----------
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = ev.x; y0 = ev.y; drag = false
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                val dy = ev.y - y0
                val dx = ev.x - x0
                if (!drag && Math.abs(dy) > dp(14) && Math.abs(dy) > Math.abs(dx) * 1.4f) {
                    val up = prog <= 0.5f && dy < 0
                    val down = prog > 0.5f && dy > 0 && drawerGrid?.canScrollVertically(-1) != true
                    if (up || down) {
                        drag = true
                        anim?.cancel(); base = prog
                        val c = MotionEvent.obtain(ev); c.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(c); c.recycle()
                        y0 = ev.y
                    }
                }
                if (drag) {
                    val h = resources.displayMetrics.heightPixels.toFloat()
                    setP((base - (ev.y - y0) / h).coerceIn(0f, 1f))
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag) {
                    drag = false
                    vt?.addMovement(ev); vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    go(if (Math.abs(vy) > 700f) vy < 0 else prog > 0.5f)
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    fun setP(v: Float) {
        prog = v
        val h = resources.displayMetrics.heightPixels.toFloat()
        drw.translationY = h * (1 - v)
        drw.alpha = minOf(1f, v * 2f)
        drw.visibility = if (v <= 0f) View.INVISIBLE else View.VISIBLE
        home.alpha = 1 - v
        home.scaleX = 1 - 0.06f * v
        home.scaleY = 1 - 0.06f * v
    }

    fun go(open: Boolean) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(prog, if (open) 1f else 0f).apply {
            duration = if (gb("an", true)) 280L else 0L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { setP(it.animatedValue as Float) }
            start()
        }
        if (!open) {
            hideKb()
            if (searchBox != null) searchBox?.setText("") else { query = ""; fillDrawer() }
        }
    }

    fun hideKb() {
        searchBox?.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(drw.windowToken, 0)
    }

    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        super.onActivityResult(r, c, d)
        val u = d?.data ?: return
        if (r != 7 || c != RESULT_OK) return
        try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
            var s = 1
            while (o.outWidth / s > 1600) s *= 2
            val b = contentResolver.openInputStream(u)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
            }
            if (b != null) {
                FileOutputStream(File(filesDir, "wall.jpg")).use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                p.edit().putInt("wm", 2).apply(); wallMode = -1; rebuild()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось загрузить фото", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- данные и иконки ----------
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

    fun bake(d: Drawable, s: Int, r: Int): Bitmap {
        val raw = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(raw)
        if (Build.VERSION.SDK_INT >= 26 && d is AdaptiveIconDrawable) {
            val l = (s * 1.5f).toInt()
            val o = -(l - s) / 2
            d.background?.apply { setBounds(o, o, o + l, o + l); draw(c) }
            d.foreground?.apply { setBounds(o, o, o + l, o + l); draw(c) }
        } else { d.setBounds(0, 0, s, s); d.draw(c) }
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val rad = s * r / 100f
        Canvas(out).drawRoundRect(
            RectF(0f, 0f, s.toFloat(), s.toFloat()), rad, rad,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(raw, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        )
        raw.recycle()
        return out
    }

    fun reload() {
        pool.execute {
            val pm = packageManager
            val hidden = p.getStringSet("hidden", emptySet())!!
            val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .distinctBy { it.activityInfo.packageName }
                .filter { it.activityInfo.packageName !in hidden }
                .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString(), icon(it)) }
            val s = dp(gi("is", 60)); val r = gi("rad", 24)
            list.forEach { it.bmp = bake(it.raw, s, r) }
            ui.post { apps = list; defaults(); rebuild() }
        }
    }

    fun rebake() {
        val cur = apps; val s = dp(gi("is", 60)); val r = gi("rad", 24)
        pool.execute {
            cur.forEach { it.bmp = bake(it.raw, s, r) }
            ui.post { rebuild() }
        }
    }

    fun change(bakeIcons: Boolean) {
        if (bakeIcons) needBake = true
        ui.removeCallbacks(applyRun); ui.postDelayed(applyRun, 150)
    }

    fun defaults() {
        if (p.contains("dockl")) return
        val ints = listOf(
            Intent(Intent.ACTION_DIAL), Intent(Intent.ACTION_VIEW, Uri.parse("sms:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://")), Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        )
        val l = ints.mapNotNull { packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }
            .filter { pk -> apps.any { it.pkg == pk } }.distinct()
        put("dockl", l.take(5))
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

    // ---------- интерфейс ----------
    fun rebuild() {
        val fr = FrameLayout(this)
        val w = wallpaper()
        if (w == null && gi("wm", 0) != 1) fr.setBackgroundColor(gi("wc", 0xFF1B1B1F.toInt()))
        if (w != null) fr.addView(ImageView(this).apply {
            setImageDrawable(w); scaleType = ImageView.ScaleType.CENTER_CROP
        }, FrameLayout.LayoutParams(-1, -1))
        fr.addView(View(this).apply { setBackgroundColor(gi("bg", 0x33000000)) }, FrameLayout.LayoutParams(-1, -1))
        val h = LinearLayout(this); buildHome(h); home = h
        fr.addView(h, FrameLayout.LayoutParams(-1, -1))
        val d = LinearLayout(this); buildDrawer(d); drw = d
        fr.addView(d, FrameLayout.LayoutParams(-1, -1))
        setContentView(fr)
        setP(prog)
        fill()
    }

    fun newGrid(n: Int) = GridView(this).apply {
        numColumns = n; verticalSpacing = dp(gi("gap", 6)); setPadding(0, dp(8), 0, 0)
        clipToPadding = false; isVerticalScrollBarEnabled = false
    }

    fun buildHome(h: LinearLayout) {
        h.orientation = LinearLayout.VERTICAL
        h.setPadding(dp(12), dp(40), dp(12), dp(28))
        val top = LinearLayout(this)
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0)
            setOnLongClickListener { pickDesk(); true }
        }
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
        h.addView(top)
        if (lst("desk").isEmpty()) {
            deskGrid = null
            h.addView(txt("Пусто. Нажми сюда и выбери приложения для главного стола", 15f).apply {
                gravity = Gravity.CENTER; setOnClickListener { pickDesk() }
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val g = newGrid(gi("hc", 4)); deskGrid = g
            h.addView(g, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        if (gb("sdock", true)) {
            val d = LinearLayout(this).apply {
                gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(8))
                background = pill(gi("dockc", 0x33FFFFFF), gi("dockr", 28))
            }
            dockBox = d
            h.addView(d, LinearLayout.LayoutParams(-1, -2))
        } else dockBox = null
        h.addView(txt("⌃", 22f).apply {
            gravity = Gravity.CENTER; setOnClickListener { go(true) }
        }, LinearLayout.LayoutParams(-1, -2))
    }

    fun buildDrawer(d: LinearLayout) {
        d.orientation = LinearLayout.VERTICAL
        d.setPadding(dp(12), dp(40), dp(12), dp(8))
        d.setBackgroundColor(gi("dbg", 0xCC0E0E12.toInt()))
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(txt("⌄", 30f).apply { setOnClickListener { go(false) } })
        searchBox = null
        if (gb("search", true)) {
            val e = EditText(this).apply {
                hint = "Поиск"; setHintTextColor(tc and 0x88FFFFFF.toInt()); setTextColor(tc); setSingleLine()
                background = pill(gi("pc", 0x33FFFFFF), 24)
                setPadding(dp(16), dp(8), dp(16), dp(8)); setText(query); setSelection(query.length)
                addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        if (s.toString() != query) { query = s.toString(); fillDrawer() }
                    }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            }
            searchBox = e
            top.addView(e, LinearLayout.LayoutParams(0, -2, 1f))
        } else top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(txt("⚙", 28f).apply { setOnClickListener { settings() } })
        d.addView(top)
        val g = newGrid(gi("cols", 4)); drawerGrid = g
        d.addView(g, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    fun adapter(list: List<App>) = object : BaseAdapter() {
        override fun getCount() = list.size
        override fun getItem(i: Int) = list[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, v: View?, parent: ViewGroup): View {
            val c = v ?: newCell(true)
            bind(c, list[i])
            return c
        }
    }

    fun fillDrawer() {
        val all = apps.filter { label(it).contains(query, true) }.sortedBy { label(it).lowercase() }
            .let { if (gb("rev", false)) it.reversed() else it }
        drawerGrid?.adapter = adapter(all)
    }

    fun fill() {
        fillDrawer()
        deskGrid?.adapter = adapter(lst("desk").mapNotNull { pk -> apps.find { it.pkg == pk } })
        dockBox?.apply {
            removeAllViews()
            lst("dockl").mapNotNull { pk -> apps.find { it.pkg == pk } }.forEach { a ->
                val c = newCell(gb("dlbl", false)); bind(c, a)
                addView(c, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
    }

    fun newCell(showLabel: Boolean): View {
        val s = dp(gi("is", 60))
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(8))
            addView(ImageView(context).apply { tag = "i" }, LinearLayout.LayoutParams(s, s))
            if (showLabel && gb("lbl", true)) addView(TextView(context).apply {
                tag = "t"; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setTextColor(tc); textSize = gi("ts", 12).toFloat()
                gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
            })
            setOnTouchListener { v, e ->
                if (gb("an", true)) {
                    if (e.actionMasked == MotionEvent.ACTION_DOWN)
                        v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
                    else if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)
                        v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                }
                false
            }
        }
    }

    fun bind(v: View, a: App) {
        v.findViewWithTag<ImageView>("i").setImageBitmap(a.bmp)
        v.findViewWithTag<TextView>("t")?.text = label(a)
        v.setOnClickListener { launch(a, v) }
        v.setOnLongClickListener { menu(a); true }
    }

    fun launch(a: App, v: View) {
        val i = packageManager.getLaunchIntentForPackage(a.pkg) ?: return
        try {
            if (gb("an", true)) startActivity(i, ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.width, v.height).toBundle())
            else startActivity(i)
        } catch (e: Exception) { startActivity(i) }
    }

    // ---------- меню приложения ----------
    fun toggle(k: String, pkg: String, max: Int) {
        val l = lst(k).toMutableList()
        if (pkg in l) l.remove(pkg)
        else if (l.size < max) l.add(pkg)
        else Toast.makeText(this, "Максимум $max", Toast.LENGTH_SHORT).show()
        put(k, l); rebuild()
    }

    fun menu(a: App) {
        val items = arrayOf(
            "Удалить", "Информация о приложении", "Переименовать", "Скрыть",
            if (a.pkg in lst("dockl")) "Убрать из дока" else "В док",
            if (a.pkg in lst("desk")) "Убрать с главного стола" else "На главный стол"
        )
        AlertDialog.Builder(this, DLG).setTitle(label(a)).setItems(items) { _, w ->
            when (w) {
                0 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)))
                1 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)))
                2 -> rename(a)
                3 -> {
                    val h = p.getStringSet("hidden", emptySet())!!.toMutableSet().apply { add(a.pkg) }
                    p.edit().putStringSet("hidden", h).apply(); reload()
                }
                4 -> toggle("dockl", a.pkg, 5)
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
                rebuild()
            }.setNegativeButton("Отмена", null).show()
    }

    fun pickDesk() {
        val l = apps.sortedBy { label(it).lowercase() }
        val cur = lst("desk")
        val on = BooleanArray(l.size) { l[it].pkg in cur }
        AlertDialog.Builder(this, DLG).setTitle("Приложения на главном столе")
            .setMultiChoiceItems(l.map { label(it) }.toTypedArray(), on) { _, i, c -> on[i] = c }
            .setPositiveButton("Готово") { _, _ ->
                val chosen = l.filterIndexed { i, _ -> on[i] }.map { it.pkg }
                put("desk", (cur.filter { it in chosen } + chosen.filter { it !in cur }).take(24))
                rebuild()
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

    fun LinearLayout.seek(t: String, k: String, mn: Int, mx: Int, d: Int, bk: Boolean = false) {
        val tv = TextView(context).apply { text = "$t: ${gi(k, d)}"; setTextColor(LT); setPadding(0, dp(10), 0, 0) }
        addView(tv)
        addView(SeekBar(context).apply {
            max = mx - mn; progress = gi(k, d) - mn
            progressTintList = ColorStateList.valueOf(ac); thumbTintList = ColorStateList.valueOf(ac)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                    if (u) { p.edit().putInt(k, v + mn).apply(); tv.text = "$t: ${v + mn}"; change(bk) }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
    }

    fun LinearLayout.sw(t: String, k: String, d: Boolean, reloadApps: Boolean = false) {
        addView(Switch(context).apply {
            text = t; setTextColor(LT); isChecked = gb(k, d); setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, c ->
                p.edit().putBoolean(k, c).apply(); if (reloadApps) reload() else change(false)
            }
        })
    }

    fun LinearLayout.color(t: String, k: String, d: Int) {
        addView(TextView(context).apply { text = "$t (#AARRGGBB)"; setTextColor(LT); setPadding(0, dp(10), 0, 0) })
        addView(EditText(context).apply {
            setText(String.format("#%08X", gi(k, d))); setSingleLine(); setTextColor(LT)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    try { p.edit().putInt(k, Color.parseColor(s.toString())).apply(); change(false) } catch (e: Exception) {}
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
            AlertDialog.Builder(this, DLG).setItems(names) { _, w -> p.edit().putInt("wm", w).apply(); change(false) }.show()
        }
        l.btn("Выбрать фото из галереи") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 7)
        }
        l.color("Затемнение обоев", "bg", 0x33000000)
        l.color("Цвет фона (режим «цвет»)", "wc", 0xFF1B1B1F.toInt())
        l.hdr("ГЛАВНЫЙ СТОЛ")
        l.btn("Выбрать приложения для стола") { pickDesk() }
        l.sw("Часы и дата", "clock", true)
        l.seek("Размер часов", "cs", 24, 96, 56)
        l.seek("Колонок на столе", "hc", 3, 7, 4)
        l.sw("Док", "sdock", true)
        l.sw("Подписи в доке", "dlbl", false)
        l.seek("Скругление дока", "dockr", 0, 50, 28)
        l.color("Цвет дока", "dockc", 0x33FFFFFF)
        l.hdr("СПИСОК ПРИЛОЖЕНИЙ")
        l.seek("Колонок в списке", "cols", 3, 7, 4)
        l.color("Фон списка", "dbg", 0xCC0E0E12.toInt())
        l.sw("Поиск", "search", true)
        l.color("Цвет поиска", "pc", 0x33FFFFFF)
        l.sw("Сортировка Я→А", "rev", false)
        l.hdr("ИКОНКИ")
        l.sw("Оригинальные иконки (без темы системы)", "oi", true, true)
        l.seek("Размер иконок", "is", 32, 96, 60, true)
        l.seek("Скругление иконок, %", "rad", 0, 50, 24, true)
        l.seek("Отступ между рядами", "gap", 0, 40, 6)
        l.hdr("ТЕКСТ")
        l.sw("Подписи под иконками", "lbl", true)
        l.seek("Размер подписей", "ts", 8, 20, 12)
        l.color("Цвет текста", "tc", Color.WHITE)
        l.hdr("ПРОЧЕЕ")
        l.sw("Анимации", "an", true)
        l.color("Акцентный цвет", "ac", 0xFF8AB4F8.toInt())
        l.btn("Показать скрытые приложения") { p.edit().remove("hidden").apply(); reload() }
        l.btn("Сбросить всё") { p.edit().clear().apply(); wallMode = -1; reload() }
        val d = AlertDialog.Builder(this, DLG).setTitle("Настройки")
            .setView(ScrollView(this).apply { addView(l) })
            .setPositiveButton("Готово", null).create()
        d.show()
        d.window?.setBackgroundDrawable(pill(0xFF1E1E24.toInt(), 28))
    }
}
