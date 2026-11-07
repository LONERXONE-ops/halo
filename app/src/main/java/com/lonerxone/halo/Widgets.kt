package com.lonerxone.halo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Three slim bars that bounce while music plays (3dp wide, 2dp apart, 14dp
 * tall), resting low when paused.
 */
class WaveformView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bar = RectF()
    private val lows = floatArrayOf(0.3f, 0.5f, 0.2f)
    private val highs = floatArrayOf(0.9f, 1.0f, 0.7f)
    private val periods = floatArrayOf(0.70f, 0.86f, 1.02f)
    private val shown = floatArrayOf(0.3f, 0.5f, 0.2f)

    var color: Int = Color.WHITE
        set(value) {
            field = value
            paint.color = value
            invalidate()
        }

    var playing: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** Low-power mode: bars hold still. */
    var still: Boolean = false

    override fun onDraw(canvas: Canvas) {
        val n = 3
        val gap = width * 0.154f
        val bw = (width - gap * (n - 1)) / n
        val r = bw * 0.35f
        val t = SystemClock.uptimeMillis() / 1000f
        for (i in 0 until n) {
            val target = if (playing && !still) {
                if (Beat.fresh()) {
                    // Real sound: bass, mids, highs (middle bar is the bass, like a meter).
                    val lv = Beat.levels[intArrayOf(1, 0, 2)[i]]
                    0.18f + 0.82f * lv
                } else {
                    val ph = 0.5f - 0.5f * sin((t / periods[i]) * 6.2832f + i * 1.3f)
                    lows[i] + (highs[i] - lows[i]) * ph
                }
            } else {
                lows[i]
            }
            shown[i] += (target - shown[i]) * 0.4f
            val f = shown[i]
            val h = max(bw, height * f)
            val x = i * (bw + gap)
            bar.set(x, (height - h) / 2f, x + bw, (height + h) / 2f)
            canvas.drawRoundRect(bar, r, r, paint)
        }
        val settling = (0 until n).any { kotlin.math.abs(shown[it] - lows[it]) > 0.01f }
        if ((playing && !still || settling) && isShown) postInvalidateOnAnimation()
    }
}

/**
 * Thin rounded track with a filled part. When [onSeek] is set it can be dragged:
 * the bar thickens, a thumb appears, and the position is sent on release.
 */
@android.annotation.SuppressLint("ClickableViewAccessibility")
class ProgressLine(context: Context) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255) }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val density = resources.displayMetrics.density

    var color: Int = Color.WHITE
        set(value) {
            field = value
            bar.color = value
            thumb.color = value
            invalidate()
        }

    var fraction: Float = 0f
        set(value) {
            if (dragging) return
            val v = value.coerceIn(0f, 1f)
            // Just after a seek the player may still report the old spot: stay
            // where you let go until it catches up (no jump back and forth).
            if (android.os.SystemClock.uptimeMillis() < holdUntil) {
                if (kotlin.math.abs(v - held) > 0.02f) return
                holdUntil = 0L
            }
            field = v
            invalidate()
        }
    private var holdUntil = 0L
    private var held = 0f

    /** Called with 0..1 when the user lets go after dragging. */
    var onSeek: ((Float) -> Unit)? = null

    var dragging = false
        private set

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        val seek = onSeek ?: return false
        val f = (e.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                setDragFraction(f)
            }
            android.view.MotionEvent.ACTION_MOVE -> setDragFraction(f)
            android.view.MotionEvent.ACTION_UP -> {
                setDragFraction(f)
                dragging = false
                fraction = f
                held = f
                holdUntil = android.os.SystemClock.uptimeMillis() + 2500L
                seek(f)
                invalidate()
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
            }
        }
        return true
    }

    private var dragValue = 0f

    private fun setDragFraction(f: Float) {
        dragValue = f
        invalidate()
    }

    /**
     * Samsung's wave scrubber: a thick line where the played part has soft
     * hills rising above it (two layers, gently breathing with the beat
     * while the song plays), and a round thumb with a glow.
     */
    var hills = false
        set(v) {
            field = v
            invalidate()
        }
    var playing = false
        set(v) {
            field = v
            invalidate()
        }
    /** Each song gets its own shape of hills. */
    var seed = 0
        set(v) {
            field = v
            peaks = null
        }
    private var peaks: FloatArray? = null
    private var breathe = 0f
    private var lastT = 0L
    private val hillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val path = android.graphics.Path()

    override fun onDraw(canvas: Canvas) {
        val shown = if (dragging) dragValue else fraction
        if (hills) {
            drawHills(canvas, shown)
            return
        }
        val th = if (dragging) 6f * density else 4f * density
        val top = (height - th) / 2f
        val r = th / 2f
        canvas.drawRoundRect(0f, top, width.toFloat(), top + th, r, r, track)
        val w = width * shown
        if (w > 0.5f) canvas.drawRoundRect(0f, top, max(w, th), top + th, r, r, bar)
        if (dragging) canvas.drawCircle(w.coerceIn(r, width - r), height / 2f, 7f * density, thumb)
    }

    /** The heights of the hills along the song (smooth, made once per song from [seed]). */
    private fun peaksFor(n: Int): FloatArray {
        peaks?.let { if (it.size == n) return it }
        val rnd = java.util.Random(seed.toLong())
        val raw = FloatArray(n) { 0.25f + rnd.nextFloat() * 0.75f }
        // Smoothed so they read as rolling hills, not spikes.
        val out = FloatArray(n)
        for (i in 0 until n) {
            var sum = 0f
            var c = 0
            for (j in -3..3) {
                val k = i + j
                if (k in 0 until n) {
                    sum += raw[k]
                    c++
                }
            }
            out[i] = sum / c
        }
        peaks = out
        return out
    }

    private var ripple = 0f
    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Builds the hill outline from x0 to x1 as a smooth curve. [scale] sets
     * the height; near the thumb the hills lift with the beat (the sound
     * "coming out" where you are), further back they're calmer.
     */
    private fun hillPath(x0: Float, x1: Float, cy: Float, maxH: Float, scale: Float, thumbX: Float, live: Float, pk: FloatArray) {
        path.reset()
        path.moveTo(x0, cy)
        val n = pk.size
        val step = 4f * density
        var px = x0
        var lastX = x0
        var lastY = cy
        while (px <= x1) {
            val pos = (px / width.coerceAtLeast(1)) * (n - 1)
            val i0 = pos.toInt().coerceIn(0, n - 1)
            val i1 = (i0 + 1).coerceAtMost(n - 1)
            val f = pos - i0
            // Smoothstep between peaks: rounded hills, no corners.
            val sf = f * f * (3f - 2f * f)
            var hgt = pk[i0] + (pk[i1] - pk[i0]) * sf
            // A slow ripple running through them while the song plays.
            hgt *= 0.85f + 0.15f * kotlin.math.sin(px / (26f * density) - ripple)
            // Closer to the thumb, the more it moves with the beat.
            val near = (1f - kotlin.math.abs(thumbX - px) / (90f * density)).coerceIn(0f, 1f)
            hgt *= 0.7f + 0.3f * (1f - near) + live * near * 0.6f
            // Soft start, and it slopes down into the thumb.
            val edge = kotlin.math.min((px - x0) / (12f * density) + 0.1f, 1f).coerceIn(0f, 1f) *
                kotlin.math.min(1f, kotlin.math.abs(thumbX - px) / (10f * density) + 0.35f)
            val y = cy - maxH * hgt * edge * scale
            path.quadTo(lastX, lastY, (lastX + px) / 2f, (lastY + y) / 2f)
            lastX = px
            lastY = y
            px += step
        }
        path.lineTo(x1, lastY)
        path.lineTo(x1, cy)
        path.close()
    }

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private var shaderKey = 0
    private var frontShader: android.graphics.Shader? = null
    private var lineShader: android.graphics.Shader? = null

    private fun mixWhite(c: Int, f: Float) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * f).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * f).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * f).toInt(),
    )

    /**
     * Samsung-style: three soft layers of hills over the played part (deep at
     * the back, bright at the front with a fine light edge), a thick line that
     * brightens toward the thumb, and a knob with a white ring and a glow
     * that pulses with the beat.
     */
    private fun drawHills(canvas: Canvas, shown: Float) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastT == 0L) 0.016f else ((now - lastT) / 1000f).coerceIn(0f, 0.05f)
        lastT = now
        val hear = playing && Beat.fresh()
        val beat = if (hear) (Beat.levels[0] * 0.65f + Beat.levels[1] * 0.35f).coerceIn(0f, 1f) else if (playing) 0.5f + 0.1f * kotlin.math.sin(now / 480f) else 0.3f
        // Quick to rise on a hit, slower to fall, like a meter.
        breathe += (beat - breathe) * (1f - kotlin.math.exp(-(if (beat > breathe) 16f else 5f) * dt))
        if (playing) ripple += dt * 1.6f
        val th = 4.5f * density
        val cy = height * 0.68f
        val x = width * shown
        val maxH = kotlin.math.min(cy - 2f * density, 15f * density)
        val pk = peaksFor(56)
        val c = bar.color
        val light = mixWhite(c, 0.45f)
        // Gradients made once per colour and size, not every frame.
        val key = c * 31 + width * 7 + height
        if (key != shaderKey) {
            shaderKey = key
            frontShader = android.graphics.LinearGradient(0f, cy - maxH, 0f, cy, Color.argb(215, Color.red(light), Color.green(light), Color.blue(light)), Color.argb(70, Color.red(c), Color.green(c), Color.blue(c)), android.graphics.Shader.TileMode.CLAMP)
            lineShader = null
        }
        // The rest of the song: a calm, soft line.
        canvas.drawRoundRect(x, cy - th / 2f, width.toFloat(), cy + th / 2f, th / 2f, th / 2f, track)
        if (x > 2f) {
            val lift = 0.6f + 0.4f * breathe
            // Back: deep and low-contrast, a little taller, so the front reads in front of it.
            hillPath(0f, x, cy, maxH, lift, x, breathe, pk)
            hillPaint.shader = null
            hillPaint.color = Color.argb(55, Color.red(c), Color.green(c), Color.blue(c))
            canvas.drawPath(path, hillPaint)
            // Middle.
            hillPath(0f, x, cy, maxH, lift * 0.78f, x, breathe, pk)
            hillPaint.color = Color.argb(95, Color.red(c), Color.green(c), Color.blue(c))
            canvas.drawPath(path, hillPaint)
            // Front: bright at the top, with a fine light edge.
            hillPath(0f, x, cy, maxH, lift * 0.52f, x, breathe, pk)
            hillPaint.shader = frontShader
            canvas.drawPath(path, hillPaint)
            hillPaint.shader = null
            edgePaint.strokeWidth = 1f * density
            edgePaint.color = Color.argb(110, 255, 255, 255)
            canvas.drawPath(path, edgePaint)
            // The played line, brightening toward the thumb.
            lineShader = android.graphics.LinearGradient(0f, 0f, x.coerceAtLeast(1f), 0f, c, light, android.graphics.Shader.TileMode.CLAMP)
            bar.shader = lineShader
            canvas.drawRoundRect(0f, cy - th / 2f, x, cy + th / 2f, th / 2f, th / 2f, bar)
            bar.shader = null
        }
        // The knob.
        val tr = (if (dragging) 8.5f else 7f) * density
        val tx = x.coerceIn(tr, width - tr)
        val g = tr * (2f + breathe * 0.7f)
        glowPaint.shader = android.graphics.RadialGradient(tx, cy, g, Color.argb((80 + 90 * breathe).toInt().coerceAtMost(190), Color.red(c), Color.green(c), Color.blue(c)), Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawCircle(tx, cy, g, glowPaint)
        bar.color = light
        canvas.drawCircle(tx, cy, tr, bar)
        bar.color = c
        ringPaint.strokeWidth = 2.4f * density
        canvas.drawCircle(tx, cy, tr - 1.2f * density, ringPaint)
        if ((playing || kotlin.math.abs(breathe - 0.3f) > 0.02f) && isShown) postInvalidateOnAnimation() else lastT = 0L
    }
}

/** A small circular progress ring; spins when progress is unknown (-1). */
class ProgressRing(context: Context) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(55, 255, 255, 255)
        strokeCap = Paint.Cap.ROUND
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }
    private val box = RectF()

    var color: Int = Color.WHITE
        set(value) {
            field = value
            arc.color = value
            invalidate()
        }

    var progress: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val sw = min(width, height) * 0.16f
        track.strokeWidth = sw
        arc.strokeWidth = sw
        box.set(sw / 2f, sw / 2f, width - sw / 2f, height - sw / 2f)
        canvas.drawOval(box, track)
        if (progress < 0f) {
            val start = (SystemClock.uptimeMillis() % 1200L) / 1200f * 360f
            canvas.drawArc(box, start, 90f, false, arc)
            if (isShown) postInvalidateOnAnimation()
        } else {
            canvas.drawArc(box, -90f, 360f * progress.coerceIn(0f, 1f), false, arc)
        }
    }
}

object BitmapFx {
    /** Cheap, smooth blur for album-art backdrops: shrink hard, then scale back up with filtering. */
    fun blur(src: Bitmap?): Bitmap? {
        src ?: return null
        return try {
            val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
            val tiny = Bitmap.createScaledBitmap(soft, 12, 12, true)
            val mid = Bitmap.createScaledBitmap(tiny, 48, 48, true)
            Bitmap.createScaledBitmap(mid, 192, 192, true)
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * Soft rings that ripple out from the middle (behind a caller's photo while
 * it rings or while you talk). Runs only while it's on screen.
 */
class PulseView(context: Context) : View(context) {
    var color = Color.WHITE
    /** 0 = still, 1 = full strength. */
    var strength = 1f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var anim: android.animation.ValueAnimator? = null
    private var phase = 0f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800L
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r0 = minOf(width, height) * 0.3f
        val r1 = minOf(width, height) / 2f - 2f
        for (k in 0 until 2) {
            val t = (phase + k * 0.5f) % 1f
            val r = r0 + (r1 - r0) * t
            p.strokeWidth = 2.5f * resources.displayMetrics.density * (1f - t * 0.6f)
            p.color = Color.argb((150 * (1f - t) * strength).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
            c.drawCircle(cx, cy, r, p)
        }
    }
}

/**
 * A padlock drawn in code. [open] 0 = locked, 1 = unlocked: the shackle
 * lifts and swings open (animate it for the unlock moment).
 */
class LockGlyph(context: Context) : View(context) {
    var open = 0f
        set(v) {
            field = v
            invalidate()
        }
    var color = Color.WHITE
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shackle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = android.graphics.RectF()
    private val path = android.graphics.Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // Drawn a little smaller and sitting low in its box, so the shackle
        // has room to spring up and swing open (with its overshoot) without
        // being cut off at the top.
        val s = minOf(w, h) * 0.84f
        val ox = (w - s) / 2f + s * 0.06f
        val oy = h - s * 0.97f
        val open = open.coerceIn(-0.1f, 1.12f)
        body.color = color
        shackle.color = color
        shackle.strokeWidth = s * 0.12f
        // Body: a rounded block in the lower half.
        rect.set(ox + s * 0.16f, oy + s * 0.46f, ox + s * 0.84f, oy + s * 0.94f)
        c.drawRoundRect(rect, s * 0.12f, s * 0.12f, body)
        // Shackle: an arch over the body that lifts and pivots on its left leg as it opens.
        c.save()
        val lift = s * 0.12f * open
        val pivotX = ox + s * 0.3f
        val pivotY = oy + s * 0.48f
        c.translate(0f, -lift)
        c.rotate(-28f * open, pivotX, pivotY)
        rect.set(ox + s * 0.3f, oy + s * 0.1f, ox + s * 0.7f, oy + s * 0.62f)
        path.reset()
        path.moveTo(ox + s * 0.3f, oy + s * 0.48f)
        path.arcTo(rect, 180f, 180f)
        path.lineTo(ox + s * 0.7f, oy + s * 0.48f - s * 0.1f * open)
        c.drawPath(path, shackle)
        c.restore()
    }
}

/** A small battery: outline, a cap, and a fill in [color] for [level] (0..1). */
class BatteryGlyph(context: Context) : View(context) {
    var level = 1f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    var color = Color.WHITE
    var charging = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = android.graphics.RectF()

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val cap = 2f * d
        // Outline
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.3f * d
        p.color = Color.argb(140, 255, 255, 255)
        r.set(p.strokeWidth, p.strokeWidth, w - cap - p.strokeWidth, h - p.strokeWidth)
        c.drawRoundRect(r, 3f * d, 3f * d, p)
        // Cap
        p.style = Paint.Style.FILL
        r.set(w - cap, h * 0.32f, w, h * 0.68f)
        c.drawRoundRect(r, d, d, p)
        // Fill
        p.color = color
        val inset = 2.6f * d
        val fw = (w - cap - inset * 2) * level
        r.set(inset, inset, inset + fw, h - inset)
        c.drawRoundRect(r, 1.6f * d, 1.6f * d, p)
    }
}

/**
 * A tick that draws itself on: [progress] 0..1 traces the stroke from the
 * short leg to the tip, so it reads as being "written" rather than faded in.
 */
class CheckGlyph(context: Context) : View(context) {
    var progress = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    var color = Color.WHITE
        set(v) {
            field = v
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val full = android.graphics.Path()
    private val part = android.graphics.Path()
    private val measure = android.graphics.PathMeasure()
    private var builtFor = 0

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        if (s <= 0f) return
        val key = width * 31 + height
        if (key != builtFor) {
            builtFor = key
            val ox = (width - s) / 2f
            val oy = (height - s) / 2f
            full.reset()
            full.moveTo(ox + s * 0.2f, oy + s * 0.53f)
            full.lineTo(ox + s * 0.42f, oy + s * 0.74f)
            full.lineTo(ox + s * 0.82f, oy + s * 0.3f)
            measure.setPath(full, false)
        }
        paint.color = color
        paint.strokeWidth = s * 0.13f
        part.reset()
        measure.getSegment(0f, measure.length * progress, part, true)
        c.drawPath(part, paint)
    }
}


/**
 * Soft waves in the album's colour rolling along the bottom of the music
 * card, moving with the song: the back wave with the bass, the middle with
 * the mids, the front with the highs (when Halo can hear the music).
 * Calm and low when paused.
 */
class AmbientWave(context: Context) : View(context) {
    var color: Int = Color.WHITE
        set(v) {
            field = v
            invalidate()
        }
    var playing = false
        set(v) {
            field = v
            invalidate()
        }
    private val phase = FloatArray(3)
    private val lift = floatArrayOf(0.3f, 0.3f, 0.3f)
    private var lastT = 0L
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = android.graphics.Path()
    private var shaders: Array<android.graphics.Shader>? = null
    private var shadersFor = 0

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastT == 0L) 0.016f else ((now - lastT) / 1000f).coerceIn(0f, 0.05f)
        lastT = now
        val hear = playing && Beat.fresh()
        val w = width.toFloat()
        val h = height.toFloat()
        if (shaders == null || shadersFor != color + height) {
            shadersFor = color + height
            shaders = Array(3) { layer ->
                android.graphics.LinearGradient(0f, h * 0.2f, 0f, h, Color.argb(75 - layer * 15, Color.red(color), Color.green(color), Color.blue(color)), Color.argb(8, Color.red(color), Color.green(color), Color.blue(color)), android.graphics.Shader.TileMode.CLAMP)
            }
        }
        var moving = false
        for (layer in 0 until 3) {
            // Each wave follows its own part of the sound; quick up, slower down, like a meter.
            val level = if (hear) Beat.levels[layer].coerceIn(0f, 1f) else if (playing) 0.45f + 0.1f * kotlin.math.sin(now / 900f + layer) else 0.2f
            val rate = if (level > lift[layer]) 14f else 4f
            lift[layer] += (level - lift[layer]) * (1f - kotlin.math.exp(-rate * dt))
            if (playing) phase[layer] += dt * (1.2f + layer * 0.5f + lift[layer] * 1.5f)
            if (kotlin.math.abs(level - lift[layer]) > 0.01f) moving = true
            val a = h * 0.28f * lift[layer]
            val base = h * (0.5f + layer * 0.12f)
            val wl = w * (0.85f + layer * 0.3f)
            path.reset()
            path.moveTo(0f, h)
            var x = 0f
            while (x <= w) {
                path.lineTo(x, base - a * kotlin.math.sin(x / wl * 6.2832f + phase[layer]))
                x += 6f
            }
            path.lineTo(w, h)
            path.close()
            p.shader = shaders!![layer]
            c.drawPath(path, p)
        }
        if ((playing || moving) && isShown) postInvalidateOnAnimation() else lastT = 0L
    }
}


/**
 * The stages of a ride or delivery as a track: dots for placed, preparing,
 * on the way and arriving, joined by a line that fills up to now, with a
 * little car or scooter that drives along to the current stage (and bobs
 * gently while it's on the way).
 */
class StageTrack(context: Context) : View(context) {
    var stage = 0
        set(v) {
            field = v
            invalidate()
        }
    var ride = false
    var vehicle: android.graphics.drawable.Drawable? = null
    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shown = -1f
    private var lastT = 0L

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastT == 0L) 0.016f else ((now - lastT) / 1000f).coerceIn(0f, 0.05f)
        lastT = now
        // Where the vehicle is along the track (0..1), driving smoothly to the stage.
        val target = (stage.coerceIn(0, 4).coerceAtMost(3) / 3f)
        if (shown < 0f) shown = 0f
        shown += (target - shown) * (1f - kotlin.math.exp(-3.5f * dt))
        val pad = 10f * d
        val w = width - pad * 2
        val cy = height * 0.68f
        line.strokeCap = Paint.Cap.ROUND
        line.strokeWidth = 4f * d
        line.color = Color.argb(46, 255, 255, 255)
        c.drawLine(pad, cy, pad + w, cy, line)
        val done = if (stage >= 4) Color.rgb(52, 199, 89) else Color.WHITE
        line.color = done
        c.drawLine(pad, cy, pad + w * shown, cy, line)
        for (i in 0..3) {
            val x = pad + w * i / 3f
            val reached = shown * 3f >= i - 0.02f
            dot.color = if (reached) done else Color.argb(70, 255, 255, 255)
            c.drawCircle(x, cy, (if (i == stage.coerceAtMost(3)) 6f else 4.5f) * d, dot)
            if (reached) {
                dot.color = Color.BLACK
                c.drawCircle(x, cy, 1.8f * d, dot)
            }
        }
        // The vehicle rides above the line where things are now.
        vehicle?.let { v ->
            val s = (20f * d).toInt()
            val bob = if (stage in 1..3) kotlin.math.sin(now / 160f) * 1.2f * d else 0f
            val x = (pad + w * shown - s / 2f).toInt()
            val y = (cy - 8f * d - s + bob).toInt()
            v.setTint(done)
            v.setBounds(x, y, x + s, y + s)
            v.draw(c)
        }
        val moving = kotlin.math.abs(target - shown) > 0.002f || stage in 1..3
        if (moving && isShown) postInvalidateOnAnimation() else lastT = 0L
    }
}

/**
 * Progress that finishes with a flourish, used everywhere something
 * completes (downloads, uploads, installs, saved recordings): a ring that
 * fills as it goes; on [complete] it sweeps to full and turns green, a green
 * disc pops up inside it with a bounce, and a white tick draws itself on it.
 * Spins while the amount is unknown (progress -1).
 */
class DoneRing(context: Context) : View(context) {
    var color: Int = Color.WHITE
        set(v) {
            field = v
            invalidate()
        }
    var doneColor: Int = Color.rgb(52, 199, 89)
    var progress = 0f
        set(v) {
            field = v
            invalidate()
        }
    /** Ring thickness as a share of its size (thinner for big rings). */
    var thickness = 0.13f
    /** 0 = a ring, 1 = the finished tick. */
    private var t = 0f
    private var anim: android.animation.ValueAnimator? = null
    private val d = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val oval = RectF()
    private val full = android.graphics.Path()
    private val part = android.graphics.Path()
    private val pm = android.graphics.PathMeasure()
    // A lively pop that still never grows past the ring (or it would be clipped into a square).
    private val pop = android.view.animation.OvershootInterpolator(2f)

    val done get() = t >= 1f

    /** Finish: animated (the flourish) or straight to the tick. */
    fun complete(animate: Boolean = true) {
        if (t >= 1f || anim?.isRunning == true) return
        if (!animate) {
            t = 1f
            invalidate()
            return
        }
        val from = t
        anim = android.animation.ValueAnimator.ofFloat(from, 1f).apply {
            duration = 760L
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun mix(a: Int, b: Int, f: Float) = Color.argb(
        255,
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt(),
    )

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val sw = max(2f * d, size * thickness)
        val r = size / 2f - sw / 2f
        oval.set(cx - r, cy - r, cx + r, cy + r)
        // 1) The ring sweeps to full and turns green (first third).
        val p1 = (t / 0.3f).coerceIn(0f, 1f)
        // 2) The green disc pops up inside it (overlapping).
        val p2 = ((t - 0.22f) / 0.42f).coerceIn(0f, 1f)
        // 3) The tick draws itself on the disc.
        val p3 = ((t - 0.42f) / 0.5f).coerceIn(0f, 1f)
        val ringAlpha = 1f - p2
        if (ringAlpha > 0.01f) {
            ring.strokeWidth = sw
            ring.color = mix(color, doneColor, p1)
            ring.alpha = (60 * ringAlpha).toInt()
            c.drawOval(oval, ring)
            ring.alpha = (255 * ringAlpha).toInt()
            if (progress < 0f && t <= 0f) {
                // Unknown amount: a short arc going round.
                val a = (SystemClock.uptimeMillis() % 1100L) / 1100f * 360f
                c.drawArc(oval, a - 90f, 90f, false, ring)
                if (isShown) postInvalidateOnAnimation()
            } else {
                val shown = max(0f, progress) + (1f - max(0f, progress)) * p1
                c.drawArc(oval, -90f, 360f * shown, false, ring)
            }
        }
        if (p2 > 0f) {
            val s = pop.getInterpolation(p2)
            disc.color = doneColor
            c.drawCircle(cx, cy, min(size / 2f, (size / 2f) * 0.86f * s), disc)
        }
        if (p3 > 0f) {
            full.reset()
            full.moveTo(cx - size * 0.22f, cy + size * 0.01f)
            full.lineTo(cx - size * 0.06f, cy + size * 0.17f)
            full.lineTo(cx + size * 0.24f, cy - size * 0.15f)
            pm.setPath(full, false)
            part.reset()
            pm.getSegment(0f, pm.length * p3, part, true)
            tick.strokeWidth = max(2f * d, size * 0.12f)
            c.drawPath(part, tick)
        }
    }
}

/**
 * A ring that ripples out once and fades, like a ping, around something that
 * just arrived (a new notification's photo). Plays by itself when shown.
 */
class PingRing(context: Context) : View(context) {
    var color: Int = Color.WHITE
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var t = 1f
    private var anim: android.animation.ValueAnimator? = null

    fun play(delay: Long = 0L) {
        anim?.cancel()
        anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = delay
            duration = 720L
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        if (t >= 1f) return
        val base = min(width, height) / 2f * 0.62f
        val r = base + (min(width, height) / 2f - base) * t
        p.strokeWidth = max(1f, 2.2f * d * (1f - t))
        p.color = color
        p.alpha = (200 * (1f - t)).toInt()
        c.drawCircle(width / 2f, height / 2f, r - p.strokeWidth / 2f, p)
    }
}

/**
 * The download (upload, install) bar: a thick rounded bar that glides to each
 * new amount instead of jumping, with a soft light running along the filled
 * part while it's moving. Paused: grey and still. Unknown amount: a short
 * piece sliding back and forth. Done: it fills, turns green and a bright
 * sweep runs across it once.
 */
class TransferBar(context: Context) : View(context) {
    var color: Int = Color.WHITE
        set(v) {
            field = v
            invalidate()
        }
    var doneColor: Int = Color.rgb(52, 199, 89)
    /** Moving (shows the running light); false while paused. */
    var active = true
        set(v) {
            field = v
            invalidate()
        }
    private var shown = 0f
    private var target = 0f
    private var glide: android.animation.ValueAnimator? = null
    private var doneT = 0f
    private var doneAnim: android.animation.ValueAnimator? = null
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(36, 255, 255, 255) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val clip = android.graphics.Path()

    /** 0..1, or -1 when the app doesn't say how far along it is. */
    var fraction: Float
        get() = target
        set(v) {
            if (doneT > 0f) return
            if (v < 0f) {
                target = -1f
                invalidate()
                return
            }
            val to = v.coerceIn(0f, 1f)
            if (target < 0f || !isAttachedToWindow) {
                target = to
                shown = to
                invalidate()
                return
            }
            if (to == target) return
            target = to
            glide?.cancel()
            glide = android.animation.ValueAnimator.ofFloat(shown, to).apply {
                duration = 520L
                interpolator = android.view.animation.DecelerateInterpolator(1.8f)
                addUpdateListener {
                    shown = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

    /** Fill up, turn green, one bright sweep. */
    fun complete() {
        if (doneT > 0f || doneAnim?.isRunning == true) return
        glide?.cancel()
        val from = max(0f, shown)
        doneAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900L
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                doneT = max(0.0001f, it.animatedValue as Float)
                shown = from + (1f - from) * android.view.animation.DecelerateInterpolator(2f).getInterpolation((doneT / 0.4f).coerceAtMost(1f))
                invalidate()
            }
            start()
        }
        target = 1f
    }

    private fun mix(a: Int, b: Int, f: Float) = Color.argb(
        255,
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt(),
    )

    override fun onDetachedFromWindow() {
        glide?.cancel()
        doneAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val rad = h / 2f
        r.set(0f, 0f, w, h)
        c.drawRoundRect(r, rad, rad, track)
        val now = SystemClock.uptimeMillis()
        val green = (doneT / 0.4f).coerceIn(0f, 1f)
        fill.color = mix(color, doneColor, green)
        if (target < 0f && doneT <= 0f) {
            // Unknown amount: a piece sliding back and forth.
            val p = (now % 1600L) / 1600f
            val tri = if (p < 0.5f) p * 2f else 2f - p * 2f
            val seg = w * 0.32f
            val x = (w - seg) * android.view.animation.AccelerateDecelerateInterpolator().getInterpolation(tri)
            r.set(x, 0f, x + seg, h)
            c.drawRoundRect(r, rad, rad, fill)
            if (isShown) postInvalidateOnAnimation()
            return
        }
        val fw = max(h, w * shown)
        if (shown <= 0f) return
        r.set(0f, 0f, fw, h)
        c.drawRoundRect(r, rad, rad, fill)
        // The running light (moving) or the one bright sweep (done).
        val sweep = when {
            doneT > 0f -> ((doneT - 0.35f) / 0.65f).takeIf { it in 0f..1f }
            active -> (now % 1500L) / 1500f
            else -> null
        }
        if (sweep != null) {
            val band = max(w * 0.22f, 40f * d)
            val cx = -band + (fw + band * 2f) * sweep
            val a = if (doneT > 0f) 200 else 90
            shine.shader = android.graphics.LinearGradient(
                cx - band, 0f, cx + band, 0f,
                intArrayOf(Color.TRANSPARENT, Color.argb(a, 255, 255, 255), Color.TRANSPARENT),
                null, android.graphics.Shader.TileMode.CLAMP,
            )
            clip.reset()
            clip.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW)
            c.save()
            c.clipPath(clip)
            c.drawRect(r, shine)
            c.restore()
        }
        if ((active && doneT <= 0f) || doneAnim?.isRunning == true) {
            if (isShown) postInvalidateOnAnimation()
        }
    }
}
