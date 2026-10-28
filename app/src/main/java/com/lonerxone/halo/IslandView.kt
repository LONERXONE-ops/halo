package com.lonerxone.halo

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateFormat
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
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The island: a black shape that morphs between a hidden circle on the camera,
 * a compact pill, and open cards.
 *
 * - Drawn in a fixed full-width window (resizing overlays makes Android stretch
 *   frames); touches come from a separate window that follows the shape.
 * - Every visual channel is its own closed-form [Spring], so motion is exact at
 *   any frame rate and keeps its velocity when interrupted.
 * - Compact pills size themselves to their content, so nothing gets squeezed
 *   against the camera.
 * - Open cards use the space beside the camera (the "camera row") for small
 *   details: time and battery, the app name, a timer.
 */
@SuppressLint("ViewConstructor")
class IslandView(context: Context, private val host: Host) : FrameLayout(context) {

    interface Host {
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
        data class Timer(val timer: TimerInfo) : Content()
        data class Transfer(val transfer: TransferInfo) : Content()
        data class Nav(val nav: NavInfo) : Content()
    }

    /** A second live activity shown as a small circle beside the pill. */
    class Bubble(
        val key: String,
        val image: Bitmap?,
        val icon: Int,
        val accent: Int,
        val progress: Float,
        val onTap: () -> Unit,
    )

    data class Geometry(val notch: Rect?, val screenW: Int, val screenH: Int, val statusBarH: Int)

    // ---------- settings pushed in by the service ----------
    var idleEnabled = true
    var hapticsEnabled = true
    var sizeScale = 1f
    var offsetX = 0f
    var offsetY = 0f
    /** 0 = pure black (default), 1 = most see-through. */
    var glassStrength = 0f
    var artBackdrop = true
    var lowPower = false
    var geometry: Geometry = Geometry(null, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels, dp(24))

    // ---------- state ----------
    var mode = Mode.HIDDEN
        private set
    private var content: Content = Content.None
    private var bubble: Bubble? = null
    private var hidden = false
    private var expanded = false
    private var panelOpen = false
    private var builtKey: String? = null

    // ---------- springs ----------
    // 0 w, 1 h, 2 radius, 3 alpha, 4 glass, 5 shadow, 6 centre y, 7 bubble alpha, 8 bubble size, 9 edge
    private val springs = Array(10) { i -> Spring(0f, if (i in setOf(0, 1, 2, 6, 8)) 0.4f else 0.002f) }
    private fun v(i: Int) = springs[i].value
    private fun t(i: Int) = springs[i].target
    private val startW = FloatArray(2)
    private var animating = false
    private var lastFrameNanos = 0L
    private val frameCallback = Choreographer.FrameCallback { onFrame(it) }

    // ---------- drawing ----------
    private val rect = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(1f)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(1.5f)
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val bubbleImagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bubbleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dpf(2.2f)
    }
    private val bubbleRect = RectF()
    private val matrix = Matrix()
    private val iconCache = HashMap<Int, Drawable?>()
    private var bubbleShaderFor: Bitmap? = null
    private var bubbleShader: BitmapShader? = null
    private var glowColor = 0
    private var glow = 0f

    // ---------- live content references (cleared before every build) ----------
    private var slot: FrameLayout? = null
    private var revealed = true
    private var wave: WaveformView? = null
    private var artImage: ImageView? = null
    private var playButton: ImageView? = null
    private var progress: ProgressLine? = null
    private var ring: ProgressRing? = null
    private var percentText: TextView? = null
    private var elapsedText: TextView? = null
    private var remainText: TextView? = null
    private var timerText: TextView? = null
    private var navText: TextView? = null
    private var navSub: TextView? = null
    private var clockText: TextView? = null
    private var batteryText: TextView? = null
    private var lastTouch = 0L

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            updateTimer()
            updateClock()
            val idleFor = SystemClock.uptimeMillis() - lastTouch
            if (mode == Mode.PANEL && idleFor > 15_000L) {
                closePanel()
                return
            }
            if (mode == Mode.EXPANDED && expanded && idleFor > 7_000L && progress?.dragging != true) {
                expanded = false
                refresh()
                return
            }
            if (needsTicker()) postDelayed(this, if (lowPower) 1000L else 500L)
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

    fun setState(newContent: Content, newBubble: Bubble?, hide: Boolean) {
        val wasCall = content is Content.Call
        if (!canExpand(newContent)) expanded = false
        if (newContent is Content.Call && !wasCall) expanded = false
        if (hide) panelOpen = false
        content = newContent
        bubble = newBubble
        hidden = hide
        refresh()
    }

    fun rebuild() {
        builtKey = null
        refresh()
    }

    /** A touch landed somewhere else on the screen: retract. */
    fun onOutsideTouch() {
        when {
            mode == Mode.PANEL -> closePanel()
            content is Content.Note -> IslandStore.dismissNotif()
            mode == Mode.EXPANDED && expanded && progress?.dragging != true -> {
                expanded = false
                refresh()
            }
        }
    }

    // =====================================================================
    // Geometry (window pixels == screen pixels)
    // =====================================================================

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    private fun notchCx(): Float = geometry.notch?.exactCenterX() ?: (geometry.screenW / 2f)
    private fun notchW(): Int = geometry.notch?.width() ?: 0
    private fun notchH(): Int = geometry.notch?.height() ?: 0
    private fun anchorY(): Float = geometry.notch?.exactCenterY() ?: (geometry.statusBarH / 2f)
    private fun cx(): Float = if (mode == Mode.IDLE || mode == Mode.HIDDEN) notchCx() else notchCx() + offsetX

    private fun idleD(): Float = if (geometry.notch != null) max(min(notchW(), notchH()).toFloat(), dpf(20f)) else dpf(22f)
    private fun compactH(): Float = dpf(34f) * sizeScale
    private fun baseCompactW(): Float = max(dpf(123f) * sizeScale, notchW() + dpf(86f))
    private fun expandedW(): Float = min(dpf(372f), geometry.screenW - dpf(16f)) * min(sizeScale, 1.05f)
    private fun panelMaxH(): Float = geometry.screenH * 0.62f
    private fun gapTop(): Float = dpf(6f)

    private fun cyFor(m: Mode, h: Float): Float = when (m) {
        Mode.IDLE, Mode.HIDDEN -> anchorY()
        else -> max(anchorY(), gapTop() + h / 2f) + offsetY
    }

    private fun pillRect(w: Float, h: Float, cy: Float, out: RectF) {
        val l = cx() - w / 2f
        out.set(l, cy - h / 2f, l + w, cy + h / 2f)
    }

    private fun bubbleCenterX(pillW: Float, d: Float): Float = cx() + pillW / 2f + dpf(6f) + d / 2f

    // =====================================================================
    // State machine
    // =====================================================================

    private fun canExpand(c: Content) =
        c is Content.Music || c is Content.Call || c is Content.Timer || c is Content.Transfer || c is Content.Nav

    private fun needsTicker() =
        mode == Mode.EXPANDED || mode == Mode.PANEL || content is Content.Call || content is Content.Timer

    private fun desiredMode(): Mode {
        if (hidden) return Mode.HIDDEN
        val c = content
        if (c is Content.Call && c.call.ringing) return Mode.EXPANDED
        if (panelOpen) return Mode.PANEL
        return when (c) {
            is Content.None -> if (idleEnabled) Mode.IDLE else Mode.HIDDEN
            is Content.Note -> Mode.EXPANDED
            is Content.Popup -> Mode.COMPACT
            else -> if (expanded) Mode.EXPANDED else Mode.COMPACT
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
            is Content.Timer -> "t:" + c.timer.key + ":" + (c.timer.anchor == 0L) + ":" + c.timer.actions.size
            is Content.Transfer -> "d:" + c.transfer.key
            is Content.Nav -> "g:" + c.nav.key
        }
    }

    private fun clearRefs() {
        wave = null
        artImage = null
        playButton = null
        progress = null
        ring = null
        percentText = null
        elapsedText = null
        remainText = null
        timerText = null
        navText = null
        navSub = null
        clockText = null
        batteryText = null
    }

    private fun refresh() {
        val m = desiredMode()
        val key = m.name + "/" + contentKey(m)
        if (key == builtKey) {
            bindInPlace()
            bubbleOnly()
            return
        }
        val previous = mode
        mode = m
        builtKey = key
        removeCallbacks(ticker)
        glow = 0f
        // Clear BEFORE building: the builders below fill these in for the new content.
        clearRefs()

        when (m) {
            Mode.HIDDEN -> {
                swapContent(null, 0, 0, 0f, 0f)
                val d = idleD()
                animateTo(d * 0.6f, d * 0.6f, d * 0.3f, 0f, 0f, 0f, 0f, cyFor(m, d), grow = false)
            }
            Mode.IDLE -> {
                swapContent(null, 0, 0, 0f, 0f)
                val d = idleD()
                animateTo(d, d, d / 2f, 1f, 0f, 0f, 0f, cyFor(m, d), grow = false)
            }
            Mode.COMPACT -> {
                val h = compactH()
                val sides = when (val c = content) {
                    is Content.Music -> compactMusic(c.info)
                    is Content.Popup -> compactPop(c.pop).also { glowColor = c.pop.accent; glow = 1f }
                    is Content.Call -> compactCall().also { glowColor = Colors.GREEN; glow = 0.8f }
                    is Content.Timer -> compactTimer()
                    is Content.Transfer -> compactTransfer(c.transfer)
                    is Content.Nav -> compactNav(c.nav)
                    else -> Sides(null, null)
                }
                val w = compactWidthFor(sides)
                val cy = cyFor(m, h)
                swapContent(sides.build(), w.toInt(), h.toInt(), h / 2f, cy)
                animateTo(w, h, h / 2f, 1f, 0f, 0f, 0f, cy, grow = previous.ordinal < Mode.COMPACT.ordinal || content is Content.Popup)
                if (previous == Mode.EXPANDED || previous == Mode.PANEL || content is Content.Popup) haptic()
            }
            Mode.EXPANDED, Mode.PANEL -> {
                val w = expandedW()
                val top = gapTop() + offsetY
                val view = if (m == Mode.PANEL) {
                    buildPanel(w.toInt(), top)
                } else {
                    when (val c = content) {
                        is Content.Music -> buildExpandedMusic(c.info, w.toInt(), top)
                        is Content.Note -> buildNote(c.note, w.toInt(), top).also { glowColor = c.note.accent; glow = 0.5f }
                        is Content.Call -> buildExpandedCall(c.call, w.toInt(), top).also { glowColor = Colors.GREEN; glow = 0.9f }
                        is Content.Timer -> buildExpandedTimer(c.timer, w.toInt(), top)
                        is Content.Transfer -> buildExpandedTransfer(c.transfer, w.toInt(), top)
                        is Content.Nav -> buildExpandedNav(c.nav, w.toInt(), top)
                        else -> View(context)
                    }
                }
                view.measure(
                    MeasureSpec.makeMeasureSpec(w.toInt(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                )
                val h = min(view.measuredHeight.toFloat(), panelMaxH())
                val r = min(dpf(32f), h / 2f)
                val cy = cyFor(m, h)
                swapContent(view, w.toInt(), h.toInt(), r, cy)
                animateTo(w, h, r, 1f, glassStrength, if (lowPower) 0f else 1f, 1f, cy, grow = true)
                lastTouch = SystemClock.uptimeMillis()
                haptic()
            }
        }
        Debug.island = "mode ${m.name.lowercase()}"
        updateProgress()
        updateTimer()
        updateClock()
        if (needsTicker()) postDelayed(ticker, 500L)
    }

    /** Same activity, same mode: update what changed without redrawing the shape. */
    private fun bindInPlace() {
        when (val c = content) {
            is Content.Music -> {
                val info = c.info
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
                updateProgress()
            }
            is Content.Transfer -> {
                val p = c.transfer.progress
                ring?.progress = p
                progress?.fraction = max(0f, p)
                percentText?.text = if (p >= 0f) "${(p * 100).toInt()}%" else ""
            }
            is Content.Nav -> {
                navText?.text = c.nav.primary
                navSub?.text = c.nav.secondary
            }
            else -> {}
        }
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

    private var k = 400f
    private var zeta = 0.8f

    private fun bubbleOnly() {
        val show = bubble != null && mode == Mode.COMPACT
        val before = t(7)
        springs[7].to(if (show) 1f else 0f, k * 0.8f, 0.85f)
        springs[8].to(if (show) compactH() else compactH() * 0.4f, k * 0.8f, 0.85f)
        if (before != t(7)) {
            updateTouchArea()
            startFrames()
        } else {
            invalidate()
        }
    }

    /**
     * Opening: damping 0.8 (a small, lively overshoot). Closing: 0.92 (settles
     * without bounce). Stiffness 400, scaled by the system animation speed.
     * Width leads height a little for the organic stretch.
     */
    private fun animateTo(w: Float, h: Float, r: Float, a: Float, glass: Float, shadow: Float, edge: Float, cy: Float, grow: Boolean) {
        val scale = animScale()
        k = if (scale <= 0f) 400f else 400f / (scale * scale)
        zeta = if (grow) 0.8f else 0.92f
        // First appearance grows out of the camera.
        if (springs[6].value == 0f) springs[6].snap(anchorY())
        startW[0] = v(0)
        startW[1] = v(1)
        springs[0].to(w, k * 1.15f, zeta)
        springs[1].to(h, k, zeta)
        springs[2].to(r, k, zeta)
        springs[3].to(a, k * 1.8f, 0.95f)
        springs[4].to(glass, k, 1f)
        springs[5].to(shadow, k * 0.8f, 1f)
        springs[6].to(cy, k, zeta)
        springs[9].to(edge, k, 1f)
        val show = bubble != null && mode == Mode.COMPACT
        springs[7].to(if (show) 1f else 0f, k * 0.8f, 0.85f)
        springs[8].to(if (show) compactH() else compactH() * 0.4f, k * 0.8f, 0.85f)
        updateTouchArea()

        if (scale <= 0f || !isAttachedToWindow) {
            for (s in springs) s.snap(s.target)
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            animating = false
            onSettled()
            invalidate()
            return
        }
        startFrames()
    }

    private fun startFrames() {
        if (!animating) {
            animating = true
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun onFrame(nanos: Long) {
        val dt = if (lastFrameNanos == 0L) 1f / 60f else (nanos - lastFrameNanos) / 1e9f
        lastFrameNanos = nanos
        var moving = false
        for (s in springs) if (s.step(dt)) moving = true
        if (!revealed && shapeProgress() >= 0.7f) reveal()
        slot?.translationY = v(6) - t(6)
        if (moving) {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else {
            animating = false
            lastFrameNanos = 0L
            slot?.translationY = 0f
            onSettled()
        }
        invalidate()
    }

    private fun shapeProgress(): Float {
        val dw = t(0) - startW[0]
        val dh = t(1) - startW[1]
        val pw = if (abs(dw) < 1f) 1f else (v(0) - startW[0]) / dw
        val ph = if (abs(dh) < 1f) 1f else (v(1) - startW[1]) / dh
        return min(pw, ph)
    }

    private fun onSettled() {
        if (!revealed) reveal()
    }

    private val touchRect = RectF()

    private fun updateTouchArea() {
        if (mode == Mode.HIDDEN || t(3) <= 0f) {
            host.setTouchArea(null)
            return
        }
        pillRect(t(0), t(1), t(6), touchRect)
        if (t(7) > 0.5f) {
            val d = t(8)
            touchRect.right = bubbleCenterX(t(0), d) + d / 2f
        }
        val extra = if (mode == Mode.IDLE) dpf(10f) else dpf(2f)
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
    // Drawing (no allocations per frame)
    // =====================================================================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val a = v(3).coerceIn(0f, 1f)
        if (a <= 0.01f || v(0) < 1f || v(1) < 1f) return
        val w = v(0)
        val h = v(1)
        pillRect(w, h, v(6), rect)
        val r = min(max(v(2), 0f), h / 2f)
        val glass = v(4).coerceIn(0f, 1f)

        val shadow = v(5).coerceIn(0f, 1f)
        if (shadow > 0.02f) {
            fillPaint.setShadowLayer(dpf(22f), 0f, dpf(8f), Color.argb((150 * shadow * a).toInt(), 0, 0, 0))
        } else {
            fillPaint.clearShadowLayer()
        }
        val fillAlpha = 255 - (80 * glass).toInt()
        fillPaint.color = Color.argb((fillAlpha * a).toInt(), (16 * glass).toInt(), (16 * glass).toInt(), (20 * glass).toInt())
        canvas.drawRoundRect(rect, r, r, fillPaint)

        // A hairline on open cards so the black card reads against dark wallpapers.
        val edge = v(9).coerceIn(0f, 1f)
        if (edge > 0.02f) {
            edgePaint.color = Color.argb((26 * edge * a).toInt(), 255, 255, 255)
            val inset = edgePaint.strokeWidth / 2f
            rect.inset(inset, inset)
            canvas.drawRoundRect(rect, r - inset, r - inset, edgePaint)
            rect.inset(-inset, -inset)
        }

        if (glow > 0.01f && glowColor != 0) {
            glowPaint.color = Color.argb((100 * glow * a).toInt(), Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor))
            val inset = glowPaint.strokeWidth / 2f
            rect.inset(inset, inset)
            canvas.drawRoundRect(rect, r - inset, r - inset, glowPaint)
        }

        drawBubble(canvas, w, a)
    }

    private fun drawBubble(canvas: Canvas, pillW: Float, a: Float) {
        val b = bubble ?: return
        val ba = (v(7) * a).coerceIn(0f, 1f)
        if (ba <= 0.01f) return
        val d = v(8)
        val bx = bubbleCenterX(pillW, d)
        val by = v(6)
        bubblePaint.alpha = (255 * ba).toInt()
        canvas.drawCircle(bx, by, d / 2f, bubblePaint)

        val inner = d / 2f - dpf(5f)
        val img = b.image
        if (img != null) {
            if (bubbleShaderFor !== img) {
                bubbleShaderFor = img
                bubbleShader = BitmapShader(img, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            val shader = bubbleShader ?: return
            val s = (inner * 2f) / min(img.width, img.height)
            matrix.reset()
            matrix.setScale(s, s)
            matrix.postTranslate(bx - img.width * s / 2f, by - img.height * s / 2f)
            shader.setLocalMatrix(matrix)
            bubbleImagePaint.shader = shader
            bubbleImagePaint.alpha = (255 * ba).toInt()
            canvas.drawCircle(bx, by, inner, bubbleImagePaint)
        } else if (b.icon != 0) {
            val dr = iconCache.getOrPut(b.icon) { context.getDrawable(b.icon)?.mutate() }
            if (dr != null) {
                val half = (inner * 0.8f).toInt()
                dr.setBounds((bx - half).toInt(), (by - half).toInt(), (bx + half).toInt(), (by + half).toInt())
                dr.setTint(b.accent)
                dr.alpha = (255 * ba).toInt()
                dr.draw(canvas)
            }
        }
        if (b.progress >= 0f) {
            bubbleRing.color = b.accent
            bubbleRing.alpha = (255 * ba).toInt()
            val rr = d / 2f - dpf(2f)
            bubbleRect.set(bx - rr, by - rr, bx + rr, by + rr)
            canvas.drawArc(bubbleRect, -90f, 360f * b.progress.coerceIn(0f, 1f), false, bubbleRing)
        }
    }

    // =====================================================================
    // Content swapping
    // =====================================================================

    private fun swapContent(view: View?, w: Int, h: Int, radius: Float, cy: Float) {
        slot?.let { old ->
            old.animate().cancel()
            old.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(90L).withEndAction { removeView(old) }.start()
        }
        slot = null
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
            leftMargin = ((notchCx() + offsetX) - w / 2f).toInt()
            topMargin = (cy - h / 2f).toInt()
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
    // Building blocks
    // =====================================================================

    private val white70 = Color.argb(178, 255, 255, 255)
    private val white55 = Color.argb(140, 255, 255, 255)

    private fun text(sp: Float, color: Int, bold: Boolean): TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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
                background = GradientDrawable().apply { setColor(accent) }
                setImageResource(placeholder)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
        }

    private fun icon(res: Int, tint: Int): ImageView = ImageView(context).apply {
        setImageResource(res)
        setColorFilter(tint)
    }

    private fun hRow(vararg views: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for (v in views) addView(v)
    }

    private fun sized(v: View, w: Int, h: Int = w): View = v.apply {
        layoutParams = LinearLayout.LayoutParams(w, h)
    }

    private fun spaced(v: TextView, start: Int): TextView = v.apply { setPadding(dp(start), 0, 0, 0) }

    /** Content for a compact pill: one group left of the camera, one right. */
    private inner class Sides(val left: View?, val right: View?, val leftSize: Int = -1, val rightW: Int = -1, val rightH: Int = -1) {
        fun build(): View {
            val root = FrameLayout(context)
            if (left != null) {
                val s = if (leftSize > 0) leftSize else ViewGroup.LayoutParams.WRAP_CONTENT
                root.addView(left, LayoutParams(s, s, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(8) })
            }
            if (right != null) {
                val w = if (rightW > 0) rightW else ViewGroup.LayoutParams.WRAP_CONTENT
                val h = if (rightH > 0) rightH else ViewGroup.LayoutParams.WRAP_CONTENT
                root.addView(right, LayoutParams(w, h, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(12) })
            }
            return root
        }
    }

    private fun widthOf(v: View?, fixed: Int): Int {
        if (v == null) return 0
        if (fixed > 0) return fixed
        v.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return v.measuredWidth
    }

    /** Wide enough that each side clears the camera by 10dp, never narrower than the base pill. */
    private fun compactWidthFor(s: Sides): Float {
        val side = max(widthOf(s.left, s.leftSize) + dp(8) + dp(10), widthOf(s.right, s.rightW) + dp(12) + dp(10))
        return min(max(baseCompactW(), notchW() + 2f * side), expandedW())
    }

    /**
     * The strip beside the camera at the top of an open card: [left] and
     * [right] sit vertically centred on the lens, so that space is used instead
     * of left empty.
     */
    private fun cameraRow(left: View?, right: View?, w: Int, top: Float): View {
        val n = geometry.notch
        val centre = if (n != null) max(n.exactCenterY() - top, dpf(12f)) else dpf(14f)
        val h = max((centre * 2f).toInt(), dp(26))
        val sideMax = ((w - notchW()) / 2 - dp(26)).coerceAtLeast(dp(60))
        val row = FrameLayout(context)
        if (left != null) {
            if (left is TextView) left.maxWidth = sideMax
            row.addView(left, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(18) })
        }
        if (right != null) {
            if (right is TextView) right.maxWidth = sideMax
            row.addView(right, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(18) })
        }
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
        return row
    }

    private fun card(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun body(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(10), dp(18), dp(18))
    }

    private fun pillButton(label: String, bg: Int, fg: Int, action: () -> Unit): TextView = text(13f, fg, true).apply {
        text = label
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(10), dp(18), dp(10))
        background = GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dpf(20f)
        }
        setOnClickListener {
            haptic()
            bounce(it)
            action()
        }
    }

    private fun bounce(v: View) {
        v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70L).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
        }.start()
    }

    private fun appTag(icon: Bitmap?, name: String, accent: Int): View {
        val row = hRow()
        row.addView(sized(roundImage(icon, dpf(5f), accent, R.drawable.ic_note), dp(16)))
        row.addView(spaced(text(12f, white70, true).apply { text = name; maxWidth = dp(110) }, 6))
        return row
    }

    // =====================================================================
    // Compact content
    // =====================================================================

    private fun compactMusic(info: MusicInfo): Sides {
        val art = roundImage(info.art, dpf(11f), info.accent, R.drawable.ic_note)
        artImage = art
        val w = WaveformView(context).apply {
            color = info.accent
            playing = info.playing
            still = lowPower
        }
        wave = w
        return Sides(art, w, dp(22), dp(13), dp(14))
    }

    private fun compactPop(pop: Pop): Sides {
        val left = hRow()
        if (pop.icon != 0) left.addView(sized(icon(pop.icon, pop.accent), dp(16)))
        left.addView(spaced(text(12.5f, Color.WHITE, true).apply { text = pop.title; maxWidth = dp(110) }, 6))
        val right = text(12.5f, pop.accent, true).apply {
            text = pop.value
            maxWidth = dp(110)
        }
        return Sides(left, right)
    }

    private fun compactCall(): Sides {
        val t = text(12.5f, Colors.GREEN, true)
        timerText = t
        return Sides(icon(R.drawable.ic_call, Colors.GREEN), t, dp(16))
    }

    private fun compactTimer(): Sides {
        val tv = text(12.5f, Colors.ORANGE, true)
        timerText = tv
        return Sides(icon(R.drawable.ic_timer, Colors.ORANGE), tv, dp(16))
    }

    private fun compactTransfer(t: TransferInfo): Sides {
        val r = ProgressRing(context).apply {
            color = Colors.BLUE
            progress = t.progress
        }
        ring = r
        return Sides(roundImage(t.icon, dpf(11f), Colors.BLUE, R.drawable.ic_download), r, dp(22), dp(18), dp(18))
    }

    private fun compactNav(nv: NavInfo): Sides {
        val arrow = if (nv.arrow != null) ImageView(context).apply { setImageBitmap(nv.arrow) } else icon(R.drawable.ic_nav, Colors.GREEN)
        val right = text(12.5f, Color.WHITE, true).apply {
            text = nv.primary
            maxWidth = dp(110)
        }
        navText = right
        return Sides(arrow, right, dp(20))
    }

    // =====================================================================
    // Open cards
    // =====================================================================

    /** Media card: blurred cover behind, app beside the camera, big round play button, draggable seek bar. */
    private fun buildExpandedMusic(info: MusicInfo, w: Int, top: Float): View {
        val root = FrameLayout(context)
        if (artBackdrop && info.art != null && !lowPower) {
            root.addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(BitmapFx.blur(info.art) ?: info.art)
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            root.addView(View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.argb(170, 0, 0, 0), Color.argb(225, 0, 0, 0)),
                )
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        val col = card()
        val wv = WaveformView(context).apply {
            color = info.accent
            playing = info.playing
            still = lowPower
        }
        wave = wv
        col.addView(cameraRow(appTag(info.appIcon, info.appName.ifEmpty { "Now playing" }, info.accent), sized(wv, dp(13), dp(14)), w, top))

        val b = body()
        val row = hRow()
        val art = roundImage(info.art, dpf(14f), info.accent, R.drawable.ic_note)
        art.setOnClickListener { openCurrent() }
        artImage = art
        row.addView(art, LinearLayout.LayoutParams(dp(54), dp(54)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(12), 0)
            setOnClickListener { openCurrent() }
        }
        texts.addView(text(16.5f, Color.WHITE, true).apply { text = info.title })
        texts.addView(text(13.5f, white70, false).apply {
            text = info.artist
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val play = ImageView(context).apply {
            setImageResource(if (info.playing) R.drawable.ic_pause else R.drawable.ic_play)
            setColorFilter(Color.BLACK)
            val pad = dp(13)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            setOnClickListener {
                haptic()
                bounce(it)
                val m = currentMusic() ?: return@setOnClickListener
                val tc = m.controller?.transportControls ?: return@setOnClickListener
                if (m.playing) tc.pause() else tc.play()
            }
        }
        playButton = play
        row.addView(play, LinearLayout.LayoutParams(dp(50), dp(50)))
        b.addView(row)

        val seekRow = hRow()
        seekRow.addView(iconButton(R.drawable.ic_prev) { currentMusic()?.controller?.transportControls?.skipToPrevious() })
        val mid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val bar = ProgressLine(context).apply {
            color = Color.WHITE
            onSeek = { f ->
                haptic()
                val m = currentMusic()
                if (m != null && m.durationMs > 0) m.controller?.transportControls?.seekTo((f * m.durationMs).toLong())
            }
        }
        mid.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)).apply { topMargin = dp(4) })
        progress = bar
        val times = FrameLayout(context)
        val el = text(10.5f, white55, false)
        val rem = text(10.5f, white55, false)
        times.addView(el, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.START))
        times.addView(rem, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END))
        mid.addView(times)
        elapsedText = el
        remainText = rem
        seekRow.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(6)
            marginEnd = dp(6)
        })
        seekRow.addView(iconButton(R.drawable.ic_next) { currentMusic()?.controller?.transportControls?.skipToNext() })
        b.addView(seekRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        col.addView(b)

        root.addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun iconButton(res: Int, action: () -> Unit): ImageView = ImageView(context).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        val pad = dp(8)
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        setOnClickListener {
            haptic()
            bounce(it)
            action()
        }
    }

    private fun buildNote(nf: NotifInfo, w: Int, top: Float): View {
        val col = card()
        col.addView(cameraRow(appTag(nf.icon, nf.appName, nf.accent), text(11.5f, white55, false).apply { text = "now" }, w, top))
        val b = body()
        val row = hRow()
        row.addView(roundImage(nf.icon, dpf(13f), nf.accent, R.drawable.ic_check), LinearLayout.LayoutParams(dp(44), dp(44)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        texts.addView(text(15.5f, Color.WHITE, true).apply { text = nf.title })
        texts.addView(text(14f, Color.argb(210, 255, 255, 255), false).apply {
            text = nf.text
            maxLines = 2
            setLineSpacing(0f, 1.1f)
            setPadding(0, dp(5), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        b.addView(row)
        col.addView(b)
        return col
    }

    private fun buildExpandedCall(c: CallInfo, w: Int, top: Float): View {
        val col = card()
        val tag = hRow(sized(icon(R.drawable.ic_call, Colors.GREEN), dp(14)), spaced(text(12f, white70, true).apply { text = "Phone" }, 6))
        val timer = text(12.5f, Colors.GREEN, true)
        timerText = timer
        col.addView(cameraRow(tag, timer, w, top))
        val b = body()
        val row = hRow()
        row.addView(roundImage(c.avatar, dpf(23f), Colors.GREEN, R.drawable.ic_call), LinearLayout.LayoutParams(dp(46), dp(46)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        texts.addView(text(17f, Color.WHITE, true).apply { text = c.name })
        texts.addView(text(13f, white70, false).apply {
            text = if (c.ringing) "Incoming call" else c.detail.ifEmpty { "On call" }
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (c.hangUp != null) row.addView(roundButton(R.drawable.ic_call_end, Colors.RED) { host.send(c.hangUp) })
        if (c.ringing && c.answer != null) {
            row.addView(roundButton(R.drawable.ic_call, Colors.GREEN) { host.send(c.answer) }.apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(12)
            })
        }
        b.addView(row)
        col.addView(b)
        return col
    }

    private fun roundButton(icon: Int, color: Int, action: () -> Unit): ImageView = ImageView(context).apply {
        setImageResource(icon)
        setColorFilter(Color.WHITE)
        val pad = dp(11)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        setOnClickListener {
            haptic()
            bounce(it)
            action()
        }
    }

    private fun buildExpandedTimer(t: TimerInfo, w: Int, top: Float): View {
        val col = card()
        val tag = hRow(sized(icon(R.drawable.ic_timer, Colors.ORANGE), dp(14)), spaced(text(12f, white70, true).apply { text = t.label }, 6))
        col.addView(cameraRow(tag, text(12f, white55, false).apply { text = if (t.countDown) "Timer" else "Stopwatch" }, w, top))
        val b = body()
        val big = text(44f, Colors.ORANGE, false).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setPadding(0, 0, 0, dp(10))
        }
        timerText = big
        b.addView(big)
        if (t.actions.isNotEmpty()) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, pi) in t.actions) {
                row.addView(
                    pillButton(label, Color.argb(42, 255, 255, 255), Color.WHITE) { host.send(pi) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) },
                )
            }
            b.addView(row)
        }
        col.addView(b)
        return col
    }

    private fun buildExpandedTransfer(t: TransferInfo, w: Int, top: Float): View {
        val col = card()
        val pct = text(12.5f, Colors.BLUE, true).apply { text = if (t.progress >= 0f) "${(t.progress * 100).toInt()}%" else "" }
        percentText = pct
        col.addView(cameraRow(appTag(t.icon, t.appName, Colors.BLUE), pct, w, top))
        val b = body()
        b.addView(text(15.5f, Color.WHITE, true).apply { text = t.title })
        val bar = ProgressLine(context).apply {
            color = Colors.BLUE
            fraction = max(0f, t.progress)
        }
        progress = bar
        b.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16)).apply { topMargin = dp(10) })
        col.addView(b)
        return col
    }

    private fun buildExpandedNav(nv: NavInfo, w: Int, top: Float): View {
        val col = card()
        val tag = hRow(sized(icon(R.drawable.ic_nav, Colors.GREEN), dp(14)), spaced(text(12f, white70, true).apply { text = "Maps" }, 6))
        col.addView(cameraRow(tag, null, w, top))
        val b = body()
        val row = hRow()
        val arrow = if (nv.arrow != null) ImageView(context).apply { setImageBitmap(nv.arrow) } else icon(R.drawable.ic_nav, Colors.GREEN)
        row.addView(arrow, LinearLayout.LayoutParams(dp(46), dp(46)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        val main = text(20f, Color.WHITE, true).apply { text = nv.primary }
        navText = main
        texts.addView(main)
        val sub = text(13.5f, white70, false).apply {
            text = nv.secondary
            maxLines = 2
            setPadding(0, dp(4), 0, 0)
        }
        navSub = sub
        texts.addView(sub)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        b.addView(row)
        col.addView(b)
        return col
    }

    /** Opened from the camera: time and battery beside the lens, then your notifications. */
    private fun buildPanel(w: Int, top: Float): View {
        val items = host.panelItems()
        val col = card()

        val clock = text(15f, Color.WHITE, true)
        clockText = clock
        val battery = hRow()
        val batt = text(13f, Color.WHITE, true)
        batteryText = batt
        battery.addView(sized(icon(R.drawable.ic_battery_full, Color.WHITE).apply { tag = "batt" }, dp(15)))
        battery.addView(spaced(batt, 4))
        col.addView(cameraRow(clock, battery, w, top))

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(6), dp(10), dp(2))
        }
        header.addView(text(16f, Color.WHITE, true).apply { text = "Notifications" })
        header.addView(text(13f, white55, false).apply {
            text = if (items.isEmpty()) "" else "  ${items.size}"
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(Color.argb(200, 255, 255, 255))
            val pad = dp(7)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(34, 255, 255, 255))
            }
            setOnClickListener {
                haptic()
                closePanel()
            }
        }, LinearLayout.LayoutParams(dp(30), dp(30)))
        col.addView(header)

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(12))
        }
        if (items.isEmpty()) {
            list.addView(text(14f, white55, false).apply {
                text = "You're all caught up"
                gravity = Gravity.CENTER
                setPadding(0, dp(22), 0, dp(22))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        for (item in items) list.addView(panelCard(item))
        list.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        col.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val room = (panelMaxH() - col.measuredHeight).toInt()
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            addView(list)
        }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, min(list.measuredHeight, max(room, dp(90)))))
        return col
    }

    private fun panelCard(item: PanelItem): View {
        val cardView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = GradientDrawable().apply {
                setColor(Color.argb(24, 255, 255, 255))
                cornerRadius = dpf(22f)
            }
            setOnClickListener {
                haptic()
                panelOpen = false
                refresh()
                host.openDetail(item.key)
            }
        }
        cardView.addView(roundImage(item.icon, dpf(11f), item.accent, R.drawable.ic_check), LinearLayout.LayoutParams(dp(40), dp(40)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, 0, 0)
        }
        val head = hRow()
        head.addView(text(14.5f, Color.WHITE, true).apply { text = item.title }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val ago = DateUtils.getRelativeTimeSpanString(item.postTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        head.addView(spaced(text(11f, white55, false).apply { text = ago }, 10))
        texts.addView(head)
        texts.addView(text(13f, Color.argb(200, 255, 255, 255), false).apply {
            text = item.text
            maxLines = 2
            setLineSpacing(0f, 1.1f)
            setPadding(0, dp(4), 0, 0)
        })
        texts.addView(text(11f, white55, false).apply {
            text = if (item.canReply) "${item.appName}  ·  Tap to reply" else item.appName
            setPadding(0, dp(6), 0, 0)
        })
        cardView.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cardView.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        return cardView
    }

    // =====================================================================
    // Live values
    // =====================================================================

    private fun currentMusic(): MusicInfo? = (content as? Content.Music)?.info

    private fun openCurrent() {
        when (val c = content) {
            is Content.Music -> host.openMusicApp(c.info)
            is Content.Note -> {
                IslandStore.dismissNotif()
                host.openDetail(c.note.key)
            }
            is Content.Call -> host.send(c.call.contentIntent)
            is Content.Timer -> host.send(c.timer.contentIntent)
            is Content.Transfer -> host.send(c.transfer.contentIntent)
            is Content.Nav -> host.send(c.nav.contentIntent)
            else -> return
        }
        haptic()
        expanded = false
        refresh()
    }

    private fun updateProgress() {
        val m = currentMusic() ?: return
        val bar = progress ?: return
        val pos = m.currentPosition()
        val dur = m.durationMs
        bar.fraction = if (dur > 0) pos.toFloat() / dur else 0f
        elapsedText?.text = fmt(pos)
        remainText?.text = if (dur > 0) "-" + fmt(max(0L, dur - pos)) else ""
    }

    private fun updateTimer() {
        val tv = timerText ?: return
        when (val c = content) {
            is Content.Call -> tv.text = when {
                c.call.ringing -> "Ringing"
                c.call.startedAt > 0 -> fmt(System.currentTimeMillis() - c.call.startedAt)
                else -> "On call"
            }
            is Content.Timer -> {
                val t = c.timer
                tv.text = when {
                    t.anchor == 0L -> t.pausedText
                    t.countDown -> fmt(max(0L, t.anchor - System.currentTimeMillis() + 999L))
                    else -> fmt(System.currentTimeMillis() - t.anchor)
                }
            }
            else -> {}
        }
    }

    private fun updateClock() {
        clockText?.text = DateFormat.format(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm", Date())
        val bt = batteryText ?: return
        val bm = context.getSystemService(BatteryManager::class.java) ?: return
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        bt.text = "$pct%"
        val color = when {
            charging -> Colors.GREEN
            pct <= 15 -> Colors.ORANGE
            else -> Color.WHITE
        }
        bt.setTextColor(color)
        ((bt.parent as? ViewGroup)?.findViewWithTag<ImageView>("batt"))?.let { img ->
            img.setImageResource(if (charging) R.drawable.ic_bolt else if (pct <= 15) R.drawable.ic_battery_low else R.drawable.ic_battery_full)
            img.setColorFilter(color)
        }
    }

    private fun fmt(ms: Long): String {
        val s = max(0L, ms) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    // =====================================================================
    // Touch (forwarded from the touch window)
    // =====================================================================

    private fun haptic() {
        if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun onBubble(x: Float, y: Float): Boolean {
        if (bubble == null || v(7) < 0.5f) return false
        val d = v(8)
        val dx = x - bubbleCenterX(v(0), d)
        val dy = y - v(6)
        val r = d / 2f + dpf(4f)
        return dx * dx + dy * dy <= r * r
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (onBubble(e.x, e.y)) {
                haptic()
                bubble?.onTap?.invoke()
                return true
            }
            when {
                mode == Mode.IDLE -> openPanel()
                mode == Mode.PANEL -> {}
                content is Content.Note -> openCurrent()
                content is Content.Popup -> IslandStore.dismissPop()
                canExpand(content) -> if (mode == Mode.COMPACT) {
                    expanded = true
                    refresh()
                } else {
                    openCurrent()
                }
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            when {
                mode == Mode.COMPACT && canExpand(content) -> openCurrent()
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
                    mode == Mode.COMPACT && canExpand(content) -> {
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
                pillRect(v(0), v(1), v(6), rect)
                pivotX = rect.centerX()
                pivotY = rect.centerY()
                if (mode == Mode.COMPACT || mode == Mode.IDLE) animate().scaleX(0.96f).scaleY(0.96f).setDuration(80L).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animate().scaleX(1f).scaleY(1f).setDuration(180L).start()
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
