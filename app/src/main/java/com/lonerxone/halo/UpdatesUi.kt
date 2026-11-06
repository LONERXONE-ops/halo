package com.lonerxone.halo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * A release's notes as the app shows them: the first line is the headline,
 * a short line on its own starts a section, and "•" lines are its points.
 * Sign-off lines (Co-Authored-By, links) are dropped.
 */
object ReleaseNotes {
    class Section(val title: String, val points: MutableList<String> = ArrayList())

    fun parse(body: String): Pair<String, List<Section>> {
        val lines = body.lines().map { it.trim() }.filter { l ->
            l.isNotEmpty() && !l.startsWith("Co-Authored-By", true) && !l.startsWith("Claude-Session", true) && !l.startsWith("http")
        }
        if (lines.isEmpty()) return "" to emptyList()
        val headline = lines.first().removePrefix("•").trim()
        val sections = ArrayList<Section>()
        for (l in lines.drop(1)) {
            val point = l.startsWith("•") || l.startsWith("-") || l.startsWith("*")
            if (point) {
                if (sections.isEmpty()) sections += Section("")
                sections.last().points += l.trimStart('•', '-', '*', ' ')
            } else if (l.length <= 40 && !l.endsWith(".")) {
                sections += Section(l)
            } else {
                if (sections.isEmpty()) sections += Section("")
                sections.last().points += l
            }
        }
        return headline to sections.filter { it.points.isNotEmpty() }
    }

    /** The first few points, for a notification. */
    fun teaser(body: String, n: Int = 3): String =
        parse(body).second.flatMap { it.points }.take(n).joinToString("\n") { "• $it" }
}

/**
 * The ring around the logo on the Updates page:
 *  - checking: a short arc spinning;
 *  - downloading: fills to [progress] with a soft light on its tip;
 *  - up to date: a full green ring;
 *  - update ready: a full white ring that breathes.
 */
class UpdateRing(context: Context) : View(context) {
    enum class State { IDLE, CHECKING, DOWNLOADING, UP_TO_DATE, READY }

    var state = State.IDLE
        set(v) {
            if (field == v) return
            field = v
            start = System.nanoTime()
            invalidate()
        }
    private var shown = 0f
    var progress = 0f
    private var start = System.nanoTime()
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f * d
        color = Color.argb(34, 255, 255, 255)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val t = (System.nanoTime() - start) / 1e9f
        val pad = 6f * d
        r.set(pad, pad, width - pad, height - pad)
        c.drawOval(r, track)
        var keep = false
        when (state) {
            State.IDLE -> Unit
            State.CHECKING -> {
                arc.color = Color.WHITE
                val a = (t * 300f) % 360f
                c.drawArc(r, a - 90f, 70f + 40f * kotlin.math.sin(t * 3f), false, arc)
                keep = true
            }
            State.DOWNLOADING -> {
                // Glides to the real value instead of jumping.
                shown += (progress - shown) * 0.12f
                if (kotlin.math.abs(progress - shown) > 0.001f) keep = true
                arc.color = Colors.BLUE
                val sweep = 360f * shown.coerceIn(0f, 1f)
                c.drawArc(r, -90f, sweep, false, arc)
                // A light riding the tip.
                val ang = Math.toRadians((sweep - 90f).toDouble())
                val cx = r.centerX() + (r.width() / 2f) * Math.cos(ang).toFloat()
                val cy = r.centerY() + (r.height() / 2f) * Math.sin(ang).toFloat()
                glow.color = Color.argb((120 + 80 * kotlin.math.sin(t * 6f)).toInt().coerceIn(0, 255), 255, 255, 255)
                c.drawCircle(cx, cy, 4.5f * d, glow)
                keep = true
            }
            State.UP_TO_DATE -> {
                // Draws itself round once, then rests.
                val f = (t / 0.7f).coerceIn(0f, 1f)
                val e = 1f - (1f - f) * (1f - f) * (1f - f)
                arc.color = Colors.GREEN
                c.drawArc(r, -90f, 360f * e, false, arc)
                keep = f < 1f
            }
            State.READY -> {
                val a = 0.55f + 0.45f * ((kotlin.math.sin(t * 2.4f) + 1f) / 2f)
                arc.color = Color.argb((255 * a).toInt(), 255, 255, 255)
                c.drawArc(r, -90f, 360f, false, arc)
                keep = true
            }
        }
        if (keep && isShown) postInvalidateOnAnimation()
    }
}
