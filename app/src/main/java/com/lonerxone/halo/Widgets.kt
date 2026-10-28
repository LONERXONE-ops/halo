package com.lonerxone.halo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.max
import kotlin.math.sin

/** Four rounded bars that dance while music plays and rest as dots when paused. */
class WaveformView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val phases = floatArrayOf(0f, 1.7f, 3.1f, 4.4f)
    private val speeds = floatArrayOf(7.0f, 9.5f, 6.2f, 8.4f)

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

    override fun onDraw(canvas: Canvas) {
        val n = 4
        val bw = width / (n * 2f - 1f)
        paint.strokeWidth = bw
        val t = SystemClock.uptimeMillis() / 1000f
        for (i in 0 until n) {
            val amp = if (playing) 0.35f + 0.65f * (0.5f + 0.5f * sin(t * speeds[i] + phases[i])) else 0f
            val bh = max(bw, height * amp)
            val x = bw / 2f + i * bw * 2f
            val y0 = (height - bh) / 2f + bw / 2f
            val y1 = (height + bh) / 2f - bw / 2f
            canvas.drawLine(x, y0, x, max(y0, y1), paint)
        }
        if (playing && isShown) postInvalidateOnAnimation()
    }
}

/** Thin rounded track with a filled part, for song progress. */
class ProgressLine(context: Context) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(51, 255, 255, 255) }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    var color: Int = Color.WHITE
        set(value) {
            field = value
            bar.color = value
            invalidate()
        }

    var fraction: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val r = h / 2f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), h, r, r, track)
        val w = width * fraction
        if (w > 0.5f) canvas.drawRoundRect(0f, 0f, max(w, h), h, r, r, bar)
    }
}
