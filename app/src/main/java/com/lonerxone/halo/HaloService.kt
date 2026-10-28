package com.lonerxone.halo

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.view.Display
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Hosts the island as a trusted accessibility overlay, so it can sit on top of
 * the status bar right over the camera notch. It never reads screen content.
 */
class HaloService : AccessibilityService(), IslandView.Host {

    private var wm: WindowManager? = null
    private var island: IslandView? = null
    private var lp: WindowManager.LayoutParams? = null

    private var locked = false
    private var landscape = false
    private var fullscreen = false

    private val storeListener: () -> Unit = { push() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> onCharging()
                Intent.ACTION_SCREEN_OFF -> locked = true
                Intent.ACTION_SCREEN_ON -> locked = keyguard().isKeyguardLocked
                Intent.ACTION_USER_PRESENT -> locked = false
            }
            push()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        locked = keyguard().isKeyguardLocked
        attach()
        IslandStore.addListener(storeListener)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        IslandStore.removeListener(storeListener)
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        island?.let { v ->
            try {
                wm?.removeView(v)
            } catch (_: Exception) {
            }
        }
        island = null
        lp = null
        if (instance === this) instance = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        val v = island ?: return
        v.geometry = geometry()
        lp?.let { p ->
            p.x = notchOffsetX(v.geometry)
            try {
                wm?.updateViewLayout(v, p)
            } catch (_: Exception) {
            }
        }
        v.rebuild()
        push()
    }

    // ---------------------------------------------------------------------

    private fun keyguard() = getSystemService(KEYGUARD_SERVICE) as KeyguardManager

    private fun attach() {
        val w = wm ?: return
        val v = IslandView(this, this)
        v.geometry = geometry()
        applyPrefs(v)
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            title = "Halo"
            x = notchOffsetX(v.geometry)
            y = 0
        }
        v.setOnApplyWindowInsetsListener { _, insets ->
            val fs = !insets.isVisible(WindowInsets.Type.statusBars())
            if (fs != fullscreen) {
                fullscreen = fs
                v.post { push() }
            }
            insets
        }
        try {
            w.addView(v, params)
        } catch (_: Exception) {
            return
        }
        island = v
        lp = params
        v.post { push() }
    }

    private fun applyPrefs(v: IslandView) {
        val p = Prefs.get(this)
        v.idleEnabled = p.getBoolean(Prefs.IDLE, true)
        v.hapticsEnabled = p.getBoolean(Prefs.HAPTICS, true)
        v.sizeScale = p.getInt(Prefs.SCALE, 100) / 100f
    }

    /** Called by the settings screen after a change. */
    fun reloadPrefs() {
        val v = island ?: return
        applyPrefs(v)
        v.rebuild()
        push()
    }

    private fun push() {
        val v = island ?: return
        val p = Prefs.get(this)
        val hide = locked || landscape || (fullscreen && p.getBoolean(Prefs.HIDE_FULLSCREEN, true))
        val pop = IslandStore.pop
        val music = IslandStore.music?.takeIf { p.getBoolean(Prefs.MUSIC, true) }
        val content = when {
            pop != null -> IslandView.Content.Popup(pop)
            music != null -> IslandView.Content.Music(music)
            else -> IslandView.Content.None
        }
        v.setState(content, hide)
    }

    private fun onCharging() {
        if (!Prefs.get(this).getBoolean(Prefs.CHARGING, true)) return
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        IslandStore.showPop("Charging", "$pct%", Colors.GREEN, R.drawable.ic_bolt, 3_000L)
    }

    private fun geometry(): IslandView.Geometry {
        val dm = resources.displayMetrics
        var screenW = dm.widthPixels
        try {
            wm?.let { screenW = it.maximumWindowMetrics.bounds.width() }
        } catch (_: Throwable) {
        }
        val notch = try {
            getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
                ?.cutout
                ?.boundingRectTop
                ?.takeIf { !it.isEmpty }
        } catch (_: Throwable) {
            null
        }
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        val sb = if (id > 0) resources.getDimensionPixelSize(id) else (24 * dm.density).toInt()
        return IslandView.Geometry(notch, screenW, sb)
    }

    private fun notchOffsetX(g: IslandView.Geometry): Int {
        val n = g.notch ?: return 0
        return n.centerX() - g.screenW / 2
    }

    // ---------------------------------------------------------------------
    // IslandView.Host
    // ---------------------------------------------------------------------

    override fun requestWindow(width: Int, height: Int, touchable: Boolean) {
        val v = island ?: return
        val p = lp ?: return
        var flags = BASE_FLAGS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (p.width == width && p.height == height && p.flags == flags) return
        p.width = width
        p.height = height
        p.flags = flags
        try {
            wm?.updateViewLayout(v, p)
        } catch (_: Exception) {
        }
    }

    override fun openMusicApp(info: MusicInfo) {
        val pi = info.controller?.sessionActivity
        if (pi != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val opts = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    pi.send(this, 0, null, null, null, null, opts.toBundle())
                } else {
                    pi.send()
                }
                return
            } catch (_: Exception) {
            }
        }
        val launch = packageManager.getLaunchIntentForPackage(info.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(launch)
        } catch (_: Exception) {
        }
    }

    companion object {
        var instance: HaloService? = null
            private set

        private const val BASE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }
}
