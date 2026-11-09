package com.dino.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
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
    lateinit var root: FrameLayout
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
    var launched = false
    var ovl: View? = null
    var drawerGrid: GridView? = null
    var deskLayer: FrameLayout? = null
    var cellHint: View? = null
    var lastHint = -1
    var dockBox: LinearLayout? = null
    var searchBox: EditText? = null
    var dragBar: LinearLayout? = null
    val zones = mutableListOf<TextView>()
    var dragApp: App? = null
    var dragSrc = ""
    var dragView: View? = null
    var moved = false
    var sx = 0f
    var sy = 0f
    var hot = -1
    val ui = Handler(Looper.getMainLooper())
    val pool = Executors.newSingleThreadExecutor()
    val DLG = android.R.style.Theme_DeviceDefault_Dialog_Alert
    val applyRun = Runnable { if (needBake) { needBake = false; rebake() } else rebuild() }
    val rcv = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { reload() }
    }

    // ---------- цвета Material You ----------
    fun dyn(id: Int, fb: Int): Int = if (Build.VERSION.SDK_INT >= 31) getColor(id) else fb
    fun withA(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
    val cAcc get() = dyn(android.R.color.system_accent1_200, 0xFF8AB4F8.toInt())
    val cOnAcc get() = dyn(android.R.color.system_accent1_800, 0xFF00315F.toInt())
    val cSecC get() = dyn(android.R.color.system_accent2_700, 0xFF3F4759.toInt())
    val cOnSecC get() = dyn(android.R.color.system_accent2_100, 0xFFD8E2F9.toInt())
    val cSurf get() = dyn(android.R.color.system_neutral1_900, 0xFF1B1B1F.toInt())
    val cSurfC get() = dyn(android.R.color.system_neutral1_800, 0xFF2B2B30.toInt())
    val cOnSurf get() = dyn(android.R.color.system_neutral1_100, 0xFFE6E1E5.toInt())
    val cVar get() = dyn(android.R.color.system_neutral2_200, 0xFFC4C6D0.toInt())

    fun gi(k: String, d: Int) = p.getInt(k, d)
    fun gb(k: String, d: Boolean) = p.getBoolean(k, d)
    fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    fun label(a: App) = p.getString("n_" + a.pkg, null) ?: a.orig
    fun lst(k: String): List<String> = (p.getString(k, "") ?: "").split(",").filter { it.isNotEmpty() }
    fun put(k: String, l: List<String>) = p.edit().putString(k, l.joinToString(",")).apply()
    val tc get() = gi("tc", Color.WHITE)
    val ac get() = gi("ac", cAcc)

    fun txt(t: String, s: Float) = TextView(this).apply {
        text = t; textSize = s; setTextColor(tc); setPadding(dp(10), dp(4), dp(10), dp(4))
    }
    fun pill(c: Int, r: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }
    fun AlertDialog.Builder.m3(): AlertDialog = create().also {
        it.show(); it.window?.setBackgroundDrawable(pill(cSurf, 28))
    }
    fun gear() = txt("⚙", 22f).apply {
        gravity = Gravity.CENTER; setTextColor(cOnSecC); background = pill(withA(cSecC, 0xCC), 26)
        setOnClickListener { settings() }
    }

    // ---------- свободное размещение на столе ----------
    // хранится как "пакет|колонка|строка;пакет|колонка|строка"
    fun deskMap(): LinkedHashMap<String, IntArray> {
        val m = LinkedHashMap<String, IntArray>()
        (p.getString("deskp", "") ?: "").split(";").forEach { s ->
            val t = s.split("|")
            if (t.size == 3) {
                val c = t[1].toIntOrNull(); val r = t[2].toIntOrNull()
                if (c != null && r != null) m[t[0]] = intArrayOf(c, r)
            }
        }
        return m
    }

    fun saveDesk(m: Map<String, IntArray>) = p.edit()
        .putString("deskp", m.entries.joinToString(";") { it.key + "|" + it.value[0] + "|" + it.value[1] }).apply()

    fun migrateDesk() {
        if (p.contains("deskp")) return
        val cols = gi("hc", 4)
        val m = LinkedHashMap<String, IntArray>()
        lst("desk").forEachIndexed { i, pk -> m[pk] = intArrayOf(i % cols, i / cols) }
        saveDesk(m)
    }

    fun fixDesk(): LinkedHashMap<String, IntArray> {
        val cols = gi("hc", 4); val rows = gi("hr", 5)
        val m = deskMap()
        var changed = false
        if (apps.isNotEmpty()) {
            val known = apps.map { it.pkg }.toSet()
            if (m.keys.retainAll(known)) changed = true
        }
        val used = HashSet<Int>()
        val pending = ArrayList<String>()
        for ((pk, pos) in m) {
            val ok = pos[0] in 0 until cols && pos[1] in 0 until rows && used.add(pos[1] * cols + pos[0])
            if (!ok) pending.add(pk)
        }
        for (pk in pending) {
            var k = 0
            while (k < cols * rows && k in used) k++
            if (k >= cols * rows) m.remove(pk) else { m[pk] = intArrayOf(k % cols, k / cols); used.add(k) }
            changed = true
        }
        if (changed) saveDesk(m)
        return m
    }

    fun freeCell(m: Map<String, IntArray>): IntArray? {
        val cols = gi("hc", 4); val rows = gi("hr", 5)
        val used = m.values.map { it[1] * cols + it[0] }.toSet()
        for (k in 0 until cols * rows) if (k !in used) return intArrayOf(k % cols, k / cols)
        return null
    }

    fun deskPlace(pkg: String, c: Int, r: Int): Boolean {
        val m = fixDesk()
        val src = m[pkg]
        val occ = m.entries.firstOrNull { it.key != pkg && it.value[0] == c && it.value[1] == r }
        if (occ != null) {
            if (src != null) m[occ.key] = intArrayOf(src[0], src[1])
            else {
                val f = freeCell(m)
                if (f == null) { Toast.makeText(this, "Стол заполнен", Toast.LENGTH_SHORT).show(); return false }
                m[occ.key] = f
            }
        } else if (src == null && freeCell(m) == null) {
            Toast.makeText(this, "Стол заполнен", Toast.LENGTH_SHORT).show(); return false
        }
        m[pkg] = intArrayOf(c, r)
        saveDesk(m)
        return true
    }

    fun removeDesk(pkg: String) {
        val m = deskMap(); m.remove(pkg); saveDesk(m)
    }

    fun addDeskAuto(pkg: String) {
        val m = fixDesk()
        val f = freeCell(m)
        if (f == null) { Toast.makeText(this, "Стол заполнен", Toast.LENGTH_SHORT).show(); return }
        m[pkg] = f; saveDesk(m)
    }

    fun cellAt(x: Float, y: Float): IntArray? {
        val dl = deskLayer ?: return null
        if (dl.width == 0 || !dl.isShown) return null
        val a = IntArray(2); dl.getLocationInWindow(a)
        val b = IntArray(2); root.getLocationInWindow(b)
        val lx = x - (a[0] - b[0]); val ly = y - (a[1] - b[1])
        if (lx < 0 || ly < 0 || lx > dl.width || ly > dl.height) return null
        val cols = gi("hc", 4); val rows = gi("hr", 5)
        return intArrayOf(
            (lx / (dl.width / cols.toFloat())).toInt().coerceIn(0, cols - 1),
            (ly / (dl.height / rows.toFloat())).toInt().coerceIn(0, rows - 1)
        )
    }

    fun hintCell(cell: IntArray?) {
        val h = cellHint ?: return
        val dl = deskLayer
        if (cell == null || dl == null || dl.width == 0) { h.visibility = View.GONE; lastHint = -1; return }
        val key = cell[1] * 100 + cell[0]
        if (key == lastHint && h.visibility == View.VISIBLE) return
        lastHint = key
        val cw = dl.width / gi("hc", 4); val ch = dl.height / gi("hr", 5)
        h.layoutParams = FrameLayout.LayoutParams(cw, ch).apply { leftMargin = cell[0] * cw; topMargin = cell[1] * ch }
        h.visibility = View.VISIBLE
    }

    fun layoutDesk() {
        val dl = deskLayer ?: return
        if (dl.width == 0) return
        val cw = dl.width / gi("hc", 4); val ch = dl.height / gi("hr", 5)
        val m = deskMap()
        for (i in 0 until dl.childCount) {
            val v = dl.getChildAt(i)
            val pk = v.tag as? String ?: continue
            val pos = m[pk] ?: continue
            v.layoutParams = FrameLayout.LayoutParams(cw, ch).apply { leftMargin = pos[0] * cw; topMargin = pos[1] * ch }
        }
    }

    // ---------- жизненный цикл ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        p = getSharedPreferences("cfg", 0)
        migrateDesk()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rcv, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(rcv, f)
        rebuild(); reload()
    }

    override fun onResume() {
        super.onResume()
        ovl?.let { root.removeView(it) }; ovl = null
        if (launched) {
            launched = false
            if (gb("an", true)) root.apply {
                alpha = 0f; scaleX = 1.06f; scaleY = 1.06f
                animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).start()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        pool.shutdown()
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
                    vt?.recycle(); vt = null
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
        pool.execute {
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
                    ui.post { p.edit().putInt("wm", 2).apply(); wallMode = -1; rebuild() }
                }
            } catch (e: Exception) {
                ui.post { Toast.makeText(this, "Не удалось загрузить фото", Toast.LENGTH_SHORT).show() }
            }
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
        val l = (s * 1.5f).toInt()
        val o = -(l - s) / 2
        val mono = if (Build.VERSION.SDK_INT >= 33 && gb("mono", false) && d is AdaptiveIconDrawable) d.monochrome else null
        if (mono != null) {
            c.drawColor(cSecC)
            val m = mono.mutate(); m.setTint(cOnSecC); m.setBounds(o, o, o + l, o + l); m.draw(c)
        } else if (Build.VERSION.SDK_INT >= 26 && d is AdaptiveIconDrawable) {
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
        if (p.contains("dockl") || apps.isEmpty()) return
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
        if (w == null && gi("wm", 0) != 1) fr.setBackgroundColor(gi("wc", cSurf))
        if (w != null) fr.addView(ImageView(this).apply {
            setImageDrawable(w); scaleType = ImageView.ScaleType.CENTER_CROP
        }, FrameLayout.LayoutParams(-1, -1))
        fr.addView(View(this).apply { setBackgroundColor(gi("bg", 0x33000000)) }, FrameLayout.LayoutParams(-1, -1))
        val h = LinearLayout(this); buildHome(h); home = h
        fr.addView(h, FrameLayout.LayoutParams(-1, -1))
        val d = LinearLayout(this); buildDrawer(d); drw = d
        fr.addView(d, FrameLayout.LayoutParams(-1, -1))
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER; visibility = View.GONE; setPadding(dp(12), dp(36), dp(12), dp(8)) }
        zones.clear()
        for (n in listOf("ℹ  Инфо", "🗑  Удалить", "✕  Убрать")) {
            val z = TextView(this).apply {
                text = n; textSize = 14f; gravity = Gravity.CENTER; setPadding(dp(8), dp(14), dp(8), dp(14))
            }
            zones.add(z)
            bar.addView(z, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        dragBar = bar
        paintZones()
        fr.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        fr.setOnDragListener { _, e -> onDrag(e) }
        root = fr
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
        h.setPadding(dp(12), dp(40), dp(12), dp(8))
        val top = LinearLayout(this)
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0)
            setOnLongClickListener { pickDesk(); true }
        }
        if (gb("clock", true)) {
            c.addView(TextClock(this).apply {
                format24Hour = "HH:mm"; format12Hour = "HH:mm"; textSize = gi("cs", 64).toFloat(); setTextColor(tc)
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            })
            c.addView(TextClock(this).apply {
                format24Hour = "EEEE, d MMMM"; format12Hour = "EEEE, d MMMM"; textSize = 16f; setTextColor(tc)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
        }
        top.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(gear(), LinearLayout.LayoutParams(dp(48), dp(48)))
        h.addView(top)
        val dl = FrameLayout(this)
        val hint = View(this).apply { background = pill(withA(ac, 0x44), 20); visibility = View.GONE }
        dl.addView(hint, FrameLayout.LayoutParams(1, 1))
        cellHint = hint; deskLayer = dl; lastHint = -1
        dl.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) dl.post { layoutDesk() }
        }
        val wrap = FrameLayout(this)
        wrap.addView(dl, FrameLayout.LayoutParams(-1, -1))
        if (deskMap().isEmpty()) wrap.addView(
            txt("Пусто. Нажми сюда или перетащи приложения из списка", 15f).apply {
                gravity = Gravity.CENTER; setOnClickListener { pickDesk() }
            }, FrameLayout.LayoutParams(-1, -1)
        )
        h.addView(wrap, LinearLayout.LayoutParams(-1, 0, 1f))
        if (gb("sdock", true)) {
            val d = LinearLayout(this).apply {
                gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(8))
                background = pill(gi("dockc", withA(cSurfC, 0xCC)), gi("dockr", 32))
            }
            dockBox = d
            h.addView(d, LinearLayout.LayoutParams(-1, -2))
        } else dockBox = null
        val hd = FrameLayout(this).apply { setOnClickListener { go(true) } }
        hd.addView(View(this).apply { background = pill(withA(cVar, 0x99), 2) }, FrameLayout.LayoutParams(dp(36), dp(4), Gravity.CENTER))
        h.addView(hd, LinearLayout.LayoutParams(-1, dp(28)))
    }

    fun buildDrawer(d: LinearLayout) {
        d.orientation = LinearLayout.VERTICAL
        d.setPadding(dp(12), dp(32), dp(12), dp(8))
        d.setBackgroundColor(gi("dbg", withA(cSurf, 0xE8)))
        d.addView(View(this).apply { background = pill(withA(cVar, 0x99), 2) },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        searchBox = null
        if (gb("search", true)) {
            val e = EditText(this).apply {
                hint = "Поиск приложений"; setHintTextColor(withA(cVar, 0xCC)); setTextColor(cOnSurf)
                setSingleLine(); textSize = 16f
                background = pill(gi("pc", withA(cSurfC, 0xE6)), 26)
                setPadding(dp(20), 0, dp(20), 0); setText(query); setSelection(query.length)
                addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        if (s.toString() != query) { query = s.toString(); fillDrawer() }
                    }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            }
            searchBox = e
            top.addView(e, LinearLayout.LayoutParams(0, dp(52), 1f))
        } else top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(gear(), LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) })
        d.addView(top)
        val g = newGrid(gi("cols", 4)); drawerGrid = g
        d.addView(g, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    fun adapter(list: List<App>, src: String) = object : BaseAdapter() {
        override fun getCount() = list.size
        override fun getItem(i: Int) = list[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, v: View?, parent: ViewGroup): View {
            val c = v ?: newCell(true)
            bind(c, list[i], src)
            return c
        }
    }

    fun fillDrawer() {
        val all = apps.filter { label(it).contains(query, true) }.sortedBy { label(it).lowercase() }
            .let { if (gb("rev", false)) it.reversed() else it }
        drawerGrid?.adapter = adapter(all, "drawer")
    }

    fun fill() {
        fillDrawer()
        deskLayer?.let { dl ->
            for (i in dl.childCount - 1 downTo 0) if (dl.getChildAt(i) !== cellHint) dl.removeViewAt(i)
            for ((pk, _) in fixDesk()) {
                val a = apps.find { it.pkg == pk } ?: continue
                val c = newCell(true); c.tag = pk; bind(c, a, "desk")
                dl.addView(c, FrameLayout.LayoutParams(1, 1))
            }
            dl.post { layoutDesk() }
        }
        dockBox?.apply {
            removeAllViews()
            lst("dockl").mapNotNull { pk -> apps.find { it.pkg == pk } }.forEach { a ->
                val c = newCell(gb("dlbl", false)); bind(c, a, "dock")
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
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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

    fun bind(v: View, a: App, src: String) {
        v.findViewWithTag<ImageView>("i").setImageBitmap(a.bmp)
        v.findViewWithTag<TextView>("t")?.text = label(a)
        v.setOnClickListener { launch(a, v) }
        v.setOnLongClickListener { startDrag(v, a, src); true }
    }

    // ---------- запуск с анимацией ----------
    fun launch(a: App, v: View) {
        val i = packageManager.getLaunchIntentForPackage(a.pkg) ?: return
        val iv = v.findViewWithTag<ImageView>("i")
        if (!gb("an", true) || iv == null || iv.width == 0) { startActivity(i); return }
        val loc = IntArray(2); iv.getLocationInWindow(loc)
        val rl = IntArray(2); root.getLocationInWindow(rl)
        val dm = resources.displayMetrics
        val ov = ImageView(this).apply { setImageBitmap(a.bmp); scaleType = ImageView.ScaleType.FIT_XY }
        root.addView(ov, FrameLayout.LayoutParams(iv.width, iv.height).apply {
            leftMargin = loc[0] - rl[0]; topMargin = loc[1] - rl[1]
        })
        ovl = ov; launched = true
        val k = maxOf(dm.widthPixels / iv.width.toFloat(), dm.heightPixels / iv.height.toFloat()) * 1.15f
        ov.animate().scaleX(k).scaleY(k)
            .translationX(dm.widthPixels / 2f - (loc[0] + iv.width / 2f))
            .translationY(dm.heightPixels / 2f - (loc[1] + iv.height / 2f))
            .alpha(0f).setDuration(320).setInterpolator(DecelerateInterpolator(1.4f)).start()
        ui.postDelayed(Runnable {
            try {
                startActivity(i, ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out).toBundle())
            } catch (e: Exception) { startActivity(i) }
        }, 90L)
        ui.postDelayed(Runnable { if (ovl === ov) { root.removeView(ov); ovl = null } }, 900L)
    }

    // ---------- перетаскивание ----------
    fun startDrag(v: View, a: App, src: String) {
        dragApp = a; dragSrc = src; dragView = v; moved = false; hot = -1
        zones[2].text = if (src == "drawer") "🙈  Скрыть" else "✕  Убрать"
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        v.startDragAndDrop(ClipData.newPlainText("app", a.pkg), View.DragShadowBuilder(v), null, 0)
        v.alpha = 0.3f
    }

    fun paintZones() {
        zones.forEachIndexed { i, z ->
            z.background = pill(if (i == hot) ac else cSecC, 24)
            z.setTextColor(if (i == hot) cOnAcc else cOnSecC)
        }
    }

    fun hit(v: View?, x: Float, y: Float): Boolean {
        if (v == null || !v.isShown) return false
        val a = IntArray(2); v.getLocationInWindow(a)
        val b = IntArray(2); root.getLocationInWindow(b)
        val l = a[0] - b[0]; val t = a[1] - b[1]
        return x >= l && x <= l + v.width && y >= t && y <= t + v.height
    }

    fun zoneAt(x: Float, y: Float): Int {
        zones.forEachIndexed { i, z -> if (hit(z, x, y)) return i }
        return -1
    }

    fun idxLinear(box: LinearLayout, x: Float): Int {
        val b = IntArray(2); root.getLocationInWindow(b)
        for (i in 0 until box.childCount) {
            val c = box.getChildAt(i); val a = IntArray(2); c.getLocationInWindow(a)
            if (x < a[0] - b[0] + c.width / 2f) return i
        }
        return box.childCount
    }

    fun place(k: String, pkg: String, idx: Int, max: Int): Boolean {
        val l = lst(k).toMutableList()
        val old = l.indexOf(pkg)
        if (old >= 0) l.removeAt(old)
        else if (l.size >= max) { Toast.makeText(this, "Максимум $max", Toast.LENGTH_SHORT).show(); return false }
        val at = (if (old in 0 until idx) idx - 1 else idx).coerceIn(0, l.size)
        l.add(at, pkg)
        put(k, l)
        return true
    }

    fun onDrag(e: DragEvent): Boolean {
        val a = dragApp ?: return false
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> { sx = e.x; sy = e.y; return true }
            DragEvent.ACTION_DRAG_LOCATION -> {
                if (!moved && Math.hypot((e.x - sx).toDouble(), (e.y - sy).toDouble()) > dp(24)) {
                    moved = true
                    dragBar?.apply { alpha = 0f; visibility = View.VISIBLE; animate().alpha(1f).setDuration(150).start() }
                    if (dragSrc == "drawer" && prog > 0f) go(false)
                }
                if (moved) {
                    val z = zoneAt(e.x, e.y)
                    if (z != hot) { hot = z; paintZones() }
                    hintCell(if (z < 0 && !hit(dockBox, e.x, e.y)) cellAt(e.x, e.y) else null)
                }
            }
            DragEvent.ACTION_DROP -> drop(a, e.x, e.y)
            DragEvent.ACTION_DRAG_ENDED -> {
                dragView?.alpha = 1f; dragBar?.visibility = View.GONE
                dragApp = null; dragView = null; hot = -1; paintZones(); hintCell(null)
            }
        }
        return true
    }

    fun drop(a: App, x: Float, y: Float) {
        if (!moved) { menu(a); return }
        val z = zoneAt(x, y)
        if (z == 0) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg))); return }
        if (z == 1) { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg))); return }
        if (z == 2) {
            if (dragSrc == "drawer") hideApp(a)
            else {
                if (dragSrc == "dock") put("dockl", lst("dockl") - a.pkg) else removeDesk(a.pkg)
                rebuild()
            }
            return
        }
        val db = dockBox
        if (db != null && hit(db, x, y)) {
            if (place("dockl", a.pkg, idxLinear(db, x), 5) && dragSrc == "desk") removeDesk(a.pkg)
            rebuild(); return
        }
        val cell = cellAt(x, y)
        if (cell != null && deskPlace(a.pkg, cell[0], cell[1])) {
            if (dragSrc == "dock") put("dockl", lst("dockl") - a.pkg)
            rebuild()
        }
    }

    // ---------- меню приложения ----------
    fun hideApp(a: App) {
        val h = p.getStringSet("hidden", emptySet())!!.toMutableSet().apply { add(a.pkg) }
        p.edit().putStringSet("hidden", h).apply(); reload()
    }

    fun toggle(k: String, pkg: String, max: Int) {
        val l = lst(k).toMutableList()
        if (pkg in l) l.remove(pkg)
        else if (l.size < max) l.add(pkg)
        else Toast.makeText(this, "Максимум $max", Toast.LENGTH_SHORT).show()
        put(k, l); rebuild()
    }

    fun menu(a: App) {
        val onDesk = a.pkg in deskMap()
        val items = arrayOf(
            "Удалить", "Информация о приложении", "Переименовать", "Скрыть",
            if (a.pkg in lst("dockl")) "Убрать из дока" else "В док",
            if (onDesk) "Убрать с главного стола" else "На главный стол"
        )
        AlertDialog.Builder(this, DLG).setTitle(label(a)).setItems(items) { _, w ->
            when (w) {
                0 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)))
                1 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)))
                2 -> rename(a)
                3 -> hideApp(a)
                4 -> toggle("dockl", a.pkg, 5)
                5 -> { if (onDesk) removeDesk(a.pkg) else addDeskAuto(a.pkg); rebuild() }
            }
        }.m3()
    }

    fun rename(a: App) {
        val e = EditText(this).apply { setText(label(a)); setTextColor(cOnSurf) }
        AlertDialog.Builder(this, DLG).setTitle("Новое имя").setView(e)
            .setPositiveButton("OK") { _, _ ->
                val t = e.text.toString().trim()
                p.edit().apply { if (t.isEmpty()) remove("n_" + a.pkg) else putString("n_" + a.pkg, t) }.apply()
                rebuild()
            }.setNegativeButton("Отмена", null).m3()
    }

    fun pickDesk() {
        val l = apps.sortedBy { label(it).lowercase() }
        val cur = deskMap()
        val on = BooleanArray(l.size) { l[it].pkg in cur }
        AlertDialog.Builder(this, DLG).setTitle("Приложения на главном столе")
            .setMultiChoiceItems(l.map { label(it) }.toTypedArray(), on) { _, i, c -> on[i] = c }
            .setPositiveButton("Готово") { _, _ ->
                val m = fixDesk()
                l.forEachIndexed { i, a ->
                    if (!on[i]) m.remove(a.pkg)
                    else if (a.pkg !in m) { val f = freeCell(m); if (f != null) m[a.pkg] = f }
                }
                saveDesk(m); rebuild()
            }.setNegativeButton("Отмена", null).m3()
    }

    // ---------- настройки (Material 3) ----------
    fun states(on: Int, off: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(on, off)
    )

    fun LinearLayout.hdr(t: String) = addView(TextView(context).apply {
        text = t; setTextColor(ac); textSize = 14f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(0, dp(22), 0, dp(4))
    })

    fun LinearLayout.btn(t: String, a: () -> Unit) = addView(Button(context).apply {
        text = t; setAllCaps(false); setTextColor(cOnSecC); background = pill(cSecC, 20)
        stateListAnimator = null; setOnClickListener { a() }
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    fun LinearLayout.seek(t: String, k: String, mn: Int, mx: Int, d: Int, bk: Boolean = false) {
        val tv = TextView(context).apply { text = "$t: ${gi(k, d)}"; setTextColor(cOnSurf); setPadding(0, dp(10), 0, 0) }
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

    // mode: 0 - обновить экран, 1 - перезагрузить приложения, 2 - перерисовать иконки
    fun LinearLayout.sw(t: String, k: String, d: Boolean, mode: Int = 0) {
        addView(Switch(context).apply {
            text = t; setTextColor(cOnSurf); isChecked = gb(k, d); setPadding(0, dp(6), 0, dp(6))
            thumbTintList = states(cOnAcc, cVar); trackTintList = states(ac, withA(cVar, 0x55))
            setOnCheckedChangeListener { _, c ->
                p.edit().putBoolean(k, c).apply()
                if (mode == 1) reload() else change(mode == 2)
            }
        })
    }

    fun LinearLayout.color(t: String, k: String, d: Int) {
        addView(TextView(context).apply { text = "$t (#AARRGGBB)"; setTextColor(cOnSurf); setPadding(0, dp(10), 0, 0) })
        addView(EditText(context).apply {
            setText(String.format("#%08X", gi(k, d))); setSingleLine(); setTextColor(cOnSurf)
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
            AlertDialog.Builder(this, DLG).setItems(names) { _, w -> p.edit().putInt("wm", w).apply(); change(false) }.m3()
        }
        l.btn("Выбрать фото из галереи") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 7)
        }
        l.color("Затемнение обоев", "bg", 0x33000000)
        l.color("Цвет фона (режим «цвет»)", "wc", cSurf)
        l.hdr("ГЛАВНЫЙ СТОЛ")
        l.btn("Выбрать приложения для стола") { pickDesk() }
        l.sw("Часы и дата", "clock", true)
        l.seek("Размер часов", "cs", 24, 96, 64)
        l.seek("Колонок на столе", "hc", 3, 7, 4)
        l.seek("Строк на столе", "hr", 3, 8, 5)
        l.sw("Док", "sdock", true)
        l.sw("Подписи в доке", "dlbl", false)
        l.seek("Скругление дока", "dockr", 0, 50, 32)
        l.color("Цвет дока", "dockc", withA(cSurfC, 0xCC))
        l.hdr("СПИСОК ПРИЛОЖЕНИЙ")
        l.seek("Колонок в списке", "cols", 3, 7, 4)
        l.color("Фон списка", "dbg", withA(cSurf, 0xE8))
        l.sw("Поиск", "search", true)
        l.color("Цвет поиска", "pc", withA(cSurfC, 0xE6))
        l.sw("Сортировка Я→А", "rev", false)
        l.hdr("ИКОНКИ")
        l.sw("Оригинальные иконки (без темы системы)", "oi", true, 1)
        l.sw("Темизированные иконки Material You (Android 13+)", "mono", false, 2)
        l.seek("Размер иконок", "is", 32, 96, 60, true)
        l.seek("Скругление иконок, %", "rad", 0, 50, 24, true)
        l.seek("Отступ между рядами", "gap", 0, 40, 6)
        l.hdr("ТЕКСТ")
        l.sw("Подписи под иконками", "lbl", true)
        l.seek("Размер подписей", "ts", 8, 20, 12)
        l.color("Цвет текста", "tc", Color.WHITE)
        l.hdr("ПРОЧЕЕ")
        l.sw("Анимации", "an", true)
        l.color("Акцентный цвет", "ac", cAcc)
        l.btn("Показать скрытые приложения") { p.edit().remove("hidden").apply(); reload() }
        l.btn("Сбросить всё") { p.edit().clear().apply(); wallMode = -1; migrateDesk(); reload() }
        AlertDialog.Builder(this, DLG).setTitle("Настройки")
            .setView(ScrollView(this).apply { addView(l) })
            .setPositiveButton("Готово", null).m3()
    }
}
