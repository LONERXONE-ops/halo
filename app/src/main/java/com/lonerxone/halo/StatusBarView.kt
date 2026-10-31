package com.lonerxone.halo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.View
import java.util.Date
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Everything the custom status bar shows, kept up to date by HaloService.
 */
object StatusData {
    var battery = 100
    var charging = false
    var powerSave = false
    /** Signal level 0..4 per SIM (empty: no SIM / no service). */
    var signal: List<Int> = emptyList()
    /** "5G", "4G", "LTE", "3G"… for each SIM ("" when unknown). */
    var netType: List<String> = emptyList()
    var wifi = false
    var wifiLevel = 0
    var bluetooth = false
    var silent = false
    var vibrate = false
    var dnd = false
    var alarm = false
    var airplane = false
    /** Download speed in bytes a second (when shown). */
    var speed = 0L
    /** App icons of what's waiting in the notification center, newest first, and how many apps. */
    var notifIcons: List<Bitmap?> = emptyList()
    var notifAccents: List<Int> = emptyList()
    var notifApps = 0
}

/**
 * Halo's own status bar: the time, notification icons, mobile signal,
 * Wi-Fi, Bluetooth and battery, drawn in a strip over the system one.
 *
 * Designed around the island, like an iPhone's: the time sits on the left,
 * the rest on the right, with the camera and island between them. When the
 * island grows into that space, whatever it would touch fades away and
 * comes back as it shrinks, so the two never collide.
 *
 * It never takes a touch: swiping down on it still pulls down the
 * notification shade. Everything is drawn by hand on one canvas (no views
 * to lay out), and it only animates while something moves (charging, a
 * fade, the time's seconds).
 */
class StatusBarView(context: Context) : View(context) {
    /** Shown on top of an app (black), or clear over Halo Launcher and its lock screen. */
    var clear = false
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    /** In Halo's settings: a preview with sample values that always animates its charging. */
    var preview = false

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    // Island span (where the island is right now), so things beside it can make way.
    private var islandL = 0f
    private var islandR = 0f
    private var islandOn = false

    fun setIsland(left: Float, right: Float, visible: Boolean) {
        if (left == islandL && right == islandR && visible == islandOn) return
        islandL = left
        islandR = right
        islandOn = visible
        invalidate()
    }

    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val small = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val r = RectF()
    private val path = Path()
    private val m = Matrix()

    // Each item fades in and out on its own (when the island needs the room).
    private val alphas = HashMap<String, Float>()
    private var lastFrame = 0L
    private var moving = false

    // Charging: the moment you plug in, the battery fills from empty to its level.
    private var wasCharging = false
    private var burstAt = 0L

    private fun prefs() = Prefs.get(context)

    override fun onDraw(c: Canvas) {
        val p = prefs()
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        moving = false
        val h = height.toFloat()
        val w = width.toFloat()
        val size = p.getInt(Prefs.SB_SIZE, 1)
        val k = listOf(0.88f, 1f, 1.12f)[size.coerceIn(0, 2)]
        val ink = COLOURS[p.getInt(Prefs.SB_COLOR, 0).coerceIn(0, COLOURS.size - 1)]
        if (!clear) c.drawColor(when (p.getInt(Prefs.SB_BG, 0)) {
            1 -> Color.rgb(22, 22, 25)
            else -> Color.BLACK
        })
        val cy = h / 2f + dp(1f)
        val full = p.getInt(Prefs.SB_LAYOUT, 0) == 1
        // Keep clear of rounded screen corners.
        val padL = dp(26f)
        val padR = dp(24f)

        // ----- Left: the time, then notification icons -----
        var x = padL
        text.color = ink
        text.textSize = dp(14.5f) * k
        text.typeface = when (p.getInt(Prefs.SB_TIME, 0)) {
            1 -> Typeface.create(Typeface.DEFAULT, 500, false)
            2 -> Typeface.create(Typeface.DEFAULT, 300, false)
            3 -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            else -> Typeface.create(Typeface.DEFAULT, 700, false)
        }
        text.fontFeatureSettings = "tnum"
        val sec = p.getBoolean(Prefs.SB_SECONDS, false)
        val h24 = DateFormat.is24HourFormat(context)
        var time = DateFormat.format(if (h24) (if (sec) "HH:mm:ss" else "HH:mm") else (if (sec) "h:mm:ss" else "h:mm"), Date()).toString()
        if (p.getBoolean(Prefs.SB_DATE, false)) time = DateFormat.format("EEE d", Date()).toString() + "  " + time
        val tw = text.measureText(time)
        item(c, "time", x, x + tw, dt) { a ->
            text.alpha = (255 * a).toInt()
            c.drawText(time, x, cy + text.textSize * 0.36f, text)
        }
        x += tw + dp(7f)

        val notifStyle = p.getInt(Prefs.SB_NOTIFS, 0)
        val apps = if (preview) 3 else StatusData.notifApps
        if (notifStyle != 3 && apps > 0) {
            val s = dp(14f) * k
            when (notifStyle) {
                // Little round app icons, overlapping like a stack of avatars.
                0 -> {
                    val shown = min(apps, if (full) 4 else 3)
                    val step = s * 0.68f
                    val width = s + step * (shown - 1) + if (apps > shown) dp(16f) else 0f
                    item(c, "notifs", x, x + width, dt) { a ->
                        for (i in shown - 1 downTo 0) {
                            val ix = x + step * i + s / 2f
                            fill.color = if (clear) Color.argb((140 * a).toInt(), 0, 0, 0) else Color.BLACK
                            c.drawCircle(ix, cy, s / 2f + dp(1.3f), fill)
                            val bmp = StatusData.notifIcons.getOrNull(i)
                            if (bmp != null) {
                                drawRoundBitmap(c, bmp, ix, cy, s / 2f, a)
                            } else {
                                fill.color = withAlpha(StatusData.notifAccents.getOrNull(i)?.takeIf { it != 0 } ?: SAMPLE[i % SAMPLE.size], a)
                                c.drawCircle(ix, cy, s / 2f, fill)
                            }
                        }
                        if (apps > shown) {
                            small.color = withAlpha(ink, a * 0.85f)
                            small.textSize = dp(10f) * k
                            small.typeface = Typeface.create(Typeface.DEFAULT, 700, false)
                            c.drawText("+${apps - shown}", x + s + step * (shown - 1) + dp(3f), cy + small.textSize * 0.36f, small)
                        }
                    }
                    x += width + dp(6f)
                }
                // Coloured dots, one per app.
                1 -> {
                    val shown = min(apps, 4)
                    val dot = dp(5.5f) * k
                    val width = shown * (dot + dp(3f))
                    item(c, "notifs", x, x + width, dt) { a ->
                        for (i in 0 until shown) {
                            fill.color = withAlpha(StatusData.notifAccents.getOrNull(i)?.takeIf { it != 0 && it != Color.BLACK } ?: ink, a)
                            c.drawCircle(x + i * (dot + dp(3f)) + dot / 2f, cy, dot / 2f, fill)
                        }
                    }
                    x += width + dp(4f)
                }
                // A count in a little pill.
                else -> {
                    small.textSize = dp(10.5f) * k
                    small.typeface = Typeface.create(Typeface.DEFAULT, 700, false)
                    val label = apps.toString()
                    val pw = small.measureText(label) + dp(10f)
                    item(c, "notifs", x, x + pw, dt) { a ->
                        fill.color = withAlpha(ink, a * 0.22f)
                        r.set(x, cy - dp(8f), x + pw, cy + dp(8f))
                        c.drawRoundRect(r, dp(8f), dp(8f), fill)
                        small.color = withAlpha(ink, a)
                        c.drawText(label, x + dp(5f), cy + small.textSize * 0.36f, small)
                    }
                    x += pw + dp(4f)
                }
            }
        }

        // ----- Right: battery, then Wi-Fi, signal, Bluetooth and the rest, leftwards -----
        var rx = w - padR
        val charging = if (preview) true else StatusData.charging
        val level = if (preview) 72 else StatusData.battery
        if (charging && !wasCharging) burstAt = now
        wasCharging = charging
        val bw = batteryWidth(p, k, level)
        item(c, "battery", rx - bw, rx, dt) { a -> drawBattery(c, p, rx - bw, cy, k, level, charging, ink, a, now) }
        rx -= bw + dp(6f)

        if (p.getBoolean(Prefs.SB_WIFI, true) && (StatusData.wifi || preview)) {
            val ww = dp(15f) * k
            item(c, "wifi", rx - ww, rx, dt) { a -> drawWifi(c, rx - ww, cy, ww, if (preview) 3 else StatusData.wifiLevel, ink, a) }
            rx -= ww + dp(5f)
        }
        val sigStyle = p.getInt(Prefs.SB_SIGNAL, 0)
        val sims = if (preview) listOf(3) else StatusData.signal
        if (sigStyle != 2 && !StatusData.airplane) {
            val shownSims = if (p.getBoolean(Prefs.SB_DUAL, true)) sims else sims.take(1)
            for ((i, lvl) in shownSims.withIndex().reversed()) {
                val sw = (if (sigStyle == 1) dp(17f) else dp(15f)) * k
                item(c, "sig$i", rx - sw, rx, dt) { a ->
                    if (sigStyle == 1) drawDots(c, rx - sw, cy, sw, lvl, ink, a, k) else drawBars(c, rx - sw, cy, sw, lvl, ink, a, k)
                }
                rx -= sw + dp(3f)
                val type = (if (preview) "5G" else StatusData.netType.getOrNull(i)).orEmpty()
                if (p.getBoolean(Prefs.SB_NETTYPE, true) && type.isNotEmpty() && !StatusData.wifi) {
                    small.textSize = dp(10.5f) * k
                    small.typeface = Typeface.create(Typeface.DEFAULT, 700, false)
                    val nw = small.measureText(type)
                    item(c, "net$i", rx - nw, rx, dt) { a ->
                        small.color = withAlpha(ink, a)
                        c.drawText(type, rx - nw, cy + small.textSize * 0.36f, small)
                    }
                    rx -= nw + dp(3f)
                }
                rx -= dp(3f)
            }
        }
        if (StatusData.airplane) {
            rx = icon(c, "air", R.drawable.ic_airplane, rx, cy, k, ink, dt)
        }
        if (p.getBoolean(Prefs.SB_BT, true) && (StatusData.bluetooth || preview)) {
            rx = icon(c, "bt", R.drawable.ic_bluetooth, rx, cy, k, ink, dt)
        }
        if (full || p.getBoolean(Prefs.SB_EXTRAS, true)) {
            if (StatusData.dnd) rx = icon(c, "dnd", R.drawable.ic_moon, rx, cy, k, Color.rgb(160, 150, 255), dt)
            if (StatusData.silent) rx = icon(c, "ring", R.drawable.ic_bell_off, rx, cy, k, ink, dt)
            else if (StatusData.vibrate) rx = icon(c, "ring", R.drawable.ic_vibrate, rx, cy, k, ink, dt)
            if (StatusData.alarm) rx = icon(c, "alarm", R.drawable.ic_timer, rx, cy, k, ink, dt)
        }
        if (p.getBoolean(Prefs.SB_SPEED, false) && (StatusData.speed > 0 || preview)) {
            small.textSize = dp(9.5f) * k
            small.typeface = Typeface.create(Typeface.DEFAULT, 700, false)
            val label = speedText(if (preview) 1_400_000L else StatusData.speed)
            val sw = small.measureText(label)
            item(c, "speed", rx - sw, rx, dt) { a ->
                small.color = withAlpha(ink, a * 0.85f)
                c.drawText(label, rx - sw, cy + small.textSize * 0.36f, small)
            }
        }

        // Keep animating only while something moves.
        val chargeAnim = charging && p.getInt(Prefs.SB_CHARGE, 0) != 3
        if (moving || chargeAnim || now - burstAt < 1400L) postInvalidateOnAnimation() else lastFrame = 0L
    }

    /**
     * Draws one item through [draw] with its own fade: it fades out when the
     * island reaches it, and back in when the island moves away.
     */
    private inline fun item(c: Canvas, id: String, left: Float, right: Float, dt: Float, draw: (Float) -> Unit) {
        val margin = dp(7f)
        val hit = islandOn && right > islandL - margin && left < islandR + margin
        val target = if (hit) 0f else 1f
        var a = alphas[id] ?: target
        if (a != target) {
            a += (target - a) * (1f - Math.exp((-14f * dt).toDouble()).toFloat())
            if (kotlin.math.abs(a - target) < 0.01f) a = target else moving = true
        }
        alphas[id] = a
        if (a <= 0.01f) return
        // Fading items also shrink a touch towards their centre.
        val s = 0.85f + 0.15f * a
        if (s < 0.999f) {
            c.save()
            c.scale(s, s, (left + right) / 2f, height / 2f)
            draw(a)
            c.restore()
        } else {
            draw(a)
        }
    }

    private fun icon(c: Canvas, id: String, res: Int, rx: Float, cy: Float, k: Float, tint: Int, dt: Float): Float {
        val s = dp(13f) * k
        item(c, id, rx - s, rx, dt) { a ->
            val dr = context.getDrawable(res)?.mutate() ?: return@item
            dr.setTint(tint)
            dr.alpha = (255 * a).toInt()
            dr.setBounds((rx - s).toInt(), (cy - s / 2f).toInt(), rx.toInt(), (cy + s / 2f).toInt())
            dr.draw(c)
        }
        return rx - s - dp(6f)
    }

    private fun withAlpha(col: Int, a: Float) = Color.argb((Color.alpha(col) * a).toInt().coerceIn(0, 255), Color.red(col), Color.green(col), Color.blue(col))

    private fun drawRoundBitmap(c: Canvas, b: Bitmap, cx: Float, cy: Float, rad: Float, a: Float) {
        val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val sc = rad * 2f / min(b.width, b.height)
        m.reset()
        m.setScale(sc, sc)
        m.postTranslate(cx - b.width * sc / 2f, cy - b.height * sc / 2f)
        sh.setLocalMatrix(m)
        iconPaint.shader = sh
        iconPaint.alpha = (255 * a).toInt()
        c.drawCircle(cx, cy, rad, iconPaint)
        iconPaint.shader = null
    }

    // ----- Signal -----

    /** Four bars rising left to right; the ones above your signal are faint. */
    private fun drawBars(c: Canvas, x: Float, cy: Float, w: Float, level: Int, ink: Int, a: Float, k: Float) {
        val bw = w / 4f - dp(1.2f)
        val top = cy - dp(5.5f) * k
        val bottom = cy + dp(5.5f) * k
        for (i in 0 until 4) {
            val hh = (bottom - top) * (0.35f + 0.65f * i / 3f)
            val bx = x + i * (bw + dp(1.6f))
            r.set(bx, bottom - hh, bx + bw, bottom)
            fill.color = withAlpha(ink, a * if (i < level) 1f else 0.3f)
            c.drawRoundRect(r, bw / 2.4f, bw / 2.4f, fill)
        }
    }

    /** Four dots, filled up to your signal (the classic look). */
    private fun drawDots(c: Canvas, x: Float, cy: Float, w: Float, level: Int, ink: Int, a: Float, k: Float) {
        val dd = w / 4f - dp(0.8f)
        for (i in 0 until 4) {
            val dx = x + i * (dd + dp(1f)) + dd / 2f
            if (i < level) {
                fill.color = withAlpha(ink, a)
                c.drawCircle(dx, cy, dd / 2f, fill)
            } else {
                stroke.strokeWidth = dp(1f)
                stroke.color = withAlpha(ink, a * 0.6f)
                c.drawCircle(dx, cy, dd / 2f - dp(0.5f), stroke)
            }
        }
    }

    // ----- Wi-Fi -----

    private fun drawWifi(c: Canvas, x: Float, cy: Float, w: Float, level: Int, ink: Int, a: Float) {
        val cx = x + w / 2f
        val base = cy + w * 0.36f
        // Three wedges of a fan, lit up to the level, and the dot at the bottom.
        for (i in 3 downTo 1) {
            val rad = w * 0.62f * i / 3f
            r.set(cx - rad, base - rad, cx + rad, base + rad)
            path.reset()
            path.moveTo(cx, base)
            path.arcTo(r, 225f, 90f)
            path.close()
            fill.color = if (i <= level.coerceAtMost(3)) withAlpha(ink, a) else withAlpha(ink, a * 0.3f)
            c.save()
            if (i > 1) {
                // Cut out the inside so each arc reads as its own band.
                hole.reset()
                hole.addCircle(cx, base, w * 0.62f * (i - 1) / 3f + dp(1.3f), Path.Direction.CW)
                c.clipOutPath(hole)
            }
            c.drawPath(path, fill)
            c.restore()
        }
    }

    private val hole = Path()

    // ----- Battery -----

    private fun batteryWidth(p: android.content.SharedPreferences, k: Float, level: Int): Float {
        val pct = p.getBoolean(Prefs.SB_BATTERY_PCT, true)
        small.textSize = dp(10.5f) * k
        small.typeface = Typeface.create(Typeface.DEFAULT, 700, false)
        return when (p.getInt(Prefs.SB_BATTERY, 0)) {
            1 -> dp(25f) * k + if (pct) small.measureText("$level%") + dp(4f) else 0f
            2 -> dp(15f) * k
            3 -> dp(22f) * k + if (pct) small.measureText("$level%") + dp(4f) else 0f
            4 -> {
                text.textSize = dp(13f) * k
                text.measureText("$level%") + dp(12f)
            }
            else -> dp(27f) * k
        }
    }

    private fun batteryColour(level: Int, charging: Boolean, ink: Int) = when {
        charging -> Color.rgb(52, 199, 89)
        StatusData.powerSave -> Color.rgb(255, 214, 10)
        level <= 20 -> Color.rgb(255, 69, 58)
        else -> ink
    }

    /** How full to draw it: the real level, or (just after plugging in) filling up to it. */
    private fun shownLevel(level: Int, now: Long): Float {
        val t = (now - burstAt) / 900f
        if (burstAt == 0L || t >= 1f) return level / 100f
        val e = 1f - (1f - t) * (1f - t) * (1f - t)
        return level / 100f * e
    }

    private fun drawBattery(c: Canvas, p: android.content.SharedPreferences, x: Float, cy: Float, k: Float, level: Int, charging: Boolean, ink: Int, a: Float, now: Long) {
        val style = p.getInt(Prefs.SB_BATTERY, 0)
        val anim = p.getInt(Prefs.SB_CHARGE, 0)
        val col = batteryColour(level, charging, ink)
        val f = shownLevel(level, now)
        val t = (now % 100000L) / 1000f
        val pct = p.getBoolean(Prefs.SB_BATTERY_PCT, true)
        // Glow: a soft halo that breathes around the battery while charging.
        fun glow(left: Float, top: Float, right: Float, bottom: Float, rad: Float) {
            if (!charging || anim != 2) return
            val br = 0.5f + 0.5f * sin(t * 2.6f)
            for (i in 1..3) {
                val g = dp(1.6f) * i
                fill.color = Color.argb((30 * br * a / i).toInt(), 52, 199, 89)
                r.set(left - g, top - g, right + g, bottom + g)
                c.drawRoundRect(r, rad + g, rad + g, fill)
            }
        }
        when (style) {
            // Ring: a circle that fills round, with a bolt in it while charging.
            2 -> {
                val rad = dp(6.5f) * k
                val cx = x + rad + dp(1f)
                glow(cx - rad, cy - rad, cx + rad, cy + rad, rad)
                stroke.strokeWidth = dp(2.2f) * k
                stroke.strokeCap = Paint.Cap.ROUND
                stroke.color = withAlpha(ink, a * 0.28f)
                c.drawCircle(cx, cy, rad, stroke)
                stroke.color = withAlpha(col, a * pulse(charging, anim, t))
                r.set(cx - rad, cy - rad, cx + rad, cy + rad)
                c.drawArc(r, -90f, 360f * f, false, stroke)
                if (charging) bolt(c, cx, cy, rad * 0.9f, withAlpha(col, a))
            }
            // Number: just the percentage (a bolt before it while charging).
            4 -> {
                text.textSize = dp(13f) * k
                text.color = withAlpha(col, a * pulse(charging, anim, t))
                val label = "$level%"
                if (charging) bolt(c, x + dp(4f), cy, dp(5f), withAlpha(col, a))
                c.drawText(label, x + dp(10f), cy + text.textSize * 0.36f, text)
            }
            else -> {
                // Pill (number inside, like iOS), Classic (number beside) and Slim.
                val slim = style == 3
                val bw = (if (slim) dp(20f) else if (style == 1) dp(23f) else dp(25f)) * k
                val bh = (if (slim) dp(7f) else dp(12f)) * k
                var bx = x
                if ((style == 1 || slim) && pct) {
                    small.color = withAlpha(col, a)
                    val label = "$level%"
                    c.drawText(label, bx, cy + small.textSize * 0.36f, small)
                    bx += small.measureText(label) + dp(4f)
                }
                val rad = bh * 0.32f
                r.set(bx, cy - bh / 2f, bx + bw, cy + bh / 2f)
                glow(r.left, r.top, r.right, r.bottom, rad)
                // Body: a faint shell, and the nub on the right.
                fill.color = withAlpha(ink, a * 0.3f)
                c.drawRoundRect(r, rad, rad, fill)
                r.set(bx + bw + dp(1f), cy - bh * 0.2f, bx + bw + dp(2.5f), cy + bh * 0.2f)
                c.drawRoundRect(r, dp(1f), dp(1f), fill)
                // The charge.
                c.save()
                r.set(bx, cy - bh / 2f, bx + bw, cy + bh / 2f)
                path.reset()
                path.addRoundRect(r, rad, rad, Path.Direction.CW)
                c.clipPath(path)
                fill.color = withAlpha(col, a * pulse(charging, anim, t))
                val fw = bw * f
                if (charging && anim == 0) {
                    // Liquid: the edge of the charge is a little wave that ripples.
                    path.reset()
                    path.moveTo(bx, r.bottom)
                    path.lineTo(bx, r.top)
                    var yy = r.top
                    val amp = dp(1.6f)
                    while (yy <= r.bottom) {
                        val xx = bx + fw + amp * sin((yy - r.top) / bh * 2f * PI.toFloat() * 1.2f + t * 7f)
                        path.lineTo(xx, yy)
                        yy += dp(1f)
                    }
                    path.lineTo(bx, r.bottom)
                    path.close()
                    c.drawPath(path, fill)
                    // A light that runs through the charge.
                    val sx = bx + (t * 0.9f % 1f) * bw * 1.4f - bw * 0.2f
                    fill.color = Color.argb((90 * a).toInt(), 255, 255, 255)
                    r.set(sx - dp(2f), r.top, sx + dp(2f), r.bottom)
                    if (sx < bx + fw) c.drawRect(r, fill)
                } else {
                    r.set(bx, cy - bh / 2f, bx + fw, cy + bh / 2f)
                    c.drawRect(r, fill)
                }
                c.restore()
                if (style == 0 && !slim && pct) {
                    // The number inside, dark over the charge so it always reads.
                    small.textSize = dp(9.5f) * k
                    small.typeface = Typeface.create(Typeface.DEFAULT, 800, false)
                    val label = level.toString()
                    val lw = small.measureText(label) + if (charging) dp(6f) else 0f
                    val lx = bx + (bw - lw) / 2f
                    small.color = if (f > 0.55f) Color.argb((235 * a).toInt(), 0, 0, 0) else withAlpha(Color.WHITE, a)
                    c.drawText(label, lx, cy + small.textSize * 0.36f, small)
                    if (charging) bolt(c, lx + lw - dp(2.5f), cy, dp(4f), small.color)
                } else if (charging) {
                    bolt(c, bx + bw / 2f, cy, bh * 0.42f, if (f > 0.55f) Color.argb((235 * a).toInt(), 0, 0, 0) else withAlpha(Color.WHITE, a))
                }
            }
        }
    }

    /** Pulse: the charge breathes while charging. */
    private fun pulse(charging: Boolean, anim: Int, t: Float): Float = if (charging && anim == 1) 0.55f + 0.45f * (0.5f + 0.5f * sin(t * 3.4f)) else 1f

    private fun bolt(c: Canvas, cx: Float, cy: Float, s: Float, col: Int) {
        path.reset()
        path.moveTo(cx + s * 0.15f, cy - s)
        path.lineTo(cx - s * 0.55f, cy + s * 0.12f)
        path.lineTo(cx - s * 0.02f, cy + s * 0.12f)
        path.lineTo(cx - s * 0.15f, cy + s)
        path.lineTo(cx + s * 0.55f, cy - s * 0.12f)
        path.lineTo(cx + s * 0.02f, cy - s * 0.12f)
        path.close()
        fill.color = col
        c.drawPath(path, fill)
    }

    private fun speedText(b: Long): String = when {
        b >= 1_000_000 -> "%.1f MB/s".format(b / 1_000_000f)
        b >= 1000 -> "${b / 1000} KB/s"
        else -> "$b B/s"
    }

    companion object {
        val COLOURS = listOf(Color.WHITE, Color.rgb(225, 225, 232), Color.rgb(120, 200, 255), Color.rgb(140, 240, 170), Color.rgb(255, 170, 200), Color.rgb(255, 196, 110))
        val COLOUR_NAMES = listOf("White", "Soft", "Sky", "Mint", "Rose", "Amber")
        private val SAMPLE = listOf(Color.rgb(37, 211, 102), Color.rgb(29, 161, 242), Color.rgb(225, 48, 108), Color.rgb(255, 196, 0))
    }
}
