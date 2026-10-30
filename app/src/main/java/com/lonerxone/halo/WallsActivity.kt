package com.lonerxone.halo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Wallpapers for the island and the notification center, from Wallhaven
 * (safe-for-work only, free, no account).
 *
 * The island is wide, so it gets desktop (landscape) wallpapers, each shown
 * in the island's own pill shape. The notification center is tall, so it
 * gets phone (portrait) wallpapers shown as tall cards. Tap one to see it in
 * place, then set it. The picture is saved on the phone, so it keeps
 * working offline.
 */
class WallsActivity : Activity() {
    private class Wall(val id: String, val thumb: String, val full: String)
    private class Topic(val name: String, val q: String, val cats: String = "111", val sort: String = "random")

    private val topics = listOf(
        Topic("Top", "", "111", "toplist"),
        Topic("Anime", "", "010", "toplist"),
        Topic("Fresh", "", "111", "date_added"),
        Topic("Nature", "nature"),
        Topic("Minimal", "minimalism"),
        Topic("Dark", "dark"),
        Topic("Space", "space"),
        Topic("Abstract", "abstract"),
        Topic("City", "city"),
        Topic("Cars", "cars"),
        Topic("Gaming", "video games"),
        Topic("Art", "digital art"),
    )

    private val io = Executors.newFixedThreadPool(4)
    private val ui = Handler(Looper.getMainLooper())
    private val thumbs = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val items = ArrayList<Wall>()
    private var topic = 0
    private var page = 1
    private var lastPage = 1
    private var loading = false
    private var gen = 0
    private val seed = (100000..999999).random().toString()

    private lateinit var key: String
    private var island = true
    private lateinit var grid: GridView
    private lateinit var status: TextView
    private lateinit var chips: LinearLayout
    private val adapter = Adapter()
    private var sheet: View? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    private fun label(t: String, sp: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun round(color: Int, r: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = r
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        key = intent.getStringExtra(EXTRA_KEY) ?: Prefs.ISLAND_PIC
        island = key == Prefs.ISLAND_PIC
        window.setDecorFitsSystemWindows(false)
        window.decorView.setBackgroundColor(Color.BLACK)
        val root = FrameLayout(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Header: back, title, one line.
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(6))
        }
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(17, 17, 19))
            }
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { bottomMargin = dp(22) })
        head.addView(label(if (island) "Island wallpapers" else "Notification wallpapers", 28f, Color.WHITE, true))
        head.addView(label(if (island) "Desktop wallpapers for the island" else "Phone wallpapers for behind your notifications", 14f, Color.rgb(142, 142, 150), false).apply {
            setPadding(0, dp(4), 0, 0)
        })
        col.addView(head)

        chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        col.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(24))
            addView(chips)
        })

        val frame = FrameLayout(this)
        grid = GridView(this).apply {
            numColumns = if (island) 2 else 3
            verticalSpacing = dp(10)
            horizontalSpacing = dp(10)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(16), dp(6), dp(16), dp(40))
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(28))
            adapter = this@WallsActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> items.getOrNull(pos)?.let { preview(it) } }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, state: Int) {}
                override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) {
                    if (!loading && total > 0 && first + visible >= total - 4 && page < lastPage) {
                        page++
                        load(reset = false)
                    }
                }
            })
        }
        frame.addView(grid)
        status = label("", 14f, Color.rgb(142, 142, 150), false).apply {
            gravity = Gravity.CENTER
            setPadding(dp(30), dp(60), dp(30), 0)
        }
        frame.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(col)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(0, b.top, 0, b.bottom)
            insets
        }
        setContentView(root)
        drawChips()
        load(reset = true)
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (sheet != null) closePreview() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun drawChips() {
        chips.removeAllViews()
        for ((i, t) in topics.withIndex()) {
            val on = i == topic
            chips.addView(label(t.name, 13.5f, if (on) Color.BLACK else Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = round(if (on) Color.WHITE else Color.rgb(34, 34, 38), dpf(18f))
                setOnClickListener {
                    if (topic != i) {
                        topic = i
                        drawChips()
                        load(reset = true)
                    }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
    }

    private fun load(reset: Boolean) {
        if (reset) {
            page = 1
            lastPage = 1
            items.clear()
            adapter.notifyDataSetChanged()
        }
        loading = true
        val my = ++gen
        if (items.isEmpty()) status.text = "Loading…"
        val t = topics[topic]
        val p = page
        // Desktop pictures for the wide island, phone pictures for the tall center.
        val shape = if (island) "&ratios=16x9,16x10,21x9&atleast=1920x1080" else "&ratios=portrait&atleast=1080x1920"
        io.execute {
            val result = try {
                val url = "https://wallhaven.cc/api/v1/search?purity=100$shape&categories=${t.cats}&sorting=${t.sort}" +
                    (if (t.sort == "toplist") "&topRange=1y" else "") + (if (t.sort == "random") "&seed=$seed" else "") +
                    "&page=$p" + (if (t.q.isNotEmpty()) "&q=" + java.net.URLEncoder.encode(t.q, "UTF-8") else "")
                val j = JSONObject(get(url))
                val data = j.getJSONArray("data")
                val list = (0 until data.length()).map { i ->
                    val o = data.getJSONObject(i)
                    val th = o.getJSONObject("thumbs")
                    // "original" keeps the picture's real shape ("large" is a zoomed-in crop).
                    Wall(o.getString("id"), th.optString("original", th.optString("large")), o.getString("path"))
                }
                list to (j.optJSONObject("meta")?.optInt("last_page", 1) ?: 1)
            } catch (_: Exception) {
                null
            }
            ui.post {
                if (my != gen || isDestroyed) return@post
                loading = false
                if (result == null) {
                    if (items.isEmpty()) status.text = "Couldn't load wallpapers. Check your connection and try again."
                    return@post
                }
                lastPage = result.second
                items += result.first
                adapter.notifyDataSetChanged()
                status.text = if (items.isEmpty()) "Nothing here right now. Try another topic." else ""
            }
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "Halo-Android")
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun bytes(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "Halo-Android")
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        return c.inputStream.use { it.readBytes() }
    }

    /** A thumbnail in the shape it'll have: the island's pill, or a tall card. */
    private inner class Thumb(c: Context) : ImageView(c) {
        var url = ""
        init {
            scaleType = ScaleType.CENTER_CROP
            background = round(Color.rgb(24, 24, 27), dpf(20f))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dpf(14f))
                }
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            // As the wallpapers really are: desktop 16:9, phone 9:16.
            val h = if (island) (w * 9f / 16f).toInt() else (w * 16f / 9f).toInt()
            setMeasuredDimension(w, h)
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val w = items[position]
            val v = (convertView as? Thumb) ?: Thumb(this@WallsActivity)
            if (v.url == w.thumb) return v
            v.url = w.thumb
            val cached = thumbs.get(w.thumb)
            if (cached != null) {
                v.setImageBitmap(cached)
                return v
            }
            v.setImageDrawable(null)
            io.execute {
                val b = try {
                    val d = bytes(w.thumb)
                    BitmapFactory.decodeByteArray(d, 0, d.size)
                } catch (_: Throwable) {
                    null
                }
                ui.post {
                    if (b != null) thumbs.put(w.thumb, b)
                    if (v.url == w.thumb && b != null) {
                        v.setImageBitmap(b)
                        v.alpha = 0f
                        v.animate().alpha(1f).setDuration(200L).start()
                    }
                }
            }
            return v
        }
    }

    /**
     * Shows the wallpaper in place: inside a big island pill (with the camera
     * dot) or a tall notification card, with Set and Cancel.
     */
    private fun preview(w: Wall) {
        val root = window.decorView as ViewGroup
        val s = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(235, 0, 0, 0))
            isClickable = true
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), 0, dp(20), 0)
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = round(Color.rgb(24, 24, 27), dpf(26f))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dpf(22f))
                }
            }
            thumbs.get(w.thumb)?.let { setImageBitmap(it) }
            val dim = Prefs.get(this@WallsActivity).getInt(Prefs.PIC_DIM, 45) / 100f
            setColorFilter(Color.argb((255 * dim).toInt(), 0, 0, 0), android.graphics.PorterDuff.Mode.SRC_ATOP)
        }
        val sw = resources.displayMetrics.widthPixels - dp(40)
        val frame = FrameLayout(this)
        frame.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // The whole wallpaper as it is: 16:9 for the island, 9:16 for notifications.
        box.addView(frame, if (island) LinearLayout.LayoutParams(sw, (sw * 9f / 16f).toInt()) else LinearLayout.LayoutParams((sw * 0.62f).toInt(), (sw * 0.62f * 16f / 9f).toInt()))
        val state = label("Getting the full picture…", 13f, Color.rgb(160, 160, 168), false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
        }
        box.addView(state)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cancel = label("Cancel", 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = round(Color.rgb(44, 44, 48), dpf(25f))
            setOnClickListener { closePreview() }
        }
        val set = label(if (island) "Set for island" else "Set for notifications", 15f, Color.BLACK, true).apply {
            gravity = Gravity.CENTER
            background = round(Color.WHITE, dpf(25f))
            alpha = 0.5f
            isEnabled = false
        }
        row.addView(cancel, LinearLayout.LayoutParams(0, dp(50), 1f))
        row.addView(set, LinearLayout.LayoutParams(0, dp(50), 1.4f).apply { marginStart = dp(10) })
        box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        s.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(s)
        sheet = s
        s.alpha = 0f
        frame.scaleX = 0.9f
        frame.scaleY = 0.9f
        s.animate().alpha(1f).setDuration(180L).start()
        frame.animate().scaleX(1f).scaleY(1f).setDuration(360L).setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()

        // The full picture, shrunk to at most 1600 px and kept ready to save.
        var full: Bitmap? = null
        io.execute {
            val b = try {
                val data = bytes(w.full)
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, o)
                var sample = 1
                while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 1600) sample *= 2
                BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (_: Throwable) {
                null
            }
            ui.post {
                if (sheet !== s) return@post
                if (b == null) {
                    state.text = "Couldn't get this one. Try another."
                    return@post
                }
                full = b
                img.setImageBitmap(b)
                state.text = if (island) "The island shows the middle of it" else "Fills the space behind your notifications"
                set.isEnabled = true
                set.animate().alpha(1f).setDuration(200L).start()
            }
        }
        set.setOnClickListener {
            val b = full ?: return@setOnClickListener
            set.isEnabled = false
            state.text = "Setting…"
            io.execute {
                val ok = try {
                    Prefs.picFile(this, key).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    Prefs.get(this).edit().putLong(key, System.currentTimeMillis()).apply()
                    true
                } catch (_: Throwable) {
                    false
                }
                ui.post {
                    if (ok) {
                        HaloService.instance?.reloadPrefs()
                        if (island) IslandStore.showPop("Wallpaper", "Set", Colors.INK, R.drawable.ic_image, 2_200L)
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        state.text = "Couldn't save it. Try again."
                        set.isEnabled = true
                    }
                }
            }
        }
    }

    private fun closePreview() {
        val s = sheet ?: return
        sheet = null
        s.animate().alpha(0f).setDuration(160L).withEndAction { (s.parent as? ViewGroup)?.removeView(s) }.start()
    }

    companion object {
        const val EXTRA_KEY = "key"

        fun open(a: Activity, key: String) {
            a.startActivity(Intent(a, WallsActivity::class.java).putExtra(EXTRA_KEY, key))
        }
    }
}
