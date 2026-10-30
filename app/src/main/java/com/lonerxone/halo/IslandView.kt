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
        fun clearAll()
        fun torchOff()
        fun dismissNotification(key: String)
        /** Things switched on that the notification center can switch off. */
        fun quickStates(): List<Quick>
        fun quickAction(id: String)
        /** The buttons beside "Notifications" (up to four, chosen in settings). */
        fun controlStates(): List<Controls.State>
        fun controlTap(id: String)
        /** Calls: microphone mute (true = muted now), and answer / end with Android's call service as a fallback. */
        fun toggleMute(): Boolean
        fun isMuted(): Boolean
        fun answerCall(intent: PendingIntent?)
        fun endCall(intent: PendingIntent?)
        /** Screen recording: pause / resume, and stop. */
        fun recPause()
        fun recStop()
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
        data class Torch(val cameraId: String) : Content()
        /** The lock screen: a padlock; [unlocked] = the moment it springs open. */
        data class Lock(val unlocked: Boolean) : Content()
        /** A screen recording (Halo's or another app's): red dot and timer. */
        data class Rec(val rec: RecInfo) : Content()
    }

    /** On the lock screen: the notification center stays shut (your notifications are private). */
    var onLockScreen = false

    /** A second live activity shown as a small circle beside the pill. */
    class Bubble(
        val key: String,
        val image: Bitmap?,
        val icon: Int,
        val accent: Int,
        val progress: Float,
        /** A small companion (the bell), rather than a full second activity. */
        val small: Boolean = false,
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
    /** Battery saver is on: update clocks less often. */
    var saver = false
    /** The user's pictures: one fills the island, one sits behind the notification center. */
    var islandPic: Bitmap? = null
        set(value) {
            field = value
            islandShader = value?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            invalidate()
        }
    var panelPic: Bitmap? = null
        set(value) {
            field = value
            panelShader = value?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            invalidate()
        }
    /** 0..0.9: black laid over the pictures so text stays readable. */
    var picDim = 0.45f
    private var islandShader: BitmapShader? = null
    private var panelShader: BitmapShader? = null
    private val picPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val picMatrix = Matrix()
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
    // 10 = the x the pill is centred on (before it is kept on screen).
    // 11 = how much of the music cover shows (0 = your island picture, 1 = the cover).
    private val springs = Array(12) { i -> Spring(0f, if (i in setOf(0, 1, 2, 6, 8, 10)) 0.4f else 0.002f) }
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
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowRect = RectF()
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
            updateCharge()
            if (mode == Mode.EXPANDED && content is Content.Popup) IslandStore.holdPop(4_000L)
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
            if (needsTicker()) postDelayed(this, if (lowPower || saver) 1000L else 500L)
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

    /**
     * True when the camera is near the middle of the top edge, so the pill can
     * wrap around it. A punch-hole in a corner (or far off-centre) can't be
     * wrapped: the pill then sits in the middle and the circle stays on the lens.
     */
    private fun wraps(): Boolean {
        val n = geometry.notch ?: return false
        return kotlin.math.abs(n.exactCenterX() - geometry.screenW / 2f) <= geometry.screenW * 0.12f
    }

    /** Camera width the pill has to make room for (0 when it doesn't wrap the camera). */
    private fun wrapW(): Int = if (wraps()) notchW() else 0

    private fun idleLike(m: Mode) = m == Mode.IDLE || m == Mode.HIDDEN

    /** Where a pill in mode [m] wants its centre, before it is kept on screen. */
    private fun anchorXFor(m: Mode): Float = when {
        idleLike(m) -> notchCx()
        wraps() -> notchCx() + offsetX
        else -> geometry.screenW / 2f + offsetX
    }

    /** Space the round bubble takes to the right of the pill. */
    private fun bubbleExtra(): Float =
        if (bubble != null && mode == Mode.COMPACT) (if (bubble?.small == true) dpf(5f) else dpf(6f)) + bubbleD() else 0f

    /** Keeps a [w]-wide pill (and its bubble) fully on screen, 8dp from the edges. */
    private fun clampCx(anchor: Float, w: Float): Float {
        val m = if (idleLike(mode)) 0f else dpf(8f)
        val extra = bubbleExtra()
        val lo = m + w / 2f
        val hi = geometry.screenW - m - w / 2f - extra
        return if (lo > hi) (geometry.screenW - extra) / 2f else anchor.coerceIn(lo, hi)
    }

    /** Camera outline preview from Settings → Camera fit. */
    private var previewUntil = 0L
    private fun previewing() = android.os.SystemClock.uptimeMillis() < previewUntil

    fun previewCamera() {
        previewUntil = android.os.SystemClock.uptimeMillis() + 4_000L
        rebuild()
        postDelayed({ rebuild() }, 4_100L)
    }

    private fun idleD(): Float = if (geometry.notch != null) max(min(notchW(), notchH()).toFloat(), dpf(20f)) else dpf(22f)
    private fun compactH(): Float = dpf(34f) * sizeScale
    private fun baseCompactW(): Float = max(dpf(123f) * sizeScale, wrapW() + dpf(86f))
    private fun expandedW(): Float = min(dpf(372f), geometry.screenW - dpf(16f)) * min(sizeScale, 1.05f)
    private fun panelMaxH(): Float = geometry.screenH * 0.62f
    private fun gapTop(): Float = dpf(6f)

    private fun cyFor(m: Mode, h: Float): Float = when (m) {
        Mode.IDLE, Mode.HIDDEN -> anchorY()
        else -> max(anchorY(), gapTop() + h / 2f) + offsetY
    }

    private fun pillRect(w: Float, h: Float, cy: Float, out: RectF, anchor: Float = v(10)) {
        val l = clampCx(anchor, w) - w / 2f
        out.set(l, cy - h / 2f, l + w, cy + h / 2f)
    }

    /** The bell is a small companion; a second activity gets a full pill-height circle. */
    private fun bubbleD(): Float = if (bubble?.small == true) dpf(26f) * sizeScale else compactH()

    private fun bubbleCenterX(pillW: Float, d: Float, anchor: Float = v(10)): Float =
        clampCx(anchor, pillW) + pillW / 2f + (if (bubble?.small == true) dpf(5f) else dpf(6f)) + d / 2f

    // =====================================================================
    // State machine
    // =====================================================================

    private fun canExpand(c: Content) =
        c is Content.Music || c is Content.Call || c is Content.Timer || c is Content.Transfer || c is Content.Nav || c is Content.Rec ||
            (c is Content.Popup && c.pop.kind == "charge")

    private fun needsTicker() =
        mode != Mode.HIDDEN && (mode == Mode.EXPANDED || mode == Mode.PANEL || content is Content.Call || content is Content.Timer || content is Content.Rec)

    private fun desiredMode(): Mode {
        if (hidden) return Mode.HIDDEN
        val c = content
        if (c is Content.Call && c.call.ringing) return Mode.EXPANDED
        if (panelOpen) return Mode.PANEL
        return when (c) {
            is Content.None -> if (idleEnabled || previewing()) Mode.IDLE else Mode.HIDDEN
            is Content.Note -> Mode.EXPANDED
            is Content.Popup -> if (expanded && c.pop.kind == "charge") Mode.EXPANDED else Mode.COMPACT
            is Content.Torch -> Mode.COMPACT
            is Content.Lock -> Mode.COMPACT
            else -> if (expanded) Mode.EXPANDED else Mode.COMPACT
        }
    }

    private fun contentKey(m: Mode): String {
        if (m == Mode.PANEL) return "panel:" + host.quickStates().joinToString(",") { it.id + it.label } + "|" + openGroups.sorted().joinToString(",")
        return when (val c = content) {
            is Content.None -> "none"
            is Content.Music -> "m:" + c.info.trackKey
            is Content.Popup -> "p:" + c.pop.id
            is Content.Note -> "n:" + c.note.id
            // Locked and unlocked are two looks: switching rebuilds, so the island
            // springs wider for the tick, like a moment of its own.
            is Content.Lock -> "lock:" + c.unlocked
            is Content.Call -> "c:" + c.call.key + ":" + c.call.ringing + ":" + c.call.name
            is Content.Timer -> "t:" + c.timer.key + ":" + (c.timer.anchor == 0L) + ":" + c.timer.actions.size
            is Content.Transfer -> "d:" + c.transfer.key
            is Content.Nav -> "g:" + c.nav.key
            is Content.Torch -> "f:" + c.cameraId
            is Content.Rec -> "r:" + c.rec.key + ":" + c.rec.paused + ":" + (c.rec.countdownUntil > 0L)
        }
    }

    private fun clearRefs() {
        recPulse?.cancel()
        recPulse = null
        recCount?.cancel()
        recCount = null
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
        chargePct = null
        chargeBar = null
        chargeInfo = null
        chargeRing = null
        chargeHow = null
        chargeAnim?.cancel()
        chargeAnim = null
        lockGlyph = null
        unlockCheck = null
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
        // The beat listener only runs while the bars are actually on screen.
        if (content !is Content.Music || m == Mode.PANEL || m == Mode.HIDDEN) Beat.ensure(context, false)

        when (m) {
            Mode.HIDDEN -> {
                swapContent(null, 0, 0, 0f, 0f)
                val d = idleD()
                animateTo(d * 0.6f, d * 0.6f, d * 0.3f, 0f, 0f, 0f, 0f, cyFor(m, d), grow = false)
            }
            Mode.IDLE -> {
                swapContent(null, 0, 0, 0f, 0f)
                val d = idleD()
                // No camera to hide behind (bezel phones): keep the tap zone, draw nothing.
                val a = if (geometry.notch == null && !previewing()) 0f else 1f
                animateTo(d, d, d / 2f, a, 0f, 0f, 0f, cyFor(m, d), grow = false)
            }
            Mode.COMPACT -> {
                val h = compactH()
                val sides = when (val c = content) {
                    is Content.Music -> compactMusic(c.info)
                    is Content.Popup -> compactPop(c.pop).also { glowColor = if (c.pop.accent == 0) tone(c.pop) else c.pop.accent; glow = 1f }
                    is Content.Call -> compactCall(c.call).also { glowColor = Colors.GREEN; glow = 0.55f }
                    is Content.Timer -> compactTimer()
                    is Content.Transfer -> compactTransfer(c.transfer)
                    is Content.Nav -> compactNav(c.nav)
                    is Content.Torch -> compactTorch().also { glowColor = Colors.INK; glow = 0.5f }
                    is Content.Lock -> compactLock(c)
                    is Content.Rec -> compactRec(c.rec).also { glowColor = Colors.RED; if (c.rec.paused || c.rec.countdownUntil > 0L) glow = 0.2f }
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
                // Rebuilding the open panel (a group fanned out, a card cleared):
                // swap instantly and keep your place, no fade-out/fade-in blink.
                panelQuiet = previous == Mode.PANEL && m == Mode.PANEL
                if (panelQuiet) {
                    savedPanelY = panelScrollView?.scrollY ?: 0
                    pendingSlide = 1
                }
                val view = if (m == Mode.PANEL) {
                    buildPanel(w.toInt(), top)
                } else {
                    when (val c = content) {
                        is Content.Music -> buildExpandedMusic(c.info, w.toInt(), top)
                        is Content.Note -> buildNote(c.note, w.toInt(), top).also { glowColor = Colors.INK; glow = 0.35f }
                        is Content.Call -> buildExpandedCall(c.call, w.toInt(), top).also { glowColor = Colors.INK; glow = 0.6f }
                        is Content.Timer -> buildExpandedTimer(c.timer, w.toInt(), top)
                        is Content.Transfer -> buildExpandedTransfer(c.transfer, w.toInt(), top)
                        is Content.Nav -> buildExpandedNav(c.nav, w.toInt(), top)
                        is Content.Rec -> buildExpandedRec(c.rec, w.toInt(), top).also { glowColor = Colors.RED; glow = 0.4f }
                        is Content.Popup -> buildCharge(w.toInt(), top).also { glowColor = Colors.GREEN; glow = 0.6f }
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
                // A message shown during Do Not Disturb arrives without a tap.
                if ((content as? Content.Note)?.note?.silent != true) haptic()
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
            is Content.Lock -> Unit
            is Content.Music -> {
                val info = c.info
                wave?.playing = info.playing
                wave?.color = Colors.INK
                Beat.ensure(context, info.playing && !lowPower)
                if (mode == Mode.EXPANDED) {
                    setCover(info)
                    springs[11].to(coverTarget(), k * 0.22f, 1f)
                    startFrames()
                }
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

    private var panelQuiet = false
    private var panelList: LinearLayout? = null
    private var panelGroups: Map<String, List<PanelItem>> = emptyMap()
    private var panelChromeH = 0
    private var panelRoom = 0

    /** The views for one app's notifications: a stack, or a header and every card. */
    private fun groupViews(pkg: String, group: List<PanelItem>, open: Boolean): List<View> {
        if (group.size == 1) return listOf(panelCard(group[0]).apply { tag = "g:$pkg" })
        if (!open) return listOf(stackedCard(pkg, group).apply { tag = "g:$pkg" })
        val out = ArrayList<View>()
        out += groupHeader(pkg, group).apply { tag = "gh:$pkg" }
        group.forEachIndexed { i, item ->
            out += panelCard(item).also { v ->
                // One app's cards sit close together; different apps get more air.
                (v.layoutParams as LinearLayout.LayoutParams).topMargin = dp(if (i == 0) 8 else 6)
                v.tag = "gc:$pkg"
            }
        }
        return out
    }

    /**
     * Fan a stack out, or fold it back, right where it is (no rebuild).
     * Everything is measured before and after (FLIP): the cards slide out
     * from under the stack one after another and settle with a little
     * bounce; the cards below glide down to make room; the island grows
     * with them. Folding runs it backwards: the cards slide up into the
     * stack and fade, and everything below glides up.
     */
    private fun toggleGroup(pkg: String, open: Boolean) {
        val list = panelList ?: return
        val group = panelGroups[pkg] ?: return
        val idx = (0 until list.childCount).firstOrNull { (list.getChildAt(it).tag as? String)?.endsWith(":$pkg") == true } ?: return
        haptic()
        // Before: where every view is now.
        val before = HashMap<View, Int>()
        for (i in 0 until list.childCount) list.getChildAt(i).let { before[it] = it.top + it.translationY.toInt() }
        val old = (0 until list.childCount).map { list.getChildAt(it) }.filter { (it.tag as? String)?.endsWith(":$pkg") == true }
        val anchorTop = old.first().top
        if (open) openGroups.add(pkg) else openGroups.remove(pkg)
        for (v in old) {
            v.animate().cancel()
            list.removeView(v)
        }
        val fresh = groupViews(pkg, group, open)
        fresh.forEachIndexed { i, v -> list.addView(v, idx + i) }
        // Folding: the old cards stay on screen for a moment as ghosts, sliding up into the stack.
        if (!open) {
            old.forEachIndexed { i, v ->
                if (i == 0) return@forEachIndexed
                list.overlay.add(v)
                v.animate().translationY((anchorTop - v.top).toFloat()).alpha(0f).scaleX(0.94f).scaleY(0.94f)
                    .setStartDelay(((old.size - i) * 18L).coerceAtMost(90L)).setDuration(240L)
                    .setInterpolator(DecelerateInterpolator(2f))
                    .withEndAction { list.overlay.remove(v) }.start()
            }
        }
        // Keep the panel the same kind of thing so a later refresh doesn't rebuild it.
        builtKey = mode.name + "/" + contentKey(mode)
        list.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                list.viewTreeObserver.removeOnPreDrawListener(this)
                val spring = android.view.animation.OvershootInterpolator(1.15f)
                // Everything that was already there glides from where it was.
                for (i in 0 until list.childCount) {
                    val v = list.getChildAt(i)
                    val was = before[v] ?: continue
                    val d = (was - v.top).toFloat()
                    if (abs(d) < 1f) continue
                    v.translationY = d
                    v.animate().translationY(0f).setStartDelay(0L).setDuration(340L).setInterpolator(spring).start()
                }
                // The new views come out of the stack.
                var k = 0
                for (v in fresh) {
                    val from = (anchorTop - v.top).toFloat() + if (open && k > 0) dpf(8f) * min(k, 2) else 0f
                    v.translationY = from
                    val isHeader = (v.tag as? String)?.startsWith("gh:") == true
                    if (open && (k > 0 || isHeader)) {
                        v.alpha = 0f
                        v.scaleX = 0.94f
                        v.scaleY = 0.94f
                    }
                    v.animate().translationY(0f).alpha(1f).scaleX(1f).scaleY(1f)
                        .setStartDelay(if (open) k * 32L else 40L).setDuration(if (open) 380L else 300L)
                        .setInterpolator(spring).start()
                    if (!isHeader) k++
                }
                return true
            }
        })
        resizePanel()
    }

    /** The list changed size: the island grows or shrinks to fit (up to its limit), smoothly. */
    private fun resizePanel() {
        if (mode != Mode.PANEL) return
        val list = panelList ?: return
        val scroll = panelScrollView ?: return
        val s = slot ?: return
        val w = expandedW()
        list.measure(MeasureSpec.makeMeasureSpec(w.toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val sh = min(list.measuredHeight, panelRoom)
        val h = (panelChromeH + sh).toFloat()
        if (abs(h - t(1)) < 1f) return
        scroll.layoutParams = scroll.layoutParams.apply { height = sh }
        val cy = cyFor(Mode.PANEL, h)
        val growing = h > t(1)
        // Growing: the content is laid out at the new size first and revealed as the shape opens.
        s.layoutParams = (s.layoutParams as LayoutParams).apply {
            height = h.toInt()
            topMargin = (cy - h / 2f).toInt()
        }
        val r = min(dpf(32f), h / 2f)
        animateTo(w, h, r, 1f, glassStrength, if (lowPower) 0f else 1f, 1f, cy, grow = growing)
    }

    private var savedPanelY = 0
    private companion object {
        /** Groups built right away when the panel opens; the rest follow a moment later. */
        const val FIRST_GROUPS = 6
    }
    private var panelScrollView: ScrollView? = null

    /** Apps whose stacked notifications you've fanned out in the panel. */
    private val openGroups = HashSet<String>()

    /** Set while Clear all is sweeping the cards away, so taps don't race it. */
    private var clearing = false

    fun openPanel() {
        if (onLockScreen) return
        openGroups.clear()
        clearing = false
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
        springs[7].to(if (show) 1f else 0f, k * 0.9f, 0.7f)
        springs[8].to(if (show) bubbleD() else bubbleD() * 0.4f, k * 0.9f, 0.6f)
        if (before != t(7)) {
            updateTouchArea()
            startFrames()
        } else {
            invalidate()
        }
    }

    /**
     * Opening: damping 0.8 (a small, lively overshoot). Closing: 0.92 (settles
     * without bounce). Stiffness 460, scaled by the system animation speed.
     * Width leads height a little for the organic stretch.
     */
    private fun animateTo(w: Float, h: Float, r: Float, a: Float, glass: Float, shadow: Float, edge: Float, cy: Float, grow: Boolean) {
        val scale = animScale()
        // Snappier and bouncier: a quick lively overshoot opening, and a
        // small settle-bounce closing too, so nothing ever feels stiff.
        k = if (scale <= 0f) 540f else 540f / (scale * scale)
        zeta = if (grow) 0.62f else 0.78f
        // First appearance grows out of the camera.
        if (springs[6].value == 0f) springs[6].snap(anchorY())
        if (springs[10].value == 0f) springs[10].snap(notchCx())
        startW[0] = v(0)
        startW[1] = v(1)
        // Opening: width leads height. Closing: height leads width. Both read as one organic motion.
        springs[0].to(w, if (grow) k * 1.15f else k, zeta)
        springs[1].to(h, if (grow) k else k * 1.2f, zeta)
        springs[2].to(r, k, zeta)
        springs[3].to(a, k * 1.8f, 0.9f)
        springs[4].to(glass, k, 1f)
        springs[5].to(shadow, k * 0.8f, 1f)
        springs[6].to(cy, k, zeta)
        springs[10].to(anchorXFor(mode), k, zeta)
        // The cover fades in slowly and calmly (no bounce), so your picture and the
        // cover blend through each other instead of swapping.
        springs[11].to(coverTarget(), k * 0.22f, 1f)
        springs[9].to(edge, k, 1f)
        val show = bubble != null && mode == Mode.COMPACT
        springs[7].to(if (show) 1f else 0f, k * 0.9f, 0.7f)
        springs[8].to(if (show) bubbleD() else bubbleD() * 0.4f, k * 0.9f, 0.6f)
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
        if (!revealed && shapeProgress() >= 0.6f) reveal()
        slot?.translationY = v(6) - t(6)
        updateClip()
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
        updateClip()
        if (!revealed) reveal()
    }

    private val touchRect = RectF()

    private fun updateTouchArea() {
        if (mode == Mode.HIDDEN || (t(3) <= 0f && mode != Mode.IDLE)) {
            host.setTouchArea(null)
            return
        }
        pillRect(t(0), t(1), t(6), touchRect, t(10))
        if (t(7) > 0.5f) {
            val d = t(8)
            touchRect.right = bubbleCenterX(t(0), d, t(10)) + d / 2f
        }
        // A bigger margin around an alert so a sideways swipe is easy to start.
        val extra = when {
            mode == Mode.IDLE -> dpf(10f)
            content is Content.Note -> dpf(14f)
            else -> dpf(2f)
        }
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
        // A real blurred shadow is costly to redraw every frame at a new size
        // (that's what made slower phones stutter as the island opened). While
        // the shape moves, a few soft layers stand in for it; the blur comes
        // back once it settles.
        if (shadow > 0.02f && !animating) {
            fillPaint.setShadowLayer(dpf(22f), 0f, dpf(8f), Color.argb((150 * shadow * a).toInt(), 0, 0, 0))
        } else {
            fillPaint.clearShadowLayer()
            if (shadow > 0.02f) {
                for (i in 1..3) {
                    val g = dpf(5f) * i
                    shadowRect.set(rect.left - g * 0.6f, rect.top + g * 0.4f, rect.right + g * 0.6f, rect.bottom + g * 1.3f)
                    shadowPaint.color = Color.argb((34 * shadow * a / i).toInt(), 0, 0, 0)
                    canvas.drawRoundRect(shadowRect, r + g, r + g, shadowPaint)
                }
            }
        }
        val fillAlpha = 255 - (80 * glass).toInt()
        fillPaint.color = Color.argb((fillAlpha * a).toInt(), (16 * glass).toInt(), (16 * glass).toInt(), (20 * glass).toInt())
        canvas.drawRoundRect(rect, r, r, fillPaint)
        drawPicture(canvas, r, a)

        // Liquid Glass shine: a soft sheen across the top and a rim that
        // catches the light top-left (stronger on open cards, gentle on the
        // pill; the idle circle over the camera stays pure black).
        val edge = v(9).coerceIn(0f, 1f)
        if (mode != Mode.IDLE && mode != Mode.HIDDEN) {
            val grow = ((rect.height() - idleD()) / max(1f, compactH() - idleD())).coerceIn(0f, 1f)
            val shine = a * grow * max(edge, 0.6f)
            if (shine > 0.02f) drawShine(canvas, r, shine)
        } else if (edge > 0.02f) {
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

        if (mode == Mode.IDLE && previewing()) {
            previewPaint.color = Colors.INK
            val inset = previewPaint.strokeWidth / 2f
            canvas.drawCircle(rect.centerX(), rect.centerY(), min(rect.width(), rect.height()) / 2f + inset, previewPaint)
        }

        drawBubble(canvas, w, a)
    }

    // Shine shaders are made once in unit space and stretched to the shape each frame.
    private val sheenShader = LinearGradient(0f, 0f, 0f, 1f,
        intArrayOf(Color.argb(34, 255, 255, 255), Color.argb(10, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(14, 255, 255, 255)),
        floatArrayOf(0f, 0.22f, 0.5f, 0.88f, 1f), Shader.TileMode.CLAMP)
    private val rimShader = LinearGradient(0f, 0f, 1f, 1f,
        intArrayOf(Color.argb(150, 255, 255, 255), Color.argb(26, 255, 255, 255), Color.argb(18, 255, 255, 255), Color.argb(70, 255, 255, 255)),
        floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
    private val shineMatrix = Matrix()
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private fun drawShine(canvas: Canvas, r: Float, amount: Float) {
        val al = (255 * amount).toInt().coerceIn(0, 255)
        shineMatrix.reset()
        shineMatrix.setScale(1f, rect.height())
        shineMatrix.postTranslate(0f, rect.top)
        sheenShader.setLocalMatrix(shineMatrix)
        sheenPaint.shader = sheenShader
        sheenPaint.alpha = al
        canvas.drawRoundRect(rect, r, r, sheenPaint)
        val sw = dpf(1.2f)
        shineMatrix.reset()
        shineMatrix.setScale(rect.width(), rect.height())
        shineMatrix.postTranslate(rect.left, rect.top)
        rimShader.setLocalMatrix(shineMatrix)
        rimPaint.shader = rimShader
        rimPaint.strokeWidth = sw
        rimPaint.alpha = al
        rect.inset(sw / 2f, sw / 2f)
        canvas.drawRoundRect(rect, r - sw / 2f, r - sw / 2f, rimPaint)
        rect.inset(-sw / 2f, -sw / 2f)
    }

    /**
     * The user's picture, centre-cropped to the island's current shape. It
     * fades in as the pill grows out of the camera, so the idle circle stays
     * pure black. The notification center uses its own (portrait) picture.
     */
    private fun drawPicture(canvas: Canvas, r: Float, a: Float) {
        if (mode == Mode.IDLE || mode == Mode.HIDDEN) return
        val grow = ((rect.height() - idleD()) / max(1f, compactH() - idleD())).coerceIn(0f, 1f)
        val f = a * grow
        if (f <= 0.01f) return
        if (mode == Mode.PANEL) {
            val sh = panelShader ?: return
            val bmp = panelPic ?: return
            paintCropped(canvas, sh, bmp, r, f)
            dim(canvas, r, picDim * f)
            return
        }
        // Your island picture underneath...
        val mix = v(11).coerceIn(0f, 1f)
        val ish = islandShader
        val ibmp = islandPic
        if (ish != null && ibmp != null) {
            paintCropped(canvas, ish, ibmp, r, f)
            dim(canvas, r, picDim * f * (1f - mix))
        }
        // ...and the music cover washing in over it.
        val csh = coverShader
        val cbmp = coverBlur
        val wash = coverWash
        if (mix > 0.01f && csh != null && cbmp != null && wash != null) {
            paintCropped(canvas, csh, cbmp, r, f * mix)
            coverMatrix.reset()
            coverMatrix.setScale(1f, rect.height())
            coverMatrix.postTranslate(0f, rect.top)
            wash.setLocalMatrix(coverMatrix)
            washPaint.shader = wash
            washPaint.alpha = (255 * f * mix).toInt()
            canvas.drawRoundRect(rect, r, r, washPaint)
        }
    }

    private fun paintCropped(canvas: Canvas, shader: BitmapShader, bmp: Bitmap, r: Float, alpha: Float) {
        val s = max(rect.width() / bmp.width, rect.height() / bmp.height)
        picMatrix.reset()
        picMatrix.setScale(s, s)
        picMatrix.postTranslate(rect.centerX() - bmp.width * s / 2f, rect.centerY() - bmp.height * s / 2f)
        shader.setLocalMatrix(picMatrix)
        picPaint.shader = shader
        picPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rect, r, r, picPaint)
    }

    private fun dim(canvas: Canvas, r: Float, amount: Float) {
        if (amount <= 0.01f) return
        dimPaint.color = Color.argb((255 * amount).toInt().coerceIn(0, 255), 0, 0, 0)
        canvas.drawRoundRect(rect, r, r, dimPaint)
    }

    // ----- Music cover backdrop -----
    private var coverKey: String? = null
    private var coverBlur: Bitmap? = null
    private var coverShader: BitmapShader? = null
    private var coverWash: android.graphics.LinearGradient? = null
    private val coverMatrix = Matrix()
    private val washPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Blurs the cover once per song and builds its colour wash (cover tint into black). */
    private fun setCover(info: MusicInfo) {
        if (!artBackdrop || lowPower || info.art == null) {
            coverKey = null
            coverBlur = null
            coverShader = null
            return
        }
        if (coverKey == info.trackKey && coverBlur != null) return
        val blur = BitmapFx.blur(info.art) ?: return
        coverKey = info.trackKey
        coverBlur = blur
        coverShader = BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val c = info.accent
        // A unit-height gradient, stretched to the card each frame (no per-frame allocation).
        coverWash = android.graphics.LinearGradient(
            0f, 0f, 0f, 1f,
            intArrayOf(
                Color.argb(120, Color.red(c) / 3, Color.green(c) / 3, Color.blue(c) / 3),
                Color.argb(165, 0, 0, 0),
                Color.argb(225, 0, 0, 0),
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    /**
     * How much cover to show. In the open player it's all cover, or, with your
     * own island picture set, a blend that keeps a hint of your picture.
     */
    private fun coverTarget(): Float {
        val music = content is Content.Music && mode == Mode.EXPANDED && coverBlur != null
        if (!music) return 0f
        return if (islandPic != null) 0.8f else 1f
    }

    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
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

        val inner = d / 2f - if (b.small) dpf(4f) else dpf(5f)
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
                dr.setTint(Colors.INK)
                dr.alpha = (255 * ba).toInt()
                dr.draw(canvas)
            }
        }
        if (b.progress >= 0f) {
            bubbleRing.color = Colors.INK
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
        val slide = pendingSlide
        pendingSlide = 0
        slot?.let { old ->
            old.animate().cancel()
            // Swiping between alerts: the old one has already slid away.
            if (slide != 0) removeView(old) else old.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(110L).withLayer().withEndAction { removeView(old) }.start()
        }
        slot = null
        if (view == null) {
            revealed = true
            return
        }
        val s = FrameLayout(context).apply {
            alpha = 0f
            tag = SLOT_TAG
            // Clipped to the island's shape *as it is right now* (not its final
            // size): while it grows or shrinks, nothing spills over its edges.
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    if (liveRect.isEmpty) {
                        outline.setRoundRect(0, 0, v.width, v.height, radius)
                        return
                    }
                    val ox = v.left + v.translationX
                    val oy = v.top + v.translationY
                    val l = (liveRect.left - ox).toInt().coerceIn(0, v.width)
                    val t = (liveRect.top - oy).toInt().coerceIn(0, v.height)
                    val r = (liveRect.right - ox).toInt().coerceIn(l, v.width)
                    val b = (liveRect.bottom - oy).toInt().coerceIn(t, v.height)
                    outline.setRoundRect(l, t, r, b, liveR.coerceAtMost(minOf(r - l, b - t) / 2f))
                }
            }
            clipToOutline = true
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        val lp = LayoutParams(w, h, Gravity.TOP or Gravity.START).apply {
            leftMargin = (clampCx(anchorXFor(mode), w.toFloat()) - w / 2f).toInt()
            topMargin = (cy - h / 2f).toInt()
        }
        addView(s, lp)
        slot = s
        revealed = false
        // Settles in from a touch smaller, so content blooms with the shape instead of just appearing.
        s.scaleX = 0.92f
        s.scaleY = 0.92f
        updateClip()
        if (slide != 0) {
            // Swiped in: the new alert is already exactly where the swipe left it.
            s.alpha = 1f
            s.scaleX = 1f
            s.scaleY = 1f
            revealed = true
        }
    }

    private fun reveal() {
        revealed = true
        slot?.animate()?.alpha(1f)?.scaleX(1f)?.scaleY(1f)?.setInterpolator(android.view.animation.OvershootInterpolator(1.6f))?.setDuration(320L)?.withLayer()?.start()
    }

    /** The island's current shape on screen (in this view), for clipping its contents. */
    private val liveRect = RectF()
    private var liveR = 0f

    private fun updateClip() {
        if (v(0) < 1f || v(1) < 1f) {
            liveRect.setEmpty()
        } else {
            pillRect(v(0), v(1), v(6), liveRect)
            liveR = min(max(v(2), 0f), v(1) / 2f)
        }
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.tag == SLOT_TAG) c.invalidateOutline()
        }
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
                background = GradientDrawable().apply { setColor(Colors.GRAPHITE) }
                setImageResource(placeholder)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
        }

    /** Pops are white; only charging keeps its green. */
    private fun tone(pop: Pop): Int = if (pop.kind == "charge") Colors.GREEN else Colors.INK

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
        return min(max(baseCompactW(), wrapW() + 2f * side), expandedW())
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
        var startM = dp(18)
        var endM = dp(18)
        var sideMax = ((w - wrapW()) / 2 - dp(26)).coerceAtLeast(dp(60))
        if (n != null && !wraps()) {
            // A camera off to one side: push that side's content past the lens.
            val cardLeft = clampCx(anchorXFor(Mode.EXPANDED), w.toFloat()) - w / 2f
            val camL = n.left - cardLeft
            val camR = n.right - cardLeft
            if (camR > 0 && camL < w) {
                if (n.exactCenterX() - cardLeft < w / 2f) startM = max(startM, (camR + dpf(10f)).toInt())
                else endM = max(endM, (w - camL + dpf(10f)).toInt())
            }
            sideMax = (w / 2 - max(startM, endM) - dp(8)).coerceAtLeast(dp(60))
        }
        val row = FrameLayout(context)
        if (left != null) {
            if (left is TextView) left.maxWidth = sideMax
            row.addView(left, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = startM })
        }
        if (right != null) {
            if (right is TextView) right.maxWidth = sideMax
            row.addView(right, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = endM })
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
        v.animate().scaleX(0.86f).scaleY(0.86f).setDuration(70L).setInterpolator(DecelerateInterpolator()).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(320L).setInterpolator(android.view.animation.OvershootInterpolator(3.2f)).start()
        }.start()
    }

    private fun appTag(icon: Bitmap?, name: String, accent: Int): View {
        val row = hRow()
        row.addView(sized(roundImage(icon, dpf(5f), Colors.GRAPHITE, R.drawable.ic_bell), dp(17)))
        row.addView(spaced(text(12f, white70, true).apply { text = name; maxWidth = dp(110) }, 6))
        return row
    }

    /** Bell with the waiting count, sized like the app tag so the camera row stays balanced. */
    private fun bellChip(): View? {
        val n = IslandStore.notifCount
        if (n <= 0) return null
        val chip = hRow()
        chip.setPadding(dp(8), dp(3), dp(9), dp(3))
        chip.background = GradientDrawable().apply {
            setColor(Color.argb(38, 255, 255, 255))
            cornerRadius = dpf(11f)
        }
        chip.addView(sized(icon(R.drawable.ic_bell, Color.WHITE), dp(12)))
        chip.addView(spaced(text(11.5f, Color.WHITE, true).apply { text = if (n > 99) "99+" else n.toString() }, 4))
        chip.setOnClickListener {
            haptic()
            expanded = false
            openPanel()
        }
        return chip
    }

    // =====================================================================
    // Compact content
    // =====================================================================

    private fun compactTorch(): Sides =
        Sides(icon(R.drawable.ic_flashlight, Colors.INK), text(12.5f, Colors.SILVER, true).apply { text = "On" }, dp(16))

    private fun compactMusic(info: MusicInfo): Sides {
        Beat.ensure(context, info.playing && !lowPower)
        val art = roundImage(info.art, dpf(11f), info.accent, R.drawable.ic_note)
        artImage = art
        val w = WaveformView(context).apply {
            color = Colors.INK
            playing = info.playing
            still = lowPower
        }
        wave = w
        return Sides(art, w, dp(22), dp(13), dp(14))
    }

    /**
     * A status moment (charging, Wi-Fi, Bluetooth, power saving, silent…):
     * the icon in a soft circle of its own colour on the left, and on the
     * right either a little battery with the level, an On/Off pill, or the
     * value. The icon pops in and the value slides in after it.
     */
    private fun compactPop(pop: Pop): Sides {
        val accent = if (pop.accent == 0) Colors.INK else pop.accent
        val left = hRow()
        val badgeBox = FrameLayout(context).apply { clipChildren = false }
        // A soft ring of the pop's colour ripples out from behind the icon.
        badgeBox.addView(PulseView(context).apply {
            color = accent
            strength = 0.7f
        }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        val badge = ImageView(context).apply {
            setImageResource(if (pop.icon != 0) pop.icon else R.drawable.ic_info)
            setColorFilter(accent)
            val pad = dp(4)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(56, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
        }
        badgeBox.addView(badge, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        left.addView(badgeBox, LinearLayout.LayoutParams(dp(26), dp(26)))
        left.addView(spaced(text(12.5f, Color.WHITE, true).apply { text = pop.title; maxWidth = dp(110) }, 7))
        val pct = pop.value.removeSuffix("%").toIntOrNull()
        val right: View = when {
            (pop.kind == "battery" || pop.kind == "charge") && pct != null -> hRow().apply {
                addView(text(12.5f, accent, true).apply { text = pop.value })
                val glyph = BatteryGlyph(context).apply {
                    level = 0f
                    color = accent
                }
                addView(glyph, LinearLayout.LayoutParams(dp(24), dp(12)).apply { marginStart = dp(6) })
                // The battery fills up to the level.
                android.animation.ValueAnimator.ofFloat(0f, pct / 100f).apply {
                    startDelay = 220L
                    duration = 600L
                    interpolator = DecelerateInterpolator(2f)
                    addUpdateListener { glyph.level = it.animatedValue as Float }
                    start()
                }
            }
            pop.kind == "saved" -> FrameLayout(context).apply {
                val tick = CheckGlyph(context).apply { color = accent }
                addView(tick, FrameLayout.LayoutParams(dp(20), dp(20)))
                android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    startDelay = 260L
                    duration = 320L
                    interpolator = DecelerateInterpolator(1.6f)
                    addUpdateListener { tick.progress = it.animatedValue as Float }
                    start()
                }
            }
            pop.kind == "toggle" -> {
                val on = pop.value.equals("On", true)
                text(11.5f, if (on) Color.BLACK else Colors.SILVER, true).apply {
                    text = pop.value
                    gravity = Gravity.CENTER
                    setPadding(dp(9), dp(3), dp(9), dp(3))
                    background = GradientDrawable().apply {
                        cornerRadius = dpf(10f)
                        if (on) setColor(accent) else setStroke(dp(1), Color.argb(90, 255, 255, 255))
                    }
                }
            }
            else -> text(12.5f, accent, true).apply {
                text = pop.value
                maxWidth = dp(110)
            }
        }
        // In: the icon pops, the right side slides in a moment later.
        badge.scaleX = 0.2f
        badge.scaleY = 0.2f
        badge.rotation = -40f
        badge.animate().scaleX(1f).scaleY(1f).rotation(0f).setStartDelay(110L).setDuration(420L).setInterpolator(android.view.animation.OvershootInterpolator(3.2f)).start()
        right.alpha = 0f
        right.scaleX = 0.6f
        right.scaleY = 0.6f
        right.translationX = dpf(12f)
        right.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f).setStartDelay(190L).setDuration(380L).setInterpolator(android.view.animation.OvershootInterpolator(2.4f)).start()
        return Sides(left, right)
    }

    /** On a call: a green phone (gently pulsing) on the left, the running time in green on the right. */
    private fun compactCall(c: CallInfo): Sides {
        val left = FrameLayout(context)
        left.addView(PulseView(context).apply { color = Colors.GREEN; strength = 0.8f }, LayoutParams(dp(26), dp(26), Gravity.CENTER))
        left.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_call)
            setColorFilter(Color.BLACK)
            val pad = dp(3)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Colors.GREEN)
            }
        }, LayoutParams(dp(18), dp(18), Gravity.CENTER))
        val t = text(12.5f, Colors.GREEN, true).apply { text = if (c.ringing) "Ringing" else "" }
        timerText = t
        return Sides(left, t, dp(26))
    }

    private var lockGlyph: LockGlyph? = null

    private var unlockCheck: CheckGlyph? = null

    /**
     * Locked: a padlock beside the camera that drops in and clicks shut.
     * Unlocked: the island springs a little wider, the padlock pops open and
     * turns green, and a tick draws itself on the right, then it all tucks
     * away. Everything moves on the view's own layer (scale, rotation,
     * alpha) so nothing re-lays out mid-animation.
     */
    private fun compactLock(c: Content.Lock): Sides {
        val g = LockGlyph(context).apply {
            color = Color.WHITE
            open = if (c.unlocked) 0f else 0.7f
        }
        lockGlyph = g
        if (c.unlocked) {
            val tick = CheckGlyph(context).apply { color = Colors.GREEN }
            unlockCheck = tick
            post { animateUnlock() }
            return Sides(g, tick, dp(18), dp(20), dp(20))
        }
        g.scaleX = 0.3f
        g.scaleY = 0.3f
        g.translationY = -dpf(6f)
        g.animate().scaleX(1f).scaleY(1f).translationY(0f).setStartDelay(90L).setDuration(380L)
            .setInterpolator(android.view.animation.OvershootInterpolator(3f)).withLayer().start()
        // The shackle clicks shut, then a quick wobble.
        android.animation.ValueAnimator.ofFloat(0.7f, 0f).apply {
            startDelay = 260L
            duration = 220L
            interpolator = android.view.animation.OvershootInterpolator(4f)
            addUpdateListener { g.open = (it.animatedValue as Float).coerceAtLeast(-0.08f) }
            doOnEndCompat { wobble(g, 7f) }
            start()
        }
        return Sides(g, null, dp(18))
    }

    /** The screen just woke on the lock screen: play the padlock's drop-in again. */
    fun replayLock() {
        val g = lockGlyph ?: return
        if (g.tag == "done") return
        g.animate().cancel()
        g.scaleX = 0.3f
        g.scaleY = 0.3f
        g.open = 0.7f
        g.animate().scaleX(1f).scaleY(1f).setStartDelay(60L).setDuration(380L)
            .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
        android.animation.ValueAnimator.ofFloat(0.7f, 0f).apply {
            startDelay = 220L
            duration = 220L
            interpolator = android.view.animation.OvershootInterpolator(4f)
            addUpdateListener { g.open = (it.animatedValue as Float).coerceAtLeast(-0.08f) }
            doOnEndCompat { wobble(g, 7f) }
            start()
        }
    }

    /** A quick decaying side-to-side shake. */
    private fun wobble(v: View, deg: Float) {
        android.animation.ObjectAnimator.ofFloat(v, View.ROTATION, 0f, deg, -deg * 0.7f, deg * 0.4f, -deg * 0.15f, 0f).apply {
            duration = 300L
            start()
        }
    }

    private fun android.animation.Animator.doOnEndCompat(action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = action()
        })
    }

    /**
     * The unlock moment (timed like DroidBubble's: content in ~130 ms, the
     * tick ~110 ms after, a soft bouncy spring): the shackle pops open with
     * a spring while the padlock flashes green, then the tick draws itself
     * on and lands with a bounce. One tick of vibration as it opens.
     */
    private fun animateUnlock() {
        val g = lockGlyph ?: return
        if (g.tag == "done") return
        g.tag = "done"
        g.open = 0f
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = 60L
            duration = 320L
            interpolator = android.view.animation.OvershootInterpolator(3f)
            addUpdateListener { g.open = it.animatedValue as Float }
            start()
        }
        android.animation.ValueAnimator.ofArgb(Color.WHITE, Colors.GREEN).apply {
            startDelay = 80L
            duration = 180L
            addUpdateListener { g.color = it.animatedValue as Int; g.invalidate() }
            start()
        }
        g.scaleX = 0.85f
        g.scaleY = 0.85f
        g.animate().scaleX(1f).scaleY(1f).setStartDelay(60L).setDuration(420L)
            .setInterpolator(android.view.animation.OvershootInterpolator(3.4f)).withLayer().start()
        unlockCheck?.let { t ->
            t.progress = 0f
            t.scaleX = 0.4f
            t.scaleY = 0.4f
            t.animate().scaleX(1f).scaleY(1f).setStartDelay(170L).setDuration(420L)
                .setInterpolator(android.view.animation.OvershootInterpolator(2.6f)).withLayer().start()
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = 170L
                duration = 260L
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener { t.progress = it.animatedValue as Float }
                start()
            }
        }
        postDelayed({ haptic() }, 70L)
    }

    // ----- Screen recording -----

    /** Makes the island's red edge breathe while recording. */
    private var recPulse: android.animation.ValueAnimator? = null
    private var recCount: android.animation.ValueAnimator? = null

    /** 3, 2, 1: each new number lands with a pop and a tick. */
    private fun showCount(tv: TextView, n: Int, animate: Boolean = true) {
        val label = if (n > 0) n.toString() else "●"
        if (tv.text == label) return
        tv.text = label
        if (!animate) {
            if (n > 0) haptic()
            return
        }
        tv.scaleX = 1.7f
        tv.scaleY = 1.7f
        tv.alpha = 0.2f
        tv.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(340L)
            .setInterpolator(android.view.animation.OvershootInterpolator(2.6f)).start()
        if (n > 0) haptic()
    }

    /**
     * Compact recording.
     *  - Counting down: a red ring around the dot drains smoothly over the
     *    3 seconds while 3, 2, 1 pop in on the right.
     *  - Recording: the dot lands with a bounce, a red ripple spreads from
     *    it, and the island's edge glows red and gently breathes.
     *  - Paused: everything goes still and grey.
     */
    private fun compactRec(r: RecInfo): Sides {
        val counting = r.countdownUntil > 0L
        val box = FrameLayout(context).apply { clipChildren = false }
        if (counting) {
            val ring = ProgressRing(context).apply { color = Colors.RED }
            box.addView(ring, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            val left = (r.countdownUntil - SystemClock.elapsedRealtime()).coerceIn(0L, 3_000L)
            ring.progress = left / 3_000f
            // Ring and number read the same clock on every screen frame, so
            // each third of the ring empties exactly as its number shows. A
            // frame loop, not an animator: animators follow the phone's
            // animation speed setting (0.5x, off), which froze the ring
            // while the numbers kept going.
            val pop = android.view.animation.OvershootInterpolator(2.6f)
            val loop = object : Runnable {
                override fun run() {
                    val now = (r.countdownUntil - SystemClock.elapsedRealtime()).coerceIn(0L, 3_000L)
                    ring.progress = now / 3_000f
                    timerText?.let { tv ->
                        showCount(tv, r.countdown(), animate = false)
                        // The pop of each number, from the same clock: it lands
                        // as its third of the ring starts to drain.
                        val since = if (now == 0L) 1_000L else (1_000L - now % 1_000L) % 1_000L
                        val t = (since / 340f).coerceIn(0f, 1f)
                        val o = pop.getInterpolation(t)
                        tv.scaleX = 1.7f - 0.7f * o
                        tv.scaleY = tv.scaleX
                        tv.alpha = 0.2f + 0.8f * t
                    }
                    if (now > 0L && ring.isAttachedToWindow) ring.postOnAnimation(this)
                }
            }
            ring.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = v.postOnAnimation(loop)
                override fun onViewDetachedFromWindow(v: View) {
                    v.removeCallbacks(loop)
                }
            })
        } else if (!r.paused) {
            box.addView(PulseView(context).apply {
                color = Colors.RED
                strength = 1f
            }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        }
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (r.paused) Colors.GREY else Colors.RED)
            }
        }
        box.addView(dot, FrameLayout.LayoutParams(dp(if (counting) 8 else 11), dp(if (counting) 8 else 11), Gravity.CENTER))
        dot.scaleX = 0f
        dot.scaleY = 0f
        dot.animate().scaleX(1f).scaleY(1f).setStartDelay(90L).setDuration(460L)
            .setInterpolator(android.view.animation.OvershootInterpolator(4f)).withLayer().start()
        val tv = text(if (counting) 16f else 13f, if (r.paused) Colors.SILVER else Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            fontFeatureSettings = "tnum"
        }
        timerText = tv
        if (!counting && !r.paused) {
            // Recording just began (or resumed): a bright flash on the edge, then a slow breathe.
            haptic()
            recPulse = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1_600L
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener {
                    glow = 0.25f + 0.55f * (it.animatedValue as Float)
                    invalidate()
                }
                startDelay = 450L
                start()
            }
            glow = 1f
        }
        return Sides(box, tv, dp(26))
    }

    /**
     * Open: "Recording" with the app's name, a big running time, then
     * Pause / Resume and a red Stop. A countdown shows its number big
     * instead, with Cancel.
     */
    private fun buildExpandedRec(r: RecInfo, w: Int, top: Float): View {
        val col = card()
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (r.paused) Colors.GREY else Colors.RED)
            }
        }
        val tag = hRow()
        val dotBox = FrameLayout(context).apply { clipChildren = false }
        if (!r.paused) dotBox.addView(PulseView(context).apply {
            color = Colors.RED
            strength = 0.9f
        }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        dotBox.addView(dot, FrameLayout.LayoutParams(dp(9), dp(9), Gravity.CENTER))
        tag.addView(dotBox, LinearLayout.LayoutParams(dp(18), dp(18)))
        tag.addView(spaced(text(12f, if (r.paused) white70 else Colors.RED, true).apply {
            text = when {
                r.countdownUntil > 0L -> "Starting"
                r.paused -> "Paused"
                else -> "Recording"
            }
        }, 4))
        col.addView(cameraRow(tag, text(12f, white55, false).apply { text = if (r.own) "Screen" else r.appName }, w, top))
        val b = body()
        val row = hRow()
        val big = text(40f, if (r.paused) Colors.SILVER else Color.WHITE, false).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
        }
        timerText = big
        row.addView(big, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val canPause = r.own || (if (r.paused) r.resumeIntent else r.pauseIntent) != null
        if (canPause && r.countdownUntil == 0L) {
            row.addView(roundButton(if (r.paused) R.drawable.ic_play else R.drawable.ic_pause, Colors.GRAPHITE) {
                host.recPause()
            }.apply { setColorFilter(Color.WHITE) })
            row.addView(View(context), LinearLayout.LayoutParams(dp(12), 1))
        }
        row.addView(roundButton(if (r.countdownUntil > 0L) R.drawable.ic_close else R.drawable.ic_stop, Colors.RED) {
            host.recStop()
            expanded = false
            refresh()
        }.apply { setColorFilter(Color.WHITE) })
        b.addView(row)
        col.addView(b)
        return col
    }

    private fun compactTimer(): Sides {
        val tv = text(12.5f, Colors.SILVER, true)
        timerText = tv
        return Sides(icon(R.drawable.ic_timer, Colors.INK), tv, dp(16))
    }

    private fun compactTransfer(t: TransferInfo): Sides {
        val r = ProgressRing(context).apply {
            color = Colors.INK
            progress = t.progress
        }
        ring = r
        return Sides(roundImage(t.icon, dpf(11f), Colors.GRAPHITE, R.drawable.ic_download), r, dp(22), dp(18), dp(18))
    }

    private fun compactNav(nv: NavInfo): Sides {
        val arrow = if (nv.arrow != null) ImageView(context).apply { setImageBitmap(nv.arrow) } else icon(R.drawable.ic_nav, Colors.INK)
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
        Beat.ensure(context, info.playing && !lowPower)
        val root = FrameLayout(context)
        // The blurred cover is drawn by the island itself (see drawPicture), so it
        // can cross-fade with your island picture as the card opens.
        setCover(info)
        val col = card()
        val wv = WaveformView(context).apply {
            color = Colors.INK
            playing = info.playing
            still = lowPower
        }
        wave = wv
        col.addView(cameraRow(appTag(info.appIcon, info.appName.ifEmpty { "Now playing" }, info.accent), bellChip(), w, top))

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
        row.addView(wv, LinearLayout.LayoutParams(dp(13), dp(14)).apply { marginEnd = dp(14) })
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

    /**
     * A notification banner. Chats get the contact photo with a small app
     * badge; every other app gets its own icon as a tile, so a delivery update
     * or an email reads as cleanly as a WhatsApp message. Text gets three
     * lines, an image preview sits on the right, actions run full width.
     */
    private fun buildNote(nf: NotifInfo, w: Int, top: Float): View {
        val r = nf.rich
        val col = card()
        col.addView(cameraRow(appTag(r.appIcon, nf.appName, Colors.GRAPHITE), noteMeta(), w, top))
        val b = body().apply { setPadding(dp(18), dp(8), dp(18), dp(16)) }
        b.addView(messageRow(r, big = true))

        // Actions: what the app offers first, then the basics.
        val buttons = mutableListOf<View>()
        if (r.replyable) {
            buttons += pillButton("Reply", Colors.INK, Color.BLACK) {
                IslandStore.dismissNotif()
                host.openDetail(nf.key)
            }
        }
        val mr = r.markRead
        if (mr != null) {
            buttons += pillButton("Mark as read", Colors.GRAPHITE, Color.WHITE) {
                host.send(mr)
                IslandStore.dismissNotif()
            }
        }
        if (buttons.isEmpty()) {
            buttons += pillButton("Open", Colors.INK, Color.BLACK) { openCurrent() }
            buttons += pillButton("Dismiss", Colors.GRAPHITE, Color.WHITE) {
                host.dismissNotification(nf.key)
                IslandStore.dismissNotif()
            }
        }
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        buttons.forEachIndexed { i, v ->
            actions.addView(v, LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        b.addView(actions)
        col.addView(b)
        return col
    }

    private fun noteMeta(): View = text(11.5f, white55, false).apply { text = "now" }

    /**
     * Who it's from. A contact photo is a circle with the app's icon as a badge;
     * without one, the app's own icon is shown as a rounded tile.
     */
    private fun avatar(r: Rich, size: Int): View {
        val box = FrameLayout(context)
        val photo = r.avatar
        if (photo != null) {
            box.addView(roundImage(photo, size / 2f, Colors.GRAPHITE, R.drawable.ic_bell), LayoutParams(size, size))
            if (r.appIcon != null) {
                val badge = (size * 0.4f).toInt()
                // The app's icon as a small rounded tile in a ring the colour of the card.
                val ring = FrameLayout(context).apply {
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(14, 14, 16))
                        cornerRadius = (badge + dp(4)) * 0.3f
                    }
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    addView(roundImage(r.appIcon, badge * 0.28f, Colors.GRAPHITE, R.drawable.ic_bell), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                }
                box.addView(ring, LayoutParams(badge + dp(4), badge + dp(4), Gravity.END or Gravity.BOTTOM).apply {
                    marginEnd = -dp(3)
                    bottomMargin = -dp(3)
                })
            }
        } else {
            // App icon tile on graphite, padded so every icon shape looks at home.
            val tile = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    setColor(Colors.GRAPHITE)
                    cornerRadius = size * 0.3f
                }
            }
            val inner = (size * 0.72f).toInt()
            tile.addView(roundImage(r.appIcon, inner * 0.28f, Colors.GRAPHITE, R.drawable.ic_bell), LayoutParams(inner, inner, Gravity.CENTER))
            box.addView(tile, LayoutParams(size, size))
        }
        box.clipChildren = false
        box.clipToPadding = false
        box.layoutParams = LinearLayout.LayoutParams(size, size)
        return box
    }

    /** Title (+ count chip), "Sender: message" for groups, and an image preview on the right. */
    private fun messageRow(r: Rich, big: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = false
        }
        row.addView(avatar(r, if (big) dp(46) else dp(40)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(1), dp(4), 0)
        }
        val head = hRow()
        head.addView(text(if (big) 15.5f else 14.5f, Color.WHITE, true).apply { text = r.title }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (r.count > 1) {
            head.addView(text(10.5f, Color.WHITE, true).apply {
                text = if (r.count > 99) "99+" else r.count.toString()
                gravity = Gravity.CENTER
                setPadding(dp(7), dp(2), dp(7), dp(2))
                background = GradientDrawable().apply {
                    setColor(Colors.GRAPHITE)
                    cornerRadius = dpf(9f)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(7) })
        }
        texts.addView(head)
        if (r.text.isNotEmpty()) {
            val line = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (big) 14f else 13f)
                setTextColor(Color.argb(200, 255, 255, 255))
                maxLines = if (big) 3 else 2
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(0f, 1.12f)
                setPadding(0, dp(4), 0, 0)
                includeFontPadding = false
                val sender = r.sender
                text = if (sender != null) {
                    val sb = android.text.SpannableStringBuilder()
                    sb.append(sender)
                    sb.setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), 0, sender.length, 0)
                    sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, sender.length, 0)
                    sb.append(": ").append(r.text)
                    sb
                } else {
                    r.text
                }
            }
            texts.addView(line)
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pic = r.picture
        if (pic != null) {
            val ps = if (big) dp(52) else dp(44)
            row.addView(roundImage(pic, dpf(12f), Colors.GRAPHITE, R.drawable.ic_bell), LinearLayout.LayoutParams(ps, ps).apply { marginStart = dp(10) })
        }
        return row
    }

    /**
     * A call, open. Ringing: the caller's photo with rings rippling out, their
     * name, and two big buttons, Decline and Accept (Accept breathes gently).
     * On a call: the photo, name and running time, and a row of controls:
     * Mute, the dialer's own buttons (Speaker, Hold…), and End.
     */
    private fun buildExpandedCall(c: CallInfo, w: Int, top: Float): View {
        val col = card()
        val tagIcon = if (c.app.isNotEmpty() && c.app != "com.android.server.telecom" && !c.app.contains("dialer") && !c.app.contains("incallui")) {
            sized(roundImage((HaloNotificationListener.instance?.appIcon(c.app)), dpf(4f), Colors.GRAPHITE, R.drawable.ic_call), dp(15))
        } else {
            sized(icon(R.drawable.ic_call, Colors.GREEN), dp(14))
        }
        val tag = hRow(tagIcon, spaced(text(12f, white70, true).apply { text = c.appName.ifEmpty { "Phone" } }, 6))
        val timer = text(12.5f, Colors.GREEN, true).apply { if (c.ringing) text = "Incoming" }
        if (!c.ringing) timerText = timer
        col.addView(cameraRow(tag, timer, w, top))
        val b = body().apply { setPadding(dp(18), dp(6), dp(18), dp(18)) }

        val row = hRow()
        val photo = FrameLayout(context)
        photo.addView(PulseView(context).apply {
            color = if (c.ringing) Colors.GREEN else Color.WHITE
            strength = if (c.ringing) 1f else 0.35f
        }, LayoutParams(dp(70), dp(70), Gravity.CENTER))
        photo.addView(roundImage(c.avatar, dpf(25f), Colors.GRAPHITE, R.drawable.ic_call), LayoutParams(dp(50), dp(50), Gravity.CENTER))
        row.addView(photo, LinearLayout.LayoutParams(dp(70), dp(70)).apply { marginStart = -dp(10) })
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(4), 0)
        }
        texts.addView(text(19f, Color.WHITE, true).apply { text = c.name })
        texts.addView(text(13f, white70, false).apply {
            text = if (c.ringing) "${c.appName.ifEmpty { "Phone" }} call" else c.detail.ifEmpty { "On call" }
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        b.addView(row)

        if (c.ringing) {
            val buttons = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(14), 0, 0)
            }
            fun big(iconRes: Int, label: String, color: Int, action: () -> Unit) = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(color)
                    cornerRadius = dpf(24f)
                }
                addView(sized(icon(iconRes, Color.WHITE), dp(18)))
                addView(spaced(text(14.5f, Color.WHITE, true).apply { text = label }, 8))
                setOnClickListener {
                    haptic()
                    bounce(it)
                    action()
                }
            }
            buttons.addView(big(R.drawable.ic_call_end, "Decline", Colors.RED) { host.endCall(c.hangUp) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            val accept = big(R.drawable.ic_call, "Accept", Colors.GREEN) { host.answerCall(c.answer) }
            buttons.addView(accept, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(10) })
            // Accept breathes, so your eye goes there.
            android.animation.ObjectAnimator.ofPropertyValuesHolder(
                accept,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.04f, 1f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.04f, 1f),
            ).apply {
                duration = 1400L
                repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                start()
            }
            b.addView(buttons)
        } else {
            val controls = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(14), 0, 0)
            }
            fun ctl(iconRes: Int, label: String, bg: Int, fg: Int, action: (ImageView, TextView) -> Unit): LinearLayout {
                val box = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                val ic = ImageView(context).apply {
                    setImageResource(iconRes)
                    setColorFilter(fg)
                    val pad = dp(13)
                    setPadding(pad, pad, pad, pad)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(bg)
                    }
                }
                val tx = text(11f, white70, false).apply {
                    text = label
                    gravity = Gravity.CENTER
                    setPadding(0, dp(5), 0, 0)
                }
                box.addView(ic, LinearLayout.LayoutParams(dp(50), dp(50)))
                box.addView(tx)
                box.setOnClickListener {
                    haptic()
                    bounce(ic)
                    action(ic, tx)
                }
                controls.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                return box
            }
            // Mute: white when on.
            fun muteLook(ic: ImageView, tx: TextView, on: Boolean) {
                ic.setImageResource(if (on) R.drawable.ic_mic_off else R.drawable.ic_mic)
                ic.setColorFilter(if (on) Color.BLACK else Color.WHITE)
                (ic.background as? GradientDrawable)?.setColor(if (on) Colors.INK else Colors.GRAPHITE)
                tx.text = if (on) "Muted" else "Mute"
            }
            val muted = host.isMuted()
            val m = ctl(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic, "Mute", Colors.GRAPHITE, Color.WHITE) { ic, tx -> muteLook(ic, tx, host.toggleMute()) }
            muteLook(m.getChildAt(0) as ImageView, m.getChildAt(1) as TextView, muted)
            for ((label, pi) in c.extras) {
                val l = label.lowercase()
                val ic = when {
                    l.contains("speaker") -> R.drawable.ic_speaker
                    l.contains("mute") -> R.drawable.ic_mic_off
                    l.contains("hold") -> R.drawable.ic_pause
                    l.contains("video") -> R.drawable.ic_camera
                    else -> R.drawable.ic_bolt
                }
                ctl(ic, label.replaceFirstChar { it.uppercase() }, Colors.GRAPHITE, Color.WHITE) { _, _ -> host.send(pi) }
            }
            ctl(R.drawable.ic_call_end, "End", Colors.RED, Color.WHITE) { _, _ -> host.endCall(c.hangUp) }
            b.addView(controls)
        }
        col.addView(b)
        col.setOnClickListener { host.send(c.contentIntent) }
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
        val tag = hRow(sized(icon(R.drawable.ic_timer, Colors.INK), dp(14)), spaced(text(12f, white70, true).apply { text = t.label }, 6))
        col.addView(cameraRow(tag, text(12f, white55, false).apply { text = if (t.countDown) "Timer" else "Stopwatch" }, w, top))
        val b = body()
        val big = text(44f, Colors.INK, false).apply {
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
        val pct = text(12.5f, Colors.SILVER, true).apply { text = if (t.progress >= 0f) "${(t.progress * 100).toInt()}%" else "" }
        percentText = pct
        col.addView(cameraRow(appTag(t.icon, t.appName, Colors.GRAPHITE), pct, w, top))
        val b = body()
        b.addView(text(15.5f, Color.WHITE, true).apply { text = t.title })
        val bar = ProgressLine(context).apply {
            color = Colors.INK
            fraction = max(0f, t.progress)
        }
        progress = bar
        b.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16)).apply { topMargin = dp(10) })
        col.addView(b)
        return col
    }

    private fun buildExpandedNav(nv: NavInfo, w: Int, top: Float): View {
        val col = card()
        val tag = hRow(sized(icon(R.drawable.ic_nav, Colors.INK), dp(14)), spaced(text(12f, white70, true).apply { text = "Maps" }, 6))
        col.addView(cameraRow(tag, null, w, top))
        val b = body()
        val row = hRow()
        val arrow = if (nv.arrow != null) ImageView(context).apply { setImageBitmap(nv.arrow) } else icon(R.drawable.ic_nav, Colors.INK)
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

    // ----- Charging card -----
    private var chargePct: TextView? = null
    private var chargeBar: ProgressLine? = null
    private var chargeInfo: TextView? = null

    /** Tap the charging pop: big percentage, power, time to full, temperature. */
    private var chargeRing: ProgressRing? = null

    /**
     * Charging, open: a big ring that fills to your level with the
     * percentage in the middle, and beside it how (wired, fast, wireless),
     * the watts, the temperature and when it'll be full.
     */
    private var chargeHow: TextView? = null
    private var chargeAnim: android.animation.ValueAnimator? = null

    private fun buildCharge(w: Int, top: Float): View {
        val col = card()
        val how = text(12f, Colors.GREEN, true).apply { text = "Charging" }
        chargeHow = how
        val tag = hRow(sized(icon(R.drawable.ic_bolt, Colors.GREEN), dp(14)), spaced(how, 6))
        col.addView(cameraRow(tag, null, w, top))
        val b = body().apply { setPadding(dp(18), dp(6), dp(18), dp(18)) }
        val row = hRow()
        val size = dp(82)
        val ringBox = FrameLayout(context)
        val ring = ProgressRing(context).apply { color = Colors.GREEN }
        chargeRing = ring
        ringBox.addView(ring, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        val pct = text(19f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
        chargePct = pct
        ringBox.addView(pct, FrameLayout.LayoutParams(size, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        row.addView(ringBox, LinearLayout.LayoutParams(size, size))
        val info = text(14f, Color.WHITE, false).apply {
            maxLines = 4
            setLineSpacing(0f, 1.35f)
            setPadding(dp(18), 0, 0, 0)
        }
        chargeInfo = info
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        b.addView(row)
        col.addView(b)
        updateCharge()
        // The ring fills up as the card opens, with a little spring at the end.
        val target = ring.progress
        ring.progress = 0f
        chargeAnim = android.animation.ValueAnimator.ofFloat(0f, target).apply {
            duration = 950L
            startDelay = 160L
            interpolator = android.view.animation.OvershootInterpolator(1.1f)
            addUpdateListener { ring.progress = (it.animatedValue as Float).coerceAtMost(1f) }
            start()
        }
        ringBox.scaleX = 0.6f
        ringBox.scaleY = 0.6f
        ringBox.animate().scaleX(1f).scaleY(1f).setStartDelay(80L).setDuration(460L).setInterpolator(android.view.animation.OvershootInterpolator(2.4f)).start()
        info.alpha = 0f
        info.translationX = dpf(14f)
        info.animate().alpha(1f).translationX(0f).setStartDelay(200L).setDuration(380L).setInterpolator(DecelerateInterpolator(2f)).start()
        return col
    }

    private fun updateCharge() {
        val pctView = chargePct ?: return
        val bm = context.getSystemService(BatteryManager::class.java) ?: return
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        pctView.text = "$pct%"
        chargeBar?.fraction = pct / 100f
        if (chargeAnim?.isRunning != true) chargeRing?.progress = pct / 100f
        val sticky = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val plug = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val mv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val temp = (sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        var ma = abs(bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
        if (ma > 20_000L) ma /= 1000L // most phones report microamps
        val watts = ma * mv / 1_000_000f
        val how = when (plug) {
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            else -> if (watts >= 10f) "Fast charging" else "Charging"
        }
        chargeHow?.text = how
        val parts = mutableListOf<String>()
        if (watts in 0.5f..120f) parts += "%.1f W".format(watts)
        if (temp > 0f) parts += "%.0f°C".format(temp)
        val left = bm.computeChargeTimeRemaining()
        val full = when {
            pct >= 100 -> "Fully charged"
            left > 0 -> "Full in " + duration(left)
            else -> ""
        }
        // One fact per line beside the ring.
        chargeInfo?.text = (parts + listOfNotNull(full.ifEmpty { null })).joinToString("\n")
    }

    private fun duration(ms: Long): String {
        val m = (ms / 60_000L).toInt()
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }

    /**
     * Opened from the camera. Beside the lens: date and time. Then the title
     * with a close button, a row of chips (battery, and anything switched on
     * that can be switched off: flashlight, Do Not Disturb, silent…), a mini
     * player when music is on, your notifications, and Clear all.
     */
    private fun buildPanel(w: Int, top: Float): View {
        val items = host.panelItems()
        val col = card()

        val date = text(13f, white70, true).apply { text = DateFormat.format("EEE d MMM", Date()) }
        val clock = text(14f, Color.WHITE, true)
        clockText = clock
        col.addView(cameraRow(date, clock, w, top))

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(6), dp(12), dp(2))
        }
        // The title shrinks to make room for your control buttons.
        val nControls = host.controlStates().size
        header.addView(text(if (nControls >= 3) 16f else 19f, Color.WHITE, true).apply {
            text = "Notifications"
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(11, if (nControls >= 3) 16 else 19, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            gravity = Gravity.CENTER_VERTICAL
        }, if (nControls == 0) LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)) else LinearLayout.LayoutParams(0, dp(28), 1f))
        if (items.isNotEmpty()) {
            header.addView(text(11f, Color.WHITE, true).apply {
                text = items.size.toString()
                gravity = Gravity.CENTER
                setPadding(dp(7), dp(2), dp(7), dp(2))
                background = GradientDrawable().apply {
                    setColor(Colors.GRAPHITE)
                    cornerRadius = dpf(9f)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        }
        if (nControls == 0) header.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        // Your controls: white when on, graphite when off.
        for (st in host.controlStates()) {
            val on = st.on == true
            header.addView(ImageView(context).apply {
                setImageResource(st.def.icon)
                setColorFilter(if (on) Color.BLACK else Color.WHITE)
                val pad = dp(7)
                setPadding(pad, pad, pad, pad)
                contentDescription = st.def.label
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (on) Colors.INK else Colors.GRAPHITE)
                }
                setOnClickListener {
                    haptic()
                    bounce(it)
                    // Screenshots and Quick Settings taps need the panel out of the way.
                    if (st.def.id == "screenshot" || st.def.id == "record" || st.def.tile.isNotEmpty()) closePanel()
                    host.controlTap(st.def.id)
                }
            }, LinearLayout.LayoutParams(dp(31), dp(31)).apply { marginStart = dp(5) })
        }
        header.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(Color.argb(210, 255, 255, 255))
            val pad = dp(7)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Colors.GRAPHITE)
            }
            setOnClickListener {
                haptic()
                closePanel()
            }
        }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(if (nControls > 0) 9 else 0) })
        col.addView(header)

        // Chips: battery first, then everything that's on.
        val chips = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(2))
        }
        val battery = chip(R.drawable.ic_battery_full, "", active = false)
        batteryText = battery.getChildAt(1) as TextView
        (battery.getChildAt(0) as ImageView).tag = "batt"
        chips.addView(battery)
        for (q in host.quickStates()) {
            chips.addView(chip(q.icon, q.label, active = true) {
                haptic()
                host.quickAction(q.id)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        }
        val chipScroll = android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(chips)
        }
        col.addView(chipScroll)

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(14))
        }
        IslandStore.music?.let { list.addView(miniPlayer(it)) }
        if (items.isEmpty()) {
            val empty = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(20), 0, dp(16))
            }
            val bell = icon(R.drawable.ic_bell, white55)
            empty.addView(bell, LinearLayout.LayoutParams(dp(26), dp(26)))
            empty.addView(text(14f, white55, false).apply {
                text = "You're all caught up"
                setPadding(0, dp(8), 0, 0)
            })
            list.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            wobble(bell, 14f)
        }
        // Grouped by app, newest app first. One notification from an app is
        // a plain card; more stack up behind it with "+N" until you tap to fan
        // them out. Only the first few groups are built straight away so the
        // panel opens without a hitch; the rest are added a moment later.
        val groups = LinkedHashMap<String, MutableList<PanelItem>>()
        for (item in items) groups.getOrPut(item.packageName) { ArrayList() }.add(item)
        panelGroups = groups
        panelList = list
        val entries = groups.entries.toList()
        val now = entries.take(FIRST_GROUPS)
        val later = entries.drop(FIRST_GROUPS)
        for ((pkg, group) in now) for (v in groupViews(pkg, group, pkg in openGroups)) list.addView(v)
        if (items.size > 1) {
            list.addView(pillButton("Clear all", Colors.GRAPHITE, Color.WHITE) {
                if (clearing) return@pillButton
                clearing = true
                haptic()
                // Sweep every card off to the right, one after another, then close.
                val cards = (0 until list.childCount).map { list.getChildAt(it) }.filter { (it.tag as? String)?.startsWith("g") == true }
                cards.forEachIndexed { i, v ->
                    v.animate().translationX(v.width.toFloat() * 1.1f).alpha(0f)
                        .setStartDelay(i.coerceAtMost(8) * 40L).setDuration(200L)
                        .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f)).start()
                }
                postDelayed({
                    host.clearAll()
                    clearing = false
                    closePanel()
                }, 200L + cards.size.coerceAtMost(8) * 40L)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
            }.also { it })
            list.getChildAt(list.childCount - 1).tag = "clear"
        }
        if (later.isNotEmpty()) postDelayed({
            if (panelList !== list) return@postDelayed
            var at = (0 until list.childCount).firstOrNull { list.getChildAt(it).tag == "clear" } ?: list.childCount
            for ((pkg, group) in later) for (v in groupViews(pkg, group, pkg in openGroups)) list.addView(v, at++)
            resizePanel()
        }, 420L)
        val cards = (0 until list.childCount).map { list.getChildAt(it) }.filter { (it.tag as? String)?.let { t -> t.startsWith("g:") || t.startsWith("gc:") } == true }
        // In: the cards rise into place one after another (only when the panel opens).
        if (!panelQuiet) cards.take(8).forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = dpf(26f)
            v.scaleX = 0.92f
            v.scaleY = 0.92f
            v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(80L + i * 45L).setDuration(460L)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()
        }
        list.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        col.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val room = (panelMaxH() - col.measuredHeight).toInt()
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            addView(list)
        }
        panelScrollView = scroll
        if (panelQuiet) {
            val y = savedPanelY
            scroll.post { scroll.scrollTo(0, y) }
        }
        panelChromeH = col.measuredHeight
        panelRoom = max(room, dp(90))
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, min(list.measuredHeight, panelRoom)))
        return col
    }

    /**
     * A small rounded chip. Active ones (something you can switch off) are
     * white with black ink and a tiny ×; the battery chip is graphite.
     */
    private fun chip(iconRes: Int, label: String, active: Boolean, onTap: (() -> Unit)? = null): LinearLayout {
        val fg = if (active) Color.BLACK else Color.WHITE
        val c = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(if (active) Colors.INK else Colors.GRAPHITE)
                cornerRadius = dpf(15f)
            }
        }
        c.addView(sized(icon(iconRes, fg), dp(14)))
        c.addView(spaced(text(12.5f, fg, true).apply { text = label }, 5))
        if (active) c.addView(sized(icon(R.drawable.ic_close, Color.argb(150, 0, 0, 0)), dp(12)).apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(5)
        })
        if (onTap != null) c.setOnClickListener {
            bounce(it)
            onTap()
        }
        return c
    }

    /** Music inside the notification center: cover, song, controls; tap opens the full player. */
    private fun miniPlayer(m: MusicInfo): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(11), dp(8), dp(11))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.argb(240, 18 + Color.red(m.accent) / 4, 18 + Color.green(m.accent) / 4, 18 + Color.blue(m.accent) / 4),
                    Color.argb(235, 30, 30, 32),
                ),
            ).apply { cornerRadius = dpf(22f) }
            setOnClickListener {
                haptic()
                panelOpen = false
                if (content is Content.Music) {
                    expanded = true
                    refresh()
                } else {
                    refresh()
                    host.openMusicApp(m)
                }
            }
        }
        box.addView(roundImage(m.art, dpf(12f), m.accent, R.drawable.ic_note), LinearLayout.LayoutParams(dp(44), dp(44)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(6), 0)
        }
        texts.addView(text(14.5f, Color.WHITE, true).apply { text = m.title })
        texts.addView(text(12.5f, white70, false).apply {
            text = m.artist.ifEmpty { m.appName }
            setPadding(0, dp(3), 0, 0)
        })
        box.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun ctl(res: Int, action: () -> Unit) = ImageView(context).apply {
            setImageResource(res)
            setColorFilter(Color.WHITE)
            val pad = dp(7)
            setPadding(pad, pad, pad, pad)
            setOnClickListener {
                haptic()
                bounce(it)
                action()
            }
        }
        val tc = m.controller?.transportControls
        box.addView(ctl(R.drawable.ic_prev) { tc?.skipToPrevious() }, LinearLayout.LayoutParams(dp(36), dp(36)))
        val play = ctl(if (m.playing) R.drawable.ic_pause else R.drawable.ic_play) {
            val now = IslandStore.music
            if (now?.playing == true) tc?.pause() else tc?.play()
        }
        playButton = play
        box.addView(play, LinearLayout.LayoutParams(dp(38), dp(38)))
        box.addView(ctl(R.drawable.ic_next) { tc?.skipToNext() }, LinearLayout.LayoutParams(dp(36), dp(36)))
        box.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        return box
    }

    /** Above a fanned-out group: the app's name and "Show less". */
    private fun groupHeader(pkg: String, group: List<PanelItem>): View {
        val row = hRow().apply { setPadding(dp(6), dp(18), dp(4), 0) }
        row.addView(text(12.5f, white70, true).apply { text = group.first().appName }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text(12f, Color.WHITE, true).apply {
            text = "Show less"
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(Colors.GRAPHITE)
                cornerRadius = dpf(11f)
            }
            setOnClickListener { toggleGroup(pkg, open = false) }
        })
        return row
    }

    /**
     * Several notifications from one app: the newest as a full card, with
     * one or two slimmer cards peeking out underneath and "+N more" — tap
     * the stack to fan them out.
     */
    private fun stackedCard(pkg: String, group: List<PanelItem>): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val front = panelCard(group.first()) as LinearLayout
        // "+N more" goes in the header (where the arrow was), so the stack
        // doesn't grow an extra row of empty space.
        val meta = front.getChildAt(0) as LinearLayout
        meta.removeViewAt(meta.childCount - 1)
        meta.addView(text(11.5f, Color.WHITE, true).apply {
            text = "+${group.size - 1}"
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(3), dp(9), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.argb(46, 255, 255, 255))
                cornerRadius = dpf(10f)
            }
        })
        front.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        box.addView(front)
        // The cards behind peek out underneath as two slim, narrower edges.
        val behind = minOf(group.size - 1, 2)
        for (i in 1..behind) {
            box.addView(View(context).apply {
                background = GradientDrawable().apply {
                    setColor(tint(Color.argb(if (i == 1) 215 else 160, 34, 34, 37), group.first().accent, 0.12f))
                    cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dpf(14f), dpf(14f), dpf(14f), dpf(14f))
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply {
                marginStart = dp(14 * i)
                marginEnd = dp(14 * i)
            })
        }
        // Tap anywhere on the stack to fan it out.
        front.setOnClickListener { toggleGroup(pkg, open = true) }
        box.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        return box
    }

    /** [base] with a little of [accent] mixed in, so each app's cards carry its colour. */
    private fun tint(base: Int, accent: Int, f: Float): Int {
        if (accent == 0) return base
        fun ch(b: Int, a: Int) = (b + (a - b) * f).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(base), ch(Color.red(base), Color.red(accent)), ch(Color.green(base), Color.green(accent)), ch(Color.blue(base), Color.blue(accent)))
    }

    /**
     * One notification in the center. A calm dark card with a soft glow of
     * the app's colour coming from behind its icon in the top corner (not a
     * tint over everything), a slim header (icon, app, time), the message,
     * and for chats a reply bar that looks like a real text field with a
     * send button in the app's colour. Tap to open, swipe either way to
     * clear; it squishes under your finger and springs back.
     */
    private fun panelCard(item: PanelItem): View {
        val raw = item.accent
        val hasAccent = raw != 0 && raw != Color.BLACK && raw != Color.WHITE
        val accent = if (hasAccent) raw else Colors.INK
        val cardView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(15))
            val glow = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dpf(190f)
                setGradientCenter(0.08f, 0f)
                colors = intArrayOf(
                    Color.argb(if (hasAccent) 62 else 26, Color.red(accent), Color.green(accent), Color.blue(accent)),
                    Color.TRANSPARENT,
                )
                cornerRadius = dpf(24f)
            }
            background = android.graphics.drawable.LayerDrawable(arrayOf(ShineDrawable(Color.argb(242, 24, 24, 26), dpf(24f), dpf(1f)), glow))
            setOnClickListener {
                haptic()
                panelOpen = false
                refresh()
                host.openDetail(item.key)
            }
        }
        val meta = hRow()
        meta.addView(roundImage(item.rich.appIcon, dpf(5f), Colors.GRAPHITE, R.drawable.ic_bell), LinearLayout.LayoutParams(dp(16), dp(16)))
        meta.addView(spaced(text(12f, Color.argb(215, 255, 255, 255), true).apply { text = item.appName }, 7))
        val ago = DateUtils.getRelativeTimeSpanString(item.postTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        meta.addView(spaced(text(11.5f, white55, false).apply { text = "·  $ago" }, 6), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        meta.addView(icon(R.drawable.ic_chevron, Color.argb(110, 255, 255, 255)), LinearLayout.LayoutParams(dp(14), dp(14)))
        cardView.addView(meta)
        cardView.addView(messageRow(item.rich, big = false), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(11) })
        if (item.canReply) cardView.addView(replyBar(item, accent), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply { topMargin = dp(12) })
        cardView.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        swipeToDismiss(cardView) { host.dismissNotification(item.key) }
        return cardView
    }

    /**
     * Looks like a text field: "Reply to <name>…" in a soft glass pill, with
     * a round send button in the app's colour at the end. Tapping either
     * opens the reply screen.
     */
    private fun replyBar(item: PanelItem, accent: Int): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(4), 0)
            background = GradientDrawable().apply {
                setColor(Color.argb(20, 255, 255, 255))
                setStroke(dp(1), Color.argb(34, 255, 255, 255))
                cornerRadius = dpf(19f)
            }
        }
        val who = item.title.take(22)
        bar.addView(text(13f, white55, false).apply { text = if (who.isNotBlank()) "Reply to $who…" else "Reply…" }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val light = Color.red(accent) * 0.3 + Color.green(accent) * 0.59 + Color.blue(accent) * 0.11 > 160
        val send = ImageView(context).apply {
            setImageResource(R.drawable.ic_send)
            setColorFilter(if (light) Color.BLACK else Color.WHITE)
            val pad = dp(7)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
            }
        }
        bar.addView(send, LinearLayout.LayoutParams(dp(30), dp(30)))
        bar.setOnClickListener {
            haptic()
            bounce(send)
            postDelayed({
                panelOpen = false
                refresh()
                host.openDetail(item.key)
            }, 120L)
        }
        return bar
    }

    /** Drag a card sideways to dismiss it; a plain tap still opens it. */
    @SuppressLint("ClickableViewAccessibility")
    /**
     * Swipe a card away. Quick: a flick of any length clears it, the card
     * follows the finger 1:1, flies off in ~0.1 s and the cards below close
     * the gap smoothly instead of jumping.
     */
    private fun swipeToDismiss(view: View, onDismiss: () -> Unit) {
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var dragging = false
        var tracker: android.view.VelocityTracker? = null
        view.setOnTouchListener { v, e ->
            tracker?.addMovement(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                    v.isPressed = true
                    v.animate().cancel()
                    // A touch during the entrance shouldn't freeze it half-faded.
                    v.alpha = 1f
                    v.translationY = 0f
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(110L).setInterpolator(DecelerateInterpolator()).start()
                    tracker?.recycle()
                    tracker = android.view.VelocityTracker.obtain().also { it.addMovement(e) }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && abs(dx) > slop * 0.8f && abs(dx) > abs(dy) * 1.1f) {
                        dragging = true
                        v.isPressed = false
                        v.animate().scaleX(1f).scaleY(1f).setDuration(160L).start()
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        v.translationX = dx
                        v.alpha = (1f - abs(dx) / v.width.coerceAtLeast(1) * 0.9f).coerceIn(0.15f, 1f)
                        v.rotation = dx / v.width.coerceAtLeast(1) * 3f
                    } else if (abs(dy) > slop) {
                        v.isPressed = false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    val dx = e.rawX - downX
                    tracker?.computeCurrentVelocity(1000)
                    val vx = tracker?.xVelocity ?: 0f
                    tracker?.recycle()
                    tracker = null
                    val flick = abs(vx) > 700f && Math.signum(vx) == Math.signum(dx)
                    if (dragging && (abs(dx) > v.width * 0.22f || flick)) {
                        haptic()
                        val dir = if ((if (flick) vx else dx) > 0) 1f else -1f
                        val left = v.width - abs(dx)
                        val ms = (if (flick) (left / abs(vx) * 1000f) else 110f).toLong().coerceIn(70L, 140L)
                        v.animate().translationX(dir * v.width * 1.1f).alpha(0f).setDuration(ms)
                            .setInterpolator(android.view.animation.LinearInterpolator())
                            .withEndAction { collapse(v, onDismiss) }.start()
                    } else if (dragging) {
                        v.animate().translationX(0f).alpha(1f).rotation(0f).scaleX(1f).scaleY(1f).setDuration(420L)
                            .setInterpolator(android.view.animation.OvershootInterpolator(1.8f)).start()
                    } else {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(360L)
                            .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
                        if (abs(e.rawY - downY) < slop) v.performClick()
                    }
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    v.animate().translationX(0f).alpha(1f).rotation(0f).scaleX(1f).scaleY(1f).setDuration(360L)
                        .setInterpolator(android.view.animation.OvershootInterpolator(1.8f)).start()
                    dragging = false
                    tracker?.recycle()
                    tracker = null
                }
            }
            true
        }
    }

    /** Closes the gap a dismissed card leaves, then removes it. */
    private fun collapse(v: View, then: () -> Unit) {
        val h = v.height
        val lp = v.layoutParams
        val mlp = lp as? ViewGroup.MarginLayoutParams
        val top = mlp?.topMargin ?: 0
        val bottom = mlp?.bottomMargin ?: 0
        then()
        android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 150L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val f = it.animatedValue as Float
                lp.height = (h * f).toInt().coerceAtLeast(0)
                mlp?.topMargin = (top * f).toInt()
                mlp?.bottomMargin = (bottom * f).toInt()
                v.layoutParams = lp
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    (v.parent as? ViewGroup)?.removeView(v)
                }
            })
            start()
        }
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
            is Content.Rec -> {
                val r = c.rec
                val n = r.countdown()
                if (r.countdownUntil > 0L) {
                    // On the pill the ring's frame loop owns the number; here only for the open card.
                    if (mode != Mode.COMPACT) showCount(tv, n)
                } else {
                    tv.text = fmt(r.elapsedMs())
                }
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
            pct <= 15 -> Colors.SILVER
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
                content is Content.Popup && (content as Content.Popup).pop.kind == "charge" && mode == Mode.COMPACT -> {
                    IslandStore.holdPop(10_000L)
                    expanded = true
                    refresh()
                }
                content is Content.Popup -> IslandStore.dismissPop()
                content is Content.Torch -> host.torchOff()
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
            val c = content
            if (c is Content.Note) {
                haptic()
                IslandStore.dismissNotif()
                host.send(c.note.contentIntent)
                return
            }
            when {
                mode == Mode.COMPACT && canExpand(content) -> openCurrent()
                mode == Mode.COMPACT || mode == Mode.IDLE -> openPanel()
            }
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val music = currentMusic()
            // Sideways on an alert is for moving between alerts (handled above), never dismiss or music.
            if (abs(velocityX) > abs(velocityY) && content is Content.Note) return true
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

    // ----- Swiping between alerts -----
    //
    // Like pages: the alert next to this one is laid out beside it, and both
    // move together under your finger, so you see the next one coming. The
    // island's height blends between the two as you drag. The one leaving
    // sinks back a touch and the one arriving rises to meet you.

    private var pendingSlide = 0
    private var noteSwiping = false
    private var swipeX = 0f
    private var swipeY = 0f
    private var swipeVt: android.view.VelocityTracker? = null
    private val swipeSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var neighbour: View? = null
    private var neighbourDir = 0
    private var neighbourH = 0f
    private var startH = 0f
    private var crossed = false
    private var stripAnim: android.animation.ValueAnimator? = null

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (content !is Content.Note || mode != Mode.EXPANDED) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeX = e.rawX
                swipeY = e.rawY
                noteSwiping = false
                swipeVt?.recycle()
                swipeVt = android.view.VelocityTracker.obtain()
                swipeVt?.addMovement(e)
            }
            MotionEvent.ACTION_MOVE -> {
                swipeVt?.addMovement(e)
                val dx = e.rawX - swipeX
                val dy = e.rawY - swipeY
                if (!noteSwiping && abs(dx) > swipeSlop * 0.7f && abs(dx) > abs(dy) && IslandStore.notePosition().second > 1) {
                    noteSwiping = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    swipeX = e.rawX
                    stripAnim?.cancel()
                    startH = v(1)
                    crossed = false
                }
            }
        }
        return noteSwiping
    }

    /** The alert on the side we're dragging towards, built and placed beside this one. */
    private fun ensureNeighbour(dir: Int, w: Int): View? {
        if (neighbourDir == dir && neighbour != null) return neighbour
        dropNeighbour()
        val (at, count) = IslandStore.notePosition()
        val j = at + dir
        if (j !in 0 until count) return null
        val note = IslandStore.recentNotes.getOrNull(j) ?: return null
        val s = slot ?: return null
        val view = buildNote(note, w, gapTop() + offsetY)
        view.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        neighbourH = min(view.measuredHeight.toFloat(), panelMaxH())
        // Tall enough for whichever is taller (the island's shape does the clipping).
        val lp = s.layoutParams
        val need = max(lp.height, neighbourH.toInt())
        if (lp.height != need) {
            lp.height = need
            s.layoutParams = lp
        }
        s.addView(view, LayoutParams(w, LayoutParams.WRAP_CONTENT))
        neighbour = view
        neighbourDir = dir
        return view
    }

    private fun dropNeighbour() {
        neighbour?.let { (it.parent as? ViewGroup)?.removeView(it) }
        neighbour = null
        neighbourDir = 0
    }

    /** Lays out the strip for an offset [dx] (how far the current card has moved). */
    private fun placeStrip(card: View, dx: Float, w: Float) {
        val gap = dpf(14f)
        card.translationX = dx
        val f = (abs(dx) / (w + gap)).coerceIn(0f, 1f)
        // Leaving: sinks back a little. Arriving: rises to full size.
        val out = 1f - 0.06f * f
        card.scaleX = out
        card.scaleY = out
        card.alpha = 1f - 0.5f * f
        neighbour?.let { n ->
            n.translationX = dx + (if (neighbourDir > 0) 1f else -1f) * (w + gap)
            val inn = 0.94f + 0.06f * f
            n.scaleX = inn
            n.scaleY = inn
            n.alpha = 0.5f + 0.5f * f
        }
        // The island's height blends between the two alerts.
        if (neighbour != null) {
            val h = startH + (neighbourH - startH) * f
            springs[1].snap(h)
            springs[2].snap(min(dpf(32f), h / 2f))
            springs[6].snap(cyFor(Mode.EXPANDED, h))
            updateClip()
            invalidate()
        }
    }

    private fun noteSwipe(e: MotionEvent) {
        val card = slot?.getChildAt(0) ?: return
        swipeVt?.addMovement(e)
        val dx = e.rawX - swipeX
        val w = card.width.coerceAtLeast(1).toFloat()
        // Swipe left = the next (older) alert; right = back to a newer one.
        val dir = if (dx < 0) 1 else -1
        val n = ensureNeighbour(dir, card.width)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                // Nothing that way: it only gives a little.
                placeStrip(card, if (n != null) dx else dx * 0.22f, w)
                val past = n != null && abs(dx) > w * 0.3f
                if (past != crossed) {
                    crossed = past
                    if (past) haptic()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                swipeVt?.computeCurrentVelocity(1000)
                val vx = swipeVt?.xVelocity ?: 0f
                swipeVt?.recycle()
                swipeVt = null
                noteSwiping = false
                val go = n != null && e.actionMasked == MotionEvent.ACTION_UP &&
                    (abs(dx) > w * 0.3f || (abs(vx) > 650f && (vx < 0) == (dx < 0)))
                val gap = dpf(14f)
                val from = card.translationX
                val to = if (go) (if (dx < 0) -(w + gap) else (w + gap)) else 0f
                // Keeps the speed of your flick, then eases in like a page settling.
                val dist = abs(to - from)
                val ms = if (abs(vx) > 400f) (dist / abs(vx) * 1000f * 1.6f).toLong().coerceIn(160L, 340L) else (180L + dist / w * 160L).toLong()
                stripAnim = android.animation.ValueAnimator.ofFloat(from, to).apply {
                    duration = ms
                    interpolator = android.view.animation.PathInterpolator(0.2f, 0.9f, 0.25f, 1f)
                    addUpdateListener { placeStrip(card, it.animatedValue as Float, w) }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(a: android.animation.Animator) {
                            if (go) {
                                // The arriving alert is now in place: make it the shown one, with no jump.
                                pendingSlide = 1
                                dropNeighbour()
                                if (!IslandStore.showSibling(dir)) pendingSlide = 0
                            } else {
                                card.scaleX = 1f
                                card.scaleY = 1f
                                card.alpha = 1f
                                dropNeighbour()
                                springs[1].snap(startH)
                                springs[2].snap(min(dpf(32f), startH / 2f))
                                springs[6].snap(cyFor(Mode.EXPANDED, startH))
                                updateClip()
                                invalidate()
                            }
                        }
                    })
                    start()
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (noteSwiping) {
            noteSwipe(event)
            return true
        }
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


/**
 * A card with a Liquid Glass feel: its colour, a soft sheen across the top,
 * and a hairline rim that's bright top-left and faint elsewhere.
 */
class ShineDrawable(private val fill: Int, private val radius: Float, private val stroke: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
    }
    private val r = RectF()

    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        sheen.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            intArrayOf(Color.argb(30, 255, 255, 255), Color.argb(8, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 0.3f, 0.6f), Shader.TileMode.CLAMP)
        rim.shader = LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            intArrayOf(Color.argb(120, 255, 255, 255), Color.argb(20, 255, 255, 255), Color.argb(45, 255, 255, 255)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        r.set(bounds)
        p.color = fill
        c.drawRoundRect(r, radius, radius, p)
        c.drawRoundRect(r, radius, radius, sheen)
        r.inset(stroke / 2f, stroke / 2f)
        c.drawRoundRect(r, radius - stroke / 2f, radius - stroke / 2f, rim)
    }

    override fun setAlpha(alpha: Int) {
        p.alpha = alpha
    }

    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radius)
    }
}

/** Marks the island's content holders (clipped to its live shape). */
private const val SLOT_TAG = "island_slot"
