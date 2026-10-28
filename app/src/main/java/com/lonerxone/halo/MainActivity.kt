package com.lonerxone.halo

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Settings in the shape of One UI: a tall header, a large title, rounded cards.
 * Widgets come from the phone's own DeviceDefault theme, so switches and
 * buttons are Samsung's.
 */
class MainActivity : Activity() {

    private var night = false
    private var bg = 0
    private var cardColor = 0
    private var text1 = 0
    private var text2 = 0
    private var divider = 0

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        bg = if (night) Color.rgb(1, 1, 1) else Color.rgb(242, 242, 242)
        cardColor = if (night) Color.rgb(23, 23, 23) else Color.rgb(252, 252, 252)
        text1 = if (night) Color.rgb(250, 250, 250) else Color.rgb(1, 1, 1)
        text2 = if (night) Color.rgb(158, 158, 158) else Color.rgb(115, 115, 115)
        divider = if (night) Color.rgb(40, 40, 40) else Color.rgb(230, 230, 230)

        window.decorView.setBackgroundColor(bg)
        window.insetsController?.setSystemBarsAppearance(
            if (night) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(24))
        }

        // Tall One UI header with the title near the bottom.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(16), 0, dp(16), dp(28))
        }
        header.addView(label("Halo", 40f, text1, false))
        header.addView(label(statusLine(), 14f, text2, false).apply { setPadding(0, dp(6), 0, 0) })
        col.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.30f).toInt()))

        // ----- Setup -----
        col.addView(sectionTitle("Setup"))
        val setup = card()
        setup.addView(statusRow("Accessibility service", "Draws the island over the notch", accessibilityOn()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        setup.addView(line())
        setup.addView(statusRow("Notification access", "Reads what is playing", listenerOn()) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        setup.addView(line())
        setup.addView(statusRow("Battery: unrestricted", "Stops One UI from closing Halo", batteryOk()) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        })
        setup.addView(line())
        setup.addView(statusRow("Calls, Bluetooth & alerts", "Phone state, end call, earbuds, notifications", runtimeOk()) {
            val missing = RUNTIME_PERMS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } else {
                requestPermissions(missing.toTypedArray(), 7)
            }
        })
        setup.addView(line())
        setup.addView(statusRow("Do Not Disturb access", "Shows silent and DND changes", dndOk()) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        })
        setup.addView(line())
        setup.addView(infoRow("Switch greyed out?", "App info → ⋮ → Allow restricted settings, then try again. Or run the ADB commands below.") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        col.addView(setup)

        // ----- Island -----
        col.addView(sectionTitle("Island"))
        val island = card()
        island.addView(switchRow("Music", "Album art and waveform while something plays", Prefs.MUSIC))
        island.addView(line())
        island.addView(switchRow("Charging", "Short pop when you plug in", Prefs.CHARGING))
        island.addView(line())
        island.addView(switchRow("Idle pill", "Keep a small pill around the notch", Prefs.IDLE))
        island.addView(line())
        island.addView(switchRow("Hide in fullscreen", "Videos, games and reels", Prefs.HIDE_FULLSCREEN))
        island.addView(line())
        island.addView(switchRow("Haptics", "Light tick when it expands", Prefs.HAPTICS))
        island.addView(line())
        island.addView(sizeRow())
        col.addView(island)

        val test = Button(this).apply {
            text = "Test the island"
            setOnClickListener {
                val svc = HaloService.instance
                if (svc == null) {
                    Toast.makeText(
                        this@MainActivity,
                        "Island service isn't running. Turn Halo off and on again in Accessibility.",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    IslandStore.showPop("Halo", "Ready", Colors.BLUE, R.drawable.ic_check, 2_500L)
                    Toast.makeText(this@MainActivity, "Sent · " + svc.diagnostics(), Toast.LENGTH_LONG).show()
                }
            }
        }
        col.addView(test, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
            marginStart = dp(8)
            marginEnd = dp(8)
        })

        // ----- ADB -----
        col.addView(sectionTitle("ADB setup (Termux)"))
        val adb = card()
        val cmds = adbCommands()
        adb.addView(label(cmds, 11.5f, text1, false).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(20), dp(16), dp(20), dp(8))
        })
        adb.addView(Button(this).apply {
            text = "Copy commands"
            setOnClickListener {
                val cm = getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("Halo ADB", cmds))
                Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END
            marginEnd = dp(12)
            bottomMargin = dp(8)
        })
        col.addView(adb)

        // ----- About -----
        col.addView(sectionTitle("About"))
        val about = card()
        about.addView(infoRow("Version", version()) {})
        about.addView(line())
        about.addView(infoRow("Built for", "Galaxy A06 · One UI 8 · Android 16") {})
        about.addView(line())
        about.addView(infoRow("What's new", WHATS_NEW) {})
        about.addView(line())
        about.addView(infoRow("Updates", "New versions land on GitHub Releases") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/LONERXONE-ops/halo/releases")))
            } catch (_: Exception) {
            }
        })
        about.addView(line())
        about.addView(infoRow("Privacy", "Halo never reads your screen and sends nothing off your phone. Music and alerts stay on-device.") {})
        col.addView(about)

        col.addView(label("Halo ${version()} · built for One UI", 12f, text2, false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
            addView(col)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        }
        setContentView(scroll)
    }

    // ---------------------------------------------------------------------
    // Status checks
    // ---------------------------------------------------------------------

    private fun accessibilityOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("$packageName/")
    }

    private fun runtimeOk(): Boolean =
        RUNTIME_PERMS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun dndOk(): Boolean =
        getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun listenerOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return s.contains("$packageName/")
    }

    private fun batteryOk(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    private fun statusLine(): String = when {
        accessibilityOn() && listenerOn() -> "Running on your notch"
        else -> "Finish setup below to turn the island on"
    }

    private fun version(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
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
            for P in READ_PHONE_STATE ANSWER_PHONE_CALLS BLUETOOTH_CONNECT POST_NOTIFICATIONS; do adb shell pm grant ${'$'}PKG android.permission.${'$'}P; done
            adb shell cmd notification allow_dnd ${'$'}PKG
        """.trimIndent()
    }

    // ---------------------------------------------------------------------
    // One UI building blocks
    // ---------------------------------------------------------------------

    private fun label(t: String, sp: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun sectionTitle(t: String): View = label(t, 13f, text2, false).apply {
        setPadding(dp(24), dp(22), dp(24), dp(8))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(cardColor)
            cornerRadius = dp(26).toFloat()
        }
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun line(): View = View(this).apply {
        setBackgroundColor(divider)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            marginStart = dp(24)
            marginEnd = dp(24)
        }
    }

    private fun rowBase(title: String, summary: String): Pair<LinearLayout, LinearLayout> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(15), dp(20), dp(15))
            isClickable = true
            val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = ta.getDrawable(0)
            ta.recycle()
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 17f, text1, false))
        if (summary.isNotEmpty()) {
            texts.addView(label(summary, 13f, text2, false).apply { setPadding(0, dp(2), 0, 0) })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row to texts
    }

    private fun statusRow(title: String, summary: String, ok: Boolean, onClick: () -> Unit): View {
        val (row, _) = rowBase(title, summary)
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (ok) Colors.GREEN else Color.rgb(255, 69, 58))
            }
        }
        row.addView(label(if (ok) "On" else "Off", 14f, text2, false).apply { setPadding(0, 0, dp(8), 0) })
        row.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)))
        row.setOnClickListener { onClick() }
        return row
    }

    private fun infoRow(title: String, summary: String, onClick: () -> Unit): View {
        val (row, _) = rowBase(title, summary)
        row.setOnClickListener { onClick() }
        return row
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun switchRow(title: String, summary: String, key: String): View {
        val (row, _) = rowBase(title, summary)
        val prefs = Prefs.get(this)
        val sw = Switch(this).apply {
            isChecked = prefs.getBoolean(key, true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                HaloService.instance?.reloadPrefs()
            }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        return row
    }

    private fun sizeRow(): View {
        val prefs = Prefs.get(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(15), dp(20), dp(12))
        }
        val value = prefs.getInt(Prefs.SCALE, 100)
        val title = label("Island size · $value%", 17f, text1, false)
        box.addView(title)
        val seek = SeekBar(this).apply {
            max = 50
            progress = value - 80
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    title.text = "Island size · ${p + 80}%"
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    prefs.edit().putInt(Prefs.SCALE, (sb?.progress ?: 20) + 80).apply()
                    HaloService.instance?.reloadPrefs()
                }
            })
        }
        box.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        return box
    }

    @Suppress("unused")
    private fun spacer(h: Int): View = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
    }

    companion object {
        private val RUNTIME_PERMS = listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
        )

        private const val WHATS_NEW =
            "New glass icon, About section, and one-tap permissions for calls, Bluetooth and alerts. " +
                "Fixed the island staying hidden on some screens."
    }
}
