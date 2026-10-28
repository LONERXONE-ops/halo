package com.lonerxone.halo

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.NotificationManager
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.media.AudioManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.max

/**
 * Hosts the island as trusted accessibility overlays, so it can sit on top of
 * the status bar right over the camera. It never reads screen content.
 *
 * Two windows:
 * - the DRAW window: fixed size, never resized, never touchable. Resizing an
 *   overlay makes Android stretch the old frame for a moment, which was the
 *   flicker in earlier builds.
 * - the TOUCH window: invisible, follows the island's shape, and forwards
 *   every touch to the draw window. Moving it costs nothing visually.
 */
class HaloService : AccessibilityService(), IslandView.Host {

    private var wm: WindowManager? = null
    private var island: IslandView? = null
    private var drawLp: WindowManager.LayoutParams? = null
    private var touch: TouchProxy? = null
    private var touchLp: WindowManager.LayoutParams? = null
    private var touchAttached = false

    private var locked = false
    private var landscape = false
    private var fullscreen = false

    private val storeListener: () -> Unit = { push() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> onCharging()
                Intent.ACTION_SCREEN_OFF -> locked = true
                Intent.ACTION_BATTERY_CHANGED -> {
                    onBattery(intent)
                    return
                }
                AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                    if (!isInitialStickyBroadcast) onRinger(intent)
                    return
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    onBluetooth(intent)
                    return
                }
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                    onDnd()
                    return
                }
                else -> locked = keyguard().isKeyguardLocked
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
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Window changes are a cheap moment to re-check the lock screen state.
        val now = try {
            keyguard().isKeyguardLocked
        } catch (_: Exception) {
            locked
        }
        if (now != locked) {
            locked = now
            push()
        }
    }

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
        removeTouch()
        island?.let { v ->
            try {
                wm?.removeView(v)
            } catch (_: Exception) {
            }
        }
        island = null
        drawLp = null
        touch = null
        touchLp = null
        if (instance === this) instance = null
        Debug.island = "not running"
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        val v = island ?: return
        v.geometry = geometry()
        drawLp?.let { p ->
            p.width = v.geometry.screenW
            p.height = drawHeight(v.geometry)
            try {
                wm?.updateViewLayout(v, p)
            } catch (_: Exception) {
            }
        }
        v.rebuild()
        push()
    }

    // ---------------------------------------------------------------------
    // Windows
    // ---------------------------------------------------------------------

    private fun keyguard() = getSystemService(KEYGUARD_SERVICE) as KeyguardManager

    private fun baseParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        flags,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        x = 0
        y = 0
    }

    private fun drawHeight(g: IslandView.Geometry): Int =
        (g.screenH * 0.6f + g.statusBarH + 64 * resources.displayMetrics.density).toInt()

    private fun attach() {
        val w = wm ?: return
        val v = IslandView(this, this)
        v.geometry = geometry()
        applyPrefs(v)

        val params = baseParams(
            v.geometry.screenW,
            drawHeight(v.geometry),
            DRAW_FLAGS,
        ).apply { title = "Halo" }

        v.setOnApplyWindowInsetsListener { _, insets ->
            // Only call it fullscreen when the status bar exists for this window
            // but is currently collapsed.
            val max = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
            val now = insets.getInsets(WindowInsets.Type.statusBars()).top
            val fs = max > 0 && now == 0
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
        drawLp = params

        touch = TouchProxy(this)
        touchLp = baseParams(1, 1, TOUCH_FLAGS).apply { title = "Halo touch" }
        v.post { push() }
    }

    private fun removeTouch() {
        val t = touch ?: return
        if (touchAttached) {
            try {
                wm?.removeView(t)
            } catch (_: Exception) {
            }
        }
        touchAttached = false
    }

    /** Forwards touches from the small touch window into the big draw window. */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class TouchProxy(ctx: Context) : View(ctx) {
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val target = island ?: return false
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                // A touch somewhere else on the screen (it still reaches that app): retract.
                target.onOutsideTouch()
                return false
            }
            val p = touchLp ?: return false
            val ev = MotionEvent.obtain(event)
            ev.offsetLocation(p.x.toFloat(), p.y.toFloat())
            target.dispatchTouchEvent(ev)
            ev.recycle()
            return true
        }
    }

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------

    private fun applyPrefs(v: IslandView) {
        val p = Prefs.get(this)
        v.idleEnabled = p.getBoolean(Prefs.IDLE, true)
        v.hapticsEnabled = p.getBoolean(Prefs.HAPTICS, true)
        v.sizeScale = p.getInt(Prefs.SCALE, 100) / 100f
        val d = resources.displayMetrics.density
        v.offsetX = p.getInt(Prefs.OFFSET_X, 0) * d
        v.offsetY = p.getInt(Prefs.OFFSET_Y, 0) * d
        v.glassStrength = p.getInt(Prefs.GLASS, 0) / 100f
        v.artBackdrop = p.getBoolean(Prefs.ART_BACKDROP, true)
        v.lowPower = p.getBoolean(Prefs.LOW_POWER, false)
    }

    /** Called by the settings screen after a change. */
    fun reloadPrefs() {
        val v = island ?: return
        applyPrefs(v)
        v.rebuild()
        push()
    }

    /** One line for the test button, so problems are visible without a PC. */
    fun diagnostics(): String {
        val v = island
        val attached = v?.isAttachedToWindow == true
        val n = v?.geometry?.notch
        val notch = if (n == null) "none" else "${n.width()}x${n.height()} at ${n.centerX()},${n.centerY()}"
        return "overlay: ${if (attached) "on" else "OFF"}, notch: $notch, fullscreen: $fullscreen, locked: $locked"
    }

    private fun push() {
        val v = island ?: return
        val p = Prefs.get(this)
        val note = IslandStore.notif
        val pop = IslandStore.pop

        // Live activities, most important first. The first takes the pill; the
        // second sits in the round bubble beside it (like the iPhone split).
        val live = buildList<IslandView.Content> {
            IslandStore.call?.takeIf { p.getBoolean(Prefs.CALLS, true) }?.let { add(IslandView.Content.Call(it)) }
            IslandStore.timer?.takeIf { p.getBoolean(Prefs.TIMERS, true) }?.let { add(IslandView.Content.Timer(it)) }
            IslandStore.music?.takeIf { p.getBoolean(Prefs.MUSIC, true) }?.let { add(IslandView.Content.Music(it)) }
            IslandStore.nav?.takeIf { p.getBoolean(Prefs.NAV, true) }?.let { add(IslandView.Content.Nav(it)) }
            IslandStore.transfer?.takeIf { p.getBoolean(Prefs.TRANSFERS, true) }?.let { add(IslandView.Content.Transfer(it)) }
        }
        val main: IslandView.Content
        val second: IslandView.Content?
        when {
            note != null -> {
                main = IslandView.Content.Note(note)
                second = null
            }
            pop != null -> {
                main = IslandView.Content.Popup(pop)
                second = live.firstOrNull()
            }
            else -> {
                main = live.firstOrNull() ?: IslandView.Content.None
                second = live.getOrNull(1)
            }
        }

        val alert = main is IslandView.Content.Call || main is IslandView.Content.Note || main is IslandView.Content.Popup
        val hideFs = fullscreen && p.getBoolean(Prefs.HIDE_FULLSCREEN, true) && !alert
        val hide = locked || landscape || hideFs
        v.setState(main, second?.let { bubbleFor(it) }, hide)
        val reasons = buildList {
            if (locked) add("locked")
            if (landscape) add("landscape")
            if (hideFs) add("fullscreen")
        }
        Debug.island = "on · ${v.mode.name.lowercase()}" + if (reasons.isEmpty()) "" else " · hidden: ${reasons.joinToString()}"
    }

    private fun bubbleFor(c: IslandView.Content): IslandView.Bubble? = when (c) {
        is IslandView.Content.Music -> IslandView.Bubble("m", c.info.art ?: c.info.appIcon, R.drawable.ic_note, c.info.accent, -1f) { openMusicApp(c.info) }
        is IslandView.Content.Call -> IslandView.Bubble("c", null, R.drawable.ic_call, Colors.GREEN, -1f) { send(c.call.contentIntent) }
        is IslandView.Content.Timer -> IslandView.Bubble("t", null, R.drawable.ic_timer, Colors.ORANGE, -1f) { send(c.timer.contentIntent) }
        is IslandView.Content.Transfer -> IslandView.Bubble("d", c.transfer.icon, R.drawable.ic_download, Colors.BLUE, max(0f, c.transfer.progress)) { send(c.transfer.contentIntent) }
        is IslandView.Content.Nav -> IslandView.Bubble("g", c.nav.arrow, R.drawable.ic_nav, Colors.GREEN, -1f) { send(c.nav.contentIntent) }
        else -> null
    }

    // ---------------------------------------------------------------------
    // System pops: low battery, full, ringer, Bluetooth, Do Not Disturb
    // ---------------------------------------------------------------------

    private var lastBattery = -1
    private var lastBt = 0L

    private fun popsOn() = Prefs.get(this).getBoolean(Prefs.SYSTEM_POPS, true)

    private fun onBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return
        val pct = level * 100 / scale
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val prev = lastBattery
        lastBattery = pct
        if (prev < 0 || !popsOn()) return
        when {
            !plugged && prev > 15 && pct <= 15 -> IslandStore.showPop("Low battery", "$pct%", Colors.ORANGE, R.drawable.ic_battery_low, 3_500L)
            !plugged && prev > 5 && pct <= 5 -> IslandStore.showPop("Battery", "$pct%", Colors.RED, R.drawable.ic_battery_low, 4_000L)
            plugged && prev < 100 && pct >= 100 -> IslandStore.showPop("Charged", "100%", Colors.GREEN, R.drawable.ic_battery_full, 3_000L)
        }
    }

    private fun onRinger(i: Intent) {
        if (!popsOn()) return
        when (i.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)) {
            AudioManager.RINGER_MODE_SILENT -> IslandStore.showPop("Silent", "On", Colors.RED, R.drawable.ic_bell_off, 2_000L)
            AudioManager.RINGER_MODE_VIBRATE -> IslandStore.showPop("Vibrate", "On", Colors.PURPLE, R.drawable.ic_vibrate, 2_000L)
            AudioManager.RINGER_MODE_NORMAL -> IslandStore.showPop("Ring", "On", Colors.BLUE, R.drawable.ic_bell, 2_000L)
        }
    }

    /**
     * Only real listening devices count: bonded (paired) audio gear such as
     * earbuds, headphones and car kits. Plain Bluetooth links (watches, tags,
     * background scans) used to trigger a bogus "Connected" pop.
     */
    @Suppress("DEPRECATION")
    private fun onBluetooth(i: Intent) {
        if (!popsOn()) return
        val device: BluetoothDevice = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }) ?: return
        val info = try {
            val bonded = device.bondState == BluetoothDevice.BOND_BONDED
            val major = device.bluetoothClass?.majorDeviceClass
            val audio = major == BluetoothClass.Device.Major.AUDIO_VIDEO || major == BluetoothClass.Device.Major.WEARABLE
            if (bonded && audio) device.name else null
        } catch (_: SecurityException) {
            null
        } ?: return
        val now = System.currentTimeMillis()
        if (now - lastBt < 8_000L) return
        lastBt = now
        IslandStore.showPop("Connected", info.take(18), Colors.BLUE, R.drawable.ic_headphones, 3_000L)
    }

    private fun onDnd() {
        if (!popsOn()) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val on = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        IslandStore.showPop("Do Not Disturb", if (on) "On" else "Off", Colors.PURPLE, R.drawable.ic_moon, 2_000L)
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
        var screenH = dm.heightPixels
        try {
            wm?.let {
                val b = it.maximumWindowMetrics.bounds
                screenW = b.width()
                screenH = b.height()
            }
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
        return IslandView.Geometry(notch, screenW, screenH, sb)
    }

    // ---------------------------------------------------------------------
    // IslandView.Host
    // ---------------------------------------------------------------------

    override fun setTouchArea(area: Rect?) {
        val w = wm ?: return
        val t = touch ?: return
        val p = touchLp ?: return
        if (area == null || area.isEmpty) {
            removeTouch()
            return
        }
        p.x = area.left
        p.y = area.top
        p.width = area.width()
        p.height = area.height()
        try {
            if (touchAttached) {
                w.updateViewLayout(t, p)
            } else {
                w.addView(t, p)
                touchAttached = true
            }
        } catch (_: Exception) {
        }
    }

    override fun panelItems(): List<PanelItem> =
        HaloNotificationListener.instance?.panelItems() ?: emptyList()

    override fun openDetail(key: String) {
        val i = Intent(this, NotificationActivity::class.java)
            .putExtra(NotificationActivity.EXTRA_KEY, key)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            startActivity(i)
        } catch (_: Exception) {
        }
    }

    override fun send(intent: PendingIntent?) {
        intent ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                intent.send(this, 0, null, null, null, null, opts.toBundle())
            } else {
                intent.send()
            }
        } catch (_: Exception) {
        }
    }

    override fun openMusicApp(info: MusicInfo) {
        val pi = info.controller?.sessionActivity
        if (pi != null) {
            send(pi)
            return
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

        private const val COMMON =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        private const val DRAW_FLAGS = COMMON or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        private const val TOUCH_FLAGS = COMMON or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
    }
}
