package com.lonerxone.halo

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from GitHub Releases: check the latest release, download the
 * APK straight into Android's package installer, and let Android show its
 * "Update this app?" confirmation. Works while the repo is public.
 */
object Updater {
    const val REPO = "LONERXONE-ops/halo"

    data class Release(val tag: String, val build: Int, val notes: String, val apkUrl: String?, val size: Long)

    sealed class Check {
        data class Ok(val release: Release) : Check()
        object NotPublic : Check()
        data class Error(val message: String) : Check()
    }

    private val main = Handler(Looper.getMainLooper())

    /** "v0.1.12" -> 12 (the build number is the last part). */
    fun buildOf(tag: String): Int = tag.substringAfterLast('.').filter { it.isDigit() }.toIntOrNull() ?: 0

    fun check(done: (Check) -> Unit) {
        Thread {
            val result = try {
                val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "Halo-Android")
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                when (val code = c.responseCode) {
                    200 -> {
                        val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                        val tag = json.optString("tag_name")
                        val assets = json.optJSONArray("assets")
                        var url: String? = null
                        var size = 0L
                        if (assets != null) {
                            for (i in 0 until assets.length()) {
                                val a = assets.getJSONObject(i)
                                if (a.optString("name").endsWith(".apk")) {
                                    url = a.optString("browser_download_url")
                                    size = a.optLong("size")
                                    break
                                }
                            }
                        }
                        Check.Ok(Release(tag, buildOf(tag), json.optString("body").trim(), url, size))
                    }
                    404 -> Check.NotPublic
                    else -> Check.Error("GitHub said $code")
                }
            } catch (e: Exception) {
                Check.Error(e.message ?: "No connection")
            }
            main.post { done(result) }
        }.start()
    }

    /** Streams the APK into a PackageInstaller session; Android then asks to confirm. */
    fun install(ctx: Context, release: Release, progress: (Float) -> Unit, failed: (String) -> Unit) {
        val url = release.apkUrl ?: return failed("This release has no APK")
        val app = ctx.applicationContext
        Thread {
            try {
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(app.packageName)
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.setRequestProperty("User-Agent", "Halo-Android")
                    c.instanceFollowRedirects = true
                    c.connectTimeout = 15_000
                    c.readTimeout = 30_000
                    val total = if (c.contentLengthLong > 0) c.contentLengthLong else release.size
                    c.inputStream.use { input ->
                        session.openWrite("halo.apk", 0, if (total > 0) total else -1).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastShown = 0L
                            while (true) {
                                val r = input.read(buf)
                                if (r < 0) break
                                out.write(buf, 0, r)
                                done += r
                                if (total > 0 && done - lastShown > total / 50) {
                                    lastShown = done
                                    val f = done.toFloat() / total
                                    main.post { progress(f) }
                                }
                            }
                            session.fsync(out)
                        }
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getBroadcast(app, id, Intent(app, InstallReceiver::class.java), flags)
                    session.commit(pi.intentSender)
                }
                main.post { progress(1f) }
            } catch (e: Exception) {
                main.post { failed(e.message ?: "Download failed") }
            }
        }.start()
    }
}

/** Receives the installer's answer: shows Android's confirm screen, or the error. */
class InstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    if (confirm != null) context.startActivity(confirm)
                } catch (_: Exception) {
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                Toast.makeText(context, "Update failed: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
