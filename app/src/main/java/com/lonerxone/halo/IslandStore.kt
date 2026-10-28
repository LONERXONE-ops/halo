package com.lonerxone.halo

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.session.MediaController
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** What is playing right now, as read from the system media session. */
data class MusicInfo(
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val accent: Int,
    val playing: Boolean,
    val packageName: String,
    val controller: MediaController?,
    val durationMs: Long,
    val positionMs: Long,
    val positionUpdatedAt: Long,
    val speed: Float,
) {
    /** Same song (art included) means the island updates in place instead of rebuilding. */
    val trackKey: String
        get() = "$packageName|$title|$artist|${art?.generationId ?: 0}"

    fun currentPosition(): Long {
        if (!playing || positionUpdatedAt <= 0L) return positionMs
        val elapsed = SystemClock.elapsedRealtime() - positionUpdatedAt
        val pos = positionMs + (elapsed * speed).toLong()
        return if (durationMs > 0) pos.coerceIn(0L, durationMs) else pos.coerceAtLeast(0L)
    }
}

/** A short alert that grows out of the island, then shrinks back on its own. */
data class Pop(
    val id: Int,
    val title: String,
    val value: String,
    val accent: Int,
    val icon: Int,
    val durationMs: Long,
)

/** One place every source writes to; the overlay reads from here. Main thread only. */
object IslandStore {
    var music: MusicInfo? = null
        private set
    var pop: Pop? = null
        private set

    private val listeners = LinkedHashSet<() -> Unit>()
    private val handler = Handler(Looper.getMainLooper())
    private var popSeq = 0
    private val clearPop = Runnable {
        pop = null
        dispatch()
    }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun setMusic(m: MusicInfo?) {
        music = m
        dispatch()
    }

    fun showPop(title: String, value: String, accent: Int, icon: Int, durationMs: Long) {
        popSeq += 1
        pop = Pop(popSeq, title, value, accent, icon, durationMs)
        handler.removeCallbacks(clearPop)
        handler.postDelayed(clearPop, durationMs)
        dispatch()
    }

    fun dismissPop() {
        handler.removeCallbacks(clearPop)
        if (pop != null) {
            pop = null
            dispatch()
        }
    }

    private fun dispatch() {
        for (l in listeners.toList()) l()
    }
}

object Prefs {
    const val MUSIC = "music"
    const val CHARGING = "charging"
    const val IDLE = "idle"
    const val HIDE_FULLSCREEN = "hide_fullscreen"
    const val HAPTICS = "haptics"
    const val SCALE = "scale"

    fun get(c: Context): SharedPreferences = c.getSharedPreferences("halo", Context.MODE_PRIVATE)
}

object Colors {
    const val ACCENT_DEFAULT = 0xFF8AB4FF.toInt()
    const val GREEN = 0xFF34C759.toInt()
    const val BLUE = 0xFF3E91FF.toInt()
}
