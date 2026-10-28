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
    val appName: String = "",
    val appIcon: Bitmap? = null,
) {
    /**
     * Same song means the island updates in place instead of rebuilding.
     * Art is deliberately left out: players hand over a fresh bitmap object on
     * every update, which used to make the island redraw constantly.
     */
    val trackKey: String
        get() = "$packageName|$title|$artist"

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
    /** Tapping grows it into a card (used by charging). */
    val kind: String = "",
)

/**
 * A notification split into the parts a message needs, so it can be drawn like
 * a chat (photo, group, who said it, image) instead of a generic box.
 */
/** Something switched on that the notification center can switch off (flashlight, DND, silent…). */
data class Quick(val id: String, val icon: Int, val label: String)

data class Rich(
    /** Conversation or sender name, without "(12 messages)" noise. */
    val title: String,
    /** Who wrote the newest line, for group chats only. */
    val sender: String?,
    val text: String,
    /** Contact or group photo when the app provides one. */
    val avatar: Bitmap?,
    val appIcon: Bitmap?,
    /** An image preview (photo message, screenshot, thumbnail). */
    val picture: Bitmap?,
    /** How many messages are waiting (shown when more than 1). */
    val count: Int,
    val replyable: Boolean,
    val markRead: PendingIntent?,
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
    val rich: Rich,
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

/** A running or paused timer / stopwatch from the clock app. */
data class TimerInfo(
    val key: String,
    val label: String,
    /** Wall-clock end (countdown) or start (stopwatch); 0 when paused. */
    val anchor: Long,
    val countDown: Boolean,
    /** Shown when paused (the clock app's own text). */
    val pausedText: String,
    val contentIntent: PendingIntent?,
    val actions: List<Pair<String, PendingIntent>>,
)

/** A download, upload or install with a progress bar. */
data class TransferInfo(
    val key: String,
    val title: String,
    val appName: String,
    val icon: Bitmap?,
    /** 0..1, or -1 when the app does not say how far along it is. */
    val progress: Float,
    val contentIntent: PendingIntent?,
)

/** Turn-by-turn navigation from Google Maps. */
data class NavInfo(
    val key: String,
    val primary: String,
    val secondary: String,
    val arrow: Bitmap?,
    val contentIntent: PendingIntent?,
)

/** One row in the notification panel that opens from the camera circle. */
/** An app's notification channel that still uses Android's pop-up banner (importance High). */
data class PopChannel(val packageName: String, val appName: String, val channelId: String, val channelName: String)

data class PanelItem(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val icon: Bitmap?,
    val accent: Int,
    val postTime: Long,
    val canReply: Boolean,
    val rich: Rich,
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
    var timer: TimerInfo? = null
        private set
    var transfer: TransferInfo? = null
        private set
    var nav: NavInfo? = null
        private set
    /** Camera id of the flashlight while it is on, else null. */
    var torch: String? = null
        private set

    /** When the flashlight came on; after a short while the island settles back to the camera. */
    var torchSince = 0L
        private set

    fun setTorch(id: String?) {
        if (id == torch) return
        torch = id
        torchSince = if (id != null) android.os.SystemClock.uptimeMillis() else 0L
        dispatch()
    }

    /** Notifications waiting in the panel (drives the bell next to the pill). */
    var notifCount = 0
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

    fun setTimer(t: TimerInfo?) {
        timer = t
        dispatch()
    }

    fun setTransfer(t: TransferInfo?) {
        transfer = t
        dispatch()
    }

    fun setNotifCount(n: Int) {
        if (n == notifCount) return
        notifCount = n
        dispatch()
    }

    fun setNav(n: NavInfo?) {
        nav = n
        dispatch()
    }

    fun showPop(title: String, value: String, accent: Int, icon: Int, durationMs: Long, kind: String = "") {
        pop = Pop(nextId(), title, value, accent, icon, durationMs, kind)
        handler.removeCallbacks(clearPop)
        handler.postDelayed(clearPop, durationMs)
        dispatch()
    }

    /** Keeps a pop open (for example while its card is expanded). */
    fun holdPop(durationMs: Long) {
        if (pop == null) return
        handler.removeCallbacks(clearPop)
        handler.postDelayed(clearPop, durationMs)
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
    const val TIMERS = "timers"
    const val TRANSFERS = "transfers"
    const val NAV = "nav"
    const val SYSTEM_POPS = "system_pops"
    const val OFFSET_X = "offset_x"
    const val OFFSET_Y = "offset_y"
    const val GLASS = "glass"
    const val ART_BACKDROP = "art_backdrop"
    const val LOW_POWER = "low_power"
    const val TORCH = "torch"
    const val CONNECTIVITY = "connectivity"
    const val CAM_X = "cam_x"
    const val CAM_Y = "cam_y"
    const val CAM_SIZE = "cam_size"
    /** A picture inside the island (landscape) and behind the notification center (portrait). */
    const val ISLAND_PIC = "island_pic"
    const val PANEL_PIC = "panel_pic"
    /** How dark the pictures are made so text stays readable, 0..90 %. */
    const val PIC_DIM = "pic_dim"
    /** Music bars follow the real sound (needs the microphone permission for Android's visualizer). */
    const val BEAT = "beat"
    /** Halo shows new notifications instead of Android's own pop-up banners. */
    const val TAKEOVER = "takeover"

    fun picFile(c: Context, key: String): java.io.File = java.io.File(c.filesDir, "$key.jpg")

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

/**
 * Halo's palette: white and greys everywhere. Green is kept for charging and
 * answering a call, red for hanging up; the old colours below remain only for
 * the few places that need them.
 */
object Colors {
    /** Main tone: icons, values, bars, rings. */
    const val INK = 0xFFFFFFFF.toInt()
    /** Secondary tone: values beside an icon, captions. */
    const val SILVER = 0xFFC9C9CE.toInt()
    const val GREY = 0xFF8E8E93.toInt()
    /** Chips and placeholders on black. */
    const val GRAPHITE = 0xFF2A2A2D.toInt()
    const val CARD = 0xFF161618.toInt()
    const val ACCENT_DEFAULT = 0xFFFF6B9A.toInt()
    const val GREEN = 0xFF34C759.toInt()
    const val RED = 0xFFFF453A.toInt()
    const val BLUE = 0xFF3E91FF.toInt()
    const val PINK = 0xFFFF6B9A.toInt()
    const val ORANGE = 0xFFFF9F0A.toInt()
    const val PURPLE = 0xFFAF8CFF.toInt()
    const val LAVENDER = 0xFFB9C3FF.toInt()
    const val YELLOW = 0xFFFFD60A.toInt()
}
