package com.lonerxone.halo

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
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
 * The island, drawn in a FIXED full-width overlay window (resizing an overlay
 * makes Android stretch the old frame, which caused flicker). Touches come from
 * a separate invisible window that follows the shape; see [HaloService].
 *
 * Proportions follow Smart Island: a 34dp-tall black pill floating just below
 * the top edge, about 123dp wide with content, 22dp round art on the left and
 * three slim bars on the right, springs at damping 0.8 / stiffness 400, and a
 * round "split" bubble to the right for a second live activity.
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
    /** 0 = solid black cards, 1 = most see-through glass. */
    var glassStrength = 0.2f
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
    // 0 w, 1 h, 2 radius, 3 alpha, 4 glass, 5 shadow, 6 centre y, 7 bubble alpha, 8 bubble size
    private val n = 9
    private val cur = FloatArray(n)
    private val vel = FloatArray(n)
    private val target = FloatArray(n)
    private val from = FloatArray(n)
    private val stiff = FloatArray(n)
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
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val bubbleImagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bubbleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bubbleRect = RectF()
    private val matrix = Matrix()
    private var glowColor = 0
    private var glow = 0f

    // ---------- content ----------
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

    /** Rebuild from scratch, e.g. after rotation or a settings change. */
    fun rebuild() {
        builtKey = null
        refresh()
    }

    /** A touch landed somewhere else on the screen: retract. */
    fun onOutsideTouch() {
        when {
            mode == Mode.PANEL -> closePanel()
            content is Content.Note -> IslandStore.dismissNotif()
            mode == Mode.EXPANDED && expanded -> {
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
    private fun cx(): Float = if (mode == Mode.IDLE) notchCx() else notchCx() + offsetX

    /** The idle circle sits exactly on the camera, so it is invisible until touched. */
    private fun idleD(): Float = if (geometry.notch != null) max(min(notchW(), notchH()).toFloat(), dpf(20f)) else dpf(22f)
    private fun compactH(): Float = dpf(34f) * sizeScale
    private fun compactW(): Float = max(dpf(123f) * sizeScale, notchW() + dpf(86f))
    private fun expandedW(): Float = min(dpf(372f), geometry.screenW - dpf(16f)) * min(sizeScale, 1.05f)
    private fun panelMaxH(): Float = geometry.screenH * 0.6f
    private fun gapTop(): Float = dpf(6f)

    /** Centre line: on the camera when idle, otherwise floating just below the top edge. */
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

    private fun refresh() {
        val m = desiredMode()
        val key = m.name + "/" + contentKey(m)
        if (key == builtKey) {
            bindInPlace()
            animateBubbleOnly()
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
                animateTo(d * 0.6f, d * 0.6f, d * 0.3f, 0f, 0f, 0f, cyFor(m, d), grow = false)
            }
            Mode.IDLE -> {
                swapContent(null, 0, 0, 0f)
                val d = idleD()
                animateTo(d, d, d / 2f, 1f, 0f, 0f, cyFor(m, d), grow = false)
            }
            Mode.COMPACT -> {
                val w = compactW()
                val h = compactH()
                val view = when (val c = content) {
                    is Content.Music -> buildCompactMusic(c.info, h.toInt())
                    is Content.Popup -> buildPop(c.pop).also { glowColor = c.pop.accent; glow = 1f }
                    is Content.Call -> buildCompactCall(c.call).also { glowColor = Colors.GREEN; glow = 0.8f }
                    is Content.Timer -> buildCompactTimer(c.timer)
                    is Content.Transfer -> buildCompactTransfer(c.transfer, h.toInt())
                    is Content.Nav -> buildCompactNav(c.nav)
                    else -> null
                }
                swapContent(view, w.toInt(), h.toInt(), h / 2f, cyFor(m, h))
                animateTo(w, h, h / 2f, 1f, 0f, 0f, cyFor(m, h), grow = previous.ordinal < Mode.COMPACT.ordinal || content is Content.Popup)
                if (previous == Mode.EXPANDED || previous == Mode.PANEL || content is Content.Popup) haptic()
            }
            Mode.EXPANDED, Mode.PANEL -> {
                val w = expandedW()
                val topGuess = gapTop() + offsetY
                val view = if (m == Mode.PANEL) {
                    buildPanel(w.toInt(), topGuess)
                } else {
                    when (val c = content) {
                        is Content.Music -> buildExpandedMusic(c.info, topGuess)
                        is Content.Note -> buildNote(c.note, topGuess).also { glowColor = c.note.accent; glow = 0.6f }
                        is Content.Call -> buildExpandedCall(c.call, topGuess).also { glowColor = Colors.GREEN; glow = 0.9f }
                        is Content.Timer -> buildExpandedTimer(c.timer, topGuess)
                        is Content.Transfer -> buildExpandedTransfer(c.transfer, topGuess)
                        is Content.Nav -> buildExpandedNav(c.nav, topGuess)
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
                animateTo(w, h, r, 1f, glassStrength, if (lowPower) 0f else 1f, cy, grow = true)
                lastTouch = SystemClock.uptimeMillis()
                haptic()
            }
        }
        Debug.island = "mode ${m.name.lowercase()}"
        updateProgress()
        updateTimer()
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
                if (mode == Mode.EXPANDED) updateProgress()
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

    private fun bubbleTargets() {
        val showBubble = bubble != null && mode == Mode.COMPACT
        target[7] = if (showBubble) 1f else 0f
        target[8] = if (showBubble) compactH() else compactH() * 0.4f
    }

    private fun animateBubbleOnly() {
        val before7 = target[7]
        bubbleTargets()
        if (before7 != target[7]) {
            updateTouchArea()
            startFrames()
        } else {
            invalidate()
        }
    }

    /** Springs: damping 0.8 growing, 0.9 settling closed, stiffness 400 (scaled by the system animation speed). */
    private fun animateTo(w: Float, h: Float, r: Float, a: Float, glass: Float, shadow: Float, cy: Float, grow: Boolean) {
        target[0] = w; target[1] = h; target[2] = r
        target[3] = a; target[4] = glass; target[5] = shadow; target[6] = cy
        bubbleTargets()
        System.arraycopy(cur, 0, from, 0, n)
        // First appearance: start from the camera instead of from nothing at the top-left.
        if (from[6] == 0f) {
            cur[6] = anchorY()
            from[6] = cur[6]
        }
        updateTouchArea()

        val scale = animScale()
        if (scale <= 0f || !isAttachedToWindow) {
            System.arraycopy(target, 0, cur, 0, n)
            vel.fill(0f)
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            animating = false
            onSettled()
            invalidate()
            return
        }
        val k = 400f / (scale * scale)
        damping = if (grow) 0.8f else 0.9f
        stiff[0] = k * 1.15f
        stiff[1] = k
        stiff[2] = k
        stiff[3] = k * 1.8f
        stiff[4] = k
        stiff[5] = k * 0.8f
        stiff[6] = k
        stiff[7] = k * 0.8f
        stiff[8] = k * 0.8f
        startFrames()
    }

    private fun startFrames() {
        if (!animating) {
            if (stiff[0] == 0f) for (i in 0 until n) stiff[i] = 400f
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
            for (i in 0 until n) {
                val k = stiff[i]
                val c = 2f * damping * sqrt(k)
                val acc = -k * (cur[i] - target[i]) - c * vel[i]
                vel[i] += acc * step
                cur[i] += vel[i] * step
            }
            left -= step
        }
        if (!revealed && shapeProgress() >= 0.7f) reveal()
        slot?.translationY = cur[6] - target[6]

        var done = true
        for (i in 0 until n) {
            val tol = if (i <= 2 || i == 6 || i == 8) 0.5f else 0.003f
            if (abs(cur[i] - target[i]) > tol || abs(vel[i]) > tol * 4f) {
                done = false
                break
            }
        }
        if (done) {
            System.arraycopy(target, 0, cur, 0, n)
            vel.fill(0f)
            animating = false
            lastFrameNanos = 0L
            slot?.translationY = 0f
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

    /** The touch window follows the target shape (and the bubble); it draws nothing. */
    private fun updateTouchArea() {
        if (mode == Mode.HIDDEN || target[3] <= 0f) {
            host.setTouchArea(null)
            return
        }
        pillRect(target[0], target[1], target[6], touchRect)
        if (target[7] > 0.5f) {
            val d = target[8]
            touchRect.right = bubbleCenterX(target[0], d) + d / 2f
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
    // Drawing
    // =====================================================================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val a = cur[3].coerceIn(0f, 1f)
        if (a <= 0.01f || cur[0] < 1f || cur[1] < 1f) return
        val w = cur[0]
        val h = cur[1]
        pillRect(w, h, cur[6], rect)
        val r = min(max(cur[2], 0f), h / 2f)
        val glass = cur[4].coerceIn(0f, 1f)

        val shadow = cur[5].coerceIn(0f, 1f)
        if (shadow > 0.02f) {
            fillPaint.setShadowLayer(dpf(20f), 0f, dpf(8f), Color.argb((140 * shadow * a).toInt(), 0, 0, 0))
        } else {
            fillPaint.clearShadowLayer()
        }
        // Pure black (melts into the camera); open cards get a touch of glass if the user wants it.
        val fillAlpha = 255 - (90 * glass).toInt()
        fillPaint.color = Color.argb((fillAlpha * a).toInt(), (14 * glass).toInt(), (14 * glass).toInt(), (18 * glass).toInt())
        canvas.drawRoundRect(rect, r, r, fillPaint)

        if (glass > 0.01f) {
            sheenPaint.shader = LinearGradient(
                0f, rect.top, 0f, rect.top + min(h, dpf(90f)),
                Color.argb((26 * glass * a).toInt(), 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, r, r, sheenPaint)
            borderPaint.shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                Color.argb((70 * glass * a).toInt(), 255, 255, 255),
                Color.argb((16 * glass * a).toInt(), 255, 255, 255),
                Shader.TileMode.CLAMP,
            )
            val inset = borderPaint.strokeWidth / 2f
            rect.inset(inset, inset)
            canvas.drawRoundRect(rect, r - inset, r - inset, borderPaint)
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
        val ba = (cur[7] * a).coerceIn(0f, 1f)
        if (ba <= 0.01f) return
        val d = cur[8]
        val bx = bubbleCenterX(pillW, d)
        val by = cur[6]
        bubblePaint.alpha = (255 * ba).toInt()
        canvas.drawCircle(bx, by, d / 2f, bubblePaint)

        val inner = d / 2f - dpf(5f)
        if (b.image != null) {
            val img = b.image
            val shader = BitmapShader(img, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val s = (inner * 2f) / min(img.width, img.height)
            matrix.reset()
            matrix.setScale(s, s)
            matrix.postTranslate(bx - img.width * s / 2f, by - img.height * s / 2f)
            shader.setLocalMatrix(matrix)
            bubbleImagePaint.shader = shader
            bubbleImagePaint.alpha = (255 * ba).toInt()
            canvas.drawCircle(bx, by, inner, bubbleImagePaint)
        } else if (b.icon != 0) {
            val dr = context.getDrawable(b.icon)
            if (dr != null) {
                val half = (inner * 0.8f).toInt()
                dr.setBounds((bx - half).toInt(), (by - half).toInt(), (bx + half).toInt(), (by + half).toInt())
                dr.setTint(b.accent)
                dr.alpha = (255 * ba).toInt()
                dr.draw(canvas)
            }
        }
        if (b.progress >= 0f) {
            bubbleRing.strokeWidth = dpf(2.2f)
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

    private fun swapContent(view: View?, w: Int, h: Int, radius: Float, cy: Float = 0f) {
        slot?.let { old ->
            old.animate().cancel()
            old.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(90L).withEndAction { removeView(old) }.start()
        }
        slot = null
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
    // Small building blocks
    // =====================================================================

    private fun text(sp: Float, color: Int, bold: Boolean): TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
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

    /** Left / right slots of a compact pill (8dp and 12dp in, like Smart Island). */
    private fun compactFrame(left: View?, right: View?, leftSize: Int = ViewGroup.LayoutParams.WRAP_CONTENT, rightW: Int = ViewGroup.LayoutParams.WRAP_CONTENT, rightH: Int = ViewGroup.LayoutParams.WRAP_CONTENT): View {
        val root = FrameLayout(context)
        if (left != null) {
            root.addView(left, LayoutParams(leftSize, leftSize, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(8) })
        }
        if (right != null) {
            root.addView(right, LayoutParams(rightW, rightH, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(12) })
        }
        return root
    }

    /** Space above card content so nothing sits under the camera. */
    private fun padTopFor(top: Float): Int {
        val notchBottom = geometry.notch?.bottom?.toFloat() ?: 0f
        return max(dp(16), (notchBottom - top + dpf(8f)).toInt())
    }

    private fun pillButton(label: String, bg: Int, fg: Int, action: () -> Unit): TextView = text(13f, fg, true).apply {
        text = label
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(9), dp(16), dp(9))
        background = GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dpf(20f)
        }
        setOnClickListener {
            haptic()
            action()
        }
    }

    // =====================================================================
    // Compact builders
    // =====================================================================

    private fun buildCompactMusic(info: MusicInfo, h: Int): View {
        val artSize = dp(22)
        val art = roundImage(info.art, artSize / 2f, info.accent, R.drawable.ic_note)
        artImage = art
        val w = WaveformView(context).apply {
            color = info.accent
            playing = info.playing
            still = lowPower
        }
        wave = w
        return compactFrame(art, w, artSize, dp(13), dp(14))
    }

    private fun buildPop(pop: Pop): View {
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (pop.icon != 0) left.addView(icon(pop.icon, pop.accent), LinearLayout.LayoutParams(dp(16), dp(16)))
        left.addView(text(12f, Color.WHITE, true).apply {
            text = pop.title
            maxWidth = dp(70)
            setPadding(dp(5), 0, 0, 0)
        })
        val right = text(12.5f, pop.accent, true).apply {
            text = pop.value
            maxWidth = dp(64)
        }
        return compactFrame(left, right)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun buildCompactCall(c: CallInfo): View {
        val t = text(12.5f, Colors.GREEN, true)
        timerText = t
        return compactFrame(icon(R.drawable.ic_call, Colors.GREEN), t, dp(16))
    }

    private fun buildCompactTimer(t: TimerInfo): View {
        val tv = text(12.5f, Colors.ORANGE, true)
        timerText = tv
        return compactFrame(icon(R.drawable.ic_timer, Colors.ORANGE), tv, dp(16))
    }

    private fun buildCompactTransfer(t: TransferInfo, h: Int): View {
        val r = ProgressRing(context).apply {
            color = Colors.BLUE
            progress = t.progress
        }
        ring = r
        return compactFrame(roundImage(t.icon, dpf(11f), Colors.BLUE, R.drawable.ic_download), r, dp(22), dp(18), dp(18))
    }

    private fun buildCompactNav(nv: NavInfo): View {
        val arrow = if (nv.arrow != null) {
            ImageView(context).apply { setImageBitmap(nv.arrow) }
        } else {
            icon(R.drawable.ic_nav, Colors.GREEN)
        }
        val right = text(12.5f, Color.WHITE, true).apply {
            text = nv.primary
            maxWidth = dp(70)
        }
        navText = right
        return compactFrame(arrow, right, dp(20))
    }

    // =====================================================================
    // Expanded builders
    // =====================================================================

    private fun cardColumn(top: Float): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), padTopFor(top), dp(16), dp(16))
    }

    /** Android-style media card: blurred cover behind, dark scrim, big round play button. */
    private fun buildExpandedMusic(info: MusicInfo, top: Float): View {
        val root = FrameLayout(context)
        val useBackdrop = artBackdrop && info.art != null && !lowPower
        if (useBackdrop) {
            root.addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(BitmapFx.blur(info.art) ?: info.art)
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            root.addView(View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.argb(150, 0, 0, 0), Color.argb(215, 0, 0, 0)),
                )
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        val col = cardColumn(top)

        // App badge row
        val badgeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        badgeRow.addView(roundImage(info.appIcon, dpf(6f), info.accent, R.drawable.ic_note), LinearLayout.LayoutParams(dp(20), dp(20)))
        badgeRow.addView(text(12f, Color.argb(190, 255, 255, 255), false).apply {
            text = info.appName.ifEmpty { "Now playing" }
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val w = WaveformView(context).apply {
            color = info.accent
            playing = info.playing
            still = lowPower
        }
        badgeRow.addView(w, LinearLayout.LayoutParams(dp(13), dp(14)))
        wave = w
        col.addView(badgeRow)

        // Art, title, play
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val art = roundImage(info.art, dpf(12f), info.accent, R.drawable.ic_note)
        art.setOnClickListener { openCurrent() }
        artImage = art
        row.addView(art, LinearLayout.LayoutParams(dp(48), dp(48)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { openCurrent() }
        }
        texts.addView(text(16f, Color.WHITE, true).apply { text = info.title })
        texts.addView(text(13f, Color.argb(178, 255, 255, 255), false).apply {
            text = info.artist
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val play = ImageView(context).apply {
            setImageResource(if (info.playing) R.drawable.ic_pause else R.drawable.ic_play)
            setColorFilter(Color.BLACK)
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(info.accent)
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
        row.addView(play, LinearLayout.LayoutParams(dp(48), dp(48)))
        col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        // prev | seek | next
        val seekRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        seekRow.addView(iconButton(R.drawable.ic_prev) { currentMusic()?.controller?.transportControls?.skipToPrevious() })
        val mid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val bar = ProgressLine(context).apply { color = Color.WHITE }
        mid.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(12) })
        progress = bar
        val times = FrameLayout(context)
        val el = text(10f, Color.argb(160, 255, 255, 255), false)
        val rem = text(10f, Color.argb(160, 255, 255, 255), false)
        times.addView(el, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.START))
        times.addView(rem, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END))
        mid.addView(times, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        elapsedText = el
        remainText = rem
        seekRow.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        })
        seekRow.addView(iconButton(R.drawable.ic_next) { currentMusic()?.controller?.transportControls?.skipToNext() })
        col.addView(seekRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

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

    private fun bounce(v: View) {
        v.animate().scaleX(0.86f).scaleY(0.86f).setDuration(70L).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
        }.start()
    }

    private fun buildNote(nf: NotifInfo, top: Float): View {
        val col = cardColumn(top)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(roundImage(nf.icon, dpf(12f), nf.accent, R.drawable.ic_check), LinearLayout.LayoutParams(dp(42), dp(42)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(text(15f, Color.WHITE, true).apply { text = nf.title }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(text(11f, Color.argb(140, 255, 255, 255), false).apply {
            text = nf.appName
            setPadding(dp(8), 0, 0, 0)
        })
        texts.addView(head)
        texts.addView(text(13.5f, Color.argb(205, 255, 255, 255), false).apply {
            text = nf.text
            maxLines = 2
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)
        return col
    }

    private fun buildExpandedCall(c: CallInfo, top: Float): View {
        val col = cardColumn(top)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(roundImage(c.avatar, dpf(22f), Colors.GREEN, R.drawable.ic_call), LinearLayout.LayoutParams(dp(44), dp(44)))
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
        if (c.hangUp != null) row.addView(roundButton(R.drawable.ic_call_end, Colors.RED) { host.send(c.hangUp) })
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
            bounce(it)
            action()
        }
    }

    private fun buildExpandedTimer(t: TimerInfo, top: Float): View {
        val col = cardColumn(top)
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(icon(R.drawable.ic_timer, Colors.ORANGE), LinearLayout.LayoutParams(dp(18), dp(18)))
        head.addView(text(13f, Color.argb(190, 255, 255, 255), false).apply {
            text = t.label
            setPadding(dp(8), 0, 0, 0)
        })
        col.addView(head)
        val big = text(40f, Colors.ORANGE, false).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setPadding(0, dp(6), 0, dp(6))
        }
        timerText = big
        col.addView(big)
        if (t.actions.isNotEmpty()) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, pi) in t.actions) {
                row.addView(pillButton(label, Color.argb(40, 255, 255, 255), Color.WHITE) { host.send(pi) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
            }
            col.addView(row)
        }
        return col
    }

    private fun buildExpandedTransfer(t: TransferInfo, top: Float): View {
        val col = cardColumn(top)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(roundImage(t.icon, dpf(10f), Colors.BLUE, R.drawable.ic_download), LinearLayout.LayoutParams(dp(40), dp(40)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        texts.addView(text(15f, Color.WHITE, true).apply { text = t.title })
        texts.addView(text(12f, Color.argb(160, 255, 255, 255), false).apply {
            text = t.appName
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pct = text(14f, Colors.BLUE, true).apply { text = if (t.progress >= 0f) "${(t.progress * 100).toInt()}%" else "" }
        percentText = pct
        row.addView(pct)
        col.addView(row)
        val bar = ProgressLine(context).apply {
            color = Colors.BLUE
            fraction = max(0f, t.progress)
        }
        progress = bar
        col.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)).apply { topMargin = dp(14) })
        return col
    }

    private fun buildExpandedNav(nv: NavInfo, top: Float): View {
        val col = cardColumn(top)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val arrow = if (nv.arrow != null) ImageView(context).apply { setImageBitmap(nv.arrow) } else icon(R.drawable.ic_nav, Colors.GREEN)
        row.addView(arrow, LinearLayout.LayoutParams(dp(44), dp(44)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        val main = text(19f, Color.WHITE, true).apply { text = nv.primary }
        navText = main
        texts.addView(main)
        val sub = text(13f, Color.argb(178, 255, 255, 255), false).apply {
            text = nv.secondary
            maxLines = 2
            setPadding(0, dp(4), 0, 0)
        }
        navSub = sub
        texts.addView(sub)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)
        return col
    }

    private fun buildPanel(w: Int, top: Float): View {
        val items = host.panelItems()
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), padTopFor(top), dp(12), dp(12))
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
        val inner = w - dp(24)
        list.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        header.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val room = (panelMaxH() - padTopFor(top) - dp(12) - header.measuredHeight).toInt()
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
                setColor(Color.argb(28, 255, 255, 255))
                cornerRadius = dpf(22f)
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
        card.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        return card
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
        if (mode != Mode.EXPANDED) return
        val pos = m.currentPosition()
        val dur = m.durationMs
        progress?.fraction = if (dur > 0) pos.toFloat() / dur else 0f
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
        if (bubble == null) return false
        if (cur[7] < 0.5f) return false
        val d = cur[8]
        val bx = bubbleCenterX(cur[0], d)
        val dx = x - bx
        val dy = y - cur[6]
        return dx * dx + dy * dy <= (d / 2f + dpf(4f)) * (d / 2f + dpf(4f))
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
                pillRect(cur[0], cur[1], cur[6], rect)
                pivotX = rect.centerX()
                pivotY = rect.centerY()
                if (mode != Mode.PANEL && mode != Mode.EXPANDED) animate().scaleX(0.96f).scaleY(0.96f).setDuration(80L).start()
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
