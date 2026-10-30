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

    /** Recent banner times per app, for the spam cooldown. */
    private val bursts = HashMap<String, ArrayDeque<Long>>()

    /** key -> last text we showed, so re-posted notifications do not pop twice. */
    private val shown = HashMap<String, String>()
    /** Per chat: the time of the newest message already shown (survives the notification being re-posted). */
    private val seenMsg = HashMap<String, Long>()

    /** In a chat notification, a message with no sender is one you sent. */
    @Suppress("DEPRECATION")
    private fun lastIsFromYou(n: Notification): Boolean = try {
        val arr = n.extras?.getParcelableArray(Notification.EXTRA_MESSAGES)
        val last = arr?.lastOrNull() as? android.os.Bundle
        last != null && last.getCharSequence("sender") == null && last.getParcelable<android.app.Person>("sender_person") == null
    } catch (_: Exception) {
        false
    }

    @Suppress("DEPRECATION")
    private fun newestMessageTime(n: Notification): Long = try {
        val arr = n.extras?.getParcelableArray(Notification.EXTRA_MESSAGES)
        arr?.mapNotNull { (it as? android.os.Bundle)?.getLong("time", 0L) }?.maxOrNull() ?: 0L
    } catch (_: Exception) {
        0L
    }

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
        instance = this
        Debug.note("Notification access connected")
        IslandStore.noteSource = { instance?.swipeNotes().orEmpty() }
        LauncherIcons.checkVersion(this) { synchronized(iconCache) { iconCache.clear() } }
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
        refreshCount()
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
        if (instance === this) instance = null
        Debug.listenerConnected = false
        Debug.note("Notification access dropped by Android: reconnecting")
        // Ask Android to connect us again right away (it used to wait until
        // you opened Halo, and the island missed every message meanwhile).
        try {
            requestRebind(android.content.ComponentName(this, HaloNotificationListener::class.java))
        } catch (_: Exception) {
        }
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
        refreshCount()
        // An open reply sheet for this chat shows the new message straight away.
        try {
            onPosted?.invoke(sbn)
        } catch (_: Exception) {
        }
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
            if (handleOngoing(sbn)) return
            handleMessage(sbn)
        } catch (e: Exception) {
            Debug.lastNotification = "error: ${e.javaClass.simpleName}"
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        refreshCount()
        shown.remove(sbn.key)
        if (IslandStore.call?.key == sbn.key) IslandStore.setCall(null)
        IslandStore.forgetNote(sbn.key)
        if (IslandStore.notif?.key == sbn.key) IslandStore.dismissNotif()
        if (IslandStore.timer?.key == sbn.key) IslandStore.setTimer(null)
        IslandStore.rec?.let { if (!it.own && it.key == sbn.key) IslandStore.setRec(null) }
        if (IslandStore.nav?.key == sbn.key) IslandStore.setNav(null)
        if (IslandStore.transfer?.key == sbn.key) IslandStore.setTransfer(null)
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

        val key = "${c.packageName}|$title|$artist|${art != null}"
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
            appName = appName(c.packageName),
            appIcon = appIcon(c.packageName),
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
        val extras = ArrayList<Pair<String, android.app.PendingIntent>>()
        n.actions?.forEach { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            when {
                t.contains("answer") || t.contains("accept") -> answer = a.actionIntent
                t.contains("decline") || t.contains("reject") || t.contains("end") || t.contains("hang") -> hangUp = a.actionIntent
                a.actionIntent != null && !a.title.isNullOrBlank() -> extras += a.title.toString() to a.actionIntent
            }
        }
        if (ringing && n.actions != null && n.actions.size >= 2) {
            // Dialers usually list decline first and answer last.
            if (hangUp == null) hangUp = n.actions.first().actionIntent
            if (answer == null) answer = n.actions.last().actionIntent
        }

        if (ringing) {
            quietReason(rankingOf(sbn))?.let {
                Debug.lastNotification = "call: ${name.ifEmpty { "unknown" }} (silenced: $it)"
                return
            }
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
                app = sbn.packageName,
                appName = appName(sbn.packageName),
                extras = if (ringing) emptyList() else extras.take(2),
            ),
        )
        Debug.lastNotification = "call: ${name.ifEmpty { "unknown" }} (${if (ringing) "ringing" else "active"})"
    }

    // =====================================================================
    // Timers, downloads, navigation (ongoing notifications)
    // =====================================================================

    private fun isScreenRecording(pkg: String, title: String, text: String): Boolean {
        val words = (title + " " + text).lowercase()
        if (words.contains("saved") || words.contains("finished")) return false
        val known = pkg == "com.samsung.android.app.smartcapture" || pkg == "com.google.android.apps.screenrecorder" ||
            pkg.contains("screenrecord", true) || pkg.contains("screenrecorder", true)
        val said = words.contains("screen record") || words.contains("recording screen") || words.contains("recording your screen") ||
            words.contains("screen recorder")
        // System UI's recorder (Pixel / AOSP) says so in its text.
        return said || (known && (words.contains("record") || words.isBlank()))
    }

    private fun isClockApp(pkg: String) =
        pkg == "com.sec.android.app.clockpackage" || pkg == "com.google.android.deskclock" || pkg.contains("clock", true)

    /** Returns true when the notification was an ongoing activity the island now shows. */
    private fun handleOngoing(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        val ex = n.extras ?: Bundle()
        val pkg = sbn.packageName
        val prefs = Prefs.get(this)
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()

        // Another app's screen recorder (Samsung's, a Pixel's, AZ…): a red dot and timer.
        if (sbn.isOngoing && pkg != packageName && isScreenRecording(pkg, title, text)) {
            if (!prefs.getBoolean(Prefs.RECORDING, true)) return true
            // Halo's own recorder wins if both somehow run.
            if (IslandStore.rec?.own == true) return true
            fun find(vararg words: String) = n.actions?.firstOrNull { a ->
                val t = a.title?.toString()?.lowercase().orEmpty()
                words.any { t.contains(it) }
            }?.actionIntent
            val pause = find("pause")
            val resume = find("resume", "continue")
            val stop = find("stop", "end", "finish", "done")
            val paused = resume != null && pause == null
            val now = android.os.SystemClock.elapsedRealtime()
            val prev = IslandStore.rec?.takeIf { it.key == sbn.key }
            val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) && n.`when` > 0L
            val info = when {
                // The recorder shows a running clock: use its start time.
                chrono && !paused -> RecInfo(sbn.key, own = false, runningSince = now - (System.currentTimeMillis() - n.`when`).coerceAtLeast(0L), bankedMs = 0L, paused = false)
                prev == null -> RecInfo(sbn.key, own = false, runningSince = if (paused) 0L else now, bankedMs = 0L, paused = paused)
                prev.paused == paused -> prev
                paused -> prev.copy(paused = true, bankedMs = prev.elapsedMs(), runningSince = 0L)
                else -> prev.copy(paused = false, runningSince = now)
            }
            IslandStore.setRec(info.copy(appName = appName(pkg), pauseIntent = pause, resumeIntent = resume, stopIntent = stop, contentIntent = n.contentIntent))
            Debug.lastNotification = "${appName(pkg)} — screen recording"
            return true
        }

        // Navigation
        if (pkg == "com.google.android.apps.maps" && sbn.isOngoing) {
            if (!prefs.getBoolean(Prefs.NAV, true)) return true
            IslandStore.setNav(
                NavInfo(
                    key = sbn.key,
                    primary = title.ifEmpty { text },
                    secondary = if (title.isEmpty()) "" else text,
                    arrow = iconBitmap(n.getLargeIcon()),
                    contentIntent = n.contentIntent,
                ),
            )
            Debug.lastNotification = "Maps — navigation: ${title.take(24)}"
            return true
        }

        // Timers and stopwatches
        if (isClockApp(pkg) && sbn.isOngoing && n.category != Notification.CATEGORY_ALARM) {
            if (!prefs.getBoolean(Prefs.TIMERS, true)) return true
            val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
            val down = ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)
            val actions = n.actions?.mapNotNull { a ->
                val t = a.title?.toString() ?: return@mapNotNull null
                val pi = a.actionIntent ?: return@mapNotNull null
                t to pi
            } ?: emptyList()
            IslandStore.setTimer(
                TimerInfo(
                    key = sbn.key,
                    label = title.ifEmpty { if (down) "Timer" else "Stopwatch" },
                    anchor = if (chrono) n.`when` else 0L,
                    countDown = down,
                    pausedText = text.ifEmpty { "Paused" },
                    contentIntent = n.contentIntent,
                    actions = actions.take(3),
                ),
            )
            Debug.lastNotification = "Clock — ${title.ifEmpty { "timer" }}"
            return true
        }

        // Downloads, uploads and installs
        val max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val prog = ex.getInt(Notification.EXTRA_PROGRESS, 0)
        val indeterminate = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val hasBar = max > 0 || (indeterminate && n.category == Notification.CATEGORY_PROGRESS)
        if (hasBar && sbn.isOngoing) {
            if (!prefs.getBoolean(Prefs.TRANSFERS, true)) return true
            IslandStore.setTransfer(
                TransferInfo(
                    key = sbn.key,
                    title = title.ifEmpty { text.ifEmpty { "Downloading" } },
                    appName = appName(pkg),
                    icon = appIcon(pkg),
                    progress = if (max > 0) (prog.toFloat() / max).coerceIn(0f, 1f) else -1f,
                    contentIntent = n.contentIntent,
                ),
            )
            return true
        }
        // A transfer that just finished turns into a normal notification.
        if (IslandStore.transfer?.key == sbn.key) {
            IslandStore.setTransfer(null)
            IslandStore.showPop("Done", title.ifEmpty { "Finished" }.take(18), Colors.GREEN, R.drawable.ic_check, 2_500L)
            return true
        }
        return false
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
            Debug.note("$app: not shown (${reason})")
        }

        if (pkg == packageName) return skip("Halo itself")
        val prefs = Prefs.get(this)
        if (!prefs.getBoolean(Prefs.NOTIFS, true)) return skip("notifications off in Halo")
        if (pkg in Prefs.blocked(this)) return skip("app is blocked")
        if (sbn.isOngoing) return skip("ongoing (progress, service)")
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return skip("group summary")

        // Follow the phone's own rules: Do Not Disturb (and its exceptions,
        // like starred contacts), silent or blocked channels, paused apps.
        val ranking = rankingOf(sbn)
        val quiet = quietReason(ranking)
        // DND peek: only Do Not Disturb is waived (a paused app or a channel
        // you turned off stays hidden). Halo itself makes no sound, and the
        // system's sound, vibration and pop-up still follow DND.
        val peek = quiet == "Do Not Disturb" && prefs.getBoolean(Prefs.DND_PEEK, false)
        if (quiet != null && !peek) return skip(quiet)
        if (ranking != null && ranking.importance in 1 until NotificationManager.IMPORTANCE_DEFAULT) return skip("silent notification")

        // Lock screen privacy: hidden notifications stay hidden, private ones lose their text.
        var hideText = prefs.getBoolean(Prefs.HIDE_TEXT, false)
        if (isLocked()) {
            if (!secureFlag("lock_screen_show_notifications", true)) return skip("lock screen notifications off")
            // (Android 12+ only; on 11 the notification's own setting decides.)
            val override = if (Build.VERSION.SDK_INT >= 31) ranking?.lockscreenVisibilityOverride ?: NO_OVERRIDE else NO_OVERRIDE
            val vis = if (override != NO_OVERRIDE) override else n.visibility
            if (vis == Notification.VISIBILITY_SECRET) return skip("hidden on lock screen")
            if (vis == Notification.VISIBILITY_PRIVATE || !secureFlag("lock_screen_allow_private_notifications", true)) hideText = true
        }

        val rich = richOf(sbn, hideText)
        val title = rich.title
        val text = rich.text
        if (title.isEmpty() && text.isEmpty()) return skip("no text")
        // "New messages from 3 chats" / "5 messages from 2 chats": a summary of
        // other notifications (each chat already pops on its own).
        if (SUMMARY.containsMatchIn(text) || SUMMARY.containsMatchIn(title)) return skip("summary of other chats")

        val fingerprint = "$title|${rich.sender}|$text"
        if (shown[sbn.key] == fingerprint) return skip("already shown")
        // The same message coming back (chat apps re-post a notification when
        // a photo finishes downloading, or cancel and post it again): only a
        // message newer than the last one shown for this chat pops the island.
        val conv = pkg + "|" + (n.shortcutId ?: sbn.key)
        val newest = newestMessageTime(n)
        if (newest > 0L && newest <= (seenMsg[conv] ?: 0L)) {
            shown[sbn.key] = fingerprint
            return skip("same message again")
        }
        if (newest > 0L) seenMsg[conv] = newest
        if (seenMsg.size > 300) seenMsg.clear()
        // Your own reply (sent from Halo or the app) updating the chat: nothing to announce.
        if (lastIsFromYou(n)) {
            shown[sbn.key] = fingerprint
            return skip("your own message")
        }
        val alertOnce = (n.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0
        if (alertOnce && shown.containsKey(sbn.key)) {
            shown[sbn.key] = fingerprint
            return skip("quiet update")
        }
        shown[sbn.key] = fingerprint
        if (shown.size > 200) shown.clear()

        // Cooldown: an app that fires 3+ alerts within 20 s stops popping the island
        // (everything still shows in the notification center).
        val now = System.currentTimeMillis()
        val times = bursts.getOrPut(pkg) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.first() > 20_000L) times.removeFirst()
        times.addLast(now)
        if (times.size > 3) return skip("busy app, cooling down")

        val icon = rich.avatar ?: rich.appIcon
        val color = if (n.color != Notification.COLOR_DEFAULT && Color.alpha(n.color) > 0) brighten(n.color) else accentFrom(rich.appIcon)
        IslandStore.showNotif(
            NotifInfo(
                id = IslandStore.nextId(),
                key = sbn.key,
                packageName = pkg,
                appName = app,
                title = title.ifEmpty { app },
                text = text,
                icon = icon,
                accent = color,
                contentIntent = n.contentIntent,
                rich = rich.copy(title = title.ifEmpty { app }),
                silent = peek,
            ),
            5_000L,
        )
        Debug.lastNotification = "$app — shown: ${title.take(24)}" + if (peek) " (DND peek)" else ""
        Debug.note("$app: shown \u2014 ${title.take(30)}" + if (peek) " (DND peek)" else "")
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
    // Panel (opened from the camera circle)
    // =====================================================================

    /** Notifications worth showing in the panel, newest first. */
    fun panelItems(): List<PanelItem> {
        val all = try {
            activeNotifications ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        val blocked = Prefs.blocked(this)
        val hideText = Prefs.get(this).getBoolean(Prefs.HIDE_TEXT, false)
        return all.asSequence()
            .filter { it.packageName != packageName }
            .filter { it.packageName !in blocked }
            .filter { !it.isOngoing || isCall(it) }
            .filter { (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 }
            .filter { mediaToken(it.notification) == null }
            .sortedByDescending { it.postTime }
            .take(20)
            .mapNotNull { sbn ->
                val n = sbn.notification
                val rich = richOf(sbn, hideText)
                if (rich.title.isEmpty() && rich.text.isEmpty()) return@mapNotNull null
                val app = appName(sbn.packageName)
                PanelItem(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    appName = app,
                    title = rich.title.ifEmpty { app },
                    text = rich.text,
                    icon = rich.avatar ?: rich.appIcon,
                    accent = if (n.color != Notification.COLOR_DEFAULT && Color.alpha(n.color) > 0) brighten(n.color) else Colors.BLUE,
                    postTime = sbn.postTime,
                    canReply = rich.replyable,
                    rich = rich.copy(title = rich.title.ifEmpty { app }),
                )
            }
            .toList()
    }

    /**
     * Every notification you have right now (any app, not only the ones that
     * popped), as alerts you can swipe through on the island, newest first.
     */
    fun swipeNotes(): List<NotifInfo> {
        val all = try {
            activeNotifications ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        val blocked = Prefs.blocked(this)
        val hideText = Prefs.get(this).getBoolean(Prefs.HIDE_TEXT, false) || isLocked()
        return all.asSequence()
            .filter { it.packageName != packageName && it.packageName !in blocked }
            .filter { !it.isOngoing }
            .filter { (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 }
            .filter { mediaToken(it.notification) == null && !isCall(it) }
            .sortedByDescending { it.postTime }
            .take(10)
            .mapNotNull { sbn ->
                val n = sbn.notification
                val rich = richOf(sbn, hideText)
                if (rich.title.isEmpty() && rich.text.isEmpty()) return@mapNotNull null
                if (SUMMARY.containsMatchIn(rich.text) || SUMMARY.containsMatchIn(rich.title)) return@mapNotNull null
                val app = appName(sbn.packageName)
                NotifInfo(
                    id = IslandStore.nextId(),
                    key = sbn.key,
                    packageName = sbn.packageName,
                    appName = app,
                    title = rich.title.ifEmpty { app },
                    text = rich.text,
                    icon = rich.avatar ?: rich.appIcon,
                    accent = if (n.color != Notification.COLOR_DEFAULT && Color.alpha(n.color) > 0) brighten(n.color) else accentFrom(rich.appIcon),
                    contentIntent = n.contentIntent,
                    rich = rich.copy(title = rich.title.ifEmpty { app }),
                    silent = true,
                )
            }
            .toList()
    }

    /** For Halo Launcher: unread counts per app (icon badges) and the newest few. */
    fun launcherSnapshot(): Pair<Map<String, Int>, List<PanelItem>> {
        val all: Array<StatusBarNotification> = try {
            activeNotifications ?: emptyArray<StatusBarNotification>()
        } catch (_: Exception) {
            emptyArray<StatusBarNotification>()
        }
        val blocked = Prefs.blocked(this)
        val counts = HashMap<String, Int>()
        for (sbn in all) {
            if (sbn.packageName == packageName || sbn.isOngoing || mediaToken(sbn.notification) != null) continue
            if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue
            val n = sbn.notification.number.takeIf { it > 0 } ?: 1
            counts[sbn.packageName] = (counts[sbn.packageName] ?: 0) + n
        }
        val recent = panelItems().filter { it.packageName !in blocked }.take(5)
        return counts to recent
    }

    /** Ranking's "no lock screen override" value (hidden constant in the SDK). */
    private val NO_OVERRIDE = -1000

    private fun rankingOf(sbn: StatusBarNotification): Ranking? = try {
        val r = Ranking()
        if (currentRanking?.getRanking(sbn.key, r) == true) r else null
    } catch (_: Exception) {
        null
    }

    /** Why the phone would keep this quiet right now, or null if it may pop up. */
    private fun quietReason(r: Ranking?): String? {
        if (r == null) {
            return if (currentInterruptionFilter > INTERRUPTION_FILTER_ALL) "Do Not Disturb" else null
        }
        if (!r.matchesInterruptionFilter()) return "Do Not Disturb"
        if (r.importance == NotificationManager.IMPORTANCE_NONE) return "channel turned off"
        if (r.isSuspended) return "app paused"
        return null
    }

    private fun isLocked(): Boolean = try {
        getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
    } catch (_: Exception) {
        false
    }

    /** Reads an on/off system setting, falling back to [def] when the phone doesn't share it. */
    private fun secureFlag(name: String, def: Boolean): Boolean = try {
        android.provider.Settings.Secure.getInt(contentResolver, name, if (def) 1 else 0) != 0
    } catch (_: Exception) {
        def
    }

    /** Parsed notifications, so opening the notification center doesn't decode every photo again. */
    private val richCache = object : LinkedHashMap<String, Rich>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Rich>?) = size > 60
    }

    /** Splits a notification into chat parts (cached per version of the notification). */
    fun richOf(sbn: StatusBarNotification, hideText: Boolean): Rich {
        val ex = sbn.notification.extras
        val k = "${sbn.key}|${sbn.postTime}|$hideText|${ex?.getCharSequence(Notification.EXTRA_TEXT)?.hashCode()}"
        richCache[k]?.let { return it }
        return parseRich(sbn, hideText).also { richCache[k] = it }
    }

    @Suppress("DEPRECATION")
    private fun parseRich(sbn: StatusBarNotification, hideText: Boolean): Rich {
        val n = sbn.notification
        val ex = n.extras ?: Bundle()
        val msgs = try {
            ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        } catch (_: Exception) {
            null
        }
        val last = msgs?.lastOrNull() as? Bundle
        val isGroup = ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)

        var title = (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: ex.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()
        var count = msgs?.size ?: 0
        // WhatsApp writes "Group (124 messages)"; keep the name, keep the number separately.
        COUNT_SUFFIX.find(title)?.let { m ->
            count = maxOf(count, m.groupValues[1].toIntOrNull() ?: 0)
            title = title.removeRange(m.range).trim()
        }

        val sender = last?.getCharSequence("sender")?.toString()?.trim()?.takeIf { isGroup && it.isNotEmpty() }
        val text = (last?.getCharSequence("text")
            ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()

        val personIcon = try {
            (last?.getParcelable("sender_person") as? android.app.Person)?.icon?.let { iconBitmap(it) }
        } catch (_: Exception) {
            null
        }
        val avatar = iconBitmap(n.getLargeIcon()) ?: personIcon

        val picture: Bitmap? = if (hideText) null else try {
            (ex.get(Notification.EXTRA_PICTURE) as? Bitmap)
                ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (ex.get(Notification.EXTRA_PICTURE_ICON) as? Icon)?.let { iconBitmap(it) }
                } else {
                    null
                }
        } catch (_: Exception) {
            null
        }

        val markRead = n.actions?.firstOrNull { a ->
            a.title?.toString()?.lowercase()?.contains("read") == true && a.remoteInputs.isNullOrEmpty()
        }?.actionIntent

        return Rich(
            title = title,
            sender = if (hideText) null else sender,
            text = if (hideText) "New message" else text,
            avatar = avatar,
            appIcon = appIcon(sbn.packageName),
            picture = picture,
            count = count,
            replyable = n.actions?.any { it.remoteInputs?.isNotEmpty() == true } == true,
            markRead = markRead,
        )
    }

    private val countNow = Runnable { countNotifications() }

    /** How many notifications the panel would show; bursts of posts are counted once. */
    fun refreshCount() {
        handler.removeCallbacks(countNow)
        handler.postDelayed(countNow, 150L)
    }

    private fun countNotifications() {
        val all: Array<StatusBarNotification> = try {
            activeNotifications ?: emptyArray<StatusBarNotification>()
        } catch (_: Exception) {
            emptyArray<StatusBarNotification>()
        }
        val blocked = Prefs.blocked(this)
        val n = all.count {
            it.packageName != packageName && it.packageName !in blocked && !it.isOngoing &&
                (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&
                mediaToken(it.notification) == null
        }
        IslandStore.setNotifCount(n)
    }

    fun clearAll() {
        try {
            cancelAllNotifications()
        } catch (_: Exception) {
        }
        handler.postDelayed({ refreshCount() }, 300L)
    }

    fun find(key: String): StatusBarNotification? = try {
        activeNotifications?.firstOrNull { it.key == key }
    } catch (_: Exception) {
        null
    }

    fun dismiss(key: String) {
        try {
            cancelNotification(key)
        } catch (_: Exception) {
        }
    }

    fun titleOf(ex: Bundle): String =
        (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: ex.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()

    fun textOf(ex: Bundle): String =
        lastMessage(ex)
            ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?: ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: ""

    // =====================================================================
    // Helpers
    // =====================================================================

    fun appName(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg.substringAfterLast('.')
    }

    private val iconCache = HashMap<String, Bitmap?>()

    /**
     * The app's icon. With Halo Launcher installed it's the same icon as on
     * the home screen (icon pack, Liquid Glass…): read from Halo's own copy,
     * or, the first time, the system icon while the launcher's is fetched in
     * the background for next time.
     */
    fun appIcon(pkg: String): Bitmap? = synchronized(iconCache) {
        iconCache[pkg]?.let { return it }
        LauncherIcons.cached(this, pkg)?.let { iconCache[pkg] = it; return it }
        val sys = try {
            drawableToBitmap(packageManager.getApplicationIcon(pkg))
        } catch (_: Exception) {
            null
        }
        iconCache[pkg] = sys
        LauncherIcons.fetch(this, pkg) { b -> synchronized(iconCache) { iconCache[pkg] = b } }
        sys
    }

    /** The launcher's icon look changed. */
    fun forgetIcons() {
        LauncherIcons.clear(this)
        synchronized(iconCache) { iconCache.clear() }
    }

    fun iconBitmap(icon: Icon?): Bitmap? {
        icon ?: return null
        return try {
            drawableToBitmap(icon.loadDrawable(this))
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** Set by the reply sheet while it's open. */
        var onPosted: ((StatusBarNotification) -> Unit)? = null
        var instance: HaloNotificationListener? = null
            private set

        private val SUMMARY = Regex("""(^|\b)(\d+\s+)?new\s+messages?\s+from\s+\d+\s+chats?|\d+\s+messages?\s+from\s+\d+\s+chats?""", RegexOption.IGNORE_CASE)
        private val COUNT_SUFFIX = Regex("""\s*\((\d+)\s+(new\s+)?messages?\)\s*$""", RegexOption.IGNORE_CASE)

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
