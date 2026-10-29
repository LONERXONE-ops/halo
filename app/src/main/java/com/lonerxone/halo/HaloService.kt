package com.lonerxone.halo

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.NotificationManager
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    if (connectivityOn()) {
                        val on = intent.getBooleanExtra("state", false)
                        IslandStore.showPop("Airplane mode", if (on) "On" else "Off", Colors.INK, R.drawable.ic_airplane, 2_200L)
                    }
                    return
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    // Battery saver: slower clock updates (the look stays the same).
                    island?.let {
                        applyPrefs(it)
                        it.rebuild()
                    }
                    if (connectivityOn()) {
                        val on = getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
                        IslandStore.showPop("Power saving", if (on) "On" else "Off", Colors.GREEN, R.drawable.ic_leaf, 2_200L)
                    }
                    return
                }
                ACTION_BT_BATTERY -> {
                    onBudsBattery(intent)
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
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(ACTION_BT_BATTERY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        watchTorch()
        watchWifi()
        PopupControl.sync(this)
        // Commands from Halo Launcher (signature-protected).
        val link = IntentFilter(LauncherLink.ACTION_COMMAND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(linkReceiver, link, LauncherLink.PERMISSION, null, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(linkReceiver, link, LauncherLink.PERMISSION, null)
        }
        LauncherLink.schedule(this)
    }

    private val linkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            LauncherLink.handle(this@HaloService, intent.getStringExtra("cmd"), intent.getStringExtra("arg"))
        }
    }

    /** What Halo Launcher asked for: media keys, the notification center, lock, switches. */
    fun launcherCommand(cmd: String, arg: String?) {
        val m = IslandStore.music
        val tc = m?.controller?.transportControls
        when (cmd) {
            "play_pause" -> if (tc != null) {
                if (m?.playing == true) tc.pause() else tc.play()
            } else {
                mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
            }
            "next" -> if (tc != null) tc.skipToNext() else mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
            "prev" -> if (tc != null) tc.skipToPrevious() else mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "open_music" -> if (m != null) openMusicApp(m) else mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
            "open_panel" -> island?.openPanel()
            "open_notif" -> if (arg != null) openDetail(arg)
            "lock" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            "quick" -> if (arg != null) quickAction(arg)
            "seek" -> arg?.toLongOrNull()?.let { tc?.seekTo(it) }
            "queue_play" -> arg?.toLongOrNull()?.let { tc?.skipToQueueItem(it) }
        }
        main.postDelayed({ LauncherLink.sendNow(this) }, 400L)
    }

    /** No player in focus: wake the last one with a media key, like a headset button. */
    private fun mediaKey(code: Int) {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        } catch (_: Exception) {
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
        // Never leave the phone without pop-ups when the island isn't there to show them.
        PopupControl.release(this)
        main.removeCallbacks(settle)
        IslandStore.removeListener(storeListener)
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        try {
            getSystemService(CameraManager::class.java)?.unregisterTorchCallback(torchCallback)
        } catch (_: Exception) {
        }
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(wifiCallback)
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
        try {
            unregisterReceiver(linkReceiver)
        } catch (_: Exception) {
        }
        Beat.stop()
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
        v.post {
            // Now the window has insets: if the display didn't report a camera, try again.
            if (v.geometry.notch == null) {
                v.geometry = geometry()
                v.rebuild()
            }
            push()
        }
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
        v.picDim = p.getInt(Prefs.PIC_DIM, 45).coerceIn(0, 90) / 100f
        val ip = loadPic(p, Prefs.ISLAND_PIC)
        if (ip !== v.islandPic) v.islandPic = ip
        val pp = loadPic(p, Prefs.PANEL_PIC)
        if (pp !== v.panelPic) v.panelPic = pp
        // Only Halo's own switch turns the effects off. Battery saver just slows
        // the clock updates; the bars and the cover backdrop stay.
        v.lowPower = p.getBoolean(Prefs.LOW_POWER, false)
        v.saver = getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
    }

    private val picCache = HashMap<String, Pair<Long, android.graphics.Bitmap>>()

    /** Decodes a saved picture once per change (the pref holds a timestamp). */
    private fun loadPic(p: android.content.SharedPreferences, key: String): android.graphics.Bitmap? {
        val stamp = p.getLong(key, 0L)
        if (stamp == 0L) {
            picCache.remove(key)
            return null
        }
        picCache[key]?.let { if (it.first == stamp) return it.second }
        val f = Prefs.picFile(this, key)
        if (!f.exists()) return null
        return try {
            android.graphics.BitmapFactory.decodeFile(f.absolutePath)?.also { picCache[key] = stamp to it }
        } catch (_: Throwable) {
            null
        }
    }

    /** Called by the settings screen after a change. */
    fun reloadPrefs() {
        val v = island ?: return
        applyPrefs(v)
        v.geometry = geometry()
        v.rebuild()
        push()
    }

    /** One line for the test button, so problems are visible without a PC. */
    fun diagnostics(): String {
        val v = island
        val attached = v?.isAttachedToWindow == true
        val n = v?.geometry?.notch
        val notch = if (n == null) "none ($cameraHow)" else "${n.width()}x${n.height()} at ${n.centerX()},${n.centerY()}"
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
            // The flashlight shows for a moment, then the island settles back into the
            // camera; it stays one tap away as a chip in the notification center.
            val torchAge = android.os.SystemClock.uptimeMillis() - IslandStore.torchSince
            IslandStore.torch?.takeIf { p.getBoolean(Prefs.TORCH, true) && torchAge < SETTLE_MS }?.let { add(IslandView.Content.Torch(it)) }
        }
        this.main.removeCallbacks(settle)
        if (IslandStore.torch != null && torchAge() < SETTLE_MS) this.main.postDelayed(settle, SETTLE_MS - torchAge() + 50L)
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
        // Landscape is fine: the camera is on the side then, so the island sits at
        // the top centre (CameraFinder ignores cutouts that aren't on the top edge).
        val hide = locked || hideFs
        // Nothing else running beside the pill? Show a small bell there so the
        // notification panel stays one tap away while music (or a timer) plays.
        val bubble = second?.let { bubbleFor(it) } ?: if (
            main !is IslandView.Content.None && main !is IslandView.Content.Note && main !is IslandView.Content.Popup &&
            IslandStore.notifCount > 0 && p.getBoolean(Prefs.NOTIFS, true)
        ) {
            IslandView.Bubble("bell", null, R.drawable.ic_bell, android.graphics.Color.WHITE, -1f, small = true) { island?.openPanel() }
        } else {
            null
        }
        v.setState(main, bubble, hide)
        LauncherLink.schedule(this)
        val reasons = buildList {
            if (locked) add("locked")
            if (hideFs) add("fullscreen")
        }
        Debug.island = "on · ${v.mode.name.lowercase()}" + if (reasons.isEmpty()) "" else " · hidden: ${reasons.joinToString()}"
    }

    private fun bubbleFor(c: IslandView.Content): IslandView.Bubble? = when (c) {
        is IslandView.Content.Music -> IslandView.Bubble("m", c.info.art ?: c.info.appIcon, R.drawable.ic_note, c.info.accent, -1f) { openMusicApp(c.info) }
        is IslandView.Content.Call -> IslandView.Bubble("c", null, R.drawable.ic_call, Colors.GREEN, -1f) { send(c.call.contentIntent) }
        is IslandView.Content.Timer -> IslandView.Bubble("t", null, R.drawable.ic_timer, Colors.INK, -1f) { send(c.timer.contentIntent) }
        is IslandView.Content.Transfer -> IslandView.Bubble("d", c.transfer.icon, R.drawable.ic_download, Colors.INK, max(0f, c.transfer.progress)) { send(c.transfer.contentIntent) }
        is IslandView.Content.Nav -> IslandView.Bubble("g", c.nav.arrow, R.drawable.ic_nav, Colors.GREEN, -1f) { send(c.nav.contentIntent) }
        is IslandView.Content.Torch -> IslandView.Bubble("f", null, R.drawable.ic_flashlight, Colors.INK, -1f) { torchOff() }
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
            !plugged && prev > 15 && pct <= 15 -> IslandStore.showPop("Low battery", "$pct%", Colors.INK, R.drawable.ic_battery_low, 3_500L)
            !plugged && prev > 5 && pct <= 5 -> IslandStore.showPop("Battery", "$pct%", Colors.INK, R.drawable.ic_battery_low, 4_000L)
            plugged && prev < 100 && pct >= 100 -> IslandStore.showPop("Charged", "100%", Colors.GREEN, R.drawable.ic_battery_full, 3_000L)
        }
    }

    private fun onRinger(i: Intent) {
        if (!popsOn()) return
        when (i.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)) {
            AudioManager.RINGER_MODE_SILENT -> IslandStore.showPop("Silent", "On", Colors.INK, R.drawable.ic_bell_off, 2_000L)
            AudioManager.RINGER_MODE_VIBRATE -> IslandStore.showPop("Vibrate", "On", Colors.INK, R.drawable.ic_vibrate, 2_000L)
            AudioManager.RINGER_MODE_NORMAL -> IslandStore.showPop("Ring", "On", Colors.INK, R.drawable.ic_bell, 2_000L)
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
        lastBudsName = info
        if (dndOn()) return
        IslandStore.showPop("Connected", info.take(18), Colors.INK, R.drawable.ic_headphones, 3_000L)
    }

    /** Do Not Disturb is on: background news (Wi-Fi, earbuds) waits; things you just did still show. */
    private fun dndOn(): Boolean = try {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
    } catch (_: Exception) {
        false
    }

    private fun connectivityOn() = popsOn() && Prefs.get(this).getBoolean(Prefs.CONNECTIVITY, true)

    // ----- Flashlight -----
    private val main = Handler(Looper.getMainLooper())
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (enabled) IslandStore.setTorch(cameraId) else if (IslandStore.torch == cameraId) IslandStore.setTorch(null)
        }
    }

    private fun watchTorch() {
        try {
            getSystemService(CameraManager::class.java)?.registerTorchCallback(torchCallback, main)
        } catch (_: Exception) {
        }
    }

    private val settle = Runnable { push() }

    private fun torchAge() = android.os.SystemClock.uptimeMillis() - IslandStore.torchSince

    // ----- Quick toggles in the notification center -----

    override fun quickStates(): List<Quick> = buildList {
        if (IslandStore.torch != null) add(Quick("torch", R.drawable.ic_flashlight, "Flashlight"))
        if (dndOn()) add(Quick("dnd", R.drawable.ic_moon, "Do Not Disturb"))
        when (ringerMode()) {
            AudioManager.RINGER_MODE_SILENT -> add(Quick("ring", R.drawable.ic_bell_off, "Silent"))
            AudioManager.RINGER_MODE_VIBRATE -> add(Quick("ring", R.drawable.ic_vibrate, "Vibrate"))
        }
        if (getSystemService(PowerManager::class.java)?.isPowerSaveMode == true) add(Quick("saver", R.drawable.ic_leaf, "Power saving"))
        if (airplaneOn()) add(Quick("airplane", R.drawable.ic_airplane, "Airplane"))
    }

    override fun quickAction(id: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        when (id) {
            "torch" -> torchOff()
            "dnd" -> if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            } else {
                openSettings(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            }
            "ring" -> try {
                (getSystemService(AUDIO_SERVICE) as AudioManager).ringerMode = AudioManager.RINGER_MODE_NORMAL
            } catch (_: SecurityException) {
                openSettings(android.provider.Settings.ACTION_SOUND_SETTINGS)
            }
            // Android doesn't let apps flip these two; open the right page instead.
            "saver" -> openSettings(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)
            "airplane" -> openSettings(android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS)
        }
        main.postDelayed({ push() }, 350L)
    }

    // ---------------------------------------------------------------------
    // Notification center controls (Controls.kt)
    // ---------------------------------------------------------------------

    override fun controlStates(): List<Controls.State> = Controls.chosen(this).map { d ->
        Controls.State(d, when (d.id) {
            "data" -> globalOn("mobile_data")
            "wifi" -> try {
                (applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager).isWifiEnabled
            } catch (_: Exception) {
                globalOn(android.provider.Settings.Global.WIFI_ON)
            }
            "bluetooth" -> globalOn(android.provider.Settings.Global.BLUETOOTH_ON)
            "airplane" -> airplaneOn()
            "torch" -> IslandStore.torch != null
            "dnd" -> dndOn()
            "rotate" -> try {
                android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 0) == 1
            } catch (_: Exception) {
                false
            }
            "location" -> try {
                getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled == true
            } catch (_: Exception) {
                false
            }
            "hotspot" -> null
            else -> null
        })
    }

    private fun globalOn(key: String): Boolean = try {
        android.provider.Settings.Global.getInt(contentResolver, key, 0) != 0
    } catch (_: Exception) {
        false
    }

    override fun controlTap(id: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        when (id) {
            // The panel has just closed; give it a moment so it's not in the picture.
            "screenshot" -> main.postDelayed({ performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) }, 450L)
            "torch" -> if (IslandStore.torch != null) torchOff() else torchOn()
            "dnd" -> if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(if (dndOn()) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            } else {
                openSettings(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            }
            "rotate" -> if (android.provider.Settings.System.canWrite(this)) {
                val on = android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 0) == 1
                android.provider.Settings.System.putInt(contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, if (on) 0 else 1)
            } else {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                }
            }
            else -> Controls.ALL.firstOrNull { it.id == id }?.let { tapTile(it) }
        }
        main.postDelayed({ push() }, 400L)
        main.postDelayed({ push() }, 1800L)
    }

    private fun torchOn() {
        try {
            val cm = getSystemService(CameraManager::class.java) ?: return
            val id = cm.cameraIdList.firstOrNull { cid ->
                val ch = cm.getCameraCharacteristics(cid)
                ch.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    ch.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: return
            cm.setTorchMode(id, true)
        } catch (_: Exception) {
        }
    }

    /**
     * Opens Quick Settings, finds the tile (by the name Android reads out for
     * it), taps it and closes Quick Settings. A few tries, because the panel
     * takes a moment to open; if it never shows up, the settings page opens.
     */
    /** Whether Android has given this (running) service the right to read Quick Settings. */
    private fun canReadScreen(): Boolean = try {
        (serviceInfo?.capabilities ?: 0) and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0
    } catch (_: Exception) {
        false
    }

    private fun toast(t: String) {
        main.post { android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show() }
    }

    /**
     * Opens Quick Settings, finds the tile (by the name Android reads out for
     * it), taps it and closes Quick Settings straight away: about half a
     * second in all. It checks every 120 ms rather than waiting a fixed time.
     */
    private fun tapTile(d: Controls.Def, attempt: Int = 0) {
        if (attempt == 0) {
            if (!canReadScreen()) {
                // The new permission only applies once the service restarts (Android's rule).
                toast("One-time step: turn Halo off and on in Accessibility so its buttons can switch ${d.label}")
                openSettings(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                return
            }
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            main.postDelayed({ tapTile(d, 1) }, 220L)
            return
        }
        val node = try {
            findTile(d)
        } catch (_: Exception) {
            null
        }
        if (node != null) {
            var n: android.view.accessibility.AccessibilityNodeInfo? = node
            while (n != null && !n.isClickable) n = n.parent
            (n ?: node).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            main.postDelayed({ closeShade() }, 180L)
            return
        }
        if (attempt < 16) {
            main.postDelayed({ tapTile(d, attempt + 1) }, 120L)
            return
        }
        closeShade()
        toast("Couldn't find ${d.label} in your Quick Settings. Add its tile there, or switch it here.")
        openSettings(when (d.id) {
            "data" -> android.provider.Settings.ACTION_DATA_ROAMING_SETTINGS
            "wifi" -> android.provider.Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
            "airplane" -> android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "location" -> android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
            else -> android.provider.Settings.ACTION_WIRELESS_SETTINGS
        })
    }

    private fun closeShade() {
        if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) else performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * The Quick Settings tile for a control. Tiles announce themselves like
     * "Mobile data, On" or "Bluetooth, Galaxy Buds connected" (Samsung shows
     * the network name under Wi-Fi, so the spoken name is what counts).
     */
    private fun findTile(d: Controls.Def): android.view.accessibility.AccessibilityNodeInfo? {
        val roots = try {
            windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(rootInActiveWindow) }
        } catch (_: Exception) {
            listOfNotNull(rootInActiveWindow)
        }
        val ui = roots.filter { it.packageName?.toString()?.contains("systemui") == true }.ifEmpty { roots }
        val all = ArrayList<Pair<String, android.view.accessibility.AccessibilityNodeInfo>>()
        for (r in ui) {
            val q = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
            q.add(r)
            while (q.isNotEmpty() && all.size < 2500) {
                val n = q.removeFirst()
                val label = listOfNotNull(n.contentDescription, n.text).joinToString(" ").lowercase().trim()
                if (label.isNotEmpty()) all += label to n
                for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
            }
        }
        fun ok(l: String) = !l.contains("saver") && !l.contains("settings") && !l.contains("roaming")
        // Best: the label starts with the name ("mobile data, on"); then: contains it.
        for (word in d.tile) all.firstOrNull { (l, _) -> l.startsWith(word) && ok(l) }?.let { return it.second }
        for (word in d.tile) all.firstOrNull { (l, _) -> l.contains(word) && ok(l) && l.length < 80 }?.let { return it.second }
        return null
    }

    private fun ringerMode(): Int = try {
        (getSystemService(AUDIO_SERVICE) as AudioManager).ringerMode
    } catch (_: Exception) {
        AudioManager.RINGER_MODE_NORMAL
    }

    private fun airplaneOn(): Boolean = try {
        android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) != 0
    } catch (_: Exception) {
        false
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    override fun torchOff() {
        val id = IslandStore.torch ?: return
        try {
            getSystemService(CameraManager::class.java)?.setTorchMode(id, false)
        } catch (_: Exception) {
        }
    }

    // ----- Wi-Fi -----
    private var wifiWatchStarted = 0L
    private var lastWifi = 0L
    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val now = System.currentTimeMillis()
            // The callback fires straight away for the current network; that is not news.
            if (now - wifiWatchStarted < 4_000L || now - lastWifi < 15_000L || !connectivityOn() || dndOn()) return
            lastWifi = now
            IslandStore.showPop("Wi-Fi", "Connected", Colors.INK, R.drawable.ic_wifi, 2_200L)
        }
    }

    private fun watchWifi() {
        try {
            wifiWatchStarted = System.currentTimeMillis()
            val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            getSystemService(ConnectivityManager::class.java)?.registerNetworkCallback(req, wifiCallback, main)
        } catch (_: Exception) {
        }
    }

    // ----- Earbuds battery (reported by most buds shortly after they connect) -----
    private var lastBudsName: String? = null

    @Suppress("DEPRECATION")
    private fun onBudsBattery(i: Intent) {
        if (!popsOn()) return
        val level = i.getIntExtra(EXTRA_BT_BATTERY, -1)
        if (level !in 0..100) return
        if (System.currentTimeMillis() - lastBt > 20_000L || dndOn()) return
        val name = lastBudsName ?: return
        IslandStore.showPop(name.take(16), "$level%", if (level <= 20) Colors.INK else Colors.INK, R.drawable.ic_headphones, 3_000L)
        lastBudsName = null
    }

    private fun onDnd() {
        if (!popsOn()) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val on = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        IslandStore.showPop("Do Not Disturb", if (on) "On" else "Off", Colors.INK, R.drawable.ic_moon, 2_000L)
    }

    private fun onCharging() {
        if (!Prefs.get(this).getBoolean(Prefs.CHARGING, true)) return
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        IslandStore.showPop("Charging", "$pct%", Colors.GREEN, R.drawable.ic_bolt, 3_500L, kind = "charge")
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
        val land = screenW > screenH
        val id = resources.getIdentifier(if (land) "status_bar_height_landscape" else "status_bar_height", "dimen", "android")
            .takeIf { it > 0 } ?: resources.getIdentifier("status_bar_height", "dimen", "android")
        val sb = if (id > 0) resources.getDimensionPixelSize(id) else (24 * dm.density).toInt()
        // The display's cutout first; if a phone reports nothing there, ask the
        // overlay window's insets (some skins only fill one of the two).
        val fromDisplay = try {
            getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.cutout
        } catch (_: Throwable) {
            null
        }
        val cut = fromDisplay ?: try {
            island?.rootWindowInsets?.displayCutout
        } catch (_: Throwable) {
            null
        }
        val found = CameraFinder.find(cut, screenW, screenH, sb, dm.density)
        cameraHow = found.how
        // The user's fine-tuning from Settings → Camera fit.
        val p = Prefs.get(this)
        val nx = (p.getInt(Prefs.CAM_X, 0) * dm.density).toInt()
        val ny = (p.getInt(Prefs.CAM_Y, 0) * dm.density).toInt()
        val grow = (p.getInt(Prefs.CAM_SIZE, 0) * dm.density / 2f).toInt()
        val notch = found.rect?.let { r ->
            Rect(r.left + nx - grow, r.top + ny - grow, r.right + nx + grow, r.bottom + ny + grow).takeIf { it.width() > 0 && it.height() > 0 }
        }
        return IslandView.Geometry(notch, screenW, screenH, sb)
    }

    /** How the camera was found, for the Camera fit card. */
    var cameraHow: String = "not checked yet"
        private set

    /** Re-reads the cutout and replays a visible outline on the camera circle. */
    fun redetectCamera(preview: Boolean = true) {
        val v = island ?: return
        v.geometry = geometry()
        v.rebuild()
        push()
        if (preview) v.previewCamera()
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

    override fun clearAll() {
        HaloNotificationListener.instance?.clearAll()
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
        Launch.send(this, intent)
    }

    override fun dismissNotification(key: String) {
        HaloNotificationListener.instance?.dismiss(key)
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

        /** How long a flashlight pill stays before settling back into the camera. */
        private const val SETTLE_MS = 12_000L

        private const val ACTION_BT_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_BT_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL"

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
