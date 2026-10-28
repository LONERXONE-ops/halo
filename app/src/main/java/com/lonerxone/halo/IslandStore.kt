package com.lonerxone.halo

import android.app.PendingIntent
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

/** A short system alert (charging, test) that grows out of the island. */
data class Pop(
    val id: Int,
    val title: String,
    val value: String,
    val accent: Int,
    val icon: Int,
    val durationMs: Long,
)

/** A message or app notification, shown as a banner for a few seconds. */
data class NotifInfo(
    val id: Int,
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val icon: Bitmap?,
    val accent: Int,
    val contentIntent: PendingIntent?,
)

/** A call in progress or ringing, taken from the dialer's call notification. */
data class CallInfo(
    val key: String,
    val name: String,
    val detail: String,
    val ringing: Boolean,
    /** Wall-clock start for the timer, or 0 when unknown/ringing. */
    val startedAt: Long,
    val avatar: Bitmap?,
    val contentIntent: PendingIntent?,
    val answer: PendingIntent?,
    val hangUp: PendingIntent?,
)

/** Live readings shown in the app's status card, so problems are visible on the phone. */
object Debug {
    var listenerConnected = false
    var sessions = "not checked"
    var nowPlaying = "nothing"
    var lastNotification = "none yet"
    var island = "not running"
}

/** One place every source writes to; the overlay reads from here. Main thread only. */
object IslandStore {
    var music: MusicInfo? = null
        private set
    var pop: Pop? = null
        private set
    var notif: NotifInfo? = null
        private set
    var call: CallInfo? = null
        private set

    private val listeners = LinkedHashSet<() -> Unit>()
    private val handler = Handler(Looper.getMainLooper())
    private var seq = 0
    private val clearPop = Runnable {
        pop = null
        dispatch()
    }
    private val clearNotif = Runnable {
        notif = null
        dispatch()
    }

    fun nextId(): Int {
        seq += 1
        return seq
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

    fun setCall(c: CallInfo?) {
        call = c
        dispatch()
    }

    fun showPop(title: String, value: String, accent: Int, icon: Int, durationMs: Long) {
        pop = Pop(nextId(), title, value, accent, icon, durationMs)
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

    fun showNotif(n: NotifInfo, durationMs: Long) {
        notif = n
        handler.removeCallbacks(clearNotif)
        handler.postDelayed(clearNotif, durationMs)
        dispatch()
    }

    fun dismissNotif() {
        handler.removeCallbacks(clearNotif)
        if (notif != null) {
            notif = null
            dispatch()
        }
    }

    /** Keeps a banner open while the user is touching it. */
    fun holdNotif(durationMs: Long) {
        if (notif == null) return
        handler.removeCallbacks(clearNotif)
        handler.postDelayed(clearNotif, durationMs)
    }

    private fun dispatch() {
        for (l in listeners.toList()) l()
    }
}

object Prefs {
    const val MUSIC = "music"
    const val CHARGING = "charging"
    const val NOTIFS = "notifs"
    const val CALLS = "calls"
    const val HIDE_TEXT = "hide_text"
    const val IDLE = "idle"
    const val HIDE_FULLSCREEN = "hide_fullscreen"
    const val HAPTICS = "haptics"
    const val SCALE = "scale"
    const val BLOCKED = "blocked_apps"

    /** Banking and OTP apps stay off the island unless the user turns them on. */
    val DEFAULT_BLOCKED = setOf(
        "com.gtbank.gtworldv1",
        "com.kudabank.app",
        "team.opay.pay",
        "com.transsnet.palmpay",
        "android",
        "com.android.systemui",
        "com.samsung.android.app.smartcapture",
    )

    fun get(c: Context): SharedPreferences = c.getSharedPreferences("halo", Context.MODE_PRIVATE)

    fun blocked(c: Context): Set<String> = get(c).getStringSet(BLOCKED, null) ?: DEFAULT_BLOCKED
}

object Colors {
    const val ACCENT_DEFAULT = 0xFF8AB4FF.toInt()
    const val GREEN = 0xFF34C759.toInt()
    const val RED = 0xFFFF453A.toInt()
    const val BLUE = 0xFF3E91FF.toInt()
}
