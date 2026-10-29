package com.lonerxone.halo

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Halo's side of the link with Halo Launcher. Both apps share a signature
 * permission, so these broadcasts only reach the launcher, and only the
 * launcher can send Halo commands.
 *
 * Halo sends: the song playing (with a small cover), unread counts per app
 * for icon badges, the newest notifications, and what's switched on.
 * The launcher sends: media buttons, open the notification center, open one
 * notification, lock the screen, switch something off, "send me your state".
 */
object LauncherLink {
    const val LAUNCHER = "com.lonerxone.halo.launcher"
    const val PERMISSION = "com.lonerxone.halo.permission.LINK"
    const val ACTION_STATE = "com.lonerxone.halo.action.STATE"
    const val ACTION_COMMAND = "com.lonerxone.halo.action.COMMAND"

    private val main = Handler(Looper.getMainLooper())
    private var installedAt = 0L
    private var installed = false
    private var pending: Runnable? = null
    private var lastArtKey: String? = null
    private var lastArt: Bitmap? = null

    fun launcherInstalled(c: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - installedAt > 30_000L) {
            installedAt = now
            installed = try {
                c.packageManager.getPackageInfo(LAUNCHER, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
        return installed
    }

    /** Sends the state soon; bursts of changes collapse into one broadcast. */
    fun schedule(svc: HaloService) {
        if (!launcherInstalled(svc)) return
        pending?.let { main.removeCallbacks(it) }
        val r = Runnable { sendNow(svc) }
        pending = r
        main.postDelayed(r, 300L)
    }

    fun sendNow(svc: HaloService) {
        if (!launcherInstalled(svc)) return
        val i = Intent(ACTION_STATE).setPackage(LAUNCHER)
        IslandStore.music?.let { m ->
            i.putExtra("m_title", m.title)
            i.putExtra("m_artist", m.artist)
            i.putExtra("m_app", m.packageName)
            i.putExtra("m_playing", m.playing)
            i.putExtra("m_accent", m.accent)
            i.putExtra("m_dur", m.durationMs)
            i.putExtra("m_pos", m.positionMs)
            i.putExtra("m_pos_at", m.positionUpdatedAt)
            i.putExtra("m_speed", m.speed)
            smallArt(m)?.let { i.putExtra("m_art", it) }
        }
        val listener = HaloNotificationListener.instance
        if (listener != null) {
            val (counts, recent) = listener.launcherSnapshot()
            i.putExtra("b_pkg", counts.keys.toTypedArray())
            i.putExtra("b_count", counts.values.toIntArray())
            i.putExtra("n_key", recent.map { it.key }.toTypedArray())
            i.putExtra("n_app", recent.map { it.packageName }.toTypedArray())
            i.putExtra("n_app_name", recent.map { it.appName }.toTypedArray())
            i.putExtra("n_title", recent.map { it.title }.toTypedArray())
            i.putExtra("n_text", recent.map { it.text }.toTypedArray())
            i.putExtra("n_time", recent.map { it.postTime }.toLongArray())
        }
        val quick = svc.quickStates()
        i.putExtra("q_id", quick.map { it.id }.toTypedArray())
        i.putExtra("q_label", quick.map { it.label }.toTypedArray())
        try {
            svc.sendBroadcast(i, PERMISSION)
        } catch (_: Exception) {
        }
    }

    /** The cover, shrunk to 256 px once per song so the broadcast stays small. */
    private fun smallArt(m: MusicInfo): Bitmap? {
        val art = m.art ?: return null
        if (lastArtKey == m.trackKey && lastArt != null) return lastArt
        val s = 256f / maxOf(art.width, art.height)
        val out = try {
            if (s >= 1f) art else Bitmap.createScaledBitmap(art, (art.width * s).toInt().coerceAtLeast(1), (art.height * s).toInt().coerceAtLeast(1), true)
        } catch (_: Throwable) {
            null
        }
        lastArtKey = m.trackKey
        lastArt = out
        return out
    }

    /** Runs a command from the launcher. */
    fun handle(svc: HaloService, cmd: String?, arg: String?) {
        when (cmd) {
            "request_state" -> sendNow(svc)
            "icons_changed" -> HaloNotificationListener.instance?.forgetIcons()
            else -> svc.launcherCommand(cmd ?: return, arg)
        }
    }
}

/**
 * App icons from Halo Launcher, so the island and notification center use
 * the same icon pack / Liquid Glass look as the home screen. Null when the
 * launcher isn't installed or doesn't have that app; Halo then uses the
 * system icon.
 */
object LauncherIcons {
    private val URI = android.net.Uri.parse("content://com.lonerxone.halo.launcher.icons")
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val pending = HashSet<String>()

    private fun dir(c: Context) = java.io.File(c.filesDir, "launcher_icons").apply { mkdirs() }

    /** From Halo's own copy (fast, safe on the main thread); null if not fetched yet. */
    fun cached(c: Context, pkg: String): Bitmap? {
        if (!LauncherLink.launcherInstalled(c)) return null
        val f = java.io.File(dir(c), "$pkg.png")
        if (!f.exists()) return null
        return try {
            android.graphics.BitmapFactory.decodeFile(f.path)
        } catch (_: Throwable) {
            null
        }
    }

    /** Fetches the launcher's icon in the background, then calls [done] on the main thread. */
    fun fetch(c: Context, pkg: String, done: (Bitmap) -> Unit) {
        if (!LauncherLink.launcherInstalled(c)) return
        synchronized(pending) { if (!pending.add(pkg)) return }
        val app = c.applicationContext
        io.execute {
            val b = try {
                app.contentResolver.openInputStream(android.net.Uri.withAppendedPath(URI, pkg))?.use { android.graphics.BitmapFactory.decodeStream(it) }
            } catch (_: Throwable) {
                null
            }
            synchronized(pending) { pending.remove(pkg) }
            if (b != null) {
                try {
                    java.io.File(dir(app), "$pkg.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } catch (_: Throwable) {
                }
                Handler(Looper.getMainLooper()).post { done(b) }
            }
        }
    }

    /** The launcher's icon look changed (or might have, while Halo was off): start fresh. */
    fun clear(c: Context) {
        dir(c).listFiles()?.forEach { it.delete() }
    }

    /** On start: if the launcher's look changed since the icons were saved, drop them. */
    fun checkVersion(c: Context, changed: () -> Unit) {
        if (!LauncherLink.launcherInstalled(c)) return
        val app = c.applicationContext
        io.execute {
            val v = try {
                app.contentResolver.call(URI, "version", null, null)?.getString("v")
            } catch (_: Throwable) {
                null
            } ?: return@execute
            val p = Prefs.get(app)
            if (p.getString("launcher_icons_v", null) != v) {
                clear(app)
                p.edit().putString("launcher_icons_v", v).apply()
                Handler(Looper.getMainLooper()).post { changed() }
            }
        }
    }
}
