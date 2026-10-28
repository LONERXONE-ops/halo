package com.lonerxone.halo

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Settings, styled after Smart Island: soft gradient background, glass cards
 * with a hairline border, lavender accent. Switches are the phone's own.
 */
class MainActivity : Activity() {

    private var night = false
    private var bgTop = 0
    private var bgBottom = 0
    private var cardFill = 0
    private var cardStroke = 0
    private var text1 = 0
    private var text2 = 0
    private var divider = 0
    private var accent = 0

    private var liveView: TextView? = null
    private val ui = Handler(Looper.getMainLooper())
    private val liveTick = object : Runnable {
        override fun run() {
            liveView?.text = liveStatus()
            ui.postDelayed(this, 1000L)
        }
    }

    // Update state survives re-renders.
    private var latest: Updater.Release? = null
    private var updateStatus = "Not checked yet"
    private var downloading = -1f
    private var checkedThisSession = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onResume() {
        super.onResume()
        render()
        if (listenerOn() && !Debug.listenerConnected) {
            try {
                NotificationListenerService.requestRebind(ComponentName(this, HaloNotificationListener::class.java))
            } catch (_: Exception) {
            }
        }
        ui.post(liveTick)
        if (!checkedThisSession) {
            checkedThisSession = true
            checkUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(liveTick)
    }

    // =====================================================================
    // Layout
    // =====================================================================

    private fun render() {
        night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        bgTop = if (night) Color.rgb(46, 48, 62) else Color.rgb(232, 235, 246)
        bgBottom = if (night) Color.rgb(17, 17, 22) else Color.rgb(250, 250, 253)
        cardFill = if (night) Color.argb(24, 255, 255, 255) else Color.WHITE
        cardStroke = if (night) Color.argb(38, 255, 255, 255) else Color.argb(18, 0, 0, 0)
        text1 = if (night) Color.rgb(246, 246, 250) else Color.rgb(18, 18, 24)
        text2 = if (night) Color.rgb(160, 162, 176) else Color.rgb(110, 112, 124)
        divider = if (night) Color.argb(26, 255, 255, 255) else Color.argb(14, 0, 0, 0)
        accent = if (night) Colors.LAVENDER else Color.rgb(74, 91, 212)

        window.decorView.setBackgroundColor(bgBottom)
        window.insetsController?.setSystemBarsAppearance(
            if (night) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), dp(28))
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(10), 0, dp(10), dp(22))
        }
        header.addView(label("Halo", 38f, text1, true))
        val running = HaloService.instance != null
        header.addView(chip(if (running) "● Active" else "○ Not running", if (running) Colors.GREEN else text2).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8)
        })
        col.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.24f).toInt()))

        // Live status
        col.addView(sectionTitle("Live status"))
        val live = card()
        val liveText = label(liveStatus(), 12.5f, text1, false).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.25f)
            setPadding(dp(20), dp(14), dp(18), dp(14))
        }
        live.addView(liveText)
        col.addView(live)
        liveView = liveText

        // Updates
        col.addView(sectionTitle("Updates"))
        col.addView(updatesCard())

        // Setup
        col.addView(sectionTitle("Setup"))
        val setup = card()
        setup.addView(statusRow("Accessibility service", "Draws the island over the camera", accessibilityOn()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        setup.addView(line())
        setup.addView(statusRow("Notification access", "Music, messages, calls, timers", listenerOn()) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        setup.addView(line())
        setup.addView(statusRow("Battery: unrestricted", "Stops One UI from closing Halo", batteryOk()) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                openAppInfo()
            }
        })
        setup.addView(line())
        setup.addView(statusRow("Calls, Bluetooth & alerts", "Phone state, end call, earbuds", runtimeOk()) {
            val missing = RUNTIME_PERMS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) openAppInfo() else requestPermissions(missing.toTypedArray(), 7)
        })
        setup.addView(line())
        setup.addView(statusRow("Do Not Disturb access", "Shows silent and DND changes", dndOk()) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        })
        setup.addView(line())
        setup.addView(statusRow("Install updates", "Lets Halo update itself", packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        })
        col.addView(setup)

        // What shows
        col.addView(sectionTitle("On the island"))
        val shows = card()
        shows.addView(switchRow("Music", "Cover art, bars, full player", Prefs.MUSIC))
        shows.addView(line())
        shows.addView(switchRow("Messages", "WhatsApp, SMS and app alerts", Prefs.NOTIFS))
        shows.addView(line())
        shows.addView(switchRow("Calls", "Caller, timer, end-call button", Prefs.CALLS))
        shows.addView(line())
        shows.addView(switchRow("Timers & stopwatch", "Countdown from the Clock app", Prefs.TIMERS))
        shows.addView(line())
        shows.addView(switchRow("Downloads & installs", "Progress ring for files and apps", Prefs.TRANSFERS))
        shows.addView(line())
        shows.addView(switchRow("Navigation", "Next turn from Google Maps", Prefs.NAV))
        shows.addView(line())
        shows.addView(switchRow("Charging", "Pop when you plug in", Prefs.CHARGING))
        shows.addView(line())
        shows.addView(switchRow("System pops", "Low battery, full, silent, earbuds, DND", Prefs.SYSTEM_POPS))
        shows.addView(line())
        shows.addView(navRow("Choose apps", "Pick which apps can pop up") {
            startActivity(Intent(this, AppsActivity::class.java))
        })
        col.addView(shows)

        // Look
        col.addView(sectionTitle("Look & feel"))
        val look = card()
        look.addView(switchRow("Camera circle", "Hidden on the camera; tap it for notifications", Prefs.IDLE))
        look.addView(line())
        look.addView(switchRow("Album art background", "Blurred cover behind the player", Prefs.ART_BACKDROP))
        look.addView(line())
        look.addView(sliderRow("Island size", Prefs.SCALE, 100, 80, 130, "%"))
        look.addView(line())
        look.addView(sliderRow("Glass strength", Prefs.GLASS, 20, 0, 100, "%"))
        look.addView(line())
        look.addView(sliderRow("Move left / right", Prefs.OFFSET_X, 0, -60, 60, "dp"))
        look.addView(line())
        look.addView(sliderRow("Move up / down", Prefs.OFFSET_Y, 0, -10, 60, "dp"))
        look.addView(line())
        look.addView(switchRowDefault("Hide message text", "Show \"New message\" instead", Prefs.HIDE_TEXT, false))
        look.addView(line())
        look.addView(switchRow("Hide in fullscreen", "Videos, games and reels", Prefs.HIDE_FULLSCREEN))
        look.addView(line())
        look.addView(switchRow("Haptics", "Light tick on taps", Prefs.HAPTICS))
        look.addView(line())
        look.addView(switchRowDefault("Low-power mode", "No blur, still bars, slower updates", Prefs.LOW_POWER, false))
        col.addView(look)

        col.addView(actionButton("Test the island") {
            val svc = HaloService.instance
            if (svc == null) {
                Toast.makeText(this, "Island service isn't running. Turn Halo off and on in Accessibility.", Toast.LENGTH_LONG).show()
            } else {
                IslandStore.showPop("Halo", "Ready", Colors.LAVENDER, R.drawable.ic_check, 2_500L)
                Toast.makeText(this, "Sent · " + svc.diagnostics(), Toast.LENGTH_LONG).show()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(18) })

        // ADB
        col.addView(sectionTitle("ADB setup (Termux)"))
        val adb = card()
        val cmds = adbCommands()
        adb.addView(label(cmds, 11f, text1, false).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(20), dp(16), dp(20), dp(8))
        })
        adb.addView(actionButton("Copy commands") {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Halo ADB", cmds))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
            gravity = Gravity.END
            marginEnd = dp(14)
            bottomMargin = dp(12)
        })
        col.addView(adb)

        // About
        col.addView(sectionTitle("About"))
        val about = card()
        about.addView(infoRow("Built for", "Galaxy A06 · One UI 8"))
        about.addView(line())
        about.addView(infoRow("Privacy", "Halo never reads your screen. Everything stays on your phone, except the update check with GitHub."))
        about.addView(line())
        about.addView(infoRow("Design", "Inspired by Smart Island (agupta07505, GPL-3.0). Halo's code is its own."))
        col.addView(about)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(bgTop, bgBottom))
            addView(col)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        }
        setContentView(scroll)
    }

    private fun updatesCard(): View {
        val c = card()
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(16), dp(12))
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(label("Current version", 12f, text2, false))
        left.addView(label("v" + version(), 22f, text1, true).apply { setPadding(0, dp(2), 0, 0) })
        top.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(chip("Installed", text2))
        c.addView(top)
        c.addView(line())

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(14), dp(12))
        }
        val st = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        st.addView(label("Update status", 12f, text2, false))
        st.addView(label(updateStatus, 15f, text1, true).apply { setPadding(0, dp(2), dp(8), 0) })
        row.addView(st, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val rel = latest
        val newer = rel != null && rel.build > currentBuild()
        val buttonText = when {
            downloading >= 0f -> "Downloading ${(downloading * 100).toInt()}%"
            newer -> "Download & install"
            else -> "Check for updates"
        }
        row.addView(actionButton(buttonText) {
            when {
                downloading >= 0f -> {}
                newer && rel != null -> startUpdate(rel)
                else -> checkUpdates()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)))
        c.addView(row)

        if (rel != null && rel.notes.isNotEmpty()) {
            c.addView(line())
            c.addView(label("What's in ${rel.tag}", 12f, text2, false).apply { setPadding(dp(20), dp(12), dp(20), 0) })
            c.addView(label(rel.notes.take(600), 13.5f, text1, false).apply {
                setLineSpacing(0f, 1.2f)
                setPadding(dp(20), dp(4), dp(20), dp(16))
            })
        }
        return c
    }

    // =====================================================================
    // Updates
    // =====================================================================

    private fun currentBuild(): Int = Updater.buildOf(version())

    private fun checkUpdates() {
        updateStatus = "Checking…"
        render()
        Updater.check { result ->
            when (result) {
                is Updater.Check.Ok -> {
                    latest = result.release
                    val newer = result.release.build > currentBuild()
                    updateStatus = if (newer) "${result.release.tag} available" else "You're up to date"
                    if (newer && HaloService.instance != null) {
                        IslandStore.showPop("Update", result.release.tag, Colors.LAVENDER, R.drawable.ic_update, 3_000L)
                    }
                }
                is Updater.Check.NotPublic -> updateStatus = "Make the GitHub repo public to update here"
                is Updater.Check.Error -> updateStatus = "Couldn't check: ${result.message.take(40)}"
            }
            render()
        }
    }

    private fun startUpdate(rel: Updater.Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow Halo to install updates, then tap again", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        downloading = 0f
        updateStatus = "Downloading ${rel.tag}"
        render()
        Updater.install(this, rel, progress = { f ->
            downloading = if (f >= 1f) -1f else f
            updateStatus = if (f >= 1f) "Confirm the install" else "Downloading ${rel.tag}"
            render()
        }, failed = { msg ->
            downloading = -1f
            updateStatus = "Failed: ${msg.take(40)}"
            render()
        })
    }

    // =====================================================================
    // Status checks
    // =====================================================================

    private fun liveStatus(): String {
        val svc = HaloService.instance
        return buildString {
            append("Island     ").append(if (svc == null) "service not running" else Debug.island).append('\n')
            append("Listener   ").append(if (Debug.listenerConnected) "connected" else "not connected").append('\n')
            append("Sessions   ").append(Debug.sessions).append('\n')
            append("Playing    ").append(Debug.nowPlaying).append('\n')
            append("Last alert ").append(Debug.lastNotification)
        }
    }

    private fun accessibilityOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("$packageName/")
    }

    private fun listenerOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return s.contains("$packageName/")
    }

    private fun batteryOk(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    private fun runtimeOk(): Boolean = RUNTIME_PERMS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun dndOk(): Boolean = getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun openAppInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun version(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
    } catch (_: Exception) {
        "0"
    }

    private fun adbCommands(): String {
        val pkg = packageName
        return """
            PKG=$pkg
            adb shell appops set ${'$'}PKG ACCESS_RESTRICTED_SETTINGS allow
            adb shell cmd notification allow_listener ${'$'}PKG/${'$'}PKG.HaloNotificationListener
            A=${'$'}(adb shell settings get secure enabled_accessibility_services)
            [ "${'$'}A" = "null" ] && A=""
            adb shell settings put secure enabled_accessibility_services "${'$'}{A:+${'$'}A:}${'$'}PKG/${'$'}PKG.HaloService"
            adb shell settings put secure accessibility_enabled 1
            adb shell cmd deviceidle whitelist +${'$'}PKG
            adb shell cmd appops set ${'$'}PKG RUN_ANY_IN_BACKGROUND allow
            adb shell appops set ${'$'}PKG REQUEST_INSTALL_PACKAGES allow
            for P in READ_PHONE_STATE ANSWER_PHONE_CALLS BLUETOOTH_CONNECT POST_NOTIFICATIONS; do adb shell pm grant ${'$'}PKG android.permission.${'$'}P; done
            adb shell cmd notification allow_dnd ${'$'}PKG
        """.trimIndent()
    }

    // =====================================================================
    // Building blocks (Smart Island style)
    // =====================================================================

    private fun label(t: CharSequence, sp: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun chip(t: String, color: Int): TextView = label(t, 12.5f, color, true).apply {
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = GradientDrawable().apply {
            setColor(Color.argb(28, Color.red(color), Color.green(color), Color.blue(color)))
            setStroke(dp(1), Color.argb(90, Color.red(color), Color.green(color), Color.blue(color)))
            cornerRadius = dp(10).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun actionButton(t: String, onClick: () -> Unit): TextView =
        label(t, 14f, if (night) Color.rgb(20, 22, 40) else Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), 0, dp(18), 0)
            background = GradientDrawable().apply {
                setColor(accent)
                cornerRadius = dp(18).toFloat()
            }
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun sectionTitle(t: String): View = label(t, 13f, text2, true).apply {
        setPadding(dp(18), dp(22), dp(18), dp(8))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(cardFill)
            setStroke(dp(1), cardStroke)
            cornerRadius = dp(26).toFloat()
        }
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun line(): View = View(this).apply {
        setBackgroundColor(divider)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            marginStart = dp(20)
            marginEnd = dp(20)
        }
    }

    private fun rowBase(title: String, summary: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(18), dp(14))
            isClickable = true
            val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = ta.getDrawable(0)
            ta.recycle()
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 16f, text1, true))
        if (summary.isNotEmpty()) texts.addView(label(summary, 12.5f, text2, false).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun statusRow(title: String, summary: String, ok: Boolean, onClick: () -> Unit): View {
        val row = rowBase(title, summary)
        row.addView(chip(if (ok) "On" else "Off", if (ok) Colors.GREEN else Colors.RED))
        row.setOnClickListener { onClick() }
        return row
    }

    private fun navRow(title: String, summary: String, onClick: () -> Unit): View {
        val row = rowBase(title, summary)
        row.addView(label("›", 22f, text2, false))
        row.setOnClickListener { onClick() }
        return row
    }

    private fun infoRow(title: String, summary: String): View = rowBase(title, summary).apply { isClickable = false }

    private fun switchRow(title: String, summary: String, key: String): View = switchRowDefault(title, summary, key, true)

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun switchRowDefault(title: String, summary: String, key: String, def: Boolean): View {
        val row = rowBase(title, summary)
        val prefs = Prefs.get(this)
        val sw = Switch(this).apply {
            isChecked = prefs.getBoolean(key, def)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                HaloService.instance?.reloadPrefs()
            }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        return row
    }

    private fun sliderRow(title: String, key: String, def: Int, min: Int, max: Int, unit: String): View {
        val prefs = Prefs.get(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(18), dp(10))
        }
        val value = prefs.getInt(key, def).coerceIn(min, max)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(label(title, 16f, text1, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val v = label("$value$unit", 14f, accent, true)
        head.addView(v)
        box.addView(head)
        val seek = SeekBar(this).apply {
            this.max = max - min
            progress = value - min
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    v.text = "${p + min}$unit"
                    if (fromUser) {
                        prefs.edit().putInt(key, p + min).apply()
                        HaloService.instance?.reloadPrefs()
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}

                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        return box
    }

    companion object {
        private val RUNTIME_PERMS = listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}
