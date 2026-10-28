package com.lonerxone.halo

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService

/**
 * Notification access is what lets Halo see the system media sessions
 * (the same ones Samsung's own media player uses). Phase 2 adds notifications.
 */
class HaloNotificationListener : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var sessions: MediaSessionManager? = null
    private var current: MediaController? = null
    private var accentKey: String? = null
    private var accent = Colors.ACCENT_DEFAULT

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pick(list ?: emptyList())
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // A different app may have started playing; re-pick the best session.
            refresh()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            publish()
        }

        override fun onSessionDestroyed() {
            current?.unregisterCallback(this)
            current = null
            refresh()
        }
    }

    private val clearPaused = Runnable { IslandStore.setMusic(null) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val m = getSystemService(MediaSessionManager::class.java) ?: return
        sessions = m
        try {
            m.addOnActiveSessionsChangedListener(sessionsChanged, component(), handler)
        } catch (_: SecurityException) {
        }
        refresh()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            sessions?.removeOnActiveSessionsChangedListener(sessionsChanged)
        } catch (_: Exception) {
        }
        current?.unregisterCallback(callback)
        current = null
        handler.removeCallbacks(clearPaused)
        IslandStore.setMusic(null)
    }

    private fun component() = ComponentName(this, HaloNotificationListener::class.java)

    private fun refresh() {
        val m = sessions ?: return
        val list = try {
            m.getActiveSessions(component())
        } catch (_: SecurityException) {
            emptyList()
        }
        pick(list)
    }

    private fun isPlaying(c: MediaController?): Boolean {
        val s = c?.playbackState?.state ?: return false
        return s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING
    }

    private fun pick(list: List<MediaController>) {
        val best = list.firstOrNull { isPlaying(it) }
            ?: list.firstOrNull { it.sessionToken == current?.sessionToken }
            ?: list.firstOrNull()
        if (best?.sessionToken != current?.sessionToken) {
            current?.unregisterCallback(callback)
            current = best
            best?.registerCallback(callback, handler)
        }
        publish()
    }

    private fun publish() {
        val c = current
        val meta = c?.metadata
        if (c == null || meta == null) {
            handler.removeCallbacks(clearPaused)
            IslandStore.setMusic(null)
            return
        }
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        if (title.isBlank()) {
            IslandStore.setMusic(null)
            return
        }
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val state = c.playbackState
        val playing = isPlaying(c)

        val key = "${c.packageName}|$title|$artist|${art?.generationId ?: 0}"
        if (key != accentKey) {
            accentKey = key
            accent = accentFrom(art)
        }

        val info = MusicInfo(
            title = title,
            artist = artist,
            art = art,
            accent = accent,
            playing = playing,
            packageName = c.packageName,
            controller = c,
            durationMs = meta.getLong(MediaMetadata.METADATA_KEY_DURATION),
            positionMs = state?.position ?: 0L,
            positionUpdatedAt = state?.lastPositionUpdateTime ?: 0L,
            speed = state?.playbackSpeed ?: 1f,
        )

        handler.removeCallbacks(clearPaused)
        // A paused session that was not already on the island stays quiet.
        if (!playing && IslandStore.music == null) return
        IslandStore.setMusic(info)
        if (!playing) handler.postDelayed(clearPaused, 8_000L)
    }

    companion object {
        /** Picks the most vivid colour in the cover, brightened so it reads on dark glass. */
        fun accentFrom(bmp: Bitmap?): Int {
            if (bmp == null) return Colors.ACCENT_DEFAULT
            return try {
                val src = if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
                val small = Bitmap.createScaledBitmap(src, 16, 16, true)
                val hsv = FloatArray(3)
                var best = -1f
                var bestHsv: FloatArray? = null
                for (y in 0 until small.height) {
                    for (x in 0 until small.width) {
                        Color.colorToHSV(small.getPixel(x, y), hsv)
                        if (hsv[2] < 0.25f) continue
                        val score = hsv[1] * hsv[2]
                        if (score > best) {
                            best = score
                            bestHsv = hsv.copyOf()
                        }
                    }
                }
                val h = bestHsv
                if (h == null || best < 0.12f) {
                    Colors.ACCENT_DEFAULT
                } else {
                    h[1] = h[1].coerceIn(0.35f, 0.75f)
                    h[2] = h[2].coerceAtLeast(0.88f)
                    Color.HSVToColor(h)
                }
            } catch (_: Throwable) {
                Colors.ACCENT_DEFAULT
            }
        }
    }
}
