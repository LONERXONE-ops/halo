package com.lonerxone.halo

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Fires another app's PendingIntent (open chat, mark as read, answer...).
 *
 * Since Android 14 the app that SENDS a PendingIntent must explicitly allow it
 * to start a screen, or Android silently blocks it. That is what broke "Open"
 * in the message sheet. Every send in Halo goes through here.
 */
object Launch {
    @Suppress("DEPRECATION")
    fun send(ctx: Context, pi: PendingIntent?, fill: Intent? = null): Boolean {
        pi ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(ctx, 0, fill, null, null, null, opts.toBundle())
            } else {
                pi.send(ctx, 0, fill)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Opens an app's main screen (fallback when a notification has no tap action). */
    fun openApp(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(i)
            true
        } catch (_: Exception) {
            false
        }
    }
}
