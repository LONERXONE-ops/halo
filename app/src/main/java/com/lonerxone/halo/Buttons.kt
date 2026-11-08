package com.lonerxone.halo

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * One look for every button Halo shows (taken from the download card):
 * the main action is a solid white pill; everything else is clear, a soft
 * see-through white; danger (Cancel, Stop, Delete, Decline) is a red tint;
 * a ringing alarm's Stop is an orange tint.
 */
enum class BtnStyle(val bg: Int, val pressedBg: Int, val fg: Int) {
    MAIN(Color.WHITE, Color.rgb(205, 205, 210), Color.BLACK),
    CLEAR(Color.argb(48, 255, 255, 255), Color.argb(105, 255, 255, 255), Color.WHITE),
    DANGER(Color.argb(60, 255, 69, 58), Color.argb(125, 255, 69, 58), Color.rgb(255, 120, 110)),
    WARN(Color.argb(70, 255, 159, 10), Color.argb(135, 255, 159, 10), Color.rgb(255, 184, 77)),
}

object HaloButtons {
    private val DANGER_WORDS = Regex("\\b(cancel|stop|delete|remove|decline|reject|end|hang ?up|block|discard)\\b", RegexOption.IGNORE_CASE)

    /** The style for one of an app's own buttons: danger by its words, else main for the first. */
    fun styleFor(label: String, first: Boolean): BtnStyle = when {
        DANGER_WORDS.containsMatchIn(label) -> BtnStyle.DANGER
        first -> BtnStyle.MAIN
        else -> BtnStyle.CLEAR
    }

    fun background(style: BtnStyle, radius: Float) = GradientDrawable().apply {
        setColor(style.bg)
        cornerRadius = radius
    }

    /**
     * Makes a button feel alive: the moment a finger lands it sinks in,
     * lights up and gives a light tick; let go and it springs back past its
     * size and settles, with a quick flash. A drag (scroll) just lets it go.
     * Taps still go to the view's own click listener.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun react(v: View, style: BtnStyle?, haptics: () -> Boolean = { true }, depth: Float = 0.9f) {
        val bg = v.background as? GradientDrawable
        var colorAnim: ValueAnimator? = null
        var current = style?.bg ?: 0
        fun tint(to: Int, ms: Long, back: Int? = null) {
            if (bg == null || style == null) return
            colorAnim?.cancel()
            colorAnim = ValueAnimator.ofObject(ArgbEvaluator(), current, to).apply {
                duration = ms
                addUpdateListener {
                    current = it.animatedValue as Int
                    bg.setColor(current)
                }
                if (back != null) {
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(a: android.animation.Animator) {
                            if (current == to) tint(back, 260L)
                        }
                    })
                }
                start()
            }
        }
        val slop = android.view.ViewConfiguration.get(v.context).scaledTouchSlop
        var x0 = 0f
        var y0 = 0f
        var down = false
        fun release(flash: Boolean) {
            if (!down) return
            down = false
            v.animate().cancel()
            v.animate().scaleX(1f).scaleY(1f).setDuration(420L).setInterpolator(OvershootInterpolator(3.6f)).start()
            if (style != null) {
                if (flash) tint(style.pressedBg, 60L, back = style.bg) else tint(style.bg, 220L)
            }
        }
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!view.isEnabled) return@setOnTouchListener false
                    down = true
                    x0 = e.x
                    y0 = e.y
                    view.animate().cancel()
                    view.animate().scaleX(depth).scaleY(depth).setDuration(90L).setInterpolator(DecelerateInterpolator(1.6f)).start()
                    style?.let { tint(it.pressedBg, 90L) }
                    if (haptics()) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(e.x - x0) > slop || kotlin.math.abs(e.y - y0) > slop) release(false)
                MotionEvent.ACTION_UP -> release(true)
                MotionEvent.ACTION_CANCEL -> release(false)
            }
            false
        }
    }
}
