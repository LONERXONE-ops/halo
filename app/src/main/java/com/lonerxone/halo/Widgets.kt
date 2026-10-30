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
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

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

    override fun onDraw(canvas: Canvas) {
        val shown = if (dragging) dragValue else fraction
        val th = if (dragging) 6f * density else 4f * density
        val top = (height - th) / 2f
        val r = th / 2f
        canvas.drawRoundRect(0f, top, width.toFloat(), top + th, r, r, track)
        val w = width * shown
        if (w > 0.5f) canvas.drawRoundRect(0f, top, max(w, th), top + th, r, r, bar)
        if (dragging) canvas.drawCircle(w.coerceIn(r, width - r), height / 2f, 7f * density, thumb)
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
