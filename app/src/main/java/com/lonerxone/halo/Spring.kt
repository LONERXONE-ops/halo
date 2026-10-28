package com.lonerxone.halo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One damped spring (mass 1), stepped with the exact solution of the damped
 * harmonic oscillator instead of small numeric steps. Every frame lands on the
 * true curve whatever the frame time, so a dropped frame never makes it jitter,
 * and 60 Hz and 120 Hz look the same.
 *
 * Changing the target keeps the current velocity, so an island that is told to
 * close while opening slows down and turns around instead of snapping.
 */
class Spring(var value: Float = 0f, private val rest: Float = 0.5f) {
    var target: Float = value
        private set
    var velocity: Float = 0f
        private set
    private var stiffness = 400f
    private var damping = 0.8f

    val atRest: Boolean
        get() = abs(value - target) <= rest && abs(velocity) <= rest * 6f

    fun to(newTarget: Float, k: Float, zeta: Float) {
        target = newTarget
        stiffness = k
        damping = zeta
    }

    fun snap(v: Float) {
        value = v
        target = v
        velocity = 0f
    }

    /** Advances by [dt] seconds; returns true while still moving. */
    fun step(dt: Float): Boolean {
        if (atRest) {
            value = target
            velocity = 0f
            return false
        }
        val t = dt.coerceIn(0f, 0.064f)
        val x0 = value - target
        val v0 = velocity
        val w = sqrt(stiffness)
        val z = damping
        val x: Float
        val v: Float
        if (z < 1f) {
            val a = z * w
            val wd = w * sqrt(1f - z * z)
            val c2 = (v0 + a * x0) / wd
            val e = exp(-a * t)
            val cs = cos(wd * t)
            val sn = sin(wd * t)
            x = e * (x0 * cs + c2 * sn)
            v = e * ((-a * x0 + wd * c2) * cs + (-a * c2 - wd * x0) * sn)
        } else {
            // Critically damped (used for calm closing): no overshoot.
            val b = v0 + w * x0
            val e = exp(-w * t)
            x = (x0 + b * t) * e
            v = (v0 - w * b * t) * e
        }
        value = target + x
        velocity = v
        if (atRest) {
            value = target
            velocity = 0f
            return false
        }
        return true
    }
}
