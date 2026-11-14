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
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class MainActivity : Activity() {
    class App(val pkg: String, val orig: String) {
        var raw: Drawable? = null
        var bmp: Bitmap? = null
    }

    // векторные иконки (сетка 24x24, как в Material Symbols), рисуются кодом
    class Ico(var k: Int, var col: Int, val size: Int) : Drawable() {
        val pt = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun getIntrinsicWidth() = size
        override fun getIntrinsicHeight() = size
        override fun draw(c: Canvas) {
            val b = bounds
            val s = minOf(b.width(), b.height()) / 24f
            c.save(); c.translate(b.left.toFloat(), b.top.toFloat()); c.scale(s, s)
            pt.style = Paint.Style.STROKE; pt.strokeCap = Paint.Cap.ROUND; pt.strokeJoin = Paint.Join.ROUND
            pt.strokeWidth = 2f; pt.color = col
            when (k) {
                0 -> { // настройки
                    val g = Path()
                    val pts = doubleArrayOf(7.0, -18.0, 9.6, -10.0, 9.6, 10.0, 7.0, 18.0)
                    for (i in 0 until 8) {
                        for (j in 0 until 4) {
                            val a = Math.toRadians(i * 45.0 + pts[j * 2 + 1])
                            val x = (12 + pts[j * 2] * Math.cos(a)).toFloat()
                            val y = (12 + pts[j * 2] * Math.sin(a)).toFloat()
                            if (i == 0 && j == 0) g.moveTo(x, y) else g.lineTo(x, y)
                        }
                    }
                    g.close(); c.drawPath(g, pt); c.drawCircle(12f, 12f, 3f, pt)
                }
                1 -> { // инфо
                    c.drawCircle(12f, 12f, 9.5f, pt); c.drawLine(12f, 11f, 12f, 17f, pt)
                    pt.style = Paint.Style.FILL; c.drawCircle(12f, 7.6f, 1.25f, pt); pt.style = Paint.Style.STROKE
                }
                2 -> { // корзина
                    c.drawLine(4.5f, 7f, 19.5f, 7f, pt)
                    val h = Path(); h.moveTo(9.5f, 7f); h.lineTo(9.5f, 4.5f); h.lineTo(14.5f, 4.5f); h.lineTo(14.5f, 7f)
                    c.drawPath(h, pt)
                    val bd = Path(); bd.moveTo(6.5f, 7f); bd.lineTo(7.5f, 19.5f); bd.lineTo(16.5f, 19.5f); bd.lineTo(17.5f, 7f)
                    c.drawPath(bd, pt)
                    c.drawLine(10.2f, 11f, 10.2f, 16f, pt); c.drawLine(13.8f, 11f, 13.8f, 16f, pt)
                }
                3 -> { // крестик
                    c.drawLine(6f, 6f, 18f, 18f, pt); c.drawLine(18f, 6f, 6f, 18f, pt)
                }
                4 -> { // скрыть (глаз с чертой)
                    val e = Path(); e.moveTo(2.5f, 12f); e.quadTo(12f, 2.5f, 21.5f, 12f); e.quadTo(12f, 21.5f, 2.5f, 12f)
                    c.drawPath(e, pt); c.drawCircle(12f, 12f, 3f, pt); c.drawLine(4.5f, 19.5f, 19.5f, 4.5f, pt)
                }
                5 -> { // поиск
                    c.drawCircle(10.5f, 10.5f, 6.5f, pt); c.drawLine(15.3f, 15.3f, 20f, 20f, pt)
                }
                6 -> { // стрелка вверх
                    val pa = Path(); pa.moveTo(6f, 15f); pa.lineTo(12f, 9f); pa.lineTo(18f, 15f); c.drawPath(pa, pt)
                }
                9 -> { // стрелка вниз
                    val pa = Path(); pa.moveTo(6f, 9f); pa.lineTo(12f, 15f); pa.lineTo(18f, 9f); c.drawPath(pa, pt)
                }
                7, 8 -> { // замок закрытый / открытый
                    c.drawRoundRect(RectF(5f, 11f, 19f, 21f), 2.5f, 2.5f, pt)
                    val sh = Path(); sh.moveTo(8f, 11f); sh.lineTo(8f, 8f); sh.arcTo(RectF(8f, 4f, 16f, 12f), 180f, 180f)
                    if (k == 7) sh.lineTo(16f, 11f) else sh.lineTo(16f, 7f)
                    c.drawPath(sh, pt)
                    pt.style = Paint.Style.FILL; c.drawCircle(12f, 15.5f, 1.4f, pt); pt.style = Paint.Style.STROKE
                }
            }
            c.restore()
        }
        override fun setAlpha(a: Int) { pt.alpha = a }
        override fun setColorFilter(f: ColorFilter?) { pt.colorFilter = f }
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

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
    var wallView: ImageView? = null
    var needBake = false
    var launched = false
    var introPending = true
    var popPkg: String? = null
    var ovl: View? = null
    var pager: Pager? = null
    val pages = mutableListOf<FrameLayout>()
    var curPage = 0
    var clockBox: LinearLayout? = null
    var dotsBox: LinearLayout? = null
    var drawerGrid: GridView? = null
    var cellHint: View? = null
    var lastHint = -1
    var dockBox: LinearLayout? = null
    var searchBox: EditText? = null
    var dragBar: LinearLayout? = null
    val zones = mutableListOf<LinearLayout>()
    val zIcons = mutableListOf<Ico>()
    val zTexts = mutableListOf<TextView>()
    var dragApp: App? = null
    var dragSrc = ""
    var dragView: View? = null
    var moved = false
    var sx = 0f
    var sy = 0f
    var hot = -1
    var edgeDir = 0
    var edgeRun: Runnable? = null
    var lockView: FrameLayout? = null
    var lockOn = false
    var lockPending = false
    var resumed = false
    var ly0 = 0f
    var padIco: Ico? = null
    var bob: ValueAnimator? = null
    var sDlg: AlertDialog? = null
    val ui = Handler(Looper.getMainLooper())
    val pool = Executors.newSingleThreadExecutor()
    val workers = Executors.newFixedThreadPool(4)
    val DLG = android.R.style.Theme_DeviceDefault_Dialog_Alert
    val applyRun = Runnable { if (needBake) { needBake = false; rebake() } else rebuild() }
    val rcv = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { reload() }
    }
    val scr = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { if (resumed) lockPending = true }
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
    fun np() = gi("np", 2).coerceIn(1, 6)
    fun label(a: App) = p.getString("n_" + a.pkg, null) ?: a.orig
    fun lst(k: String): List<String> = (p.getString(k, "") ?: "").split(",").filter { it.isNotEmpty() }
    fun put(k: String, l: List<String>) = p.edit().putString(k, l.joinToString(",")).apply()
    val tc get() = gi("tc", Color.WHITE)
    val ac get() = gi("ac", cAcc)

    fun txt(t: String, s: Float) = TextView(this).apply {
        text = t; textSize = s; setTextColor(tc); setPadding(dp(10), dp(4), dp(10), dp(4))
    }
    fun pill(c: Int, r: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }
    fun ripple(fill: Int, r: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(withA(cOnSecC, 0x55)), pill(fill, r), pill(Color.WHITE, r))

    // анимация с явным сбросом задержки и интерполятора
    fun View.fx(dur: Long, ip: android.view.animation.Interpolator = DecelerateInterpolator(2f), delay: Long = 0L): ViewPropertyAnimator =
        animate().setDuration(dur).setInterpolator(ip).setStartDelay(delay)

    fun AlertDialog.Builder.m3(): AlertDialog = create().also { d ->
        d.show()
        d.window?.setBackgroundDrawable(pill(cSurf, 28))
        if (gb("an", true)) d.window?.decorView?.let { v ->
            v.alpha = 0f; v.scaleX = 0.86f; v.scaleY = 0.86f; v.translationY = dp(28).toFloat()
            v.fx(300, OvershootInterpolator(0.9f)).alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).start()
        }
    }

    fun gear() = ImageView(this).apply {
        setImageDrawable(Ico(0, cOnSecC, dp(24)))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = ripple(withA(cSecC, 0xCC), 26)
        setOnClickListener {
            if (gb("an", true)) fx(420).rotationBy(180f).start()
            settings()
        }
    }

    // ---------- стол: клетки и экраны ----------
    // хранится как "пакет|колонка|строка|экран;..."
    fun deskMap(): LinkedHashMap<String, IntArray> {
        val m = LinkedHashMap<String, IntArray>()
        (p.getString("deskp", "") ?: "").split(";").forEach { s ->
            val t = s.split("|")
            if (t.size >= 3) {
                val c = t[1].toIntOrNull(); val r = t[2].toIntOrNull()
                val pg = if (t.size >= 4) (t[3].toIntOrNull() ?: 0) else 0
                if (c != null && r != null) m[t[0]] = intArrayOf(c, r, pg)
            }
        }
        return m
    }

    fun saveDesk(m: Map<String, IntArray>) = p.edit()
        .putString("deskp", m.entries.joinToString(";") {
            it.key + "|" + it.value[0] + "|" + it.value[1] + "|" + it.value[2]
        }).apply()

    fun migrateDesk() {
        if (p.contains("deskp")) return
        val cols = gi("hc", 4)
        val m = LinkedHashMap<String, IntArray>()
        lst("desk").forEachIndexed { i, pk -> m[pk] = intArrayOf(i % cols, i / cols, 0) }
        saveDesk(m)
    }

    fun fixDesk(): LinkedHashMap<String, IntArray> {
        val cols = gi("hc", 4); val rows = gi("hr", 5); val n = np()
        val m = deskMap()
        var changed = false
        if (apps.isNotEmpty()) {
            val known = apps.map { it.pkg }.toSet()
            if (m.keys.retainAll(known)) changed = true
        }
        val used = HashSet<Int>()
        val pending = ArrayList<String>()
        for ((pk, pos) in m) {
            val ok = pos[2] in 0 until n && pos[0] in 0 until cols && pos[1] in 0 until rows &&
                used.add(pos[2] * 10000 + pos[1] * 100 + pos[0])
            if (!ok) pending.add(pk)
        }
        for (pk in pending) {
            var placed = false
            loop@ for (pg in 0 until n) {
                for (k in 0 until cols * rows) {
                    val key = pg * 10000 + (k / cols) * 100 + (k % cols)
                    if (key !in used) {
                        m[pk] = intArrayOf(k % cols, k / cols, pg); used.add(key); placed = true
                        break@loop
                    }
                }
            }
            if (!placed) m.remove(pk)
            changed = true
        }
        if (changed) saveDesk(m)
        return m
    }

    fun freeCell(m: Map<String, IntArray>, start: Int = curPage): IntArray? {
        val cols = gi("hc", 4); val rows = gi("hr", 5); val n = np()
        val used = m.values.map { it[2] * 10000 + it[1] * 100 + it[0] }.toSet()
        for (j in 0 until n) {
            val pg = (start + j) % n
            for (k in 0 until cols * rows) {
                val r = k / cols; val c = k % cols
                if (pg * 10000 + r * 100 + c !in used) return intArrayOf(c, r, pg)
            }
        }
        return null
    }

    fun deskPlace(pkg: String, c: Int, r: Int, pg: Int): Boolean {
        val m = fixDesk()
        val src = m[pkg]
        val occ = m.entries.firstOrNull { it.key != pkg && it.value[0] == c && it.value[1] == r && it.value[2] == pg }
        if (occ != null) {
            if (src != null) m[occ.key] = intArrayOf(src[0], src[1], src[2])
            else {
                val f = freeCell(m, pg)
                if (f == null) { Toast.makeText(this, "Стол заполнен", Toast.LENGTH_SHORT).show(); return false }
                m[occ.key] = f
            }
        } else if (src == null && freeCell(m, pg) == null) {
            Toast.makeText(this, "Стол заполнен", Toast.LENGTH_SHORT).show(); return false
        }
        m[pkg] = intArrayOf(c, r, pg)
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
        val pg = pager ?: return null
        if (pg.width == 0 || !pg.isShown) return null
        val a = IntArray(2); pg.getLocationInWindow(a)
        val b = IntArray(2); root.getLocationInWindow(b)
        val lx = x - (a[0] - b[0]); val ly = y - (a[1] - b[1])
        if (lx < 0 || ly < 0 || lx > pg.width || ly > pg.height) return null
        val cols = gi("hc", 4); val rows = gi("hr", 5)
        val ax = lx + pg.scrollX
        val page = (ax / pg.width).toInt().coerceIn(0, np() - 1)
        val localX = ax - page * pg.width
        return intArrayOf(
            (localX / (pg.width / cols.toFloat())).toInt().coerceIn(0, cols - 1),
            (ly / (pg.height / rows.toFloat())).toInt().coerceIn(0, rows - 1),
            page
        )
    }

    fun hintCell(cell: IntArray?) {
        val h = cellHint ?: return
        val pg = pager
        if (cell == null || pg == null || pg.width == 0) { h.visibility = View.GONE; lastHint = -1; return }
        val key = cell[2] * 10000 + cell[1] * 100 + cell[0]
        if (key == lastHint && h.visibility == View.VISIBLE) return
        lastHint = key
        val cw = pg.width / gi("hc", 4); val ch = pg.height / gi("hr", 5)
        h.layoutParams = FrameLayout.LayoutParams(cw, ch).apply {
            leftMargin = cell[2] * pg.width - pg.scrollX + cell[0] * cw; topMargin = cell[1] * ch
        }
        h.visibility = View.VISIBLE
        h.alpha = 0f; h.scaleX = 0.8f; h.scaleY = 0.8f
        h.fx(160, OvershootInterpolator(1.5f)).alpha(1f).scaleX(1f).scaleY(1f).start()
    }

    fun layoutDesk() {
        val pg = pager ?: return
        if (pg.width == 0) return
        val cw = pg.width / gi("hc", 4); val ch = pg.height / gi("hr", 5)
        val m = deskMap()
        pages.forEach { layer ->
            for (i in 0 until layer.childCount) {
                val v = layer.getChildAt(i)
                val pk = v.tag as? String ?: continue
                val pos = m[pk] ?: continue
                v.layoutParams = FrameLayout.LayoutParams(cw, ch).apply { leftMargin = pos[0] * cw; topMargin = pos[1] * ch }
            }
        }
    }

    inner class Pager(c: Context) : HorizontalScrollView(c) {
        val strip = LinearLayout(c)
        var flung = false
        var snapAnim: ValueAnimator? = null

        init {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            addView(strip, FrameLayout.LayoutParams(-2, -1))
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            post {
                for (i in 0 until strip.childCount) strip.getChildAt(i).layoutParams = LinearLayout.LayoutParams(w, h)
                post { scrollTo(curPage * w, 0); pageScrolled(scrollX); layoutDesk() }
            }
        }

        override fun onScrollChanged(l: Int, t: Int, ol: Int, ot: Int) {
            super.onScrollChanged(l, t, ol, ot)
            pageScrolled(l)
        }

        override fun fling(velocityX: Int) {
            flung = true
            snap(if (velocityX > 500) curPage + 1 else if (velocityX < -500) curPage - 1 else nearest())
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) { flung = false; snapAnim?.cancel() }
            val r = super.onTouchEvent(ev)
            if ((ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) && !flung) snap(nearest())
            return r
        }

        fun nearest(): Int = if (width == 0) 0 else Math.round(scrollX / width.toFloat())

        fun snap(pg: Int) {
            val t = pg.coerceIn(0, maxOf(0, strip.childCount - 1))
            curPage = t
            snapAnim?.cancel()
            val to = t * width
            if (!gb("an", true)) { scrollTo(to, 0); return }
            snapAnim = ValueAnimator.ofInt(scrollX, to).apply {
                duration = 340L; interpolator = DecelerateInterpolator(2f)
                addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
                start()
            }
        }
    }

    fun pageScrolled(x: Int) {
        val pg = pager ?: return
        val w = pg.width
        if (w == 0) return
        val pos = x / w.toFloat()
        pages.forEachIndexed { i, v ->
            val o = Math.abs(i - pos).coerceAtMost(1f)
            v.alpha = 1f - 0.5f * o; v.scaleX = 1f - 0.08f * o; v.scaleY = 1f - 0.08f * o
        }
        dotsBox?.let { d ->
            for (i in 0 until d.childCount) {
                val t = (1f - Math.abs(pos - i)).coerceIn(0f, 1f)
                val v = d.getChildAt(i)
                val lp = v.layoutParams
                val nw = dp(6) + (dp(16) * t).toInt()
                if (lp.width != nw) { lp.width = nw; v.layoutParams = lp }
                (v.background as? GradientDrawable)?.setColor(withA(if (t > 0.5f) ac else cVar, (0x66 + 0x99 * t).toInt()))
            }
        }
        val n = pages.size
        wallView?.translationX = if (n > 1) -(pos / (n - 1) - 0.5f) * 0.1f * w else 0f
    }

    // ---------- анимации ----------
    fun popView(c: View) {
        if (!gb("an", true)) return
        c.alpha = 0f; c.scaleX = 0.4f; c.scaleY = 0.4f
        c.fx(380, OvershootInterpolator(2.2f)).alpha(1f).scaleX(1f).scaleY(1f).start()
    }

    fun popAll(d0: Long) {
        if (!gb("an", true)) return
        val cells = ArrayList<View>()
        pager?.let { pg ->
            pages.getOrNull(pg.nearest())?.let { l ->
                for (j in 0 until l.childCount) if (l.getChildAt(j).tag is String) cells.add(l.getChildAt(j))
            }
        }
        dockBox?.let { d -> for (j in 0 until d.childCount) cells.add(d.getChildAt(j)) }
        var i = 0
        cells.forEach { v ->
            v.alpha = 0f; v.scaleX = 0.6f; v.scaleY = 0.6f
            v.fx(340, OvershootInterpolator(1.4f), d0 + i * 28L).alpha(1f).scaleX(1f).scaleY(1f).start()
            i++
        }
    }

    fun intro() {
        if (!gb("an", true)) return
        clockBox?.apply { alpha = 0f; translationY = -dp(28).toFloat(); fx(420).alpha(1f).translationY(0f).start() }
        dockBox?.apply {
            alpha = 0f; translationY = dp(72).toFloat()
            fx(460, OvershootInterpolator(0.9f), 120L).alpha(1f).translationY(0f).start()
        }
        dotsBox?.apply { alpha = 0f; fx(300, DecelerateInterpolator(2f), 300L).alpha(1f).start() }
        popAll(80L)
    }

    fun returnAnim() {
        if (!gb("an", true)) return
        val v = if (prog > 0.5f) drw else home
        v.alpha = 0f; v.scaleX = 1.1f; v.scaleY = 1.1f
        v.fx(420).alpha(1f).scaleX(1f).scaleY(1f).start()
        wallView?.let { w ->
            w.scaleX = 1.3f; w.scaleY = 1.3f
            w.fx(560).scaleX(1.12f + 0.06f * prog).scaleY(1.12f + 0.06f * prog).start()
        }
        if (prog <= 0.5f) popAll(80L)
    }

    fun staggerDrawer() {
        val g = drawerGrid ?: return
        val cols = gi("cols", 4)
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            c.alpha = 0f; c.translationY = dp(36).toFloat()
            c.fx(320, DecelerateInterpolator(2f), 40L + (i / cols) * 35L).alpha(1f).translationY(0f).start()
        }
    }

    // ---------- экран блокировки ----------
    fun showLock() {
        if (lockOn) return
        lockOn = true
        val lv = FrameLayout(this)
        lv.setBackgroundColor(gi("wc", cSurf))
        val wd = wallpaper()?.constantState?.newDrawable()
        if (wd != null) lv.addView(ImageView(this).apply {
            setImageDrawable(wd); scaleType = ImageView.ScaleType.CENTER_CROP
        }, FrameLayout.LayoutParams(-1, -1))
        lv.addView(View(this).apply { setBackgroundColor(withA(Color.BLACK, 0x70)) }, FrameLayout.LayoutParams(-1, -1))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(96), dp(24), dp(40))
        }
        val ico = Ico(7, cOnSecC, dp(28)); padIco = ico
        col.addView(ImageView(this).apply {
            setImageDrawable(ico); setPadding(dp(22), dp(22), dp(22), dp(22)); background = pill(withA(cSecC, 0xCC), 36)
        }, LinearLayout.LayoutParams(dp(72), dp(72)))
        col.addView(TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"; textSize = 92f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(28) })
        col.addView(TextClock(this).apply {
            format24Hour = "EEEE, d MMMM"; format12Hour = "EEEE, d MMMM"; textSize = 18f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val hint = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        hint.addView(ImageView(this).apply { setImageDrawable(Ico(6, withA(Color.WHITE, 0xDD), dp(28))) },
            LinearLayout.LayoutParams(dp(28), dp(28)))
        hint.addView(TextView(this).apply {
            text = "Смахни вверх, чтобы открыть"; setTextColor(withA(Color.WHITE, 0xDD)); textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        col.addView(hint)
        lv.addView(col, FrameLayout.LayoutParams(-1, -1))
        root.addView(lv, FrameLayout.LayoutParams(-1, -1))
        lockView = lv
        if (gb("an", true)) {
            lv.alpha = 0f; lv.fx(320).alpha(1f).start()
            bob?.cancel()
            bob = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1100L; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
                addUpdateListener { hint.translationY = -dp(10) * (it.animatedValue as Float) }
                start()
            }
        }
    }

    fun lockTouch(ev: MotionEvent): Boolean {
        val lv = lockView ?: return true
        val h = resources.displayMetrics.heightPixels.toFloat()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ly0 = ev.y
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                val dy = minOf(0f, ev.y - ly0)
                lv.translationY = dy
                lv.alpha = 1f - 0.7f * (-dy / h)
                val want = if (-dy > dp(90)) 8 else 7
                padIco?.let { if (it.k != want) { it.k = want; it.invalidateSelf() } }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(ev); vt?.computeCurrentVelocity(1000)
                val vy = vt?.yVelocity ?: 0f
                vt?.recycle(); vt = null
                val dy = ev.y - ly0
                if (-dy > dp(150) || vy < -1000f) unlock()
                else {
                    padIco?.let { it.k = 7; it.invalidateSelf() }
                    lv.fx(300, OvershootInterpolator(1.6f)).translationY(0f).alpha(1f).start()
                }
            }
        }
        return true
    }

    fun unlock() {
        val lv = lockView ?: return
        lockOn = false
        bob?.cancel(); bob = null
        padIco?.let { it.k = 8; it.invalidateSelf() }
        lv.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val done = Runnable {
            (lv.parent as? ViewGroup)?.removeView(lv)
            if (lockView === lv) lockView = null
            if (prog <= 0.5f) popAll(0L)
        }
        if (gb("an", true)) {
            val h = resources.displayMetrics.heightPixels.toFloat()
            lv.fx(340, DecelerateInterpolator(1.8f)).translationY(-h).alpha(0f).withEndAction(done).start()
        } else done.run()
    }

    // ---------- жизненный цикл ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        p = getSharedPreferences("cfg", 0)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        migrateDesk()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
        }
        val f2 = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(rcv, f, Context.RECEIVER_EXPORTED)
            registerReceiver(scr, f2, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(rcv, f)
            registerReceiver(scr, f2)
        }
        rebuild(); reload()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        ovl?.let { root.removeView(it) }; ovl = null
        if (lockPending) {
            lockPending = false
            if (gb("lock", true) && !lockOn) showLock()
        }
        if (launched) { launched = false; returnAnim() }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) lockPending = true
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        pool.shutdown()
        workers.shutdown()
        try { unregisterReceiver(rcv) } catch (e: Exception) {}
        try { unregisterReceiver(scr) } catch (e: Exception) {}
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        if (lockOn) return
        if (prog > 0f) go(false)
        else pager?.snap(0)
    }

    override fun onBackPressed() {
        if (lockOn) { unlock(); return }
        if (prog > 0.5f) go(false)
    }

    // ---------- жесты: список едет за пальцем ----------
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (lockOn) return lockTouch(ev)
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
        wallView?.let { it.scaleX = 1.12f + 0.06f * v; it.scaleY = 1.12f + 0.06f * v }
    }

    fun go(open: Boolean) {
        anim?.cancel()
        val an = gb("an", true)
        if (open && prog < 0.05f && an) staggerDrawer()
        if (!open && prog > 0.3f && an) popAll(120L)
        anim = ValueAnimator.ofFloat(prog, if (open) 1f else 0f).apply {
            duration = if (an) 300L else 0L
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

    // ---------- данные, иконки и кэш ----------
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

    fun cacheKey() = "${dp(gi("is", 60))}|${gi("rad", 24)}|${gi("shape", 0)}|${gb("mono", false)}|${gb("oi", true)}|$cSecC|$cOnSecC"

    fun sig(l: List<App>) = l.sortedBy { it.pkg }.joinToString("|") { it.pkg + "=" + it.orig }

    fun readCache(): List<App>? {
        try {
            val dir = File(cacheDir, "ic")
            val idx = File(dir, "index.txt")
            if (!idx.exists()) return null
            val lines = idx.readLines()
            if (lines.isEmpty() || lines[0] != cacheKey()) return null
            val hidden = p.getStringSet("hidden", emptySet())!!
            val out = ArrayList<App>()
            for (ln in lines.drop(1)) {
                val t = ln.split("\t")
                if (t.size < 2 || t[0] in hidden) continue
                val b = BitmapFactory.decodeFile(File(dir, t[0] + ".png").path) ?: continue
                out.add(App(t[0], t[1]).also { it.bmp = b })
            }
            return if (out.isEmpty()) null else out
        } catch (e: Exception) { return null }
    }

    fun writeCache(list: List<App>) {
        try {
            val dir = File(cacheDir, "ic"); dir.mkdirs()
            list.forEach { a ->
                a.bmp?.let { b -> FileOutputStream(File(dir, a.pkg + ".png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            }
            File(dir, "index.txt").writeText(
                cacheKey() + "\n" + list.joinToString("\n") { it.pkg + "\t" + it.orig.replace("\t", " ").replace("\n", " ") }
            )
        } catch (e: Exception) {}
    }

    fun reload() {
        pool.execute {
            val cached = if (apps.isEmpty()) readCache() else null
            if (cached != null) ui.post { if (apps.isEmpty()) { apps = cached; defaults(); rebuild() } }
            val pm = packageManager
            val hidden = p.getStringSet("hidden", emptySet())!!
            val ris = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .distinctBy { it.activityInfo.packageName }
                .filter { it.activityInfo.packageName !in hidden }
            val list = ris.map { App(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            val s = dp(gi("is", 60)); val r = gi("rad", 24)
            val latch = CountDownLatch(list.size)
            list.forEachIndexed { i, a ->
                workers.execute {
                    try { val d = icon(ris[i]); a.raw = d; a.bmp = Shapes.shape(bake(d, s, r), gi("shape", 0), a.pkg) }
                    catch (e: Exception) {}
                    finally { latch.countDown() }
                }
            }
            latch.await()
            val same = cached != null && sig(cached) == sig(list)
            ui.post {
                apps = list
                if (!same) { defaults(); rebuild() }
            }
            if (!same) writeCache(list)
        }
    }

    fun rebake() {
        val cur = apps
        if (cur.any { it.raw == null }) { reload(); return }
        val s = dp(gi("is", 60)); val r = gi("rad", 24)
        pool.execute {
            val latch = CountDownLatch(cur.size)
            cur.forEach { a ->
                workers.execute {
                    try { a.raw?.let { a.bmp = Shapes.shape(bake(it, s, r), gi("shape", 0), a.pkg) } }
                    catch (e: Exception) {}
                    finally { latch.countDown() }
                }
            }
            latch.await()
            ui.post { rebuild() }
            writeCache(cur)
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
        wallView = null
        if (w != null) {
            val iv = ImageView(this).apply {
                setImageDrawable(w); scaleType = ImageView.ScaleType.CENTER_CROP; scaleX = 1.12f; scaleY = 1.12f
            }
            wallView = iv
            fr.addView(iv, FrameLayout.LayoutParams(-1, -1))
        }
        fr.addView(View(this).apply { setBackgroundColor(gi("bg", 0x33000000)) }, FrameLayout.LayoutParams(-1, -1))
        val h = LinearLayout(this); buildHome(h); home = h
        fr.addView(h, FrameLayout.LayoutParams(-1, -1))
        val d = LinearLayout(this); buildDrawer(d); drw = d
        fr.addView(d, FrameLayout.LayoutParams(-1, -1))
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER; visibility = View.GONE; setPadding(dp(12), dp(36), dp(12), dp(8)) }
        zones.clear(); zIcons.clear(); zTexts.clear()
        val zt = listOf("Инфо", "Удалить", "Убрать")
        for (n in 0 until 3) {
            val ic = Ico(n + 1, cOnSecC, dp(20))
            val tv = TextView(this).apply { text = zt[n]; textSize = 14f; setPadding(dp(6), 0, 0, 0) }
            val z = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(14), dp(8), dp(14)) }
            z.addView(ImageView(this).apply { setImageDrawable(ic) }, LinearLayout.LayoutParams(dp(20), dp(20)))
            z.addView(tv, LinearLayout.LayoutParams(-2, -2))
            zIcons.add(ic); zTexts.add(tv); zones.add(z)
            bar.addView(z, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        dragBar = bar
        paintZones()
        fr.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        fr.setOnDragListener { _, e -> onDrag(e) }
        lockView?.let { lv ->
            if (lockOn) { (lv.parent as? ViewGroup)?.removeView(lv); fr.addView(lv, FrameLayout.LayoutParams(-1, -1)) }
        }
        fr.setOnApplyWindowInsetsListener { _, ins ->
            home.setPadding(dp(12), ins.stableInsetTop + dp(8), dp(12), ins.stableInsetBottom + dp(8))
            drw.setPadding(dp(12), ins.stableInsetTop + dp(12), dp(12), ins.stableInsetBottom + dp(8))
            ins
        }
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
        clockBox = c
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

        curPage = curPage.coerceIn(0, np() - 1)
        val pg = Pager(this)
        pager = pg
        pages.clear()
        for (i in 0 until np()) {
            val layer = FrameLayout(this)
            pages.add(layer)
            pg.strip.addView(layer, LinearLayout.LayoutParams(1, 1))
        }
        val wrap = FrameLayout(this)
        wrap.addView(pg, FrameLayout.LayoutParams(-1, -1))
        val hint = View(this).apply { background = pill(withA(ac, 0x44), 20); visibility = View.GONE }
        wrap.addView(hint, FrameLayout.LayoutParams(1, 1))
        cellHint = hint; lastHint = -1
        if (deskMap().isEmpty()) wrap.addView(
            txt("Пусто. Нажми сюда или перетащи приложения из списка", 15f).apply {
                gravity = Gravity.CENTER; setOnClickListener { pickDesk() }
            }, FrameLayout.LayoutParams(-1, -1)
        )
        h.addView(wrap, LinearLayout.LayoutParams(-1, 0, 1f))

        val dots = LinearLayout(this).apply { gravity = Gravity.CENTER }
        for (i in 0 until np()) dots.addView(
            View(this).apply { background = pill(withA(cVar, 0x66), 3) },
            LinearLayout.LayoutParams(dp(6), dp(6)).apply { setMargins(dp(3), 0, dp(3), 0) }
        )
        dotsBox = dots
        if (np() < 2) dots.visibility = View.GONE
        h.addView(dots, LinearLayout.LayoutParams(-1, dp(18)))

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
        val rr = dp(28).toFloat()
        d.background = GradientDrawable().apply {
            setColor(gi("dbg", withA(cSurf, 0xE8)))
            cornerRadii = floatArrayOf(rr, rr, rr, rr, 0f, 0f, 0f, 0f)
        }
        d.addView(View(this).apply { background = pill(withA(cVar, 0x99), 2) },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        searchBox = null
        if (gb("search", true)) {
            val e = EditText(this).apply {
                hint = "Поиск приложений"; setHintTextColor(withA(cVar, 0xCC)); setTextColor(cOnSurf)
                setSingleLine(); textSize = 16f
                background = pill(gi("pc", withA(cSurfC, 0xE6)), 26)
                setCompoundDrawablesRelativeWithIntrinsicBounds(Ico(5, withA(cVar, 0xCC), dp(20)), null, null, null)
                compoundDrawablePadding = dp(10)
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
        val m = fixDesk()
        pages.forEachIndexed { i, layer ->
            layer.removeAllViews()
            for ((pk, pos) in m) {
                if (pos[2] != i) continue
                val a = apps.find { it.pkg == pk } ?: continue
                val c = newCell(true); c.tag = pk; bind(c, a, "desk")
                layer.addView(c, FrameLayout.LayoutParams(1, 1))
                if (pk == popPkg) popView(c)
            }
        }
        pager?.post { layoutDesk() }
        dockBox?.apply {
            removeAllViews()
            lst("dockl").mapNotNull { pk -> apps.find { it.pkg == pk } }.forEach { a ->
                val c = newCell(gb("dlbl", false)); bind(c, a, "dock")
                addView(c, LinearLayout.LayoutParams(0, -2, 1f))
                if (a.pkg == popPkg) popView(c)
            }
        }
        popPkg = null
        if (introPending && apps.isNotEmpty()) { introPending = false; root.post { intro() } }
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
                        v.fx(90).scaleX(0.9f).scaleY(0.9f).start()
                    else if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)
                        v.fx(260, OvershootInterpolator(3f)).scaleX(1f).scaleY(1f).start()
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
        ov.fx(320, DecelerateInterpolator(1.4f)).scaleX(k).scaleY(k)
            .translationX(dm.widthPixels / 2f - (loc[0] + iv.width / 2f))
            .translationY(dm.heightPixels / 2f - (loc[1] + iv.height / 2f))
            .alpha(0f).start()
        home.fx(300).alpha(0.4f).scaleX(0.94f).scaleY(0.94f).start()
        ui.postDelayed(Runnable {
            try {
                startActivity(i, ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out).toBundle())
            } catch (e: Exception) { startActivity(i) }
        }, 90L)
        ui.postDelayed(Runnable {
            if (ovl === ov) { root.removeView(ov); ovl = null }
            if (prog <= 0.5f) { home.alpha = 1f; home.scaleX = 1f; home.scaleY = 1f }
        }, 900L)
    }

    // ---------- перетаскивание ----------
    fun startDrag(v: View, a: App, src: String) {
        dragApp = a; dragSrc = src; dragView = v; moved = false; hot = -1
        zTexts[2].text = if (src == "drawer") "Скрыть" else "Убрать"
        zIcons[2].k = if (src == "drawer") 4 else 3
        zIcons[2].invalidateSelf()
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        v.startDragAndDrop(ClipData.newPlainText("app", a.pkg), View.DragShadowBuilder(v), null, 0)
        v.alpha = 0.3f
    }

    fun paintZones() {
        zones.forEachIndexed { i, z ->
            val on = i == hot
            z.background = pill(if (on) ac else cSecC, 24)
            zTexts[i].setTextColor(if (on) cOnAcc else cOnSecC)
            zIcons[i].col = if (on) cOnAcc else cOnSecC
            zIcons[i].invalidateSelf()
            val sc = if (on) 1.08f else 1f
            z.fx(140, OvershootInterpolator(2f)).scaleX(sc).scaleY(sc).start()
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

    fun stopEdge() {
        edgeRun?.let { ui.removeCallbacks(it) }
        edgeRun = null; edgeDir = 0
    }

    fun onDrag(e: DragEvent): Boolean {
        val a = dragApp ?: return false
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> { sx = e.x; sy = e.y; return true }
            DragEvent.ACTION_DRAG_LOCATION -> {
                if (!moved && Math.hypot((e.x - sx).toDouble(), (e.y - sy).toDouble()) > dp(24)) {
                    moved = true
                    dragBar?.apply {
                        visibility = View.VISIBLE; alpha = 0f; translationY = -dp(60).toFloat()
                        fx(240, DecelerateInterpolator(1.6f)).alpha(1f).translationY(0f).start()
                    }
                    if (dragSrc == "drawer" && prog > 0f) go(false)
                }
                if (moved) {
                    val z = zoneAt(e.x, e.y)
                    if (z != hot) { hot = z; paintZones() }
                    hintCell(if (z < 0 && !hit(dockBox, e.x, e.y)) cellAt(e.x, e.y) else null)
                    val pgv = pager
                    if (pgv != null && prog < 0.5f) {
                        val dir = if (e.x < dp(36)) -1 else if (e.x > root.width - dp(36)) 1 else 0
                        if (dir != edgeDir) {
                            stopEdge()
                            edgeDir = dir
                            if (dir != 0) {
                                val r = object : Runnable {
                                    override fun run() {
                                        val target = curPage + dir
                                        if (target in 0 until np()) pgv.snap(target)
                                        if (edgeDir == dir) ui.postDelayed(this, 750L)
                                    }
                                }
                                edgeRun = r
                                ui.postDelayed(r, 450L)
                            }
                        }
                    }
                }
            }
            DragEvent.ACTION_DROP -> { stopEdge(); drop(a, e.x, e.y) }
            DragEvent.ACTION_DRAG_ENDED -> {
                stopEdge()
                dragView?.alpha = 1f
                val b = dragBar
                if (b != null && b.visibility == View.VISIBLE) {
                    b.fx(180).alpha(0f).translationY(-dp(40).toFloat()).withEndAction {
                        if (dragApp == null) b.visibility = View.GONE
                    }.start()
                }
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
            popPkg = a.pkg
            rebuild(); return
        }
        val cell = cellAt(x, y)
        if (cell != null && deskPlace(a.pkg, cell[0], cell[1], cell[2])) {
            if (dragSrc == "dock") put("dockl", lst("dockl") - a.pkg)
            popPkg = a.pkg
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
        put(k, l); popPkg = pkg; rebuild()
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
                5 -> { if (onDesk) removeDesk(a.pkg) else { addDeskAuto(a.pkg); popPkg = a.pkg }; rebuild() }
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
        setPadding(dp(8), dp(22), 0, dp(4))
    })

    fun LinearLayout.card(t: String): LinearLayout {
        hdr(t)
        val c = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; background = pill(cSurfC, 24)
            setPadding(dp(16), dp(6), dp(16), dp(14))
        }
        addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        return c
    }

    fun LinearLayout.btn(t: String, a: () -> Unit) = addView(Button(context).apply {
        text = t; setAllCaps(false); setTextColor(cOnSecC); background = ripple(cSecC, 20)
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
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(10)) }
        val names = arrayOf("Мои обои (встроенные)", "Системные обои", "Фото из галереи", "Один цвет")
        var c = l.card("ОБОИ")
        c.btn("Источник обоев") {
            AlertDialog.Builder(this, DLG).setItems(names) { _, w -> p.edit().putInt("wm", w).apply(); change(false) }.m3()
        }
        c.btn("Выбрать фото из галереи") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 7)
        }
        c.color("Затемнение обоев", "bg", 0x33000000)
        c.color("Цвет фона (режим «цвет»)", "wc", cSurf)
        c = l.card("ГЛАВНЫЙ СТОЛ")
        c.btn("Выбрать приложения для стола") { pickDesk() }
        c.seek("Экранов стола", "np", 1, 6, 2)
        c.seek("Колонок на столе", "hc", 3, 7, 4)
        c.seek("Строк на столе", "hr", 3, 8, 5)
        c.sw("Часы и дата", "clock", true)
        c.seek("Размер часов", "cs", 24, 96, 64)
        c = l.card("БЛОКИРОВКА")
        c.sw("Экран блокировки Dino", "lock", true)
        c.btn("Показать экран блокировки") { sDlg?.dismiss(); showLock() }
        c = l.card("ДОК")
        c.sw("Показывать док", "sdock", true)
        c.sw("Подписи в доке", "dlbl", false)
        c.seek("Скругление дока", "dockr", 0, 50, 32)
        c.color("Цвет дока", "dockc", withA(cSurfC, 0xCC))
        c = l.card("СПИСОК ПРИЛОЖЕНИЙ")
        c.seek("Колонок в списке", "cols", 3, 7, 4)
        c.color("Фон списка", "dbg", withA(cSurf, 0xE8))
        c.sw("Поиск", "search", true)
        c.color("Цвет поиска", "pc", withA(cSurfC, 0xE6))
        c.sw("Сортировка Я→А", "rev", false)
        c = l.card("ИКОНКИ")
        c.sw("Оригинальные иконки (без темы системы)", "oi", true, 1)
        c.sw("Темизированные иконки Material You (Android 13+)", "mono", false, 2)
        c.seek("Размер иконок", "is", 32, 96, 60, true)
        c.seek("Скругление иконок, %", "rad", 0, 50, 24, true)
        c.btn("Форма иконок: " + Shapes.names[gi("shape", 0)]) {
            AlertDialog.Builder(this, DLG).setItems(Shapes.names) { _, w ->
                p.edit().putInt("shape", w).apply(); change(true)
            }.m3()
        }
        c.seek("Отступ между рядами", "gap", 0, 40, 6)
        c = l.card("ТЕКСТ")
        c.sw("Подписи под иконками", "lbl", true)
        c.seek("Размер подписей", "ts", 8, 20, 12)
        c.color("Цвет текста", "tc", Color.WHITE)
        c = l.card("ПРОЧЕЕ")
        c.sw("Анимации", "an", true)
        c.color("Акцентный цвет", "ac", cAcc)
        c.btn("Показать скрытые приложения") { p.edit().remove("hidden").apply(); reload() }
        c.btn("Сбросить всё") { p.edit().clear().apply(); wallMode = -1; migrateDesk(); reload() }
        sDlg = AlertDialog.Builder(this, DLG).setTitle("Настройки")
            .setView(ScrollView(this).apply { addView(l) })
            .setPositiveButton("Готово", null).m3()
        if (gb("an", true)) for (i in 0 until minOf(l.childCount, 12)) {
            val v = l.getChildAt(i)
            v.alpha = 0f; v.translationY = dp(30).toFloat()
            v.fx(360, DecelerateInterpolator(2f), 60L + i * 40L).alpha(1f).translationY(0f).start()
        }
    }
}
