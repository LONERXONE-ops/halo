package com.lonerxone.halo

import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.DisplayCutout
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds the front camera on any phone, not just the A06.
 *
 * Phones describe their cutout differently: some give the exact outline, some
 * a tight box, some a box as wide as the status bar, some only a safe inset,
 * and some (bezel phones) nothing at all. We try the most precise source first
 * and fall back step by step, keeping only what sits along the top edge.
 */
object CameraFinder {

    data class Result(val rect: Rect?, val how: String)

    fun find(cut: DisplayCutout?, screenW: Int, screenH: Int, statusBarH: Int, density: Float): Result {
        if (cut == null) return Result(null, "no cutout (bezel phone): invisible tap zone at the top")
        val topBand = maxOf(statusBarH * 2, (screenH * 0.12f).toInt())
        val maxW = screenW * 0.45f

        // 1. The boxes the phone reports along the top edge. With several, the
        //    camera is the one nearest the middle. A tight box is used as is.
        val boxes = try {
            cut.boundingRects.filter { !it.isEmpty && it.top < topBand }
        } catch (_: Throwable) {
            emptyList()
        }
        val box = boxes.minByOrNull { abs(it.centerX() - screenW / 2) }
        if (box != null && box.width() < maxW) return Result(Rect(box), describe(box, screenW, "reported box"))

        // 2. The box is missing or as wide as the status bar: the exact outline
        //    (Android 12+), clipped to the top of the screen, is more precise.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val path = cut.cutoutPath
                if (path != null && !path.isEmpty) {
                    val band = Path().apply { addRect(0f, 0f, screenW.toFloat(), topBand.toFloat(), Path.Direction.CW) }
                    val top = Path(path).apply { op(band, Path.Op.INTERSECT) }
                    val b = RectF()
                    @Suppress("DEPRECATION")
                    top.computeBounds(b, true)
                    if (!b.isEmpty && b.width() < maxW) {
                        val r = Rect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt())
                        return Result(r, describe(r, screenW, "exact shape"))
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // 3. Only a wide strip: the lens is a circle in its middle.
        if (box != null) {
            val d = min(box.height(), (36 * density).toInt()).coerceAtLeast((18 * density).toInt())
            val r = Rect(box.centerX() - d / 2, box.centerY() - d / 2, box.centerX() + d / 2, box.centerY() + d / 2)
            return Result(r, describe(r, screenW, "wide strip, centre estimated"))
        }

        // 4. Only a safe inset: assume a centred punch-hole inside it.
        val inset = cut.safeInsetTop
        if (inset > 0) {
            val d = min((inset * 0.6f).toInt(), (30 * density).toInt()).coerceAtLeast((16 * density).toInt())
            val cx = screenW / 2
            val cy = inset / 2
            val r = Rect(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2)
            return Result(r, describe(r, screenW, "inset only, estimated"))
        }
        return Result(null, "no camera found: invisible tap zone at the top")
    }

    private fun describe(r: Rect, screenW: Int, source: String): String {
        val shape = if (abs(r.width() - r.height()) <= r.height() * 0.35f) "punch-hole" else "notch"
        val where = when {
            r.centerX() < screenW * 0.35f -> "top-left"
            r.centerX() > screenW * 0.65f -> "top-right"
            else -> "centre"
        }
        return "$shape, $where, ${r.width()}×${r.height()} px ($source)"
    }
}
