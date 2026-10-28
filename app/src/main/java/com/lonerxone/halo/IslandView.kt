package com.lonerxone.halo

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The island, drawn inside a FIXED full-width overlay window that never resizes
 * (resizing an overlay makes Android stretch the old frame, which was the
 * flicker). Touches arrive from a separate invisible window that follows the
 * pill; see [HaloService].
 *
 * Every visual channel has its own spring and keeps its velocity when
 * interrupted. Width leads height for the iPhone-style stretch.
 */
@SuppressLint("ViewConstructor")
class IslandView(context: Context, private val host: Host) : FrameLayout(context) {

    interface Host {
        /** Where touches should be caught, in screen pixels; null when nothing is touchable. */
        fun setTouchArea(area: Rect?)
        fun openMusicApp(info: MusicInfo)
        fun send(intent: PendingIntent?)
        fun openDetail(key: String)
        fun panelItems(): List<PanelItem>
    }

    enum class Mode { HIDDEN, IDLE, COMPACT, EXPANDED, PANEL }

    sealed class Content {
        object None : Content()
        data class Music(val info: MusicInfo) : Content()
        data class Popup(val pop: Pop) : Content()
        data class Note(val note: NotifInfo) : Content()
        data class Call(val call: CallInfo) : Content()
    }

    data class Geometry(val notch: Rect?, val screenW: Int, val screenH: Int, val statusBarH: Int)

    // ---------- settings pushed in by the service ----------
    var idleEnabled = true
    var hapticsEnabled = true
    var sizeScale = 1f
    var geometry: Geometry = Geometry(null, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels, dp(24))

    // ---------- state ----------
    var mode = Mode.HIDDEN
        private set
    private var content: Content = Content.None
    private var hidden = false
    private var expanded = false
    private var panelOpen = false
    private var builtKey: String? = null

    // ---------- springs: 0 w, 1 h, 2 radius, 3 alpha, 4 glass, 5 shadow ----------
    private val cur = FloatArray(6)
    private val vel = FloatArray(6)
    private val target = FloatArray(6)
    private val from = FloatArray(6)
    private val stiff = FloatArray(6)
    private var damping = 0.8f
    private var animating = false
    private var lastFrameNanos = 0L
    private val frameCallback = Choreographer.FrameCallback { onFrame(it) }

    // ---------- drawing ----------
    private val rect = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(1f)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(1.5f)
    }
    private var glowColor = 0
    private var glow = 0f

    // ---------- content ----------
    private var slot: FrameLayout? = null
    private var revealed = true
    private var wave: WaveformView? = null
    private var artImage: ImageView? = null
    private var playButton: ImageView? = null
    private var progress: ProgressLine? = null
    private var elapsedText: TextView? = null
    private var remainText: TextView? = null
    private var timerText: TextView? = null
    private var lastTouch = 0L

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            updateTimer()
            val idleFor = SystemClock.uptimeMillis() - lastTouch
            if (mode == Mode.PANEL && idleFor > 15_000L) {
                closePanel()
                return
            }
            if (mode == Mode.EXPANDED && expanded && idleFor > 6_000L) {
                expanded = false
                refresh()
                return
            }
            if (mode == Mode.EXPANDED || mode == Mode.PANEL || content is Content.Call) postDelayed(this, 500L)
        }
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    // =====================================================================
    // Public API
    // =====================================================================

    fun setState(newContent: Content, hide: Boolean) {
        val wasCall = content is Content.Call
        if (newContent !is Content.Music && newContent !is Content.Call) expanded = false
        if (newContent is Content.Call && !wasCall) expanded = false
        if (hide) panelOpen = false
        content = newContent
        hidden = hide
        refresh()
    }

    /** Rebuild from scratch, e.g. after rotation or a size change. */
    fun rebuild() {
        builtKey = null
        refresh()
    }

    // =====================================================================
    // Geometry (all in this window's pixels, which equal screen pixels)
    // =====================================================================

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    private fun cx(): Float = geometry.notch?.exactCenterX() ?: (geometry.screenW / 2f)
    private fun notchW(): Int = geometry.notch?.width() ?: 0
    private fun notchH(): Int = geometry.notch?.height() ?: 0
    private fun anchorY(): Float = geometry.notch?.exactCenterY() ?: (geometry.statusBarH / 2f)
    private fun notchBottom(): Int = geometry.notch?.bottom ?: (geometry.statusBarH * 2 / 3)

    /** The idle circle sits exactly on the camera lens, so it is invisible until touched. */
    private fun idleD(): Float = if (geometry.notch != null) max(min(notchW(), notchH()).toFloat(), dpf(20f)) else dpf(22f)
    private fun compactW(): Float = max(dpf(206f) * sizeScale, notchW() + dpf(140f))
    private fun compactH(): Float = max(notchH() + dpf(4f), dpf(34f) * sizeScale)
    private fun expandedW(): Float = min(dpf(352f) * sizeScale, geometry.screenW - dpf(14f))
    private fun panelMaxH(): Float = geometry.screenH * 0.6f
    private fun topFor(h: Float): Float = max(0f, anchorY() - h / 2f)

    private fun pillRect(w: Float, h: Float, out: RectF) {
        val l = cx() - w / 2f
        val t = topFor(h)
        out.set(l, t, l + w, t + h)
    }

    // =====================================================================
    // State machine
    // =====================================================================

    private fun desiredMode(): Mode {
        if (hidden) return Mode.HIDDEN
        val c = content
        if (c is Content.Call && c.call.ringing) return Mode.EXPANDED
        if (panelOpen) return Mode.PANEL
        return when (c) {
            is Content.None -> if (idleEnabled) Mode.IDLE else Mode.HIDDEN
            is Content.Note -> Mode.EXPANDED
            is Content.Call -> if (expanded) Mode.EXPANDED else Mode.COMPACT
            is Content.Popup -> Mode.COMPACT
            is Content.Music -> if (expanded) Mode.EXPANDED else Mode.COMPACT
        }
    }

    private fun contentKey(m: Mode): String {
        if (m == Mode.PANEL) return "panel"
        return when (val c = content) {
            is Content.None -> "none"
            is Content.Music -> "m:" + c.info.trackKey
            is Content.Popup -> "p:" + c.pop.id
            is Content.Note -> "n:" + c.note.id
            is Content.Call -> "c:" + c.call.key + ":" + c.call.ringing + ":" + c.call.name
        }
    }

    private fun refresh() {
        val m = desiredMode()
        val key = m.name + "/" + contentKey(m)
        if (key == builtKey) {
            bindInPlace()
            return
        }
        val previous = mode
        mode = m
        builtKey = key
        removeCallbacks(ticker)
        glow = 0f

        when (m) {
            Mode.HIDDEN -> {
                swapContent(null, 0, 0, 0f)
                val d = idleD()
                animateTo(d * 0.6f, d * 0.6f, d * 0.3f, 0f, 0f, 0f, grow = false)
            }
            Mode.IDLE -> {
                swapContent(null, 0, 0, 0f)
                val d = idleD()
                animateTo(d, d, d / 2f, 1f, 0f, 0f, grow = false)
            }
            Mode.COMPACT -> {
                val w = compactW()
                val h = compactH()
                val view = when (val c = content) {
                    is Content.Music -> buildCompactMusic(c.info, h.toInt())
                    is Content.Popup -> buildPop(c.pop).also { glowColor = c.pop.accent; glow = 1f }
                    is Content.Call -> buildCompactCall(c.call).also { glowColor = Colors.GREEN; glow = 0.8f }
                    else -> null
                }
                swapContent(view, w.toInt(), h.toInt(), h / 2f)
                animateTo(w, h, h / 2f, 1f, 0.45f, 0f, grow = previous.ordinal < Mode.COMPACT.ordinal || content is Content.Popup)
                if (previous == Mode.EXPANDED || previous == Mode.PANEL || content is Content.Popup) haptic()
            }
            Mode.EXPANDED, Mode.PANEL -> {
                val w = expandedW()
                val view = if (m == Mode.PANEL) {
                    buildPanel(w.toInt())
                } else {
                    when (val c = content) {
                        is Content.Music -> buildExpandedMusic(c.info)
                        is Content.Note -> buildNote(c.note).also { glowColor = c.note.accent; glow = 0.7f }
                        is Content.Call -> buildExpandedCall(c.call).also { glowColor = Colors.GREEN; glow = 1f }
                        else -> View(context)
                    }
                }
                view.measure(
                    MeasureSpec.makeMeasureSpec(w.toInt(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                )
                val h = min(view.measuredHeight.toFloat(), panelMaxH())
                val r = min(dpf(30f), h / 2f)
                swapContent(view, w.toInt(), h.toInt(), r)
                animateTo(w, h, r, 1f, 1f, 1f, grow = true)
                lastTouch = SystemClock.uptimeMillis()
                haptic()
            }
        }
        Debug.island = "mode ${m.name.lowercase()}"
        updateProgress()
        updateTimer()
        if (m == Mode.EXPANDED || m == Mode.PANEL || content is Content.Call) postDelayed(ticker, 500L)
    }

    /** Same song or call, same mode: just update what changed, no redraw of the shape. */
    private fun bindInPlace() {
        val info = (content as? Content.Music)?.info ?: return
        wave?.playing = info.playing
        wave?.color = info.accent
        if (info.art != null) artImage?.let { img ->
            img.background = null
            img.clearColorFilter()
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            img.setPadding(0, 0, 0, 0)
            img.setImageBitmap(info.art)
        }
        playButton?.setImageResource(if (info.playing) R.drawable.ic_pause else R.drawable.ic_play)
        if (mode == Mode.EXPANDED) updateProgress()
    }

    private fun openPanel() {
        panelOpen = true
        refresh()
    }

    private fun closePanel() {
        if (!panelOpen) return
        panelOpen = false
        refresh()
    }

    // =====================================================================
    // Springs
    // =====================================================================

    private fun animScale(): Float = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    } catch (_: Exception) {
        1f
    }

    /**
     * Growing is bouncier (damping 0.7) so the island "pops"; shrinking settles
     * calmly (0.88). Width leads height slightly for an organic stretch.
     */
    private fun animateTo(w: Float, h: Float, r: Float, a: Float, glass: Float, shadow: Float, grow: Boolean) {
        target[0] = w; target[1] = h; target[2] = r
        target[3] = a; target[4] = glass; target[5] = shadow
        System.arraycopy(cur, 0, from, 0, cur.size)
        updateTouchArea()

        val scale = animScale()
        if (scale <= 0f || !isAttachedToWindow) {
            System.arraycopy(target, 0, cur, 0, cur.size)
            vel.fill(0f)
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            animating = false
            onSettled()
            invalidate()
            return
        }
        val k = 360f / (scale * scale)
        damping = if (grow) 0.7f else 0.88f
        stiff[0] = k * 1.22f
        stiff[1] = k
        stiff[2] = k
        stiff[3] = k * 1.8f
        stiff[4] = k
        stiff[5] = k * 0.8f
        if (!animating) {
            animating = true
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun onFrame(nanos: Long) {
        val dt = if (lastFrameNanos == 0L) 1f / 60f else ((nanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastFrameNanos = nanos
        var left = dt
        while (left > 0f) {
            val step = min(left, 0.004f)
            for (i in cur.indices) {
                val k = stiff[i]
                val c = 2f * damping * sqrt(k)
                val acc = -k * (cur[i] - target[i]) - c * vel[i]
                vel[i] += acc * step
                cur[i] += vel[i] * step
            }
            left -= step
        }
        if (!revealed && shapeProgress() >= 0.7f) reveal()

        var done = true
        for (i in cur.indices) {
            val tol = if (i <= 2) 0.5f else 0.003f
            if (abs(cur[i] - target[i]) > tol || abs(vel[i]) > tol * 4f) {
                done = false
                break
            }
        }
        if (done) {
            System.arraycopy(target, 0, cur, 0, cur.size)
            vel.fill(0f)
            animating = false
            lastFrameNanos = 0L
            onSettled()
        } else {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
        invalidate()
    }

    private fun shapeProgress(): Float {
        val dw = target[0] - from[0]
        val dh = target[1] - from[1]
        val pw = if (abs(dw) < 1f) 1f else (cur[0] - from[0]) / dw
        val ph = if (abs(dh) < 1f) 1f else (cur[1] - from[1]) / dh
        return min(pw, ph)
    }

    private fun onSettled() {
        if (!revealed) reveal()
    }

    private val touchRect = RectF()

    /** The touch window follows the target shape; it draws nothing, so moving it is invisible. */
    private fun updateTouchArea() {
        if (mode == Mode.HIDDEN || target[3] <= 0f) {
            host.setTouchArea(null)
            return
        }
        pillRect(target[0], target[1], touchRect)
        // The camera circle is small; give it a friendlier tap target.
        val extra = if (mode == Mode.IDLE) dpf(10f) else 0f
        host.setTouchArea(
            Rect(
                (touchRect.left - extra).toInt(),
                max(0f, touchRect.top - extra).toInt(),
                (touchRect.right + extra).toInt(),
                (touchRect.bottom + extra).toInt(),
            ),
        )
    }

    // =====================================================================
    // Drawing
    // =====================================================================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = cur[0]
        val h = cur[1]
        val a = cur[3].coerceIn(0f, 1f)
        if (a <= 0.01f || w < 1f || h < 1f) return
        pillRect(w, h, rect)
        val r = min(max(cur[2], 0f), h / 2f)
        val glass = cur[4].coerceIn(0f, 1f)

        val shadow = cur[5].coerceIn(0f, 1f)
        if (shadow > 0.02f) {
            fillPaint.setShadowLayer(dpf(18f), 0f, dpf(6f), Color.argb((130 * shadow * a).toInt(), 0, 0, 0))
        } else {
            fillPaint.clearShadowLayer()
        }
        // Pure black at rest (melts into the camera), dark glass when open.
        val fillAlpha = 255 - (20 * glass).toInt()
        fillPaint.color = Color.argb((fillAlpha * a).toInt(), (12 * glass).toInt(), (12 * glass).toInt(), (16 * glass).toInt())
        canvas.drawRoundRect(rect, r, r, fillPaint)

        if (glass > 0.01f) {
            sheenPaint.shader = LinearGradient(
                0f, rect.top, 0f, rect.top + min(h, dpf(90f)),
                Color.argb((28 * glass * a).toInt(), 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, r, r, sheenPaint)

            borderPaint.shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                Color.argb((75 * glass * a).toInt(), 255, 255, 255),
                Color.argb((18 * glass * a).toInt(), 255, 255, 255),
                Shader.TileMode.CLAMP,
            )
            val inset = borderPaint.strokeWidth / 2f
            rect.inset(inset, inset)
            canvas.drawRoundRect(rect, r - inset, r - inset, borderPaint)
            rect.inset(-inset, -inset)
        }

        if (glow > 0.01f && glowColor != 0) {
            glowPaint.color = Color.argb(
                (110 * glow * a).toInt(),
                Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor),
            )
            val inset = glowPaint.strokeWidth / 2f
            rect.inset(inset, inset)
            canvas.drawRoundRect(rect, r - inset, r - inset, glowPaint)
        }
    }

    // =====================================================================
    // Content swapping
    // =====================================================================

    private fun swapContent(view: View?, w: Int, h: Int, radius: Float) {
        slot?.let { old ->
            old.animate().cancel()
            old.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(90L).withEndAction { removeView(old) }.start()
        }
        slot = null
        wave = null
        artImage = null
        playButton = null
        progress = null
        elapsedText = null
        remainText = null
        timerText = null
        if (view == null) {
            revealed = true
            return
        }
        val s = FrameLayout(context).apply {
            alpha = 0f
            scaleX = 0.94f
            scaleY = 0.94f
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, radius)
                }
            }
            clipToOutline = true
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        val lp = LayoutParams(w, h, Gravity.TOP or Gravity.START).apply {
            leftMargin = (cx() - w / 2f).toInt()
            topMargin = topFor(h.toFloat()).toInt()
        }
        addView(s, lp)
        slot = s
        revealed = false
    }

    private fun reveal() {
        revealed = true
        slot?.animate()?.alpha(1f)?.scaleX(1f)?.scaleY(1f)
            ?.setInterpolator(DecelerateInterpolator(1.6f))?.setDuration(220L)?.start()
    }

    // =====================================================================
    // Content builders
    // =====================================================================

    private fun text(sp: Float, color: Int, bold: Boolean): TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    private fun roundImage(bitmap: Bitmap?, radius: Float, accent: Int, placeholder: Int): ImageView =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, radius)
                }
            }
            clipToOutline = true
            if (bitmap != null) {
                setImageBitmap(bitmap)
            } else {
                background = GradientDrawable().apply {
                    setColor(Color.argb(60, Color.red(accent), Color.green(accent), Color.blue(accent)))
                }
                setImageResource(placeholder)
                setColorFilter(accent)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
        }

    private fun expandedPadTop(): Int = max(dp(14), notchBottom() + dp(8))

    private fun buildCompactMusic(info: MusicInfo, h: Int): View {
        val root = FrameLayout(context)
        val artSize = h - dp(10)
        val art = roundImage(info.art, artSize / 2f, info.accent, R.drawable.ic_note)
        root.addView(art, LayoutParams(artSize, artSize, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(5) })
        artImage = art
        val w = WaveformView(context).apply {
            color = info.accent
            playing = info.playing
        }
        root.addView(w, LayoutParams(dp(22), dp(14), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(13) })
        wave = w
        return root
    }

    private fun buildPop(pop: Pop): View {
        val root = FrameLayout(context)
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (pop.icon != 0) {
            left.addView(
                ImageView(context).apply {
                    setImageResource(pop.icon)
                    setColorFilter(pop.accent)
                },
                LinearLayout.LayoutParams(dp(16), dp(16)),
            )
        }
        left.addView(text(12.5f, Color.WHITE, true).apply {
            text = pop.title
            setPadding(dp(6), 0, 0, 0)
        })
        root.addView(left, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(14) })
        root.addView(
            text(13f, pop.accent, true).apply { text = pop.value },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(14) },
        )
        return root
    }

    private fun buildNote(n: NotifInfo): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), expandedPadTop(), dp(16), dp(14))
        }
        row.addView(roundImage(n.icon, dp(12).toFloat(), n.accent, R.drawable.ic_check), LinearLayout.LayoutParams(dp(42), dp(42)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(text(14.5f, Color.WHITE, true).apply { text = n.title }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(text(11f, Color.argb(140, 255, 255, 255), false).apply {
            text = n.appName
            setPadding(dp(8), 0, 0, 0)
        })
        texts.addView(head)
        texts.addView(text(13f, Color.argb(200, 255, 255, 255), false).apply {
            text = n.text
            maxLines = 2
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun buildPanel(w: Int): View {
        val items = host.panelItems()
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), expandedPadTop(), dp(12), dp(12))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, 0, dp(4))
        }
        header.addView(text(15f, Color.WHITE, true).apply { text = "Notifications" })
        header.addView(text(13f, Color.argb(130, 255, 255, 255), false).apply {
            text = if (items.isEmpty()) "" else "  ${items.size}"
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(Color.argb(200, 255, 255, 255))
            val pad = dp(6)
            setPadding(pad, pad, pad, pad)
            setOnClickListener {
                haptic()
                closePanel()
            }
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        col.addView(header)

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (items.isEmpty()) {
            list.addView(text(13.5f, Color.argb(150, 255, 255, 255), false).apply {
                text = "You're all caught up"
                gravity = Gravity.CENTER
                setPadding(0, dp(18), 0, dp(18))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        for (item in items) list.addView(panelCard(item))

        // Measure the list so the panel is only as tall as it needs to be.
        val inner = w - dp(24)
        list.measure(
            MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        header.measure(
            MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val room = (panelMaxH() - expandedPadTop() - dp(12) - header.measuredHeight).toInt()
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            addView(list)
        }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, min(list.measuredHeight, max(room, dp(80)))))
        return col
    }

    private fun panelCard(item: PanelItem): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = GradientDrawable().apply {
                setColor(Color.argb(26, 255, 255, 255))
                cornerRadius = dpf(20f)
            }
            setOnClickListener {
                haptic()
                panelOpen = false
                refresh()
                host.openDetail(item.key)
            }
        }
        card.addView(roundImage(item.icon, dpf(10f), item.accent, R.drawable.ic_check), LinearLayout.LayoutParams(dp(38), dp(38)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, 0, 0)
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(text(14f, Color.WHITE, true).apply { text = item.title }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val ago = DateUtils.getRelativeTimeSpanString(item.postTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        head.addView(text(11f, Color.argb(130, 255, 255, 255), false).apply {
            text = if (item.canReply) "$ago · Reply" else ago
            setPadding(dp(8), 0, 0, 0)
        })
        texts.addView(head)
        texts.addView(text(12.5f, Color.argb(190, 255, 255, 255), false).apply {
            text = item.text
            maxLines = 2
            setPadding(0, dp(3), 0, 0)
        })
        texts.addView(text(10.5f, Color.argb(110, 255, 255, 255), false).apply {
            text = item.appName
            setPadding(0, dp(4), 0, 0)
        })
        card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        }
        return card
    }

    private fun buildCompactCall(c: CallInfo): View {
        val root = FrameLayout(context)
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        left.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_call)
            setColorFilter(Colors.GREEN)
        }, LinearLayout.LayoutParams(dp(16), dp(16)))
        left.addView(text(12.5f, Color.WHITE, true).apply {
            text = c.name
            maxWidth = dp(70)
            setPadding(dp(6), 0, 0, 0)
        })
        root.addView(left, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(14) })
        val t = text(13f, Colors.GREEN, true)
        root.addView(t, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(14) })
        timerText = t
        return root
    }

    private fun buildExpandedCall(c: CallInfo): View {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), expandedPadTop(), dp(16), dp(14))
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(roundImage(c.avatar, dp(22).toFloat(), Colors.GREEN, R.drawable.ic_call), LinearLayout.LayoutParams(dp(44), dp(44)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        texts.addView(text(16f, Color.WHITE, true).apply { text = c.name })
        val sub = text(12.5f, Color.argb(166, 255, 255, 255), false).apply {
            text = if (c.ringing) "Incoming call" else c.detail
            setPadding(0, dp(3), 0, 0)
        }
        texts.addView(sub)
        if (!c.ringing) timerText = sub
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (c.hangUp != null) {
            row.addView(roundButton(R.drawable.ic_call_end, Colors.RED) { host.send(c.hangUp) })
        }
        if (c.ringing && c.answer != null) {
            row.addView(roundButton(R.drawable.ic_call, Colors.GREEN) { host.send(c.answer) }.apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(12)
            })
        }
        col.addView(row)
        return col
    }

    private fun roundButton(icon: Int, color: Int, action: () -> Unit): ImageView = ImageView(context).apply {
        setImageResource(icon)
        setColorFilter(Color.WHITE)
        val pad = dp(10)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener {
            haptic()
            action()
        }
    }

    private fun buildExpandedMusic(info: MusicInfo): View {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), expandedPadTop(), dp(18), dp(12))
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val art = roundImage(info.art, dpf(12f), info.accent, R.drawable.ic_note)
        art.setOnClickListener { openCurrent() }
        row.addView(art, LinearLayout.LayoutParams(dp(54), dp(54)))
        artImage = art

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            setOnClickListener { openCurrent() }
        }
        texts.addView(text(15.5f, Color.WHITE, true).apply { text = info.title })
        texts.addView(text(13f, Color.argb(166, 255, 255, 255), false).apply {
            text = info.artist
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val w = WaveformView(context).apply {
            color = info.accent
            playing = info.playing
        }
        row.addView(w, LinearLayout.LayoutParams(dp(24), dp(18)))
        wave = w
        col.addView(row)

        val bar = ProgressLine(context).apply { color = info.accent }
        col.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(14) })
        progress = bar

        val times = FrameLayout(context)
        val el = text(10.5f, Color.argb(153, 255, 255, 255), false)
        val rem = text(10.5f, Color.argb(153, 255, 255, 255), false)
        times.addView(el, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.START))
        times.addView(rem, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END))
        col.addView(times, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        elapsedText = el
        remainText = rem

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(iconButton(R.drawable.ic_prev, dp(26)) { currentMusic()?.controller?.transportControls?.skipToPrevious() })
        val play = iconButton(if (info.playing) R.drawable.ic_pause else R.drawable.ic_play, dp(34)) {
            val m = currentMusic() ?: return@iconButton
            val tc = m.controller?.transportControls ?: return@iconButton
            if (m.playing) tc.pause() else tc.play()
        }
        controls.addView(play)
        playButton = play
        controls.addView(iconButton(R.drawable.ic_next, dp(26)) { currentMusic()?.controller?.transportControls?.skipToNext() })
        col.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        return col
    }

    private fun iconButton(res: Int, size: Int, action: () -> Unit): ImageView = ImageView(context).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        val pad = dp(7)
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(size + pad * 2, size + pad * 2).apply {
            marginStart = dp(14)
            marginEnd = dp(14)
        }
        setOnClickListener {
            haptic()
            it.animate().scaleX(0.85f).scaleY(0.85f).setDuration(70L).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
            }.start()
            action()
        }
    }

    private fun currentMusic(): MusicInfo? = (content as? Content.Music)?.info

    private fun openCurrent() {
        when (val c = content) {
            is Content.Music -> host.openMusicApp(c.info)
            is Content.Note -> {
                IslandStore.dismissNotif()
                host.openDetail(c.note.key)
            }
            is Content.Call -> host.send(c.call.contentIntent)
            else -> return
        }
        haptic()
        expanded = false
        refresh()
    }

    private fun updateProgress() {
        val m = currentMusic() ?: return
        if (mode != Mode.EXPANDED) return
        val pos = m.currentPosition()
        val dur = m.durationMs
        progress?.fraction = if (dur > 0) pos.toFloat() / dur else 0f
        elapsedText?.text = fmt(pos)
        remainText?.text = if (dur > 0) "-" + fmt(max(0L, dur - pos)) else ""
    }

    private fun updateTimer() {
        val c = (content as? Content.Call)?.call ?: return
        val t = timerText ?: return
        t.text = when {
            c.ringing -> "Ringing"
            c.startedAt > 0 -> fmt(System.currentTimeMillis() - c.startedAt)
            else -> "On call"
        }
    }

    private fun fmt(ms: Long): String {
        val s = max(0L, ms) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    // =====================================================================
    // Touch (events are forwarded here from the touch window)
    // =====================================================================

    private fun haptic() {
        if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            when {
                mode == Mode.IDLE -> openPanel()
                mode == Mode.PANEL -> {}
                content is Content.Music || content is Content.Call -> if (mode == Mode.COMPACT) {
                    expanded = true
                    refresh()
                } else {
                    openCurrent()
                }
                content is Content.Note -> openCurrent()
                content is Content.Popup -> IslandStore.dismissPop()
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            when {
                mode == Mode.COMPACT && content is Content.Music -> openCurrent()
                mode == Mode.COMPACT || mode == Mode.IDLE -> openPanel()
            }
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val music = currentMusic()
            if (abs(velocityX) > abs(velocityY) && mode != Mode.PANEL) {
                val tc = music?.controller?.transportControls ?: return false
                haptic()
                if (velocityX < 0) tc.skipToNext() else tc.skipToPrevious()
                return true
            }
            if (velocityY < 0) {
                when {
                    mode == Mode.PANEL -> closePanel()
                    content is Content.Note -> IslandStore.dismissNotif()
                    content is Content.Popup -> IslandStore.dismissPop()
                    mode == Mode.EXPANDED -> {
                        expanded = false
                        refresh()
                    }
                }
                return true
            }
            if (velocityY > 0) {
                when {
                    mode == Mode.IDLE -> openPanel()
                    mode == Mode.COMPACT && (music != null || content is Content.Call) -> {
                        expanded = true
                        refresh()
                    }
                    content is Content.Note -> openCurrent()
                }
                return true
            }
            return false
        }
    })

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pillRect(cur[0], cur[1], rect)
                pivotX = rect.centerX()
                pivotY = rect.centerY()
                // Press feedback: a slight squeeze (skipped for the big panel).
                if (mode != Mode.PANEL) animate().scaleX(0.97f).scaleY(0.97f).setDuration(80L).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animate().scaleX(1f).scaleY(1f).setDuration(160L).start()
            }
        }
        lastTouch = SystemClock.uptimeMillis()
        if (content is Content.Note) IslandStore.holdNotif(4_000L)
        return super.dispatchTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        animating = false
        removeCallbacks(ticker)
    }
}
