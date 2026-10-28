package com.lonerxone.halo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * "Halo handles pop-ups": switches off Android's own heads-up banners so new
 * notifications only appear on the island. They still land in the shade and
 * still make their sound; only the banner sliding down from the top is gone.
 *
 * Android only lets an app change this switch with WRITE_SECURE_SETTINGS,
 * which is granted once over ADB (it survives updates, not an uninstall).
 * Halo turns the system banners back on whenever its service stops, so they
 * never stay off by accident.
 */
object PopupControl {
    private const val KEY = "heads_up_notifications_enabled"
    const val GRANT = "adb shell pm grant com.lonerxone.halo android.permission.WRITE_SECURE_SETTINGS"

    fun canControl(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Are Android's own pop-ups on right now? */
    fun systemPopupsOn(c: Context): Boolean = try {
        Settings.Global.getInt(c.contentResolver, KEY, 1) != 0
    } catch (_: Exception) {
        true
    }

    /** Applies the setting: Halo takes over (system banners off) or hands back. */
    fun apply(c: Context, haloHandles: Boolean): Boolean {
        if (!canControl(c)) return false
        return try {
            Settings.Global.putInt(c.contentResolver, KEY, if (haloHandles) 0 else 1)
        } catch (_: Exception) {
            false
        }
    }

    /** What the user chose, applied (called when the island service starts). */
    fun sync(c: Context) {
        val want = Prefs.get(c).getBoolean(Prefs.TAKEOVER, false)
        if (want) apply(c, true)
    }

    /** The island is going away: give pop-ups back to Android. */
    fun release(c: Context) {
        if (Prefs.get(c).getBoolean(Prefs.TAKEOVER, false)) apply(c, false)
    }
}
