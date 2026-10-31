package com.lonerxone.halo

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.os.Build

/**
 * The light of Liquid Glass, the same as Halo Launcher's new glass: a crisp
 * line along the edge where it faces the light (top left, fainter bottom
 * right), a soft glow inside the curved rim like a thick glass edge, a gentle
 * lift across the top, and a glow under your finger while you press.
 *
 * The island floats over other apps and Android doesn't let an app see
 * what's behind it, so (unlike the launcher) it can't bend the picture
 * underneath; it draws the glass's light over its own dark body instead.
 * Android 13 and newer draw it with a GPU shader; older phones keep the
 * simple gradients.
 */
object GlassRim {
    private const val SRC = """
        uniform float2 size;
        uniform float radius;
        uniform float rimH;
        uniform float amt;
        uniform float3 touch;

        float sdBox(float2 p, float2 hs, float r) {
            float2 q = abs(p) - (hs - float2(r));
            return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
        }

        float2 edgeDir(float2 p, float2 hs, float r) {
            float2 q = abs(p) - (hs - float2(r));
            if (q.x >= 0.0 || q.y >= 0.0) return sign(p) * normalize(max(q, 0.0001));
            float gx = step(q.y, q.x);
            return sign(p) * float2(gx, 1.0 - gx);
        }

        half4 main(float2 p) {
            float2 hs = size * 0.5;
            float2 cc = p - hs;
            float r = min(radius, min(hs.x, hs.y));
            float inside = -sdBox(cc, hs, r);
            if (inside < 0.0) return half4(0.0);
            float2 dir = edgeDir(cc, hs, min(r * 1.5, min(hs.x, hs.y)));
            float2 L = normalize(float2(-1.0, -1.0));
            float facing = dot(dir, L);
            float lit = pow(abs(facing), 1.2) * (facing > 0.0 ? 1.0 : 0.5);
            float edgeLine = 1.0 - smoothstep(0.0, 1.5, inside);
            float glow = 1.0 - smoothstep(0.0, rimH, inside);
            float a = (edgeLine * 0.62 + glow * glow * 0.15) * lit;
            float y = p.y / size.y;
            a += 0.045 * (1.0 - smoothstep(0.0, 0.42, y));
            a *= amt;
            if (touch.z > 0.0) {
                float rr = min(size.x, size.y) * 1.5;
                float spot = smoothstep(rr, rr * 0.35, distance(p, touch.xy));
                a += touch.z * (0.05 + 0.13 * spot);
            }
            a = clamp(a, 0.0, 1.0);
            return half4(half3(a), half(a));
        }
    """

    @Volatile private var broken = false
    private var shader: Any? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    val usable: Boolean get() = Build.VERSION.SDK_INT >= 33 && !broken

    /**
     * Draws the light over [rect] (rounded by [radius]); [amount] 0..1, and
     * [press] 0..1 with the finger at [tx],[ty] (in the canvas's coordinates).
     * Returns false if it couldn't (draw the old shine instead).
     */
    fun draw(c: Canvas, rect: RectF, radius: Float, rimH: Float, amount: Float, press: Float = 0f, tx: Float = 0f, ty: Float = 0f): Boolean {
        if (!usable || rect.width() < 2f || rect.height() < 2f) return false
        return try {
            @Suppress("NewApi")
            val rs = (shader as? RuntimeShader) ?: RuntimeShader(SRC).also { shader = it }
            rs.setFloatUniform("size", rect.width(), rect.height())
            rs.setFloatUniform("radius", radius)
            rs.setFloatUniform("rimH", rimH)
            rs.setFloatUniform("amt", amount)
            rs.setFloatUniform("touch", tx - rect.left, ty - rect.top, press)
            paint.shader = rs
            c.save()
            c.translate(rect.left, rect.top)
            c.drawRoundRect(0f, 0f, rect.width(), rect.height(), radius, radius, paint)
            c.restore()
            true
        } catch (_: Throwable) {
            broken = true
            false
        }
    }
}
