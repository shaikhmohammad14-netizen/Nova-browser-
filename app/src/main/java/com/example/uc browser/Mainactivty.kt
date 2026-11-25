package com.example.ucbrowser

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/** All user-customisable glass / layout settings, saved between launches. */
object Glass {
    const val DEFAULT_ACCENT = 0xFF3B6DFF.toInt()
    var blur = 16f
    var tint = 0.2f
    var hi = 0.7f
    var rad = 22f
    var jelly = 1f
    var acc = DEFAULT_ACCENT
    var abarTop = true
    var dockFloat = true
    var wall = 0
    var auto = true
    var hz = 2
    var ver = 0
    var wallpaper: WallpaperView? = null
    private lateinit var p: SharedPreferences

    val scrim: Float get() = if (auto) maxOf(0f, 0.36f * (1f - tint / 0.5f)) else 0f

    fun init(c: Context) {
        p = c.getSharedPreferences("nova", Context.MODE_PRIVATE)
        blur = p.getFloat("g_blur", 16f); tint = p.getFloat("g_tint", 0.2f); hi = p.getFloat("g_hi", 0.7f)
        rad = p.getFloat("g_rad", 22f); jelly = p.getFloat("g_jelly", 1f); acc = p.getInt("g_acc", DEFAULT_ACCENT)
        abarTop = p.getBoolean("g_abar", true); dockFloat = p.getBoolean("g_dock", true)
        wall = p.getInt("g_wall", 0); auto = p.getBoolean("g_auto", true); hz = p.getInt("g_hz", 2)
    }

    fun save() {
        p.edit().putFloat("g_blur", blur).putFloat("g_tint", tint).putFloat("g_hi", hi).putFloat("g_rad", rad)
            .putFloat("g_jelly", jelly).putInt("g_acc", acc).putBoolean("g_abar", abarTop)
            .putBoolean("g_dock", dockFloat).putInt("g_wall", wall).putBoolean("g_auto", auto).putInt("g_hz", hz).apply()
    }

    fun reset() {
        blur = 16f; tint = 0.2f; hi = 0.7f; rad = 22f; jelly = 1f; acc = DEFAULT_ACCENT
        abarTop = true; dockFloat = true; wall = 0; auto = true
    }
}

fun hex(s: String): Int = Color.parseColor(s)
fun white(a: Float): Int = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), 255, 255, 255)

package com.example.ucbrowser

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator

/** iOS-style liquid glass slider: the thumb stretches while you drag it. */
class GlassSlider(
    ctx: Context, private val min: Float, private val max: Float, var value: Float,
    private val onChange: (Float) -> Unit
) : View(ctx) {
    private var stretch = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val thumbW get() = 38.dp.toFloat()
    private val thumbH get() = 24.dp.toFloat()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val frac = ((value - min) / (max - min)).coerceIn(0f, 1f)
        val cx = thumbW / 2 + frac * (w - thumbW)
        val th = 8.dp.toFloat()
        p.shader = null; p.color = white(.2f)
        r.set(0f, h / 2 - th / 2, w, h / 2 + th / 2); c.drawRoundRect(r, th / 2, th / 2, p)
        p.color = Glass.acc
        r.set(0f, h / 2 - th / 2, cx, h / 2 + th / 2); c.drawRoundRect(r, th / 2, th / 2, p)
        val tw = thumbW * (1f + .45f * stretch); val tH = thumbH * (1f + .25f * stretch)
        r.set(cx - tw / 2, h / 2 - tH / 2, cx + tw / 2, h / 2 + tH / 2)
        p.color = 0x55000000; c.drawRoundRect(RectF(r.left, r.top + 2.dp, r.right, r.bottom + 2.dp), tH / 2, tH / 2, p)
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, white(.97f), hex("#DDE5FF"), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, tH / 2, tH / 2, p); p.shader = null
    }

    private fun setFromX(x: Float) {
        val frac = ((x - thumbW / 2) / (width - thumbW)).coerceIn(0f, 1f)
        value = min + frac * (max - min)
        onChange(value); invalidate()
    }

    private fun anim(to: Float) {
        ValueAnimator.ofFloat(stretch, to).apply {
            duration = 350; interpolator = OvershootInterpolator(2.5f)
            addUpdateListener { stretch = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent.requestDisallowInterceptTouchEvent(true); anim(1f); setFromX(e.x) }
            MotionEvent.ACTION_MOVE -> setFromX(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> anim(0f)
        }
        return true
    }
}

package com.example.ucbrowser

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.widget.FrameLayout

/** A frosted liquid-glass panel: blurred wallpaper + tint + shine + bright rim. */
class GlassView(ctx: Context) : FrameLayout(ctx) {
    var fixedRadius = -1f
    var circle = false
    var accentRim = false
    var topOnly = false
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val dst = Rect()
    private var ox = 0f
    private var oy = 0f

    init { setWillNotDraw(false) }

    private fun offsetIn(root: View) {
        var x = 0f; var y = 0f; var v: View = this
        while (true) {
            x += v.left; y += v.top
            val p = v.parent as? View ?: break
            if (p === root) break
            x -= p.scrollX; y -= p.scrollY
            v = p
        }
        ox = x; oy = y
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val rr = when {
            circle -> minOf(w, h) / 2
            fixedRadius >= 0f -> fixedRadius.dp
            else -> Glass.rad.dp
        }.coerceAtMost(minOf(w, h) / 2)
        rect.set(0f, 0f, w, h)
        path.reset()
        if (topOnly) path.addRoundRect(rect, floatArrayOf(rr, rr, rr, rr, 0f, 0f, 0f, 0f), Path.Direction.CW)
        else path.addRoundRect(rect, rr, rr, Path.Direction.CW)

        c.save(); c.clipPath(path)
        val wp = Glass.wallpaper
        val bm = wp?.blurred
        if (wp != null && bm != null) {
            offsetIn(wp.parent as View)
            c.save(); c.translate(-ox, -oy)
            dst.set(0, 0, wp.width, wp.height)
            c.drawBitmap(bm, null, dst, bmpPaint)
            c.restore()
        }
        val t = Glass.tint
        fill.shader = LinearGradient(0f, 0f, w, h, intArrayOf(white(t * 1.4f), white(t * .35f), white(t * .85f)),
            floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(rect, fill)
        val sc = Glass.scrim
        if (sc > 0f) {
            fill.shader = null
            fill.color = android.graphics.Color.argb((sc * 255).toInt(), 6, 12, 38)
            c.drawRect(rect, fill)
        }
        fill.shader = RadialGradient(.18f * w, 0f, maxOf(w * .9f, h * .8f),
            intArrayOf(white(Glass.hi * .43f), 0), floatArrayOf(0f, .55f), Shader.TileMode.CLAMP)
        c.drawRect(rect, fill)
        fill.shader = null
        c.restore()

        rim.shader = null; rim.strokeWidth = 2.dp.toFloat(); rim.color = white(Glass.hi * .55f)
        c.drawPath(path, rim)
        rim.shader = LinearGradient(0f, 0f, 0f, h * .5f, white(Glass.hi), 0, Shader.TileMode.CLAMP)
        rim.strokeWidth = 3.dp.toFloat()
        c.drawPath(path, rim)
        rim.shader = null
        if (accentRim) { rim.color = Glass.acc; rim.strokeWidth = 3.dp.toFloat(); c.drawPath(path, rim) }
    }
}

package com.example.ucbrowser

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.URLUtil
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse
import java.net.URLEncoder

class Tab(val session: GeckoSession) {
    var url = ""
    var title = ""
    var showHome = true
    var canBack = false
    var canForward = false
    var loading = false
    var desktop = false
}

class MainActivity : AppCompatActivity() {

    companion object {
        private var runtimeInstance: GeckoRuntime? = null
    }

    private val ctx: Context get() = this
    private lateinit var runtime: GeckoRuntime
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private val tabs = mutableListOf<Tab>()
    private var cur = -1
    private val tab: Tab get() = tabs[cur]

    private lateinit var root: FrameLayout
    private lateinit var geckoView: GeckoView
    private lateinit var wallpaper: WallpaperView
    private val screens = LinkedHashMap<String, View>()
    private var screen = "splash"
    private var topInset = 0
    private var botInset = 0
    private var fullscreen = false

    private lateinit var bar: GlassView
    private lateinit var urlInput: EditText
    private lateinit var homeInput: EditText
    private lateinit var lockIcon: ImageView
    private lateinit var btnReload: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var nav: GlassView
    private lateinit var navRow: LinearLayout
    private lateinit var indicator: View
    private lateinit var btnBack: View
    private lateinit var btnFwd: View
    private lateinit var btnHome: View
    private lateinit var btnTabs: View
    private lateinit var btnMenu: View
    private lateinit var tabCount: TextView
    private lateinit var tabsTitle: TextView
    private lateinit var tabsList: LinearLayout
    private lateinit var dlList: LinearLayout
    private lateinit var dlFilters: LinearLayout
    private lateinit var setRows: LinearLayout
    private lateinit var cusPanel: LinearLayout
    private var dlFilter = 0
    private var indInit = false

    private val engines = linkedMapOf(
        "Google" to "https://www.google.com/search?q=%s",
        "DuckDuckGo" to "https://duckduckgo.com/?q=%s",
        "Bing" to "https://www.bing.com/search?q=%s",
        "Brave" to "https://search.brave.com/search?q=%s"
    )
    private val hzNames = listOf("Auto", "60 Hz", "Max (120 Hz)")
    private val palette = listOf("#3B6DFF", "#FF8A1F", "#8B5CF6", "#10B981", "#EC4899", "#06B6D4").map { hex(it) }

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("nova", MODE_PRIVATE)
        Glass.init(this)

        if (runtimeInstance == null) runtimeInstance = GeckoRuntime.create(applicationContext)
        runtime = runtimeInstance!!
        applyTracking(prefs.getBoolean("etp", true))

        root = findViewById(R.id.root)
        geckoView = findViewById(R.id.geckoView)
        wallpaper = WallpaperView(this)
        Glass.wallpaper = wallpaper
        root.addView(wallpaper, 0, FrameLayout.LayoutParams(MATCH, MATCH))

        buildAll()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            val k = ins.getInsets(WindowInsetsCompat.Type.ime())
            topInset = b.top
            botInset = maxOf(b.bottom, k.bottom)
            layoutUi()
            ins
        }
        applyHz()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        newTab()
        show("splash")
        val link = intent?.data
        if (link != null) load(link.toString()) else handler.postDelayed({ if (screen == "splash") showPage() }, 1800)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (isFinishing) tabs.forEach { it.session.close() }
    }

    // ------------------------------------------------------------ building the UI

    private fun buildAll() {
        screens["splash"] = buildSplash()
        screens["home"] = buildHome()
        screens["tabs"] = buildTabs()
        screens["dl"] = buildDownloads()
        screens["set"] = buildSettings()
        screens["cus"] = buildCustomize()
        screens.values.forEach { root.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)); it.visibility = View.GONE }
        buildBar()
        buildNav()
        layoutUi()
    }

    private fun logo(size: Int): GlassView {
        val g = GlassView(this).apply { fixedRadius = size * 0.3f }
        val t = txt(ctx, "HO", size * 0.34f, true).apply { gravity = Gravity.CENTER }
        val w = t.paint.measureText("HO")
        t.paint.shader = LinearGradient(0f, 0f, w, t.textSize,
            intArrayOf(hex("#FFB066"), hex("#FFFFFF"), hex("#8FB0FF")), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        g.addView(t, FrameLayout.LayoutParams(MATCH, MATCH))
        return g
    }

    private fun screenColumn(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun header(title: String, back: (() -> Unit)?, extra: View? = null): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (back != null) {
            val b = iconView(ctx, R.drawable.ic_back).apply { setPadding(6.dp, 6.dp, 6.dp, 6.dp); setOnClickListener { back() }; jelly() }
            r.addView(b, lp(40.dp, 40.dp))
        }
        r.addView(txt(ctx, title, 20f, true), lp(0, WRAP, 1f).apply { marginStart = 6.dp })
        extra?.let { r.addView(it) }
        return r
    }

    private fun buildSplash(): View {
        val c = screenColumn().apply { gravity = Gravity.CENTER }
        c.addView(logo(120), lp(120.dp, 120.dp))
        c.addView(txt(ctx, "HO Browser", 30f, true), lp(WRAP, WRAP).apply { topMargin = 18.dp })
        c.addView(txt(ctx, "Surf · Explore · Faster", 13f), lp(WRAP, WRAP).apply { topMargin = 4.dp })
        val pb = ProgressBar(this).apply { indeterminateTintList = ColorStateList.valueOf(Color.WHITE) }
        c.addView(pb, lp(30.dp, 30.dp).apply { topMargin = 60.dp })
        c.addView(txt(ctx, "Loading your world…", 12f), lp(WRAP, WRAP).apply { topMargin = 10.dp })
        return c
    }

    private fun buildHome(): View {
        val col = screenColumn()
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val inner = screenColumn().apply { gravity = Gravity.CENTER_HORIZONTAL }
        inner.addView(logo(72), lp(72.dp, 72.dp).apply { topMargin = 10.dp })
        inner.addView(txt(ctx, "HO Browser", 26f, true), lp(WRAP, WRAP).apply { topMargin = 10.dp })
        inner.addView(txt(ctx, "Fast · Smart · Secure", 12f), lp(WRAP, WRAP))

        val search = GlassView(this)
        val sr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(16.dp, 0, 12.dp, 0) }
        sr.addView(txt(ctx, "G", 16f, true, hex("#9CC0FF")))
        homeInput = editor("Search or enter address")
        sr.addView(homeInput, lp(0, MATCH, 1f))
        sr.addView(iconView(ctx, R.drawable.ic_mic), lp(22.dp, 22.dp).apply { marginEnd = 10.dp })
        sr.addView(iconView(ctx, R.drawable.ic_scan), lp(22.dp, 22.dp))
        search.addView(sr, FrameLayout.LayoutParams(MATCH, MATCH))
        inner.addView(search, lp(MATCH, 50.dp).apply { topMargin = 36.dp })

        val sites = listOf(
            Triple("Google", "https://www.google.com", "#8AB4FF"), Triple("YouTube", "https://www.youtube.com", "#FF5A52"),
            Triple("Facebook", "https://www.facebook.com", "#6AA3FF"), Triple("Instagram", "https://www.instagram.com", "#FF7AB0"),
            Triple("X", "https://x.com", "#FFFFFF"), Triple("Wikipedia", "https://www.wikipedia.org", "#F1F1F1"),
            Triple("Amazon", "https://www.amazon.com", "#FFB340"), Triple("GitHub", "https://github.com", "#D0D7DE")
        )
        sites.chunked(4).forEach { rowSites ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowSites.forEach { (name, url, color) ->
                val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
                val tile = GlassView(this).apply { fixedRadius = 16f }
                tile.addView(txt(ctx, name.first().toString(), 22f, true, hex(color)).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH, MATCH))
                cell.addView(tile, lp(56.dp, 56.dp))
                cell.addView(txt(ctx, name, 11f).apply { gravity = Gravity.CENTER }, lp(WRAP, WRAP).apply { topMargin = 5.dp })
                cell.setOnClickListener { load(url) }
                cell.jelly()
                row.addView(cell, lp(0, WRAP, 1f))
            }
            inner.addView(row, lp(MATCH, WRAP).apply { topMargin = 22.dp })
        }

        val disc = GlassView(this)
        val dc = screenColumn().apply { setPadding(14.dp, 12.dp, 14.dp, 12.dp) }
        val dh = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dh.addView(iconView(ctx, R.drawable.ic_flame).apply { imageTintList = ColorStateList.valueOf(hex("#FFA447")) }, lp(20.dp, 20.dp))
        dh.addView(txt(ctx, "Discover", 14f, true), lp(0, WRAP, 1f).apply { marginStart = 6.dp })
        dh.addView(iconView(ctx, R.drawable.ic_chevron), lp(20.dp, 20.dp))
        dc.addView(dh)
        dc.addView(txt(ctx, "Top stories and trending news", 13f).apply { setPadding(0, 8.dp, 0, 0) })
        disc.addView(dc)
        disc.setOnClickListener { load("https://news.google.com") }
        disc.jelly()
        inner.addView(disc, lp(MATCH, WRAP).apply { topMargin = 30.dp; bottomMargin = 10.dp })

        scroll.addView(inner)
        col.addView(scroll, lp(MATCH, 0, 1f))
        return col
    }

    private fun buildTabs(): View {
        val col = screenColumn()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val pill = GlassView(this).apply { setPadding(20.dp, 9.dp, 20.dp, 9.dp) }
        tabsTitle = txt(ctx, "Tabs", 14f, true)
        pill.addView(tabsTitle)
        val plus = GlassView(this).apply { circle = true }
        plus.addView(iconView(ctx, R.drawable.ic_add).apply { setPadding(9.dp, 9.dp, 9.dp, 9.dp) }, FrameLayout.LayoutParams(MATCH, MATCH))
        plus.setOnClickListener { newTab() }
        plus.jelly()
        head.addView(pill, lp(WRAP, WRAP))
        head.addView(plus, lp(40.dp, 40.dp).apply { marginStart = 10.dp })
        col.addView(head, lp(MATCH, WRAP))
        tabsList = screenColumn()
        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(tabsList) }
        col.addView(sv, lp(MATCH, 0, 1f).apply { topMargin = 14.dp })
        val closeAll = GlassView(this)
        closeAll.addView(txt(ctx, "Close all tabs", 14f).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH, MATCH))
        closeAll.setOnClickListener {
            while (tabs.size > 1) closeTab(tabs.lastIndex)
            closeTab(0); showPage()
        }
        closeAll.jelly()
        col.addView(closeAll, lp(MATCH, 46.dp).apply { topMargin = 8.dp; marginStart = 24.dp; marginEnd = 24.dp })
        return col
    }

    private fun buildDownloads(): View {
        val col = screenColumn()
        col.addView(header("Downloads", null))
        dlFilters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(dlFilters, lp(MATCH, WRAP).apply { topMargin = 12.dp; bottomMargin = 12.dp })
        dlList = screenColumn()
        col.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(dlList) }, lp(MATCH, 0, 1f))
        val foot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun fb(res: Int, label: String, click: () -> Unit): View {
            val g = GlassView(this)
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            r.addView(iconView(ctx, res), lp(18.dp, 18.dp))
            r.addView(txt(ctx, label, 13f), lp(WRAP, WRAP).apply { marginStart = 6.dp })
            g.addView(r, FrameLayout.LayoutParams(MATCH, MATCH))
            g.setOnClickListener { click() }; g.jelly()
            return g
        }
        foot.addView(fb(R.drawable.ic_trash, "Clear failed") {
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            queryDownloads().filter { it.status == DownloadManager.STATUS_FAILED }.forEach { dm.remove(it.id) }
            refreshDownloads()
        }, lp(0, 44.dp, 1f).apply { marginEnd = 5.dp })
        foot.addView(fb(R.drawable.ic_folder, "Open folder") {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, lp(0, 44.dp, 1f).apply { marginStart = 5.dp })
        col.addView(foot, lp(MATCH, WRAP).apply { topMargin = 8.dp })
        return col
    }

    private fun buildSettings(): View {
        val col = screenColumn()
        col.addView(header("Settings", { showPage() }))
        val prof = GlassView(this)
        val pr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(14.dp, 12.dp, 14.dp, 12.dp) }
        pr.addView(circleLetter(ctx, "HO", Glass.acc, 44), lp(44.dp, 44.dp))
        val pt = screenColumn().apply { setPadding(12.dp, 0, 0, 0) }
        pt.addView(txt(ctx, "HO Browser", 15f, true))
        pt.addView(txt(ctx, "Liquid glass edition", 11f, false, hex("#FFD48A")))
        pr.addView(pt, lp(0, WRAP, 1f))
        prof.addView(pr)
        col.addView(prof, lp(MATCH, WRAP).apply { topMargin = 14.dp; bottomMargin = 14.dp })
        val card = GlassView(this)
        setRows = screenColumn()
        card.addView(setRows)
        col.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(card) }, lp(MATCH, 0, 1f))
        return col
    }

    private fun buildCustomize(): View {
        val col = screenColumn()
        val reset = chip(ctx, "Reset", false) { Glass.reset(); Glass.save(); styleChanged(true); refreshCustomize() }
        col.addView(header("Customize", { show("set") }, reset))
        val card = GlassView(this)
        cusPanel = screenColumn().apply { setPadding(16.dp, 14.dp, 16.dp, 14.dp) }
        card.addView(cusPanel)
        col.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(card) }, lp(MATCH, 0, 1f).apply { topMargin = 12.dp })
        return col
    }

    private fun editor(hint: String): EditText = EditText(this).apply {
        setBackgroundColor(Color.TRANSPARENT)
        this.hint = hint
        setHintTextColor(hex("#B8C4E8")); setTextColor(Color.WHITE); textSize = 15f
        setSingleLine(); imeOptions = EditorInfo.IME_ACTION_GO
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setPadding(10.dp, 0, 6.dp, 0)
        setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                load(v.text.toString())
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
                if (v === homeInput) v.text = null
                true
            } else false
        }
    }

    private fun buildBar() {
        bar = GlassView(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(16.dp, 0, 4.dp, 0) }
        lockIcon = iconView(ctx, R.drawable.ic_search).apply { alpha = .8f }
        row.addView(lockIcon, lp(18.dp, 18.dp))
        urlInput = editor("Search or enter address")
        urlInput.setSelectAllOnFocus(true)
        urlInput.setOnFocusChangeListener { _, has ->
            if (has) { urlInput.setText(if (cur >= 0 && !tab.showHome) tab.url else ""); urlInput.selectAll() } else updateUi()
        }
        row.addView(urlInput, lp(0, MATCH, 1f))
        btnReload = iconView(ctx, R.drawable.ic_refresh).apply { setPadding(12.dp, 12.dp, 12.dp, 12.dp) }
        btnReload.setOnClickListener { if (tab.loading) tab.session.stop() else if (!tab.showHome) tab.session.reload() }
        btnReload.jelly()
        row.addView(btnReload, lp(44.dp, 44.dp))
        bar.addView(row, FrameLayout.LayoutParams(MATCH, MATCH))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        bar.addView(progress, FrameLayout.LayoutParams(MATCH, 3.dp, Gravity.BOTTOM).apply { setMargins(18.dp, 0, 18.dp, 3.dp) })
        root.addView(bar, FrameLayout.LayoutParams(MATCH, 48.dp))
        bar.visibility = View.GONE
    }

    private fun buildNav() {
        nav = GlassView(this)
        indicator = View(this)
        nav.addView(indicator, FrameLayout.LayoutParams(54.dp, 46.dp, Gravity.TOP or Gravity.START))
        navRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun nb(res: Int, desc: String, click: () -> Unit): FrameLayout {
            val f = FrameLayout(this)
            f.addView(iconView(ctx, res), FrameLayout.LayoutParams(24.dp, 24.dp, Gravity.CENTER))
            f.contentDescription = desc
            f.setOnClickListener { click() }
            f.jelly()
            return f
        }
        btnBack = nb(R.drawable.ic_back, "Back") { goBack(false) }
        btnFwd = nb(R.drawable.ic_forward, "Forward") { if (tab.canForward) tab.session.goForward() }
        btnHome = nb(R.drawable.ic_home, "Home") { tab.showHome = true; showPage() }
        val tb = nb(R.drawable.ic_tabs, "Tabs") { show("tabs") }
        tabCount = txt(ctx, "1", 10f, true).apply { gravity = Gravity.CENTER }
        tb.addView(tabCount, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        btnTabs = tb
        btnMenu = nb(R.drawable.ic_menu2, "Menu") { showMenu() }
        listOf(btnBack, btnFwd, btnHome, btnTabs, btnMenu).forEach { navRow.addView(it, lp(0, MATCH, 1f)) }
        nav.addView(navRow, FrameLayout.LayoutParams(MATCH, 54.dp, Gravity.TOP))
        root.addView(nav, FrameLayout.LayoutParams(MATCH, 54.dp, Gravity.BOTTOM))
        nav.visibility = View.GONE
        restyleIndicator()
    }

    private fun restyleIndicator() {
        indicator.background = GradientDrawable().apply {
            cornerRadius = (Glass.rad + 2f).dp
            setColor((Glass.acc and 0x00FFFFFF) or (0xB4 shl 24))
            setStroke(1.dp, white(.55f))
        }
    }

    // ------------------------------------------------------------ layout / style

    private fun navZone(): Int = if (Glass.dockFloat) 12.dp + 54.dp + botInset else 62.dp + botInset

    private fun layoutUi() {
        if (!::nav.isInitialized) return
        val navZ = navZone()
        val rowH = if (Glass.dockFloat) 54.dp else 62.dp
        val nlp = nav.layoutParams as FrameLayout.LayoutParams
        if (Glass.dockFloat) { nlp.height = 54.dp; nlp.setMargins(12.dp, 0, 12.dp, 12.dp + botInset); nav.topOnly = false }
        else { nlp.height = 62.dp + botInset; nlp.setMargins(0, 0, 0, 0); nav.topOnly = true }
        nlp.gravity = Gravity.BOTTOM
        nav.layoutParams = nlp
        val rlp = navRow.layoutParams as FrameLayout.LayoutParams
        rlp.height = rowH; navRow.layoutParams = rlp
        val ilp = indicator.layoutParams as FrameLayout.LayoutParams
        ilp.topMargin = (rowH - 46.dp) / 2; indicator.layoutParams = ilp

        val blp = bar.layoutParams as FrameLayout.LayoutParams
        blp.height = 48.dp
        if (Glass.abarTop) { blp.gravity = Gravity.TOP; blp.setMargins(14.dp, topInset + 8.dp, 14.dp, 0) }
        else { blp.gravity = Gravity.BOTTOM; blp.setMargins(14.dp, 0, 14.dp, navZ + 8.dp) }
        bar.layoutParams = blp

        val glp = geckoView.layoutParams as FrameLayout.LayoutParams
        if (fullscreen) glp.setMargins(0, 0, 0, 0)
        else glp.setMargins(0,
            if (Glass.abarTop) topInset + 8.dp + 48.dp + 8.dp else topInset + 8.dp, 0,
            navZ + 8.dp + (if (Glass.abarTop) 0 else 48.dp + 8.dp))
        geckoView.layoutParams = glp

        screens.forEach { (k, v) -> if (k != "splash") v.setPadding(14.dp, topInset + 12.dp, 14.dp, navZ + 8.dp) }
        indInit = false
        positionIndicator()
    }

    private fun restyle(v: View) {
        if (v === geckoView) return
        v.invalidate()
        if (v is TextView) v.setShadowLayer(if (Glass.auto) 8f else 0f, 0f, 2f, 0x99000A28.toInt())
        if (v is ViewGroup) for (i in 0 until v.childCount) restyle(v.getChildAt(i))
    }

    private fun styleChanged(relayout: Boolean = false) {
        Glass.save()
        wallpaper.rebuild()
        restyleIndicator()
        progress.progressTintList = ColorStateList.valueOf(Glass.acc)
        progress.progressBackgroundTintList = ColorStateList.valueOf(0x30FFFFFF)
        restyle(root)
        if (relayout) layoutUi()
    }

    private fun positionIndicator() {
        val b = when (screen) { "tabs" -> btnTabs; "set", "dl", "cus" -> btnMenu; else -> btnHome }
        nav.post {
            if (b.width == 0) return@post
            val target = b.left + (b.width - indicator.width) / 2f
            if (!indInit) { indicator.translationX = target; indInit = true; return@post }
            if (Math.abs(indicator.translationX - target) < 1f) return@post
            SpringAnimation(indicator, DynamicAnimation.TRANSLATION_X, target).apply {
                spring.dampingRatio = 0.5f; spring.stiffness = 180f; start()
            }
            indicator.animate().cancel()
            indicator.animate().scaleX(1.6f).setDuration(160).withEndAction {
                indicator.animate().scaleX(1f).setDuration(280).setInterpolator(OvershootInterpolator(3f)).start()
            }.start()
        }
    }

    // ------------------------------------------------------------ screens

    private fun show(name: String) {
        screen = name
        screens.forEach { (k, v) ->
            if (k == name) { if (v.visibility != View.VISIBLE) { v.visibility = View.VISIBLE; pop(v) } }
            else v.visibility = View.GONE
        }
        geckoView.visibility = if (name == "browse") View.VISIBLE else View.INVISIBLE
        bar.visibility = if (name == "browse" && !fullscreen) View.VISIBLE else View.GONE
        nav.visibility = if (name == "splash" || fullscreen) View.GONE else View.VISIBLE
        when (name) {
            "tabs" -> refreshTabs(); "dl" -> refreshDownloads(); "set" -> refreshSettings(); "cus" -> refreshCustomize()
        }
        positionIndicator()
        updateChrome()
    }

    private fun pop(v: View) {
        v.alpha = 0f; v.scaleX = .96f; v.scaleY = .96f
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(1.4f)).start()
    }

    private fun showPage() = show(if (cur >= 0 && !tab.showHome) "browse" else "home")

    private fun goBack(allowClose: Boolean = true) {
        when {
            screen == "cus" -> show("set")
            screen == "tabs" || screen == "dl" || screen == "set" -> showPage()
            urlInput.hasFocus() -> urlInput.clearFocus()
            tab.showHome && tab.url.isNotEmpty() -> { tab.showHome = false; updateUi() }
            tab.canBack -> tab.session.goBack()
            allowClose && tabs.size > 1 -> closeTab(cur)
            allowClose -> finish()
        }
    }

    private fun refreshTabs() {
        tabsTitle.text = "Tabs (${tabs.size})"
        tabsList.removeAllViews()
        tabs.forEachIndexed { i, t ->
            val label = t.title.ifBlank { if (t.url.isEmpty()) "New tab" else pretty(t.url) }
            val g = GlassView(this).apply { accentRim = i == cur }
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(14.dp, 0, 6.dp, 0) }
            r.addView(circleLetter(ctx, label.first().uppercase(), palette[i % palette.size], 38), lp(38.dp, 38.dp))
            val tx = screenColumn().apply { setPadding(12.dp, 0, 8.dp, 0) }
            tx.addView(txt(ctx, label, 14f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            tx.addView(txt(ctx, if (t.url.isEmpty()) "Start page" else pretty(t.url), 11f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            r.addView(tx, lp(0, WRAP, 1f))
            val x = iconView(ctx, R.drawable.ic_close).apply { setPadding(11.dp, 11.dp, 11.dp, 11.dp); setOnClickListener { closeTab(i) } }
            r.addView(x, lp(44.dp, 44.dp))
            g.addView(r, FrameLayout.LayoutParams(MATCH, MATCH))
            g.setOnClickListener { select(i); showPage() }
            g.jelly()
            tabsList.addView(g, lp(MATCH, if (i == cur) 78.dp else 62.dp).apply { bottomMargin = 10.dp })
        }
    }

    private class Dl(val id: Long, val name: String, val status: Int, val total: Long, val sofar: Long)

    private fun queryDownloads(): List<Dl> {
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val out = mutableListOf<Dl>()
        dm.query(DownloadManager.Query()).use { c ->
            while (c.moveToNext()) {
                out.add(Dl(
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)),
                    c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: "file",
                    c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                ))
            }
        }
        return out.reversed()
    }

    private fun refreshDownloads() {
        if (screen != "dl") return
        dlFilters.removeAllViews()
        listOf("All", "Downloading", "Completed").forEachIndexed { i, s ->
            dlFilters.addView(chip(ctx, s, i == dlFilter) { dlFilter = i; refreshDownloads() }, lp(WRAP, WRAP).apply { marginEnd = 8.dp })
        }
        dlList.removeAllViews()
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val list = queryDownloads().filter {
            val done = it.status == DownloadManager.STATUS_SUCCESSFUL
            when (dlFilter) { 1 -> !done && it.status != DownloadManager.STATUS_FAILED; 2 -> done; else -> true }
        }
        if (list.isEmpty()) dlList.addView(txt(ctx, "Nothing here yet", 13f).apply { gravity = Gravity.CENTER; setPadding(0, 40.dp, 0, 0) }, lp(MATCH, WRAP))
        list.forEach { d ->
            val done = d.status == DownloadManager.STATUS_SUCCESSFUL
            val failed = d.status == DownloadManager.STATUS_FAILED
            val g = GlassView(this)
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(12.dp, 10.dp, 8.dp, 10.dp) }
            r.addView(circleLetter(ctx, d.name.first().uppercase(), if (failed) hex("#EF4444") else Glass.acc, 42), lp(42.dp, 42.dp))
            val tx = screenColumn().apply { setPadding(12.dp, 0, 8.dp, 0) }
            tx.addView(txt(ctx, d.name, 13f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            val pct = if (d.total > 0) (d.sofar * 100 / d.total).toInt() else 0
            val status = when { done -> "Completed"; failed -> "Failed"; else -> "$pct%" }
            tx.addView(txt(ctx, (if (d.total > 0) Formatter.formatShortFileSize(this, d.total) + " · " else "") + status, 11f))
            if (!done && !failed) {
                val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100; progress = pct
                    progressTintList = ColorStateList.valueOf(Glass.acc); progressBackgroundTintList = ColorStateList.valueOf(0x40FFFFFF)
                }
                tx.addView(pb, lp(MATCH, 3.dp).apply { topMargin = 6.dp })
            }
            r.addView(tx, lp(0, WRAP, 1f))
            val act = iconView(ctx, if (done) R.drawable.ic_check else R.drawable.ic_close).apply {
                setPadding(10.dp, 10.dp, 10.dp, 10.dp)
                setOnClickListener {
                    if (done) startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    else { dm.remove(d.id); refreshDownloads() }
                }
            }
            r.addView(act, lp(42.dp, 42.dp))
            g.addView(r)
            g.jelly()
            dlList.addView(g, lp(MATCH, WRAP).apply { bottomMargin = 10.dp })
        }
        handler.removeCallbacks(dlTick)
        handler.postDelayed(dlTick, 900)
    }

    private val dlTick = Runnable { if (screen == "dl") refreshDownloads() }

    private fun settingRow(res: Int, color: Int, label: String, right: View?, onClick: () -> Unit): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(14.dp, 13.dp, 14.dp, 13.dp) }
        val box = FrameLayout(this).apply { background = GradientDrawable().apply { cornerRadius = 8.dp.toFloat(); setColor(color) } }
        box.addView(iconView(ctx, res), FrameLayout.LayoutParams(16.dp, 16.dp, Gravity.CENTER))
        r.addView(box, lp(28.dp, 28.dp))
        r.addView(txt(ctx, label, 14f), lp(0, WRAP, 1f).apply { marginStart = 12.dp })
        right?.let { r.addView(it) }
        r.setOnClickListener { onClick() }
        r.jelly()
        return r
    }

    private fun toggle(on: Boolean): View = FrameLayout(this).apply {
        background = GradientDrawable().apply { cornerRadius = 12.dp.toFloat(); setColor(if (on) Glass.acc else 0x48FFFFFF) }
        addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) } },
            FrameLayout.LayoutParams(20.dp, 20.dp, Gravity.CENTER_VERTICAL or (if (on) Gravity.END else Gravity.START)).apply { setMargins(2.dp, 0, 2.dp, 0) })
        layoutParams = LinearLayout.LayoutParams(42.dp, 24.dp)
    }

    private fun valueRight(s: String): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(txt(ctx, s, 12f))
        r.addView(iconView(ctx, R.drawable.ic_chevron), lp(18.dp, 18.dp))
        return r
    }

    private fun refreshSettings() {
        setRows.removeAllViews()
        val etp = prefs.getBoolean("etp", true)
        setRows.addView(settingRow(R.drawable.ic_droplet, Glass.acc, "Liquid glass and layout", valueRight("")) { show("cus") })
        setRows.addView(settingRow(R.drawable.ic_search, hex("#FF8A1F"), "Search engine", valueRight(prefs.getString("engine", "Google") ?: "Google")) { pickEngine() })
        setRows.addView(settingRow(R.drawable.ic_gauge, hex("#22C55E"), "Refresh rate", valueRight(hzNames[Glass.hz])) {
            Glass.hz = (Glass.hz + 1) % 3; Glass.save(); applyHz(); refreshSettings()
        })
        setRows.addView(settingRow(R.drawable.ic_shield, hex("#EF4444"), "Ad block and tracking protection", toggle(etp)) {
            prefs.edit().putBoolean("etp", !etp).apply(); applyTracking(!etp); refreshSettings()
        })
        setRows.addView(settingRow(R.drawable.ic_puzzle, hex("#6D5CF6"), "Extensions", valueRight("Soon")) { toast("Extensions are coming soon") })
        setRows.addView(settingRow(R.drawable.ic_download, hex("#FF8A1F"), "Downloads", valueRight("")) { show("dl") })
    }

    private fun refreshCustomize() {
        cusPanel.removeAllViews()
        fun slider(label: String, min: Float, max: Float, get: () -> Float, set: (Float) -> Unit, fmt: (Float) -> String) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val vt = txt(ctx, fmt(get()), 11f).apply { gravity = Gravity.END }
            val s = GlassSlider(this, min, max, get()) { v -> set(v); vt.text = fmt(v); styleChanged() }
            row.addView(txt(ctx, label, 12f), lp(96.dp, WRAP))
            row.addView(s, lp(0, 34.dp, 1f))
            row.addView(vt, lp(46.dp, WRAP))
            cusPanel.addView(row, lp(MATCH, WRAP).apply { bottomMargin = 4.dp })
        }
        slider("Glass blur", 0f, 40f, { Glass.blur }, { Glass.blur = it }) { "${it.toInt()} px" }
        slider("Glass opacity", .02f, .5f, { Glass.tint }, { Glass.tint = it }) { "${(it * 200).toInt()}%" }
        slider("Shine and edge", 0f, 1f, { Glass.hi }, { Glass.hi = it }) { "${(it * 100).toInt()}%" }
        slider("Roundness", 6f, 34f, { Glass.rad }, { Glass.rad = it }) { "${it.toInt()} px" }
        slider("Jelly", 0f, 1.6f, { Glass.jelly }, { Glass.jelly = it }) { String.format("%.1fx", it) }

        fun group(title: String, opts: List<String>, sel: Int, pick: (Int) -> Unit) {
            cusPanel.addView(txt(ctx, title, 12f), lp(WRAP, WRAP).apply { topMargin = 14.dp; bottomMargin = 6.dp })
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            opts.forEachIndexed { i, s ->
                r.addView(chip(ctx, s, i == sel) { pick(i); styleChanged(true); refreshCustomize() }, lp(WRAP, WRAP).apply { marginEnd = 8.dp })
            }
            cusPanel.addView(r)
        }
        group("Address bar", listOf("Top", "Bottom"), if (Glass.abarTop) 0 else 1) { Glass.abarTop = it == 0 }
        group("Navigation bar", listOf("Floating", "Docked"), if (Glass.dockFloat) 0 else 1) { Glass.dockFloat = it == 0 }
        group("Wallpaper", listOf("Mountain", "Aurora", "Night"), Glass.wall) { Glass.wall = it }
        group("Keep text readable", listOf("On", "Off"), if (Glass.auto) 0 else 1) { Glass.auto = it == 0 }

        cusPanel.addView(txt(ctx, "Accent color", 12f), lp(WRAP, WRAP).apply { topMargin = 14.dp; bottomMargin = 6.dp })
        val sw = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        palette.forEach { c ->
            val v = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(c)
                    setStroke(if (c == Glass.acc) 3.dp else 1.dp, if (c == Glass.acc) Color.WHITE else 0x66FFFFFF)
                }
                setOnClickListener { Glass.acc = c; styleChanged(); refreshCustomize() }
                jelly()
            }
            sw.addView(v, lp(32.dp, 32.dp).apply { marginEnd = 10.dp })
        }
        cusPanel.addView(sw)
        restyle(cusPanel)
    }

    private fun showMenu() {
        val d = BottomSheetDialog(this)
        val col = screenColumn().apply {
            setPadding(0, 12.dp, 0, 24.dp)
            background = GradientDrawable().apply {
                setColor(hex("#151C3A")); val r = 24.dp.toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        fun item(label: String, click: () -> Unit) {
            col.addView(TextView(this).apply {
                text = label; setTextColor(Color.WHITE); textSize = 16f; setPadding(24.dp, 16.dp, 24.dp, 16.dp)
                setOnClickListener { d.dismiss(); click() }
            })
        }
        item("New tab") { newTab() }
        item("Desktop site: ${if (tab.desktop) "On" else "Off"}") {
            val t = tab
            t.desktop = !t.desktop
            t.session.settings.userAgentMode = if (t.desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            t.session.settings.viewportMode = if (t.desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
            t.session.reload()
        }
        item("Share page") {
            if (!tab.showHome && tab.url.isNotEmpty()) startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, tab.url) }, null))
        }
        item("Downloads") { show("dl") }
        item("Customize glass") { show("cus") }
        item("Settings") { show("set") }
        d.setContentView(col)
        d.show()
        d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun pickEngine() {
        val names = engines.keys.toTypedArray()
        val checked = names.indexOf(prefs.getString("engine", "Google")).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Search engine")
            .setSingleChoiceItems(names, checked) { dlg, which ->
                prefs.edit().putString("engine", names[which]).apply(); dlg.dismiss(); refreshSettings()
            }.show()
    }

    // ------------------------------------------------------------ tabs & browsing

    private fun newTab() {
        val t = Tab(GeckoSession())
        attach(t)
        t.session.open(runtime)
        tabs.add(t)
        select(tabs.lastIndex)
        if (screen == "tabs") showPage()
    }

    private fun select(i: Int) {
        cur = i
        geckoView.setSession(tabs[i].session)
        updateUi()
    }

    private fun closeTab(i: Int) {
        val t = tabs[i]
        val wasCurrent = i == cur
        if (wasCurrent) geckoView.releaseSession()
        t.session.close()
        tabs.removeAt(i)
        if (tabs.isEmpty()) { cur = -1; newTab() }
        else {
            if (i < cur) cur-- else if (wasCurrent) cur = minOf(cur, tabs.lastIndex)
            if (wasCurrent) select(cur) else updateUi()
        }
        if (screen == "tabs") refreshTabs()
    }

    private fun isCurrent(t: Tab) = cur >= 0 && tabs[cur] === t

    private fun attach(t: Tab) {
        val s = t.session
        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) { t.loading = true; if (isCurrent(t)) updateUi() }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                t.loading = false
                if (isCurrent(t)) { progress.visibility = View.GONE; updateUi() }
            }
            override fun onProgressChange(session: GeckoSession, p: Int) {
                if (isCurrent(t)) { progress.progress = p; progress.visibility = if (p in 1..99) View.VISIBLE else View.GONE }
            }
        }
        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>) {
                if (url != null && url != "about:blank") { t.url = url; t.showHome = false }
                if (isCurrent(t)) updateUi()
            }
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { t.canBack = canGoBack; if (isCurrent(t)) updateUi() }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) { t.canForward = canGoForward; if (isCurrent(t)) updateUi() }
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                t.session.loadUri(uri); return null
            }
        }
        s.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) { t.title = title ?: "" }
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) { download(response) }
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) { setFullscreen(fullScreen) }
        }
    }

    private fun updateUi() {
        if (cur < 0) return
        if (screen == "home" || screen == "browse") {
            val target = if (tab.showHome) "home" else "browse"
            if (screen != target) { show(target); return }
        }
        updateChrome()
    }

    private fun updateChrome() {
        if (cur < 0 || !::urlInput.isInitialized) return
        val t = tab
        if (!urlInput.hasFocus()) urlInput.setText(if (t.showHome || t.url.isEmpty()) "" else pretty(t.url))
        val canGoBack = (t.showHome && t.url.isNotEmpty()) || t.canBack
        btnBack.alpha = if (canGoBack) 1f else 0.4f
        btnFwd.alpha = if (t.canForward) 1f else 0.4f
        btnReload.setImageResource(if (t.loading) R.drawable.ic_close else R.drawable.ic_refresh)
        lockIcon.setImageResource(if (!t.showHome && t.url.startsWith("https://")) R.drawable.ic_lock else R.drawable.ic_search)
        tabCount.text = tabs.size.toString()
        progress.progressTintList = ColorStateList.valueOf(Glass.acc)
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else c.show(WindowInsetsCompat.Type.systemBars())
        layoutUi()
        show(screen)
    }

    private fun load(input: String) {
        val u = toUrl(input) ?: return
        val t = tab
        t.url = u; t.title = ""; t.showHome = false
        t.session.loadUri(u)
        showPage()
    }

    private fun toUrl(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*").matches(s) || s.startsWith("about:")) return s
        if (!s.contains(' ') && (s.contains('.') || s.startsWith("localhost"))) return "https://$s"
        val template = engines[prefs.getString("engine", "Google")] ?: engines.getValue("Google")
        return template.replace("%s", URLEncoder.encode(s, "UTF-8"))
    }

    private fun pretty(url: String) = url.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

    private fun applyTracking(on: Boolean) {
        runtime.settings.contentBlocking.setAntiTracking(
            if (on) ContentBlocking.AntiTracking.DEFAULT else ContentBlocking.AntiTracking.NONE)
    }

    @Suppress("DEPRECATION")
    private fun applyHz() {
        val lp = window.attributes
        try {
            val disp = windowManager.defaultDisplay
            val cm = disp.mode
            val same = disp.supportedModes.filter { it.physicalWidth == cm.physicalWidth && it.physicalHeight == cm.physicalHeight }
            val best = when (Glass.hz) {
                0 -> null
                1 -> same.minByOrNull { Math.abs(it.refreshRate - 60f) }
                else -> same.maxByOrNull { it.refreshRate }
            }
            if (best != null) { lp.preferredDisplayModeId = best.modeId; lp.preferredRefreshRate = best.refreshRate }
            else { lp.preferredDisplayModeId = 0; lp.preferredRefreshRate = 0f }
        } catch (e: Exception) { /* keep system default */ }
        window.attributes = lp
    }

    private fun download(response: WebResponse) {
        try {
            val uri = response.uri
            val mime = response.headers["content-type"]
            val name = URLUtil.guessFileName(uri, response.headers["content-disposition"], mime)
            val req = DownloadManager.Request(Uri.parse(uri))
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            if (mime != null) req.setMimeType(mime)
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast("Downloading $name")
        } catch (e: Exception) { toast("Download failed") }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

package com.example.ucbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation

const val MATCH = -1
const val WRAP = -2

val Int.dp: Int get() = (this * Resources.getSystem().displayMetrics.density).toInt()
val Float.dp: Float get() = this * Resources.getSystem().displayMetrics.density

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

fun txt(c: Context, s: String, sp: Float = 14f, bold: Boolean = false, color: Int = Color.WHITE) = TextView(c).apply {
    text = s; textSize = sp; setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
    if (Glass.auto) setShadowLayer(8f, 0f, 2f, 0x99000A28.toInt())
}

fun chip(c: Context, s: String, on: Boolean, click: () -> Unit) = txt(c, s, 12f).apply {
    setPadding(12.dp, 7.dp, 12.dp, 7.dp)
    background = GradientDrawable().apply {
        cornerRadius = 16.dp.toFloat(); setColor(if (on) Glass.acc else 0x24FFFFFF); setStroke(1.dp, 0x38FFFFFF)
    }
    setOnClickListener { click() }
    jelly()
}

fun circleLetter(c: Context, letter: String, color: Int, size: Int) = TextView(c).apply {
    text = letter; setTextColor(Color.WHITE); textSize = size * 0.38f; typeface = Typeface.DEFAULT_BOLD
    gravity = android.view.Gravity.CENTER
    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
}

fun iconView(c: Context, res: Int) = ImageView(c).apply { setImageResource(res) }

/** Jelly: squash under the finger, then wobble back on release. */
@SuppressLint("ClickableViewAccessibility")
fun View.jelly() {
    setOnTouchListener { v, e ->
        val j = Glass.jelly
        if (j > 0f) when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                @Suppress("UNCHECKED_CAST")
                (v.getTag(R.id.jelly_tag) as? List<SpringAnimation>)?.forEach { if (it.isRunning) it.cancel() }
                v.pivotX = e.x; v.pivotY = e.y
                v.animate().cancel()
                v.animate().scaleX(1f - 0.16f * j).scaleY(1f - 0.10f * j).setDuration(120).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().cancel()
                val sx = SpringAnimation(v, DynamicAnimation.SCALE_X, 1f).apply { spring.stiffness = 380f; spring.dampingRatio = 0.24f }
                val sy = SpringAnimation(v, DynamicAnimation.SCALE_Y, 1f).apply { spring.stiffness = 300f; spring.dampingRatio = 0.22f }
                v.setTag(R.id.jelly_tag, listOf(sx, sy))
                sx.start(); sy.start()
            }
        }
        false
    }
}

package com.example.ucbrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View

/** Draws the wallpaper and keeps a blurred copy that glass panels sample from. */
class WallpaperView(ctx: Context) : View(ctx) {
    var blurred: Bitmap? = null
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh); rebuild()
    }

    override fun onDraw(c: Canvas) = render(c, width.toFloat(), height.toFloat())

    fun rebuild() {
        if (width < 8 || height < 8) return
        val sw = (width / 6).coerceAtLeast(8)
        val sh = (height / 6).coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.scale(sw / width.toFloat(), sh / height.toFloat())
        render(cv, width.toFloat(), height.toFloat())
        blur(bmp, (Glass.blur.dp / 6f / 1.8f).toInt())
        blurred = bmp
        invalidate()
    }

    private fun blob(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, a: Float) {
        val base = color and 0x00FFFFFF
        paint.shader = RadialGradient(cx, cy, r, ((a * 255).toInt() shl 24) or base, base, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, paint); paint.shader = null
    }

    private fun poly(c: Canvas, color: String, s: Float, oy: Float, vararg pts: Float) {
        path.reset(); path.moveTo(pts[0] * s, oy + pts[1] * s)
        var i = 2
        while (i < pts.size) { path.lineTo(pts[i] * s, oy + pts[i + 1] * s); i += 2 }
        path.close(); paint.color = hex(color); c.drawPath(path, paint)
    }

    private fun render(c: Canvas, w: Float, h: Float) {
        paint.shader = null
        when (Glass.wall) {
            0 -> {
                paint.shader = LinearGradient(0f, 0f, 0f, h,
                    intArrayOf(hex("#2A56C6"), hex("#6D8FE0"), hex("#FFB27A"), hex("#FF8A3D"), hex("#1B2F6E"), hex("#0B1740")),
                    floatArrayOf(0f, .28f, .46f, .52f, .53f, 1f), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, paint); paint.shader = null
                val s = w / 330f; val oy = h * .30f
                poly(c, "#26396E", s, oy, 0f,160f, 0f,110f, 50f,70f, 90f,100f, 150f,30f, 210f,95f, 260f,60f, 330f,110f, 330f,160f)
                poly(c, "#E9EEFC", s, oy, 150f,30f, 175f,62f, 150f,55f, 128f,66f)
                poly(c, "#14224F", s, oy, 0f,160f, 0f,130f, 70f,105f, 140f,135f, 220f,100f, 330f,135f, 330f,160f)
                blob(c, w * .1f, h * .8f, w * .7f, hex("#FF7A18"), .40f)
                blob(c, w * .95f, h * .1f, w * .8f, hex("#2B4BFF"), .45f)
            }
            1 -> {
                paint.color = hex("#071A2E"); c.drawRect(0f, 0f, w, h, paint)
                blob(c, w * .3f, h * .2f, w * .9f, hex("#22D3A0"), .75f)
                blob(c, w * .8f, h * .6f, w * 1.0f, hex("#8B5CF6"), .8f)
                blob(c, w * .2f, h * .85f, w * .7f, hex("#2B4BFF"), .5f)
            }
            else -> {
                paint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(hex("#050816"), hex("#10204A"), hex("#1B2F6E")),
                    floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, paint); paint.shader = null
                blob(c, w * .85f, h * .15f, w * .7f, hex("#6D5CF6"), .5f)
                blob(c, w * .15f, h * .9f, w * .8f, hex("#FF7A18"), .35f)
            }
        }
    }

    private fun blur(bmp: Bitmap, r: Int) {
        if (r < 1) return
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) { box(px, w, h, r, true); box(px, w, h, r, false) }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun box(px: IntArray, w: Int, h: Int, r: Int, horiz: Boolean) {
        val n = if (horiz) w else h
        val lines = if (horiz) h else w
        val out = IntArray(n)
        val d = 2 * r + 1
        for (l in 0 until lines) {
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val k = i.coerceIn(0, n - 1)
                val p = px[if (horiz) l * w + k else k * w + l]
                sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255
            }
            for (i in 0 until n) {
                out[i] = (0xFF shl 24) or ((sr / d) shl 16) or ((sg / d) shl 8) or (sb / d)
                val a = i + r + 1; val b = i - r
                val pa = px[if (horiz) l * w + minOf(a, n - 1) else minOf(a, n - 1) * w + l]
                val pb = px[if (horiz) l * w + maxOf(b, 0) else maxOf(b, 0) * w + l]
                sr += ((pa shr 16) and 255) - ((pb shr 16) and 255)
                sg += ((pa shr 8) and 255) - ((pb shr 8) and 255)
                sb += (pa and 255) - (pb and 255)
            }
            for (i in 0 until n) px[if (horiz) l * w + i else i * w + l] = out[i]
        }
    }
}
