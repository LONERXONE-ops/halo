package com.lonerxone.halo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.WindowManager

/**
 * Runs Halo's custom status bar: its window (a strip over the system bar
 * that never takes a touch, so swiping down still opens the shade), and
 * everything it shows: battery, mobile signal and network type per SIM,
 * Wi-Fi strength, Bluetooth, silent, vibrate, Do Not Disturb, alarm,
 * airplane mode, data speed and the time.
 *
 * Off by default. Listens only while it's on.
 */
class StatusBarController(private val svc: HaloService) {
    private val main = Handler(Looper.getMainLooper())
    private var view: StatusBarView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var watching = false
    private val telCallbacks = ArrayList<Pair<TelephonyManager, TelephonyCallback>>()
    private var shown = false

    val on get() = Prefs.get(svc).getBoolean(Prefs.SB_ON, false)

    /** Adds the strip's window (before the island's, so the island stays above it). */
    fun attach(wm: WindowManager, screenW: Int, barH: Int) {
        if (view != null) return
        val v = StatusBarView(svc)
        val p = WindowManager.LayoutParams(
            screenW, barH,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            title = "Halo status bar"
        }
        v.visibility = android.view.View.GONE
        try {
            wm.addView(v, p)
        } catch (_: Exception) {
            return
        }
        view = v
        lp = p
    }

    fun detach(wm: WindowManager?) {
        val v = view ?: return
        try {
            wm?.removeView(v)
        } catch (_: Exception) {
        }
        view = null
        stopWatching()
    }

    fun resize(wm: WindowManager?, screenW: Int, barH: Int) {
        val v = view ?: return
        val p = lp ?: return
        p.width = screenW
        p.height = barH
        try {
            wm?.updateViewLayout(v, p)
        } catch (_: Exception) {
        }
    }

    /** The island's span right now (things beside it make way). */
    fun island(left: Float, right: Float, visible: Boolean) {
        view?.setIsland(left, right, visible)
    }

    /**
     * Shows or hides the strip: on, and not in fullscreen, landscape or with
     * the shade pulled down. [clear] = over Halo Launcher or its lock screen
     * (with the Clear background), where the launcher hides the system bar.
     */
    fun show(visible: Boolean, clear: Boolean) {
        val v = view ?: return
        val want = visible && on
        if (want) startWatching() else stopWatching()
        v.clear = clear && Prefs.get(svc).getInt(Prefs.SB_BG, 0) == 2
        if (want == shown) {
            if (want) v.invalidate()
            return
        }
        shown = want
        v.animate().cancel()
        if (want) {
            v.visibility = android.view.View.VISIBLE
            v.alpha = 0f
            v.translationY = -v.height * 0.4f
            v.animate().alpha(1f).translationY(0f).setDuration(260L).start()
        } else {
            v.animate().alpha(0f).setDuration(140L).withEndAction { v.visibility = android.view.View.GONE }.start()
        }
    }

    fun refresh() {
        view?.invalidate()
    }

    // ----- Notification icons -----

    fun setNotifications(items: List<PanelItem>) {
        val apps = LinkedHashMap<String, PanelItem>()
        for (i in items) if (i.packageName !in apps) apps[i.packageName] = i
        StatusData.notifApps = apps.size
        StatusData.notifIcons = apps.values.take(4).map { it.rich.appIcon ?: it.icon }
        StatusData.notifAccents = apps.values.take(4).map { it.accent }
        view?.invalidate()
    }

    // ----- Watching the phone's state -----

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                    val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    if (level >= 0) StatusData.battery = level * 100 / scale
                    StatusData.charging = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
                }
                android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED -> StatusData.bluetooth = true
                android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED -> main.postDelayed({ readBluetooth() }, 800L)
                else -> readSimple()
            }
            view?.invalidate()
        }
    }

    private val tick = object : Runnable {
        private var lastRx = -1L
        private var lastAt = 0L
        override fun run() {
            val p = Prefs.get(svc)
            if (p.getBoolean(Prefs.SB_SPEED, false)) {
                val rx = android.net.TrafficStats.getTotalRxBytes()
                val now = android.os.SystemClock.elapsedRealtime()
                if (lastRx >= 0 && now > lastAt) StatusData.speed = ((rx - lastRx) * 1000L / (now - lastAt)).coerceAtLeast(0L)
                lastRx = rx
                lastAt = now
            }
            view?.invalidate()
            val sec = p.getBoolean(Prefs.SB_SECONDS, false)
            val wait = if (sec || p.getBoolean(Prefs.SB_SPEED, false)) 1000L - System.currentTimeMillis() % 1000L else 60_000L - System.currentTimeMillis() % 60_000L
            main.postDelayed(this, wait + 5L)
        }
    }

    private val net = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            StatusData.wifi = wifi
            if (wifi) {
                val rssi = caps.signalStrength
                StatusData.wifiLevel = when {
                    rssi == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED -> 3
                    rssi >= -60 -> 3
                    rssi >= -72 -> 2
                    else -> 1
                }
            }
            main.post { view?.invalidate() }
        }

        override fun onLost(network: Network) {
            StatusData.wifi = false
            main.post { view?.invalidate() }
        }
    }

    private fun startWatching() {
        if (watching) return
        watching = true
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(android.app.AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) svc.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED) else svc.registerReceiver(receiver, f)
        } catch (_: Exception) {
        }
        try {
            svc.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(net, main)
        } catch (_: Exception) {
        }
        watchSignal()
        readSimple()
        readBluetooth()
        main.post(tick)
    }

    private fun stopWatching() {
        if (!watching) return
        watching = false
        try {
            svc.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        try {
            svc.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(net)
        } catch (_: Exception) {
        }
        for ((tm, cb) in telCallbacks) {
            try {
                tm.unregisterTelephonyCallback(cb)
            } catch (_: Exception) {
            }
        }
        telCallbacks.clear()
        main.removeCallbacks(tick)
    }

    private fun readSimple() {
        val am = svc.getSystemService(AudioManager::class.java)
        StatusData.silent = am?.ringerMode == AudioManager.RINGER_MODE_SILENT
        StatusData.vibrate = am?.ringerMode == AudioManager.RINGER_MODE_VIBRATE
        val nm = svc.getSystemService(android.app.NotificationManager::class.java)
        StatusData.dnd = nm != null && nm.currentInterruptionFilter > android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        StatusData.alarm = svc.getSystemService(android.app.AlarmManager::class.java)?.nextAlarmClock != null
        StatusData.airplane = android.provider.Settings.Global.getInt(svc.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        StatusData.powerSave = svc.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true
    }

    @SuppressLint("MissingPermission")
    private fun readBluetooth() {
        StatusData.bluetooth = try {
            val a: BluetoothAdapter? = svc.getSystemService(BluetoothManager::class.java)?.adapter
            a != null && a.isEnabled && listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).any { a.getProfileConnectionState(it) == BluetoothProfile.STATE_CONNECTED }
        } catch (_: Throwable) {
            false
        }
        view?.invalidate()
    }

    /** Signal strength and network type for each SIM (one listener per SIM). */
    @SuppressLint("MissingPermission")
    private fun watchSignal() {
        if (Build.VERSION.SDK_INT < 31) return
        val base = svc.getSystemService(TelephonyManager::class.java) ?: return
        val subs = try {
            svc.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList?.map { it.subscriptionId }
        } catch (_: Throwable) {
            null
        }?.takeIf { it.isNotEmpty() } ?: listOf(SubscriptionManager.getDefaultSubscriptionId())
        val levels = IntArray(subs.size)
        val types = Array(subs.size) { "" }
        StatusData.signal = levels.toList()
        StatusData.netType = types.toList()
        for ((i, id) in subs.withIndex()) {
            val tm = if (id != SubscriptionManager.INVALID_SUBSCRIPTION_ID) base.createForSubscriptionId(id) else base
            val cb = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener, TelephonyCallback.DisplayInfoListener {
                override fun onSignalStrengthsChanged(s: SignalStrength) {
                    levels[i] = s.level.coerceIn(0, 4)
                    StatusData.signal = levels.toList()
                    main.post { view?.invalidate() }
                }

                override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                    types[i] = typeName(info)
                    StatusData.netType = types.toList()
                    main.post { view?.invalidate() }
                }
            }
            try {
                tm.registerTelephonyCallback(svc.mainExecutor, cb)
                telCallbacks += tm to cb
            } catch (_: Throwable) {
                // Without phone permission: signal only.
                try {
                    val only = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
                        override fun onSignalStrengthsChanged(s: SignalStrength) {
                            levels[i] = s.level.coerceIn(0, 4)
                            StatusData.signal = levels.toList()
                            main.post { view?.invalidate() }
                        }
                    }
                    tm.registerTelephonyCallback(svc.mainExecutor, only)
                    telCallbacks += tm to only
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun typeName(info: TelephonyDisplayInfo): String {
        when (info.overrideNetworkType) {
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> return "5G"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> return "4G+"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> return "4G+"
        }
        return when (info.networkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G"
            TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA -> "H+"
            TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE -> "E"
            TelephonyManager.NETWORK_TYPE_GPRS -> "G"
            else -> ""
        }
    }
}
