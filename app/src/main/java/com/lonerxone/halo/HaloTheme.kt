package com.lonerxone.halo

/**
 * Halo's design tokens: every size, spring and timing the island uses, in
 * one place, so every feature (music, codes, rides, timers, moments) looks
 * and moves like the same family. The rules behind these numbers are in
 * DESIGN.md at the top of the repo; change them there and here together.
 *
 * Sizes are in dp, times in milliseconds.
 */
object HaloTheme {
    // ----- Shape -----
    /** The pill's height (before the user's size and fine sizing). */
    const val COMPACT_H = 34f
    /** The smallest pill: the camera, plus room for one small thing each side. */
    const val COMPACT_MIN_W = 100f
    /** Extra width beside the camera cut-out when the island wraps around it. */
    const val COMPACT_CAMERA_ROOM = 70f
    /** The open card's widest, and its margin from the screen edges. */
    const val CARD_MAX_W = 372f
    const val CARD_EDGE = 8f
    /** The open card's corners. */
    const val CARD_RADIUS = 32f
    /** Inner padding of cards and the gap between rows. */
    const val PAD = 18f
    const val GAP = 8f

    // ----- Motion (spatial springs, after Material Expressive's tokens) -----
    /** Stiffness for opening, closing and resizing. */
    const val STIFF = 640f
    /** A little stiffer for quick moments, so they flick out and tuck back in. */
    const val STIFF_MOMENT = 720f
    /** Damping while growing (bouncy, about 15% overshoot) and shrinking (settles cleanly). */
    const val DAMP_GROW = 0.5f
    const val DAMP_SHRINK = 0.66f

    /** The user's Motion speed: Relaxed, Normal, Quick (stiffness multiplier). */
    fun speed(choice: Int) = when (choice) {
        0 -> 0.6f
        2 -> 1.45f
        else -> 1f
    }

    /** The user's Bounce: Subtle, Normal, Bouncy (added to the damping; lower = bouncier). */
    fun bounce(choice: Int) = when (choice) {
        0 -> 0.16f
        2 -> -0.12f
        else -> 0f
    }

    // ----- Content timing -----
    /** Content appears once the shape is this far open (0..1), so it feels instant. */
    const val REVEAL_AT = 0.45f
    const val REVEAL_MS = 270L
    /** Old content fades out this fast when something new arrives. */
    const val SWAP_OUT_MS = 100L
    /** Rows of an opened card rise in one after another, this far apart. */
    const val CASCADE_STEP_MS = 38L
}
