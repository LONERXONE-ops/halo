package com.lonerxone.halo

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Notification access powers three things:
 * - music, through the system media sessions (with the media notification as a backup path)
 * - message banners
 * - calls, from the dialer's call notification
 */
class HaloNotificationListener : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var sessions: MediaSessionManager? = null
    private var current: MediaController? = null
    private var accentKey: String? = null
    private var accent = Colors.ACCENT_DEFAULT

    /** key -> last text we showed, so re-posted notifications do not pop twice. */
    private val shown = HashMap<String, String>()

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pick(list ?: emptyList())
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
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

    // =====================================================================
    // Lifecycle
    // =====================================================================

    override fun onListenerConnected() {
        super.onListenerConnected()
        Debug.listenerConnected = true
        val m = getSystemService(MediaSessionManager::class.java)
        sessions = m
        if (m != null) {
            try {
                m.addOnActiveSessionsChangedListener(sessionsChanged, component(), handler)
            } catch (e: SecurityException) {
                Debug.sessions = "blocked: ${e.message}"
            }
        }
        refresh()
        // Pick up a call or media notification that was already showing.
        try {
            activeNotifications?.forEach { sbn ->
                mediaToken(sbn.notification)?.let { adopt(it) }
                if (isCall(sbn)) handleCall(sbn)
            }
        } catch (_: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Debug.listenerConnected = false
        try {
            sessions?.removeOnActiveSessionsChangedListener(sessionsChanged)
        } catch (_: Exception) {
        }
        current?.unregisterCallback(callback)
        current = null
        handler.removeCallbacks(clearPaused)
        IslandStore.setMusic(null)
        IslandStore.setCall(null)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        try {
            val token = mediaToken(sbn.notification)
            if (token != null) {
                adopt(token)
                return
            }
            if (isCall(sbn)) {
                handleCall(sbn)
                return
            }
            handleMessage(sbn)
        } catch (e: Exception) {
            Debug.lastNotification = "error: ${e.javaClass.simpleName}"
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        shown.remove(sbn.key)
        if (IslandStore.call?.key == sbn.key) IslandStore.setCall(null)
        if (IslandStore.notif?.key == sbn.key) IslandStore.dismissNotif()
    }

    private fun component() = ComponentName(this, HaloNotificationListener::class.java)

    // =====================================================================
    // Music
    // =====================================================================

    @Suppress("DEPRECATION")
    private fun mediaToken(n: Notification): MediaSession.Token? {
        val extras = n.extras ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        } else {
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
        }
    }

    /** Backup path: take the session straight from the player's notification. */
    private fun adopt(token: MediaSession.Token) {
        if (current?.sessionToken == token) {
            publish()
            return
        }
        val c = try {
            MediaController(this, token)
        } catch (_: Exception) {
            return
        }
        if (current == null || isPlaying(c) || !isPlaying(current)) {
            current?.unregisterCallback(callback)
            current = c
            c.registerCallback(callback, handler)
        }
        publish()
    }

    private fun refresh() {
        val m = sessions ?: return
        val list = try {
            m.getActiveSessions(component())
        } catch (e: SecurityException) {
            Debug.sessions = "blocked: ${e.message}"
            null
        } ?: return
        pick(list)
    }

    private fun isPlaying(c: MediaController?): Boolean {
        val s = c?.playbackState?.state ?: return false
        return s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING
    }

    private fun pick(list: List<MediaController>) {
        Debug.sessions = if (list.isEmpty()) "0 sessions" else list.joinToString { it.packageName }
        val best = list.firstOrNull { isPlaying(it) }
            ?: list.firstOrNull { it.sessionToken == current?.sessionToken }
            ?: list.firstOrNull()
            ?: current // keep a session adopted from a notification
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
            Debug.nowPlaying = if (c == null) "nothing" else "${c.packageName}: no song info"
            handler.removeCallbacks(clearPaused)
            IslandStore.setMusic(null)
            return
        }
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        if (title.isBlank()) {
            Debug.nowPlaying = "${c.packageName}: no title"
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
        Debug.nowPlaying = "$title (${if (playing) "playing" else "paused"}, ${c.packageName})"

        handler.removeCallbacks(clearPaused)
        if (!playing && IslandStore.music == null) return
        IslandStore.setMusic(info)
        if (!playing) handler.postDelayed(clearPaused, 8_000L)
    }

    // =====================================================================
    // Calls
    // =====================================================================

    private fun isCall(sbn: StatusBarNotification): Boolean =
        sbn.notification.category == Notification.CATEGORY_CALL && sbn.isOngoing

    private fun handleCall(sbn: StatusBarNotification) {
        if (!Prefs.get(this).getBoolean(Prefs.CALLS, true)) return
        val n = sbn.notification
        val ex = n.extras ?: Bundle()
        val name = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val detail = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) || n.`when` in 1 until System.currentTimeMillis() - 1500
        val ringing = !ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) &&
            (n.fullScreenIntent != null || detail.contains("incoming", true))

        var answer: android.app.PendingIntent? = null
        var hangUp: android.app.PendingIntent? = null
        n.actions?.forEach { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            when {
                t.contains("answer") || t.contains("accept") -> answer = a.actionIntent
                t.contains("decline") || t.contains("reject") || t.contains("end") || t.contains("hang") -> hangUp = a.actionIntent
            }
        }
        if (ringing && n.actions != null && n.actions.size >= 2) {
            // Dialers usually list decline first and answer last.
            if (hangUp == null) hangUp = n.actions.first().actionIntent
            if (answer == null) answer = n.actions.last().actionIntent
        }

        val avatar = iconBitmap(n.getLargeIcon())
        IslandStore.setCall(
            CallInfo(
                key = sbn.key,
                name = name.ifEmpty { "Call" },
                detail = detail,
                ringing = ringing,
                startedAt = if (!ringing && chrono) n.`when` else 0L,
                avatar = avatar,
                contentIntent = n.contentIntent,
                answer = answer,
                hangUp = hangUp,
            ),
        )
        Debug.lastNotification = "call: ${name.ifEmpty { "unknown" }} (${if (ringing) "ringing" else "active"})"
    }

    // =====================================================================
    // Messages
    // =====================================================================

    private fun handleMessage(sbn: StatusBarNotification) {
        val n = sbn.notification
        val pkg = sbn.packageName
        val app = appName(pkg)
        fun skip(reason: String) {
            Debug.lastNotification = "$app — skipped: $reason"
        }

        if (pkg == packageName) return skip("Halo itself")
        val prefs = Prefs.get(this)
        if (!prefs.getBoolean(Prefs.NOTIFS, true)) return skip("notifications off in Halo")
        if (pkg in Prefs.blocked(this)) return skip("app is blocked")
        if (sbn.isOngoing) return skip("ongoing (progress, service)")
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return skip("group summary")

        val ranking = Ranking()
        if (currentRanking?.getRanking(sbn.key, ranking) == true) {
            if (ranking.importance in 1 until NotificationManager.IMPORTANCE_DEFAULT) return skip("silent notification")
        }

        val ex = n.extras ?: Bundle()
        val title = (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: ex.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()
        val text = lastMessage(ex)
            ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?: ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: ""
        if (title.isEmpty() && text.isEmpty()) return skip("no text")

        val fingerprint = "$title|$text"
        if (shown[sbn.key] == fingerprint) return skip("already shown")
        val alertOnce = (n.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0
        if (alertOnce && shown.containsKey(sbn.key)) {
            shown[sbn.key] = fingerprint
            return skip("quiet update")
        }
        shown[sbn.key] = fingerprint
        if (shown.size > 200) shown.clear()

        val icon = iconBitmap(n.getLargeIcon()) ?: appIcon(pkg)
        val color = if (n.color != Notification.COLOR_DEFAULT && Color.alpha(n.color) > 0) brighten(n.color) else accentFrom(icon)
        val hide = prefs.getBoolean(Prefs.HIDE_TEXT, false)
        IslandStore.showNotif(
            NotifInfo(
                id = IslandStore.nextId(),
                key = sbn.key,
                packageName = pkg,
                appName = app,
                title = title.ifEmpty { app },
                text = if (hide) "New message" else text,
                icon = icon,
                accent = color,
                contentIntent = n.contentIntent,
            ),
            4_500L,
        )
        Debug.lastNotification = "$app — shown: ${title.take(24)}"
    }

    /** WhatsApp and most chat apps use MessagingStyle; take the newest line. */
    @Suppress("DEPRECATION")
    private fun lastMessage(ex: Bundle): String? {
        val arr = ex.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val last = arr.lastOrNull() as? Bundle ?: return null
        val text = last.getCharSequence("text")?.toString()?.trim() ?: return null
        val sender = last.getCharSequence("sender")?.toString()?.trim()
        val isGroup = ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        return if (isGroup && !sender.isNullOrEmpty()) "$sender: $text" else text
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private fun appName(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg.substringAfterLast('.')
    }

    private fun appIcon(pkg: String): Bitmap? = try {
        drawableToBitmap(packageManager.getApplicationIcon(pkg))
    } catch (_: Exception) {
        null
    }

    private fun iconBitmap(icon: Icon?): Bitmap? {
        icon ?: return null
        return try {
            drawableToBitmap(icon.loadDrawable(this))
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        fun drawableToBitmap(d: Drawable?): Bitmap? {
            d ?: return null
            if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
            val w = d.intrinsicWidth.takeIf { it > 0 } ?: 96
            val h = d.intrinsicHeight.takeIf { it > 0 } ?: 96
            val bmp = Bitmap.createBitmap(w.coerceAtMost(256), h.coerceAtMost(256), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            d.setBounds(0, 0, c.width, c.height)
            d.draw(c)
            return bmp
        }

        fun brighten(color: Int): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            hsv[1] = hsv[1].coerceIn(0.3f, 0.8f)
            hsv[2] = hsv[2].coerceAtLeast(0.88f)
            return Color.HSVToColor(hsv)
        }

        /** Picks the most vivid colour in an image, brightened so it reads on dark glass. */
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
                        val px = small.getPixel(x, y)
                        if (Color.alpha(px) < 128) continue
                        Color.colorToHSV(px, hsv)
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
