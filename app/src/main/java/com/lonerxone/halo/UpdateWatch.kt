package com.lonerxone.halo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tells you when there's a new Halo or Halo Launcher: a few times a day
 * (never more than every six hours, and only with a connection) Halo asks
 * GitHub for the latest release of each, and if it's newer than what's
 * installed, posts a notification. Tap it to open that app's update page.
 * Each new version is announced once.
 */
object UpdateWatch {
    private const val CHANNEL = "updates"
    private const val EVERY_MS = 6 * 60 * 60 * 1000L
    private const val LAUNCHER_REPO = "LONERXONE-ops/halo-launcher"

    fun maybeCheck(c: Context) {
        val p = c.getSharedPreferences("update_watch", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong("checked", 0L) < EVERY_MS) return
        p.edit().putLong("checked", now).apply()
        val app = c.applicationContext
        Thread {
            check(app, Updater.REPO, app.packageName, "Halo", 1)
            if (LauncherLink.launcherInstalled(app)) check(app, LAUNCHER_REPO, LauncherLink.LAUNCHER, "Halo Launcher", 2)
        }.start()
    }

    private fun installedBuild(c: Context, pkg: String): Int = try {
        Updater.buildOf(c.packageManager.getPackageInfo(pkg, 0).versionName ?: "0")
    } catch (_: Exception) {
        -1
    }

    private fun check(c: Context, repo: String, pkg: String, name: String, id: Int) {
        val have = installedBuild(c, pkg)
        if (have < 0) return
        var body = ""
        val tag = try {
            val con = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
            con.setRequestProperty("Accept", "application/vnd.github+json")
            con.setRequestProperty("User-Agent", "Halo-Android")
            con.connectTimeout = 10_000
            con.readTimeout = 10_000
            if (con.responseCode != 200) return
            val json = JSONObject(con.inputStream.bufferedReader().use { it.readText() })
            body = json.optString("body")
            json.optString("tag_name")
        } catch (_: Exception) {
            return
        }
        val latest = Updater.buildOf(tag)
        if (latest <= have) return
        val p = c.getSharedPreferences("update_watch", Context.MODE_PRIVATE)
        if (p.getString("told_$pkg", "") == tag) return
        p.edit().putString("told_$pkg", tag).apply()
        notify(c, name, tag, pkg, id, body)
    }

    private fun notify(c: Context, name: String, tag: String, pkg: String, id: Int, body: String) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a new Halo or Halo Launcher is out"
            })
        }
        val open = if (pkg == c.packageName) {
            Intent(c, MainActivity::class.java).putExtra("page", "updates")
        } else {
            Intent().setClassName(pkg, "$pkg.SettingsShortcut").putExtra("page", "updates")
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val (headline, _) = ReleaseNotes.parse(body)
        val teaser = ReleaseNotes.teaser(body)
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle("$name $tag is out")
            .setContentText(headline.ifEmpty { "Tap to see what's new and update" })
            // Pull it down: the first few things that are new.
            .setStyle(Notification.BigTextStyle().bigText(listOf(headline, teaser).filter { it.isNotEmpty() }.joinToString("\n").ifEmpty { "Tap to see what's new and update" }))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(9000 + id, n)
        } catch (_: SecurityException) {
        }
    }
}
