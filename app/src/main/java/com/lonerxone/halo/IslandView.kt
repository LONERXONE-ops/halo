package com.lonerxone.halo

import android.annotation.SuppressLint
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
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The island: a dark glass pill drawn around the camera notch.
 * Shape changes run on a spring (damping 0.78); content fades in once the
 * shape is 70% of the way there, so text never squashes.
 */
@SuppressLint("ViewConstructor")
class IslandView(context: Context, private val host: Host) : FrameLayout(context) {

    interface Host {
        fun requestWindow(width: Int, height: Int, touchable: Boolean)
        fun openMusicApp(info: MusicInfo)
    }

    enum class Mode { HIDDEN, IDLE, COMPACT, EXPANDED }

    sealed class Content {
        object None : Content()
        data class Music(val info: MusicInfo) : Content()
        data class Popup(val pop: Pop) : Content()
    }

    data class Geometry(val notch: Rect?, val screenW: Int, val statusBarH: Int)

    // ---------- settings pushed in by the service ----------
    var idleEnabled = true
    var hapticsEnabled = true
    var sizeScale = 1f
    var geometry: Geometry = Geometry(null, resources.displayMetrics.widthPixels, dp(24))

    // ---------- state ----------
    private var mode = Mode.HIDDEN
    private var content: Content = Content.None
    private var hidden = false
    private var expanded = false
    private var builtKey: String? = null

    // ---------- spring: w, h, radius, alpha, border, shadow ----------
    private val cur = FloatArray(6)
    private val start = FloatArray(6)
    private val target = FloatArray(6)
    private var p = 1f
    private var v = 0f
    private var k = 420f
    private var animating = false
    private var lastFrameNanos = 0L
    private val frameCallback = Choreographer.FrameCallback { onFrame(it) }

    // ---------- drawing ----------
    private val rect = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(11, 11, 15) }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(1f)
    }

    // ---------- content ----------
    private var slot: FrameLayout? = null
    private var revealed = true
    private var wave: WaveformView? = null
    private var playButton: ImageView? = null
    private var progress: ProgressLine? = null
    private var elapsedText: TextView? = null
    private var remainText: TextView? = null
    private var lastTouch = 0L

    private val ticker = object : Runnable {
        override fun run() {
            if (mode != Mode.EXPANDED) return
            updateProgress()
            if (SystemClock.uptimeMillis() - lastTouch > 6_000L) {
                expanded = false
                refresh()
                return
            }
            postDelayed(this, 500L)
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
        if (newContent !is Content.Music) expanded = false
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
    // Geometry
    // =====================================================================

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    private fun notchW(): Int = geometry.notch?.width() ?: 0
    private fun notchH(): Int = geometry.notch?.height() ?: 0
    private fun anchorY(): Float = geometry.notch?.exactCenterY() ?: (geometry.statusBarH / 2f)
    private fun notchBottom(): Int = geometry.notch?.bottom ?: (geometry.statusBarH * 2 / 3)

    private fun idleW(): Float = max(notchW() + dpf(24f), dpf(64f))
    private fun idleH(): Float = max(notchH() + dpf(6f), dpf(30f))
    private fun compactW(): Float = max(dpf(200f) * sizeScale, notchW() + dpf(140f))
    private fun compactH(): Float = max(idleH(), dpf(34f) * sizeScale)
    private fun expandedW(): Float = min(dpf(340f) * sizeScale, geometry.screenW - dpf(16f))
    private fun topFor(h: Float): Float = max(dpf(4f), anchorY() - h / 2f)

    // =====================================================================
    // State machine
    // =====================================================================

    private fun desiredMode(): Mode = when {
        hidden -> Mode.HIDDEN
        content is Content.None -> if (idleEnabled) Mode.IDLE else Mode.HIDDEN
        content is Content.Music && expanded -> Mode.EXPANDED
        else -> Mode.COMPACT
    }

    private fun contentKey(): String = when (val c = content) {
        is Content.None -> "none"
        is Content.Music -> "m:" + c.info.trackKey
        is Content.Popup -> "p:" + c.pop.id
    }

    private fun refresh() {
        val m = desiredMode()
        val key = m.name + "/" + contentKey()
        if (key == builtKey) {
            bindInPlace()
            return
        }
        val wasExpanded = mode == Mode.EXPANDED
        mode = m
        builtKey = key
        removeCallbacks(ticker)

        when (m) {
            Mode.HIDDEN -> {
                swapContent(null, 0, 0, 0f)
                animateTo(idleW() * 0.6f, idleH() * 0.8f, idleH() * 0.4f, 0f, 0f, 0f)
            }
            Mode.IDLE -> {
                swapContent(null, 0, 0, 0f)
                animateTo(idleW(), idleH(), idleH() / 2f, 1f, 0.35f, 0f)
            }
            Mode.COMPACT -> {
                val w = compactW()
                val h = compactH()
                val view = when (val c = content) {
                    is Content.Music -> buildCompactMusic(c.info, h.toInt())
                    is Content.Popup -> buildPop(c.pop)
                    else -> null
                }
                swapContent(view, w.toInt(), h.toInt(), h / 2f)
                animateTo(w, h, h / 2f, 1f, 1f, 0f)
                if (wasExpanded) haptic()
            }
            Mode.EXPANDED -> {
                val info = (content as Content.Music).info
                val w = expandedW()
                val view = buildExpandedMusic(info)
                view.measure(
                    MeasureSpec.makeMeasureSpec(w.toInt(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                )
                val h = view.measuredHeight.toFloat()
                val r = dpf(28f)
                swapContent(view, w.toInt(), h.toInt(), r)
                animateTo(w, h, r, 1f, 1f, 1f)
                lastTouch = SystemClock.uptimeMillis()
                updateProgress()
                postDelayed(ticker, 500L)
                haptic()
            }
        }
    }

    /** Same song, same mode: just update play state and progress. */
    private fun bindInPlace() {
        val info = (content as? Content.Music)?.info ?: return
        wave?.playing = info.playing
        wave?.color = info.accent
        playButton?.setImageResource(if (info.playing) R.drawable.ic_pause else R.drawable.ic_play)
        if (mode == Mode.EXPANDED) updateProgress()
    }

    // =====================================================================
    // Spring animation
    // =====================================================================

    private fun animScale(): Float = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    } catch (_: Exception) {
        1f
    }

    private fun animateTo(w: Float, h: Float, r: Float, a: Float, border: Float, shadow: Float) {
        System.arraycopy(cur, 0, start, 0, cur.size)
        target[0] = w; target[1] = h; target[2] = r
        target[3] = a; target[4] = border; target[5] = shadow

        val scale = animScale()
        if (scale <= 0f || !isAttachedToWindow) {
            System.arraycopy(target, 0, cur, 0, cur.size)
            p = 1f; v = 0f
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            animating = false
            onSettled()
            invalidate()
            return
        }
        k = 420f / (scale * scale)
        p = 0f
        v = 0f
        pushWindow(envelope = true)
        if (!animating) {
            animating = true
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun onFrame(nanos: Long) {
        val dt = if (lastFrameNanos == 0L) 1f / 60f else ((nanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastFrameNanos = nanos
        val c = 2f * 0.78f * sqrt(k)
        var left = dt
        while (left > 0f) {
            val step = min(left, 0.004f)
            val acc = -k * (p - 1f) - c * v
            v += acc * step
            p += v * step
            left -= step
        }
        for (i in cur.indices) cur[i] = start[i] + (target[i] - start[i]) * p
        if (!revealed && p >= 0.7f) reveal()
        if (abs(p - 1f) < 0.002f && abs(v) < 0.02f) {
            p = 1f
            v = 0f
            System.arraycopy(target, 0, cur, 0, cur.size)
            animating = false
            lastFrameNanos = 0L
            onSettled()
        } else {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
        invalidate()
    }

    private fun onSettled() {
        if (!revealed) reveal()
        pushWindow(envelope = false)
    }

    /** Sizes the overlay window to fit the pill (and, mid-animation, both its old and new shape). */
    private fun pushWindow(envelope: Boolean) {
        val w = if (envelope) max(max(start[0], target[0]), cur[0]) else target[0]
        val hTarget = target[1]
        val hStart = start[1]
        val bottom = if (envelope) {
            max(topFor(hTarget) + hTarget, topFor(hStart) + hStart)
        } else {
            topFor(hTarget) + hTarget
        }
        val pad = if (target[5] > 0f || (envelope && start[5] > 0f)) dpf(24f) else dpf(6f)
        val winW = (w + pad * 2f).toInt().coerceAtLeast(1)
        val winH = (bottom + pad).toInt().coerceAtLeast(1)
        val touchable = mode == Mode.COMPACT || mode == Mode.EXPANDED
        host.requestWindow(winW, winH, touchable)
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
        val l = width / 2f - w / 2f
        val t = topFor(h)
        rect.set(l, t, l + w, t + h)
        val r = min(max(cur[2], 0f), h / 2f)

        val shadow = cur[5].coerceIn(0f, 1f)
        if (shadow > 0.02f) {
            fillPaint.setShadowLayer(dpf(14f), 0f, dpf(4f), Color.argb((110 * shadow * a).toInt(), 0, 0, 0))
        } else {
            fillPaint.clearShadowLayer()
        }
        fillPaint.alpha = (235 * a).toInt()
        canvas.drawRoundRect(rect, r, r, fillPaint)

        // Soft sheen across the top half: the "glass" without real blur.
        sheenPaint.shader = LinearGradient(
            0f, t, 0f, t + h * 0.55f,
            Color.argb((26 * a).toInt(), 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(rect, r, r, sheenPaint)

        val b = cur[4].coerceIn(0f, 1f) * a
        if (b > 0.01f) {
            borderPaint.shader = LinearGradient(
                0f, t, 0f, t + h,
                Color.argb((90 * b).toInt(), 255, 255, 255), Color.argb((30 * b).toInt(), 255, 255, 255),
                Shader.TileMode.CLAMP,
            )
            val inset = borderPaint.strokeWidth / 2f
            rect.inset(inset, inset)
            canvas.drawRoundRect(rect, r - inset, r - inset, borderPaint)
        }
    }

    // =====================================================================
    // Content swapping
    // =====================================================================

    private fun swapContent(view: View?, w: Int, h: Int, radius: Float) {
        slot?.let { old ->
            old.animate().cancel()
            old.animate().alpha(0f).setDuration(90L).withEndAction { removeView(old) }.start()
        }
        slot = null
        wave = null
        playButton = null
        progress = null
        elapsedText = null
        remainText = null
        if (view == null) {
            revealed = true
            return
        }
        val s = FrameLayout(context).apply {
            alpha = 0f
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, radius)
                }
            }
            clipToOutline = true
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        val lp = LayoutParams(w, h, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = topFor(h.toFloat()).toInt()
        }
        addView(s, lp)
        slot = s
        revealed = false
    }

    private fun reveal() {
        revealed = true
        slot?.animate()?.alpha(1f)?.setDuration(160L)?.start()
    }

    // =====================================================================
    // Content builders
    // =====================================================================

    private fun text(sp: Float, color: Int, bold: Boolean): TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    private fun artView(bitmap: Bitmap?, size: Int, radius: Float, accent: Int): ImageView =
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
                background = GradientDrawable().apply { setColor(Color.argb(60, Color.red(accent), Color.green(accent), Color.blue(accent))) }
                setImageResource(R.drawable.ic_note)
                setColorFilter(accent)
                val pad = size / 4
                setPadding(pad, pad, pad, pad)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        }

    private fun buildCompactMusic(info: MusicInfo, h: Int): View {
        val root = FrameLayout(context)
        val artSize = h - dp(10)
        root.addView(
            artView(info.art, artSize, artSize / 2f, info.accent),
            LayoutParams(artSize, artSize, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(5) },
        )
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
        left.addView(text(12f, Color.WHITE, true).apply {
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

    private fun buildExpandedMusic(info: MusicInfo): View {
        val padTop = max(dp(16), notchBottom() - dp(4) + dp(8))
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), padTop, dp(18), dp(12))
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val art = artView(info.art, dp(52), dpf(12f), info.accent)
        art.setOnClickListener { openCurrent() }
        row.addView(art, LinearLayout.LayoutParams(dp(52), dp(52)))

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            setOnClickListener { openCurrent() }
        }
        texts.addView(text(15f, Color.WHITE, true).apply { text = info.title })
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
            action()
        }
    }

    private fun currentMusic(): MusicInfo? = (content as? Content.Music)?.info

    private fun openCurrent() {
        val m = currentMusic() ?: return
        haptic()
        host.openMusicApp(m)
        expanded = false
        refresh()
    }

    private fun updateProgress() {
        val m = currentMusic() ?: return
        val pos = m.currentPosition()
        val dur = m.durationMs
        progress?.fraction = if (dur > 0) pos.toFloat() / dur else 0f
        elapsedText?.text = fmt(pos)
        remainText?.text = if (dur > 0) "-" + fmt(max(0L, dur - pos)) else ""
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    // =====================================================================
    // Touch
    // =====================================================================

    private fun haptic() {
        if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            when (content) {
                is Content.Music -> if (mode == Mode.COMPACT) {
                    expanded = true
                    refresh()
                } else if (mode == Mode.EXPANDED) {
                    openCurrent()
                }
                is Content.Popup -> IslandStore.dismissPop()
                else -> {}
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (mode == Mode.COMPACT && content is Content.Music) openCurrent()
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val music = currentMusic()
            if (abs(velocityX) > abs(velocityY)) {
                val tc = music?.controller?.transportControls ?: return false
                haptic()
                if (velocityX < 0) tc.skipToNext() else tc.skipToPrevious()
                return true
            }
            if (velocityY < 0) {
                if (mode == Mode.EXPANDED) {
                    expanded = false
                    refresh()
                } else if (content is Content.Popup) {
                    IslandStore.dismissPop()
                }
                return true
            }
            if (velocityY > 0 && mode == Mode.COMPACT && music != null) {
                expanded = true
                refresh()
                return true
            }
            return false
        }
    })

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val w = cur[0]
            val h = cur[1]
            val l = width / 2f - w / 2f
            val t = topFor(h)
            if (ev.x < l || ev.x > l + w || ev.y < t || ev.y > t + h) return false
        }
        lastTouch = SystemClock.uptimeMillis()
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
