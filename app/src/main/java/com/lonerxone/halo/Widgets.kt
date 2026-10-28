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
