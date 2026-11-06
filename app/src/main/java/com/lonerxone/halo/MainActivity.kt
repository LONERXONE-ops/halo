package com.lonerxone.halo

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
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
 * Settings: a calm home screen with an island preview, and a few grouped
 * pages behind it (One UI style). Always black. Switches are the phone's own.
 */
class MainActivity : Activity() {

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
    /** The update card's status line and button, updated in place while downloading. */
    private var statusView: TextView? = null
    private var progressView: TextView? = null
    private var downloading = -1f
    private var checkedThisSession = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // From the "new version" notification: straight to Updates.
        if (intent?.getStringExtra("page") == "updates") {
            intent?.removeExtra("page")
            page = Page.UPDATES
        }
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
    // Pages
    // =====================================================================

    private enum class Page(val title: String) {
        HOME("Halo"),
        ISLAND("On the island"),
        LOOK("Look & feel"),
        CAMERA("Camera fit"),
        PICTURES("Pictures"),
        SETUP("Permissions"),
        UPDATES("Updates"),
        ABOUT("About"),
        REC_QUALITY("Recording quality"),
        REC_SOUND("Recording sound"),
        MOMENTS("System moments"),
        PLAYER("Music player"),
    }

    /** Where Back goes from a page. */
    private fun parentOf(p: Page): Page = when (p) {
        Page.REC_QUALITY, Page.REC_SOUND, Page.MOMENTS, Page.PLAYER -> Page.ISLAND
        else -> Page.HOME
    }

    private var page = Page.HOME
    private var shownPage: Page? = null
    private var scroll: ScrollView? = null
    /** Where you were on each page, so coming back from a section lands you where you left. */
    private val scrollMemory = HashMap<Page, Int>()

    private fun go(p: Page) {
        shownPage?.let { sp -> scroll?.let { scrollMemory[sp] = it.scrollY } }
        page = p
        render()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (page != Page.HOME) {
            go(parentOf(page))
        } else {
            super.onBackPressed()
        }
    }

    private fun render() {
        // Always black: the look Halo started with, refined.
        bgTop = Color.BLACK
        bgBottom = Color.BLACK
        cardFill = Color.rgb(17, 17, 19)
        cardStroke = Color.argb(16, 255, 255, 255)
        text1 = Color.rgb(245, 245, 247)
        text2 = Color.rgb(142, 142, 150)
        divider = Color.argb(14, 255, 255, 255)
        // Halo's palette: white and greys on black.
        accent = Colors.INK

        window.decorView.setBackgroundColor(bgBottom)
        window.insetsController?.setSystemBarsAppearance(
            0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(32))
        }
        liveView = null
        when (page) {
            Page.HOME -> homePage(col)
            Page.ISLAND -> islandPage(col)
            Page.LOOK -> lookPage(col)
            Page.CAMERA -> cameraPage(col)
            Page.PICTURES -> picturesPage(col)
            Page.SETUP -> setupPage(col)
            Page.UPDATES -> updatesPage(col)
            Page.ABOUT -> aboutPage(col)
            Page.REC_QUALITY -> recQualityPage(col)
            Page.REC_SOUND -> recSoundPage(col)
            Page.MOMENTS -> momentsPage(col)
            Page.PLAYER -> playerPage(col)
        }

        // Same page re-rendered (a status change): keep the scroll position.
        val samePage = shownPage == page
        // Back on the main menu: return to where you were before opening a section.
        val keepY = when {
            samePage -> scroll?.scrollY ?: 0
            page == Page.HOME || page == Page.ISLAND -> scrollMemory[page] ?: 0
            else -> 0
        }
        val sv = ScrollView(this).apply {
            // Fades out at the edges instead of cutting things off.
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength((32 * resources.displayMetrics.density).toInt())
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setBackgroundColor(bgBottom)
            addView(col)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        }
        setContentView(sv)
        scroll = sv
        // Put the page back where it was before its first frame is drawn (scrolling
        // after a post showed one frame at the top: a jump).
        if (keepY > 0) sv.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                sv.viewTreeObserver.removeOnPreDrawListener(this)
                sv.scrollTo(0, keepY)
                return true
            }
        })
        if (!samePage) {
            // A soft slide between pages.
            val dir = if (page == Page.HOME) -1 else 1
            col.alpha = 0f
            col.translationX = dp(28) * dir.toFloat()
            col.animate().alpha(1f).translationX(0f).setDuration(260)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        }
        shownPage = page
    }

    /** Big One UI style title; sub-pages get a round back button above it. */
    private fun pageHeader(col: LinearLayout, title: String, subtitle: String?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(if (page == Page.HOME) 64 else 14), dp(6), dp(20))
        }
        if (page != Page.HOME) {
            val back = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_back)
                imageTintList = ColorStateList.valueOf(text1)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(cardFill)
                }
                contentDescription = "Back"
                isClickable = true
                setOnClickListener { go(parentOf(page)) }
            }
            // Back on the left; on pages with settings, "Reset" on the right puts just this page back to default.
            val top = android.widget.FrameLayout(this)
            top.addView(back, android.widget.FrameLayout.LayoutParams(dp(42), dp(42), Gravity.START or Gravity.CENTER_VERTICAL))
            val keys = resetKeys(page)
            if (keys.isNotEmpty()) {
                val reset = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), 0, dp(14), 0)
                    background = GradientDrawable().apply {
                        setColor(cardFill)
                        cornerRadius = dp(21).toFloat()
                        setStroke(dp(1), cardStroke)
                    }
                    addView(android.widget.ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_rotate)
                        imageTintList = ColorStateList.valueOf(text1)
                    }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(7) })
                    addView(label("Reset", 13.5f, text1, true))
                    isClickable = true
                    contentDescription = "Reset this page to default"
                    setOnClickListener { v ->
                        v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(70).withEndAction {
                            v.animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
                        }.start()
                        confirmPageReset(page, keys)
                    }
                }
                top.addView(reset, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42), Gravity.END or Gravity.CENTER_VERTICAL))
            }
            box.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { bottomMargin = dp(34) })
        }
        if (page == Page.HOME) {
            // The brand: the halo logo, glowing on black.
            box.gravity = Gravity.CENTER_HORIZONTAL
            box.setPadding(dp(6), dp(30), dp(6), dp(22))
            box.addView(android.widget.ImageView(this).apply { setImageResource(R.drawable.logo_halo) }, LinearLayout.LayoutParams(dp(112), dp(112)))
            box.addView(label(title, 34f, text1, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
            if (subtitle != null) box.addView(label(subtitle, 14f, text2, false).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
            col.addView(box)
            return
        }
        box.addView(label(title, 30f, text1, true))
        if (subtitle != null) box.addView(label(subtitle, 14f, text2, false).apply { setPadding(0, dp(4), 0, 0) })
        col.addView(box)
    }

    private fun gap(col: LinearLayout, h: Int = 12) {
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(h)))
    }

    // ---------------------------------------------------------------------

    private fun homePage(col: LinearLayout) {
        pageHeader(col, "Halo", "Dynamic island for Android")
        col.addView(heroCard())
        gap(col)

        // Only nag when something actually needs doing.
        val missing = setupMissing()
        if (missing > 0) {
            col.addView(card().apply {
                addView(iconRow(R.drawable.ic_shield, Colors.INK, "Finish setup", "$missing ${if (missing == 1) "permission needs" else "permissions need"} your OK") { go(Page.SETUP) })
            })
            gap(col)
        }
        val rel = latest
        if (rel != null && rel.build > currentBuild()) {
            col.addView(card().apply {
                addView(iconRow(R.drawable.ic_update, Colors.INK, "${rel.tag} is out", "Tap to download and install") { go(Page.UPDATES) })
            })
            gap(col)
        }

        col.addView(group(
            iconRow(R.drawable.ic_bell, Colors.GRAPHITE, "On the island", "Music, messages, calls and more") { go(Page.ISLAND) },
            iconRow(R.drawable.ic_apps, Colors.GRAPHITE, "Choose apps", "Which apps can pop up") {
                startActivity(Intent(this, AppsActivity::class.java))
            },
        ))
        gap(col)
        col.addView(group(
            iconRow(R.drawable.ic_palette, Colors.GRAPHITE, "Look & feel", "Size, glass, position, haptics") { go(Page.LOOK) },
            iconRow(R.drawable.ic_image, Colors.GRAPHITE, "Pictures", "Your own picture in the island") { go(Page.PICTURES) },
            iconRow(R.drawable.ic_camera, Colors.GRAPHITE, "Camera fit", "Sit the circle right on your lens") { go(Page.CAMERA) },
        ))
        gap(col)
        col.addView(group(
            iconRow(R.drawable.ic_shield, Colors.GRAPHITE, "Permissions", if (missing == 0) "All set" else "$missing to go") { go(Page.SETUP) },
            iconRow(R.drawable.ic_update, Colors.GRAPHITE, "Updates", "v${version()} · $updateStatus") { go(Page.UPDATES) },
            iconRow(R.drawable.ic_apps, Colors.GRAPHITE, "Halo Launcher", if (LauncherLink.launcherInstalled(this)) "Installed · linked with the island" else "A home screen made for Halo") {
                val launch = packageManager.getLaunchIntentForPackage(LauncherLink.LAUNCHER)
                try {
                    if (launch != null) startActivity(launch) else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/LONERXONE-ops/halo-launcher/releases/latest")))
                } catch (_: Exception) {
                }
            },
            iconRow(R.drawable.ic_info, Colors.GRAPHITE, "About", "Creator, privacy, diagnostics") { go(Page.ABOUT) },
        ))
        credit(col)
    }

    /** The settings each page owns (what its Reset puts back). Pages without settings have none. */
    private fun resetKeys(p: Page): List<String> = when (p) {
        Page.ISLAND -> listOf(
            Prefs.MUSIC, Prefs.BEAT, Prefs.CALLS, Prefs.LOCK_MODE, Prefs.LOCK_SHAKE, Prefs.ALARMS, Prefs.TIMERS, Prefs.TRANSFERS,
            Prefs.NAV, Prefs.TORCH, Prefs.RECORDING, Prefs.NOTIFS, Prefs.HIDE_TEXT, Prefs.DND_PEEK, Prefs.CHARGING,
            Prefs.OTP, Prefs.LIVE,
        )
        Page.MOMENTS -> listOf(
            Prefs.SYSTEM_POPS, Prefs.CONNECTIVITY, Prefs.EV_RINGER, Prefs.EV_DND, Prefs.EV_BT, Prefs.EV_WIFI, Prefs.EV_HOTSPOT,
            Prefs.EV_AIRPLANE, Prefs.EV_SAVER, Prefs.EV_BATTERY, Prefs.EV_BUDS,
        )
        Page.LOOK -> listOf(
            Prefs.IDLE, Prefs.SCALE, Prefs.CAP_H, Prefs.CAP_W, Prefs.CARD_W, Prefs.GLASS, Prefs.ART_BACKDROP,
            Prefs.OFFSET_X, Prefs.OFFSET_Y, Prefs.HIDE_FULLSCREEN, Prefs.HAPTICS, Prefs.LOW_POWER, Prefs.IN_SHOTS,
            Prefs.MOTION_SPEED, Prefs.MOTION_BOUNCE,
        )
        Page.CAMERA -> listOf(Prefs.CAM_X, Prefs.CAM_Y, Prefs.CAM_SIZE)
        Page.PICTURES -> listOf(Prefs.PIC_DIM)
        Page.REC_QUALITY -> listOf(Prefs.REC_QUALITY)
        Page.PLAYER -> listOf(Prefs.MP_TINT, Prefs.MP_AMBIENT, Prefs.MP_WAVY, Prefs.MP_EXTRAS, Prefs.MP_DEVICE)
        Page.REC_SOUND -> listOf(Prefs.REC_AUDIO)
        else -> emptyList()
    }

    private fun confirmPageReset(p: Page, keys: List<String>) {
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Reset ${p.title}?")
            .setMessage(if (p == Page.PICTURES) "The dimming goes back to default. Your pictures stay." else "Everything on this page goes back to how it was when you installed Halo. Other pages stay as they are.")
            .setPositiveButton("Reset") { _, _ ->
                val e = Prefs.get(this).edit()
                keys.forEach { e.remove(it) }
                e.apply()
                if (p == Page.CAMERA) HaloService.instance?.redetectCamera() else HaloService.instance?.reloadPrefs()
                Toast.makeText(this, "${p.title} is back to default", Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Puts every setting back as it was on a fresh install: what shows on the
     * island, look and feel, buttons, recording options, camera fit. Your
     * pictures and the apps you blocked from popping up are kept.
     */
    private fun confirmReset() {
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Back to default settings?")
            .setMessage("Every Halo setting goes back to how it was when you installed it. Your island and notification pictures stay.")
            .setPositiveButton("Reset") { _, _ ->
                val p = Prefs.get(this)
                val keep = listOf(Prefs.ISLAND_PIC, Prefs.PANEL_PIC, Prefs.BLOCKED)
                val saved = keep.associateWith { p.all[it] }
                val e = p.edit().clear()
                for ((k, v) in saved) when (v) {
                    is Long -> e.putLong(k, v)
                    is Int -> e.putInt(k, v)
                    is Boolean -> e.putBoolean(k, v)
                    is String -> e.putString(k, v)
                    is Set<*> -> e.putStringSet(k, v.filterIsInstance<String>().toSet())
                }
                e.apply()
                HaloService.instance?.reloadPrefs()
                Toast.makeText(this, "Halo is back to its default settings", Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** A little preview of the island itself, with the service state and a test button. */
    private fun heroCard(): View {
        val running = HaloService.instance != null
        val c = card().apply { setPadding(dp(20), dp(26), dp(20), dp(20)) }

        // The pill, drawn at real size.
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(14), 0)
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke(dp(1), Color.argb(26, 255, 255, 255))
                cornerRadius = dp(17).toFloat()
            }
        }
        val dotColor = if (running) Colors.INK else Color.rgb(90, 90, 96)
        val dot = android.widget.ImageView(this).apply {
            setImageResource(if (running) R.drawable.ic_check else R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(Color.BLACK)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(dotColor)
            }
        }
        pill.addView(dot, LinearLayout.LayoutParams(dp(22), dp(22)))
        pill.addView(View(this), LinearLayout.LayoutParams(dp(28), 1))
        pill.addView(label(if (running) "Active" else "Off", 13.5f, if (running) text1 else text2, true))
        val pillWrap = LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)))
        }
        c.addView(pillWrap)

        c.addView(label(if (running) "The island is running" else "The island is off", 18f, text1, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        })
        c.addView(label(
            if (running) "Play a song or get a message to see it work." else "Turn on Halo in Accessibility to start it.",
            13f, text2, false,
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(18))
        })
        c.addView(actionButton(if (running) "Test the island" else "Turn it on") {
            val svc = HaloService.instance
            if (svc == null) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                IslandStore.showPop("Halo", "Ready", Colors.INK, R.drawable.ic_check, 2_500L)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        return c
    }

    private fun islandPage(col: LinearLayout) {
        pageHeader(col, "On the island", "Pick what Halo shows")
        col.addView(sectionTitle("Live activities"))
        col.addView(group(
            switchRow("Music", "Cover art, bars, full player", Prefs.MUSIC),
            navRow("Music player", "Album colours, ambient waves, wave scrubber, the app's buttons") { go(Page.PLAYER) },
            switchRowDefault("Bars follow the beat", "The music bars move with the real sound", Prefs.BEAT, false),
            switchRow("Calls", "Caller photo, Accept / Decline, Mute, Speaker and End", Prefs.CALLS),
            switchRow("Lock screen", "A padlock on the lock screen that springs open when you unlock", Prefs.LOCK_MODE),
            switchRow("Shake on a wrong try", "The island shakes no when the PIN, pattern or fingerprint is wrong, like an iPhone", Prefs.LOCK_SHAKE),
            switchRow("Alarms", "An alarm going off (or a timer ending) with big Snooze and Stop buttons", Prefs.ALARMS),
            switchRow("One-time codes", "A code in a message (OTP) shows big on the island, with Copy", Prefs.OTP),
            switchRow("Rides and deliveries", "Bolt, Uber, Chowdeck, Glovo and others as live progress: how far, and the app's buttons", Prefs.LIVE),
            switchRow("Timers & stopwatch", "Countdown from the Clock app", Prefs.TIMERS),
            switchRow("Downloads & installs", "Progress ring for files and apps", Prefs.TRANSFERS),
            switchRow("Navigation", "Next turn from Google Maps", Prefs.NAV),
            switchRow("Flashlight", "Stays on the island while it's on", Prefs.TORCH),
        ))
        col.addView(sectionTitle("Screen recording"))
        col.addView(group(
            navRow("Record your screen", "Starts after a 3-2-1 on the island. Saved to Movies/Halo") { RecordActivity.launch(this) },
            recQualityRow(),
            recSoundRow(),
            switchRow("Other recorders on the island", "Samsung's (or any) screen recorder shows a red dot and timer too", Prefs.RECORDING),
        ))
        col.addView(sectionTitle("Alerts"))
        col.addView(group(
            switchRow("Messages", "WhatsApp, SMS and app alerts", Prefs.NOTIFS),
            switchRowDefault("Hide message text", "Show \"New message\" instead", Prefs.HIDE_TEXT, false),
            switchRowDefault("See messages in Do Not Disturb", "The island still shows new messages, silently. Sounds, vibration and pop-ups keep following Do Not Disturb", Prefs.DND_PEEK, false),
            switchRow("Charging", "Pop when you plug in", Prefs.CHARGING),
            navRow("System moments", "Ring and silent, Bluetooth, Wi-Fi, hotspot, battery, earbuds, Do Not Disturb and more") { go(Page.MOMENTS) },
        ))
        gap(col, 16)
        col.addView(group(
            iconRow(R.drawable.ic_apps, Colors.GRAPHITE, "Choose apps", "Which apps can pop up") {
                startActivity(Intent(this, AppsActivity::class.java))
            },
        ))

        col.addView(sectionTitle("Notification center buttons"))
        col.addView(controlsCard())

        col.addView(sectionTitle("Pop-ups"))
        col.addView(takeoverCard())
    }

    /**
     * Up to four buttons beside "Notifications": tap to pick (the number is
     * the order they appear in), tap again to remove.
     */
    private fun controlsCard(): View {
        val c = card().apply { setPadding(dp(16), dp(16), dp(16), dp(14)) }
        val chosen = Controls.chosenIds(this).toMutableList()
        c.addView(label("Pick up to ${Controls.MAX}. When all ${Controls.MAX} are picked, tapping another swaps it in for the last one. Mobile data, Wi-Fi, Bluetooth, airplane mode, location and hotspot are switched through Quick Settings, which Android only lets the system change directly, so you'll see it open and close for a moment.", 12.5f, text2, false).apply {
            setLineSpacing(0f, 1.15f)
            setPadding(dp(2), 0, dp(2), dp(12))
        })
        val grid = android.widget.GridLayout(this).apply { columnCount = 2 }
        for (d in Controls.ALL) {
            val pos = chosen.indexOf(d.id)
            val on = pos >= 0
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = GradientDrawable().apply {
                    setColor(if (on) Colors.INK else Colors.GRAPHITE)
                    cornerRadius = dp(16).toFloat()
                }
                setOnClickListener {
                    if (on) {
                        chosen.remove(d.id)
                    } else if (chosen.size < Controls.MAX) {
                        chosen.add(d.id)
                    } else {
                        // Full: swap. The new one takes the place of the last one you picked.
                        val out = chosen.removeAt(chosen.size - 1)
                        chosen.add(d.id)
                        val outName = Controls.ALL.firstOrNull { it.id == out }?.label ?: out
                        android.widget.Toast.makeText(this@MainActivity, "Swapped $outName for ${d.label}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    Controls.set(this@MainActivity, chosen)
                    render()
                }
            }
            cell.addView(android.widget.ImageView(this).apply {
                setImageResource(d.icon)
                imageTintList = ColorStateList.valueOf(if (on) Color.BLACK else Color.WHITE)
            }, LinearLayout.LayoutParams(dp(18), dp(18)))
            cell.addView(label(d.label, 13f, if (on) Color.BLACK else Color.WHITE, true).apply {
                setPadding(dp(8), 0, 0, 0)
                maxLines = 1
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (on) cell.addView(label((pos + 1).toString(), 11f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.BLACK)
                }
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
            grid.addView(cell, android.widget.GridLayout.LayoutParams(
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f),
            ).apply {
                width = 0
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        c.addView(grid)
        if (!android.provider.Settings.System.canWrite(this) && "rotate" in chosen) {
            c.addView(actionButton("Allow auto-rotate switching") {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:$packageName")))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        return c
    }

    /**
     * "Halo handles pop-ups": Android's own banners off, the island shows new
     * notifications instead. Needs a one-time ADB grant; until then the card
     * explains how, with the command ready to copy.
     */
    private fun takeoverCard(): View {
        if (PopupControl.canControl(this)) {
            val prefs = Prefs.get(this)
            val row = rowBase("Halo handles pop-ups", "Android's own banners stay off; new notifications show on the island. They still go to your shade and still make sound.")
            val sw = Switch(this).apply {
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, Color.rgb(170, 170, 176)))
                trackTintList = ColorStateList(states, intArrayOf(Color.rgb(120, 120, 128), Color.rgb(58, 58, 62)))
                isChecked = prefs.getBoolean(Prefs.TAKEOVER, false) && !PopupControl.systemPopupsOn(this@MainActivity)
                setOnCheckedChangeListener { _, on ->
                    prefs.edit().putBoolean(Prefs.TAKEOVER, on).apply()
                    // Only take over while the island is actually running.
                    PopupControl.apply(this@MainActivity, on && HaloService.instance != null)
                    if (on && HaloService.instance == null) {
                        Toast.makeText(this@MainActivity, "Turn on the island first; Halo takes over once it's running.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            row.addView(sw)
            row.setOnClickListener { sw.toggle() }
            return group(row, infoRow("Good to know", "If the island stops (or you turn Halo off in Accessibility), Android's banners come straight back. Turn this off before uninstalling Halo."))
        }
        // Not unlocked yet: one-time setup.
        val c = card().apply { setPadding(dp(20), dp(16), dp(20), dp(18)) }
        c.addView(label("Halo handles pop-ups", 16f, text1, true))
        c.addView(label(
            "Turn off Android's own pop-up banners so new notifications only show on the island. Android only lets an app change this after a one-time unlock over ADB (the same wireless debugging you used before). Run this once in Termux:",
            12.5f, text2, false,
        ).apply {
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(4), 0, dp(12))
        })
        c.addView(label(PopupControl.GRANT, 12f, text1, false).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke(dp(1), Color.argb(30, 255, 255, 255))
                cornerRadius = dp(14).toFloat()
            }
        })
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        buttons.addView(actionButton("Copy command") {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("Halo", PopupControl.GRANT))
            Toast.makeText(this, "Copied. Paste it in Termux after adb connect.", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        buttons.addView(label("I've done it", 14f, text1, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Colors.GRAPHITE)
                cornerRadius = dp(24).toFloat()
            }
            isClickable = true
            setOnClickListener {
                if (PopupControl.canControl(this@MainActivity)) {
                    render()
                } else {
                    Toast.makeText(this@MainActivity, "Not unlocked yet. Check the command ran without errors.", Toast.LENGTH_LONG).show()
                }
            }
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(10) })
        c.addView(buttons)
        return c
    }

    private fun lookPage(col: LinearLayout) {
        pageHeader(col, "Look & feel", "Make it yours")
        col.addView(sectionTitle("Island"))
        col.addView(group(
            switchRow("Camera circle", "Rests on the camera; tap it for notifications", Prefs.IDLE),
            sliderRow("Size", Prefs.SCALE, 100, 80, 130, "%"),
        ))
        col.addView(sectionTitle("Motion"))
        col.addView(group(
            segRow("Speed", Prefs.MOTION_SPEED, listOf("Relaxed", "Normal", "Quick"), 1),
            segRow("Bounce", Prefs.MOTION_BOUNCE, listOf("Subtle", "Normal", "Bouncy"), 1),
        ))
        col.addView(footnote("How fast the island opens and closes, and how much it springs past and settles. Changes show on the next move."))
        col.addView(sectionTitle("Fine sizing"))
        col.addView(group(
            sliderRow("Pill height", Prefs.CAP_H, 100, 80, 125, "%"),
            sliderRow("Pill width", Prefs.CAP_W, 100, 85, 125, "%"),
            sliderRow("Open card width", Prefs.CARD_W, 100, 80, 100, "%"),
        ))
        col.addView(footnote("Optional. 100% is the standard size, so nothing changes unless you move these. Use − and + for one step at a time."))
        col.addView(sectionTitle("Glass"))
        col.addView(group(
            sliderRow("Glass", Prefs.GLASS, 0, 0, 100, "%"),
            switchRow("Album art background", "Blurred cover behind the player", Prefs.ART_BACKDROP),
        ))
        col.addView(sectionTitle("Position"))
        col.addView(group(
            sliderRow("Left / right", Prefs.OFFSET_X, 0, -60, 60, "dp"),
            sliderRow("Up / down", Prefs.OFFSET_Y, 0, -10, 60, "dp"),
        ))
        col.addView(sectionTitle("Behaviour"))
        col.addView(group(
            switchRow("Hide in fullscreen", "Videos, games and reels", Prefs.HIDE_FULLSCREEN),
            switchRow("Haptics", "Light tick on taps", Prefs.HAPTICS),
            switchRowDefault("Low-power mode", "No blur, still bars, slower updates", Prefs.LOW_POWER, false),
            switchRow("Show in screenshots", "Off: the island steps out of the picture when you press the screenshot buttons", Prefs.IN_SHOTS),
        ))
        if (!Prefs.get(this).getBoolean(Prefs.IN_SHOTS, true)) col.addView(footnote("Works with the button screenshot (power and volume down): while volume down is held the island fades out, and it comes back a moment later. Palm swipe and the Quick panel screenshot can't be caught. After this update, turn Halo off and on in Accessibility once so Android lets it see the volume key."))
    }

    // ---------------------------------------------------------------------
    // Pictures
    // ---------------------------------------------------------------------

    private fun picturesPage(col: LinearLayout) {
        pageHeader(col, "Pictures", "Make the island yours")
        col.addView(sectionTitle("Island"))
        col.addView(pictureCard(Prefs.ISLAND_PIC, "Fills the island. Pick from wallpapers made for its wide shape, or use your own photo.", landscape = true))
        col.addView(sectionTitle("Notification center"))
        col.addView(pictureCard(Prefs.PANEL_PIC, "Sits behind your notifications. Pick a tall wallpaper, or use your own photo.", landscape = false))
        col.addView(sectionTitle("Readability"))
        col.addView(group(sliderRow("Dim pictures", Prefs.PIC_DIM, 45, 0, 90, "%")))
        col.addView(footnote("The camera circle always stays pure black, so it keeps hiding the lens."))
    }

    private fun pictureCard(key: String, hint: String, landscape: Boolean): View {
        val c = card().apply { setPadding(dp(18), dp(18), dp(18), dp(16)) }
        val file = Prefs.picFile(this, key)
        val has = Prefs.get(this).getLong(key, 0L) != 0L && file.exists()
        // Preview in the real shape: a wide pill, or a tall card.
        val pw = if (landscape) dp(260) else dp(150)
        val ph = if (landscape) dp(76) else dp(230)
        val preview = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, if (landscape) v.height / 2f else dp(26).toFloat())
                }
            }
            clipToOutline = true
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke(dp(1), Color.argb(30, 255, 255, 255))
                cornerRadius = if (landscape) ph / 2f else dp(26).toFloat()
            }
            if (has) {
                try {
                    setImageBitmap(android.graphics.BitmapFactory.decodeFile(file.absolutePath))
                    setColorFilter(Color.argb((255 * Prefs.get(this@MainActivity).getInt(Prefs.PIC_DIM, 45) / 100f).toInt(), 0, 0, 0), android.graphics.PorterDuff.Mode.SRC_ATOP)
                } catch (_: Throwable) {
                }
            } else {
                setImageResource(R.drawable.ic_image)
                imageTintList = ColorStateList.valueOf(Color.rgb(80, 80, 86))
                scaleType = android.widget.ImageView.ScaleType.CENTER
            }
        }
        val wrap = LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(preview, LinearLayout.LayoutParams(pw, ph))
        }
        c.addView(wrap)
        c.addView(label(hint, 13f, text2, false).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(14), dp(6), dp(14))
        })
        // Wallpapers (made for this shape) first, your own photo beside it, then Remove.
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(actionButton("Wallpapers") { WallsActivity.open(this, key) }, LinearLayout.LayoutParams(0, dp(46), 1f))
        fun ghost(t: String, click: () -> Unit) = label(t, 14f, text1, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Colors.GRAPHITE)
                cornerRadius = dp(24).toFloat()
            }
            isClickable = true
            setOnClickListener { click() }
        }
        buttons.addView(ghost("Your photo") { pickPicture(key) }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(10) })
        if (has) {
            c.addView(buttons)
            c.addView(ghost("Remove") {
                Prefs.get(this@MainActivity).edit().remove(key).apply()
                file.delete()
                HaloService.instance?.reloadPrefs()
                render()
            }.apply { setTextColor(Colors.RED) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(10) })
            return c
        }
        c.addView(buttons)
        return c
    }

    private var pickingKey: String? = null

    private fun pickPicture(key: String) {
        pickingKey = key
        val intent = if (android.os.Build.VERSION.SDK_INT >= 33) {
            Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        } else {
            Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PICTURE)
        } catch (_: Exception) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_PICTURE)
            } catch (_: Exception) {
                Toast.makeText(this, "No photo picker found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICTURE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val key = pickingKey ?: return
        // Decode off the main thread, shrink to at most 1600 px, save as JPEG.
        Thread {
            val ok = try {
                val src = android.graphics.ImageDecoder.createSource(contentResolver, uri)
                val bmp = android.graphics.ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                    dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > 1600) {
                        val f = 1600f / longest
                        dec.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
                    }
                }
                Prefs.picFile(this, key).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                Prefs.get(this).edit().putLong(key, System.currentTimeMillis()).apply()
                true
            } catch (_: Throwable) {
                false
            }
            runOnUiThread {
                if (ok) {
                    HaloService.instance?.reloadPrefs()
                    if (key == Prefs.ISLAND_PIC) {
                        IslandStore.showPop("Picture", "Set", Colors.INK, R.drawable.ic_image, 2_500L)
                    }
                } else {
                    Toast.makeText(this, "Couldn't open that picture", Toast.LENGTH_SHORT).show()
                }
                render()
            }
        }.start()
    }

    private fun cameraPage(col: LinearLayout) {
        pageHeader(col, "Camera fit", "Halo finds your camera by itself")
        val svc = HaloService.instance
        col.addView(group(
            infoRow("Detected", svc?.cameraHow?.replaceFirstChar { it.uppercase() } ?: "Turn on the island to detect the camera"),
            iconRow(R.drawable.ic_camera, Colors.GRAPHITE, "Find my camera again", "Re-detects and outlines the circle in white") {
                val s = HaloService.instance
                if (s == null) {
                    Toast.makeText(this, "The island isn't running", Toast.LENGTH_SHORT).show()
                } else {
                    s.redetectCamera()
                    render()
                }
            },
        ))
        col.addView(sectionTitle("Fine-tune"))
        col.addView(group(
            sliderRow("Nudge left / right", Prefs.CAM_X, 0, -40, 40, "dp", camera = true),
            sliderRow("Nudge up / down", Prefs.CAM_Y, 0, -20, 20, "dp", camera = true),
            sliderRow("Circle size", Prefs.CAM_SIZE, 0, -10, 20, "dp", camera = true),
        ))
        col.addView(footnote("While you drag, a white outline shows where the circle sits. Leave everything at 0 if it already covers the lens."))
    }

    private var guideOpen = false

    private fun setupPage(col: LinearLayout) {
        pageHeader(col, "Permissions", "What Halo needs to work")
        if (!accessibilityOn() || !listenerOn() || guideOpen) col.addView(restrictedGuide())
        gap(col, 12)
        col.addView(group(
            statusRow("Accessibility service", "Draws the island over the camera", accessibilityOn()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            statusRow("Notification access", "Music, messages, calls, timers", listenerOn()) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            statusRow("Battery: unrestricted", "Stops the system closing Halo", batteryOk()) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    openAppInfo()
                }
            },
            statusRow("Calls, Bluetooth & alerts", "Phone state, end call, earbuds", runtimeOk()) {
                val missing = RUNTIME_PERMS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) openAppInfo() else requestPermissions(missing.toTypedArray(), 7)
            },
            statusRow("Do Not Disturb access", "Shows silent and DND changes", dndOk()) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            },
            statusRow("Install updates", "Lets Halo update itself", packageManager.canRequestPackageInstalls()) {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            },
        ))
        col.addView(footnote("Tap any row to open the right system screen. Everything stays on your phone."))
    }

    /**
     * Android 13+ greys out Accessibility and Notification access for apps
     * installed from outside the Play Store ("Restricted setting"). A tap-to-
     * open guide for getting past it, with a button to the exact screen.
     */
    private fun restrictedGuide(): View {
        val c = card().apply { setPadding(dp(18), dp(14), dp(18), dp(14)) }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_shield)
            imageTintList = ColorStateList.valueOf(Color.BLACK)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(Colors.INK)
                cornerRadius = dp(10).toFloat()
            }
        }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("Says \"Restricted setting\"?", 15.5f, Color.WHITE, true))
        titles.addView(label("Android blocks this for apps not from the Play Store. Tap for the 1-minute fix", 12.5f, text2, false).apply { setPadding(0, dp(2), 0, 0) })
        head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val arrow = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron)
            imageTintList = ColorStateList.valueOf(Color.argb(150, 255, 255, 255))
            rotation = if (guideOpen) 270f else 90f
        }
        head.addView(arrow, LinearLayout.LayoutParams(dp(20), dp(20)))
        c.addView(head)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (guideOpen) View.VISIBLE else View.GONE
            setPadding(0, dp(12), 0, 0)
        }
        val steps = listOf(
            "Try once" to "Go to Accessibility (or Notification access), tap Halo and try to switch it on. Android shows \"Restricted setting\". Tap OK. (This step makes the next option appear.)",
            "Open Halo's App info" to "Use the button below, or Settings → Apps → Halo.",
            "Tap ⋮ (top right)" to "Choose \"Allow restricted settings\" and confirm with your PIN, pattern or fingerprint.",
            "Switch Halo on" to "Go back to Accessibility and Notification access: Halo's switch now works. Do both.",
        )
        for ((i, st) in steps.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(label((i + 1).toString(), 12f, Color.BLACK, true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Colors.INK)
                }
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(label(st.first, 14.5f, Color.WHITE, true))
            t.addView(label(st.second, 12.5f, text2, false).apply {
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(2), 0, 0)
            })
            row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            body.addView(row)
        }
        body.addView(actionButton("Open Halo's App info") { openAppInfo() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        body.addView(label("On Samsung it's the same: App info → ⋮ → Allow restricted settings. The ⋮ option only shows after step 1.", 12f, text2, false).apply { setPadding(0, dp(10), 0, 0) })
        c.addView(body)
        head.setOnClickListener {
            guideOpen = !guideOpen
            arrow.animate().rotation(if (guideOpen) 270f else 90f).setDuration(200).start()
            if (guideOpen) {
                body.visibility = View.VISIBLE
                body.alpha = 0f
                body.translationY = -dp(8).toFloat()
                body.animate().alpha(1f).translationY(0f).setDuration(220).start()
            } else {
                body.visibility = View.GONE
            }
        }
        return c
    }

    /**
     * Updates, redesigned: the logo in a ring that tells you where things
     * stand (spinning while it checks, filling while it downloads, green when
     * you're up to date, breathing when an update is ready), one big button,
     * the download's size, speed and time left, what's new laid out in its
     * sections, and Halo Launcher's version beside it.
     */
    private fun updatesPage(col: LinearLayout) {
        pageHeader(col, "Updates", "Straight from GitHub")
        // First visit: look straight away instead of waiting for a tap.
        if (latest == null && updateStatus == "Not checked yet") window.decorView.post { checkUpdates() }
        val rel = latest
        val newer = rel != null && rel.build > currentBuild()

        // ----- The hero: logo in its ring, version, status -----
        val hero = card().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(26), dp(20), dp(20))
        }
        val ringBox = android.widget.FrameLayout(this)
        val ring = UpdateRing(this).apply {
            state = when {
                downloading >= 0f -> UpdateRing.State.DOWNLOADING
                updateStatus == "Checking…" -> UpdateRing.State.CHECKING
                newer -> UpdateRing.State.READY
                updateStatus == "You're up to date" -> UpdateRing.State.UP_TO_DATE
                else -> UpdateRing.State.IDLE
            }
            progress = downloading.coerceAtLeast(0f)
        }
        ringView = ring
        ringBox.addView(ring, android.widget.FrameLayout.LayoutParams(dp(132), dp(132)))
        ringBox.addView(android.widget.ImageView(this).apply { setImageResource(R.drawable.logo_halo) }, android.widget.FrameLayout.LayoutParams(dp(92), dp(92), Gravity.CENTER))
        hero.addView(ringBox, LinearLayout.LayoutParams(dp(132), dp(132)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        hero.addView(label(if (newer && rel != null) "${rel.tag} is ready" else "Halo v${version()}", 24f, text1, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        })
        val sub = when {
            downloading >= 0f -> pctText(downloading, rel)
            newer -> "You have v${version()}" + if (rel != null && rel.size > 0) "  ·  ${"%.1f".format(rel.size / 1_048_576.0)} MB download" else ""
            updateStatus == "You're up to date" -> "You're on the latest version"
            else -> updateStatus
        }
        hero.addView(label(sub, 13.5f, text2, false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }.also { pctView = it; statusView = it })
        val btnText = when {
            downloading >= 0f -> null
            newer && rel != null -> "Download and install"
            updateStatus == "Checking…" -> "Checking…"
            else -> "Check for updates"
        }
        if (btnText != null) {
            hero.addView(actionButton(btnText) {
                when {
                    newer && rel != null -> startUpdate(rel)
                    updateStatus != "Checking…" -> checkUpdates()
                }
            }.also { progressView = it }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(18) })
        } else {
            progressView = null
            hero.addView(label("Android will ask you to confirm when it's downloaded", 12f, text2, false).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, 0)
            })
        }
        col.addView(hero)
        // The ring settles in with a little pop.
        ringBox.scaleX = 0.8f
        ringBox.scaleY = 0.8f
        ringBox.alpha = 0f
        ringBox.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(520).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()

        // ----- What's new -----
        val notes = rel?.notes.orEmpty()
        val (headline, sections) = ReleaseNotes.parse(notes)
        if (sections.isNotEmpty() || headline.isNotEmpty()) {
            col.addView(sectionTitle(if (newer && rel != null) "What's new in ${rel.tag}" else "In this version"))
            val box = card().apply { setPadding(dp(20), dp(18), dp(20), dp(16)) }
            if (headline.isNotEmpty()) box.addView(label(headline, 16f, text1, true).apply { setPadding(0, 0, 0, dp(6)) })
            var i = 0
            for (sec in sections) {
                if (sec.title.isNotEmpty()) box.addView(label(sec.title.uppercase(), 11.5f, text2, true).apply {
                    letterSpacing = 0.08f
                    setPadding(0, dp(14), 0, dp(4))
                })
                for (pt in sec.points) {
                    val r = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(5), 0, dp(5))
                    }
                    r.addView(View(this).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(if (newer) Colors.GREEN else text2)
                        }
                    }, LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                        topMargin = dp(7)
                        marginEnd = dp(12)
                    })
                    r.addView(label(pt, 13.5f, text1, false).apply { setLineSpacing(0f, 1.2f) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    box.addView(r)
                    // Points rise in one after another.
                    r.alpha = 0f
                    r.translationY = dp(10).toFloat()
                    r.animate().alpha(1f).translationY(0f).setStartDelay(120L + (i++).coerceAtMost(14) * 35L).setDuration(360).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
                }
            }
            col.addView(box)
        }

        // ----- Halo Launcher -----
        col.addView(sectionTitle("Halo Launcher"))
        col.addView(launcherUpdateCard())
        col.addView(footnote("Halo also checks by itself every few hours when you turn the screen on, and sends a notification when Halo or Halo Launcher has a new version."))
    }

    private var ringView: UpdateRing? = null
    private var launcherLatest: Updater.Release? = null
    private var launcherChecking = false

    /** Halo Launcher's installed version and the newest one, with a button to get it. */
    private fun launcherUpdateCard(): View {
        val c = card()
        val installed = try {
            packageManager.getPackageInfo(LauncherLink.LAUNCHER, 0).versionName
        } catch (_: Exception) {
            null
        }
        val lat = launcherLatest
        if (lat == null && !launcherChecking) {
            launcherChecking = true
            Updater.check(repo = "LONERXONE-ops/halo-launcher") { r ->
                launcherChecking = false
                if (r is Updater.Check.Ok) {
                    launcherLatest = r.release
                    if (page == Page.UPDATES) render()
                }
            }
        }
        val newer = installed != null && lat != null && lat.build > Updater.buildOf(installed)
        val summary = when {
            installed == null -> "Not installed · " + (lat?.let { "${it.tag} is the newest" } ?: "a home screen made for Halo")
            newer && lat != null -> "v$installed installed · ${lat.tag} is out"
            lat != null -> "v$installed · up to date"
            else -> "v$installed"
        }
        c.addView(iconRow(R.drawable.ic_apps, if (newer) Colors.GREEN else Colors.GRAPHITE, if (newer) "Update Halo Launcher" else "Halo Launcher", summary) {
            try {
                if (installed != null) {
                    startActivity(Intent().setClassName(LauncherLink.LAUNCHER, LauncherLink.LAUNCHER + ".SettingsShortcut").putExtra("page", "updates").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/LONERXONE-ops/halo-launcher/releases/latest")))
                }
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/LONERXONE-ops/halo-launcher/releases/latest")))
                } catch (_: Exception) {
                }
            }
        })
        return c
    }

    private fun aboutPage(col: LinearLayout) {
        pageHeader(col, "About", null)
        col.addView(group(
            infoRow("Creator", "LONERXONE"),
            infoRow("Works on", "Android 11 and newer. Tuned for Samsung One UI, where it feels most at home."),
            infoRow("Privacy", "Halo never reads your apps. While the phone is locked it reads only the lock screen's message line (to shake when the PIN or fingerprint is wrong). Everything stays on your phone, except the update check with GitHub."),
            infoRow("Thanks", "Ideas from Smart Island, Island and Islandify, open-source islands worth a look. Halo's code is its own."),
        ))

        col.addView(sectionTitle("Diagnostics"))
        val live = card()
        val liveText = label(liveStatus(), 12f, text2, false).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.3f)
            setPadding(dp(20), dp(16), dp(18), dp(16))
        }
        live.addView(liveText)
        liveView = liveText
        col.addView(live)
        val svc = HaloService.instance
        if (svc != null) col.addView(footnote(svc.diagnostics()))
        col.addView(sectionTitle("Start over"))
        col.addView(group(iconRow(R.drawable.ic_rotate, Colors.RED, "Reset everything", "Every page back to default at once. Your pictures and chosen apps stay. (Each page also has its own Reset at the top.)") { confirmReset() }))
        credit(col)
    }

    private fun credit(col: LinearLayout) {
        val credit = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(36), 0, dp(6))
        }
        credit.addView(label("Made by LONERXONE", 14f, text1, true).apply { gravity = Gravity.CENTER })
        credit.addView(label("Halo v${version()} · open source", 12f, text2, false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, dp(12))
        })
        credit.addView(chip("github.com/LONERXONE-ops/halo", accent).apply {
            isClickable = true
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/LONERXONE-ops/halo")))
                } catch (_: Exception) {
                }
            }
        })
        col.addView(credit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun setupMissing(): Int = listOf(
        accessibilityOn(), listenerOn(), batteryOk(), runtimeOk(), dndOk(), packageManager.canRequestPackageInstalls(),
    ).count { !it }

    private var pctView: TextView? = null

    private var dlStart = 0L

    /** "42%  ·  7.6 of 18.0 MB  ·  1.2 MB/s  ·  9s left" */
    private fun pctText(f: Float, rel: Updater.Release?): String {
        val pct = (f * 100).toInt().coerceIn(0, 100)
        val size = rel?.size ?: 0L
        if (size <= 0) return "$pct%"
        val done = size * f
        val secs = (android.os.SystemClock.elapsedRealtime() - dlStart) / 1000.0
        val speed = if (secs > 0.5) done / secs else 0.0
        val left = if (speed > 0) ((size - done) / speed).toInt() else -1
        return buildString {
            append("$pct%  ·  ${"%.1f".format(done / 1_048_576.0)} of ${"%.1f".format(size / 1_048_576.0)} MB")
            if (speed > 0) append("  ·  ${"%.1f".format(speed / 1_048_576.0)} MB/s")
            if (left >= 0) append("  ·  ${if (left >= 60) "${left / 60}m ${left % 60}s" else "${left}s"} left")
        }
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
                        IslandStore.showPop("Update", result.release.tag, Colors.INK, R.drawable.ic_update, 3_000L)
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
        dlStart = android.os.SystemClock.elapsedRealtime()
        updateStatus = "Downloading ${rel.tag}"
        render()
        Updater.install(this, rel, progress = { f ->
            val pct = (downloading * 100).toInt()
            downloading = if (f >= 1f) -1f else f
            updateStatus = if (f >= 1f) "Confirm the install" else "Downloading ${rel.tag}"
            // Download progress only changes the ring and one line; rebuilding
            // the whole page every percent is what made it flicker and jump.
            val ring = ringView
            if (f < 1f && ring != null && ring.isAttachedToWindow) {
                ring.progress = f
                ring.invalidate()
                if ((f * 100).toInt() != pct) pctView?.text = pctText(f, rel)
            } else {
                render()
            }
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
            val recent = synchronized(Debug.log) { Debug.log.toList() }
            if (recent.isNotEmpty()) {
                append("\n\nRecent alerts (why each did or didn't show)\n")
                for (l in recent.take(12)) append(l).append('\n')
            }
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
        if (requestCode == REQ_BEAT && !Beat.allowed(this)) {
            Prefs.get(this).edit().putBoolean(Prefs.BEAT, false).apply()
        }
        HaloService.instance?.reloadPrefs()
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
        label(t, 14f, Color.BLACK, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), 0, dp(18), 0)
            background = GradientDrawable().apply {
                setColor(accent)
                cornerRadius = dp(24).toFloat()
            }
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun sectionTitle(t: String): View = label(t, 13f, text2, true).apply {
        setPadding(dp(20), dp(22), dp(20), dp(8))
    }

    private fun footnote(t: String): View = label(t, 12.5f, text2, false).apply {
        setLineSpacing(0f, 1.2f)
        setPadding(dp(20), dp(10), dp(20), 0)
    }

    /** One rounded card holding [rows], with hairlines between them. */
    private fun group(vararg rows: View): LinearLayout {
        val c = card()
        rows.forEachIndexed { i, r ->
            if (i > 0) c.addView(line())
            c.addView(r)
        }
        return c
    }

    /** Row with a coloured icon tile, like One UI's settings list. */
    private fun iconRow(icon: Int, tint: Int, title: String, summary: String, onClick: () -> Unit): View {
        val row = rowBase(title, summary)
        val tile = android.widget.ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(if (tint == Colors.INK) Color.BLACK else Color.WHITE)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = GradientDrawable().apply {
                setColor(tint)
                cornerRadius = dp(11).toFloat()
            }
        }
        row.addView(tile, 0, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(16) })
        row.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron)
            imageTintList = ColorStateList.valueOf(Color.argb(110, 255, 255, 255))
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        row.setOnClickListener { onClick() }
        return row
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(cardFill)
            setStroke(dp(1), cardStroke)
            cornerRadius = dp(24).toFloat()
        }
        setPadding(0, dp(2), 0, dp(2))
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
        row.addView(chip(if (ok) "On" else "Off", if (ok) Colors.INK else Colors.GREY))
        row.setOnClickListener { onClick() }
        return row
    }

    private fun navRow(title: String, summary: String, onClick: () -> Unit): View {
        val row = rowBase(title, summary)
        row.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron)
            imageTintList = ColorStateList.valueOf(Color.argb(110, 255, 255, 255))
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        row.setOnClickListener { onClick() }
        return row
    }

    private fun recQualityRow(): View {
        val q = Quality.of(Prefs.get(this).getString(Prefs.REC_QUALITY, Quality.HD.id))
        return navRow("Quality", "${q.label}, about ${q.mbPerMin} MB a minute") { go(Page.REC_QUALITY) }
    }

    private fun recSoundRow(): View {
        val cur = RecSound.of(Prefs.get(this).getString(Prefs.REC_AUDIO, "device"))
        return navRow("Sound", cur.title) { go(Page.REC_SOUND) }
    }

    private enum class RecSound(val id: String, val title: String, val about: String, val icon: Int) {
        DEVICE("device", "Phone sound", "What your phone plays: games, videos and music. Some apps, like Netflix, don't allow their sound to be recorded.", R.drawable.ic_speaker),
        MIC("mic", "Microphone", "Your voice and whatever is around you, for tutorials and commentary.", R.drawable.ic_mic),
        NONE("none", "No sound", "Picture only. The smallest files.", R.drawable.ic_mic_off);

        companion object {
            fun of(id: String?) = values().firstOrNull { it.id == id } ?: DEVICE
        }
    }

    /**
     * A big choice card: icon tile, title, a line about it, and small facts
     * under it. The picked one has a white ring and a filled check.
     */
    private fun optionCard(icon: Int, title: String, about: String, facts: List<String>, picked: Boolean, onPick: () -> Unit): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply {
                setColor(if (picked) Color.rgb(28, 28, 31) else cardFill)
                setStroke(dp(if (picked) 2 else 1), if (picked) text1 else cardStroke)
                cornerRadius = dp(24).toFloat()
            }
            isClickable = true
            setOnClickListener {
                it.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).withEndAction {
                    it.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(android.view.animation.OvershootInterpolator(2.5f)).start()
                }.start()
                onPick()
            }
        }
        c.addView(android.widget.ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(if (picked) Color.BLACK else text1)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(if (picked) text1 else Color.rgb(38, 38, 42))
                cornerRadius = dp(14).toFloat()
            }
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(16) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 17f, text1, true))
        texts.addView(label(about, 13f, text2, false).apply {
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(4), 0, 0)
        })
        if (facts.isNotEmpty()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            for (f in facts) row.addView(label(f, 11.5f, text1, true).apply {
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = GradientDrawable().apply {
                    setColor(Color.argb(22, 255, 255, 255))
                    cornerRadius = dp(10).toFloat()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
            texts.addView(row)
        }
        c.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(android.widget.ImageView(this).apply {
            setImageResource(if (picked) R.drawable.ic_check else 0)
            imageTintList = ColorStateList.valueOf(Color.BLACK)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (picked) setColor(text1) else setStroke(dp(2), Color.argb(70, 255, 255, 255))
            }
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(10) })
        c.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        return c
    }

    /**
     * Every short system moment, each with its own switch and a button that
     * shows it on the island right now, so you can see exactly how it looks.
     */
    private fun momentsPage(col: LinearLayout) {
        pageHeader(col, "System moments", "Quick moments that flick out of the island and tuck back in")
        col.addView(group(switchRow("System moments", "All of them at once", Prefs.SYSTEM_POPS)))
        if (!Prefs.get(this).getBoolean(Prefs.SYSTEM_POPS, true)) {
            col.addView(footnote("Turn this on to choose them one by one."))
            return
        }
        fun moment(icon: Int, tint: Int, title: String, summary: String, key: String, show: () -> Unit): View {
            val row = switchRow(title, summary, key) as LinearLayout
            val play = android.widget.ImageView(this).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(tint)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(30, Color.red(tint), Color.green(tint), Color.blue(tint)))
                }
                contentDescription = "Show $title on the island"
                isClickable = true
                setOnClickListener { b ->
                    b.animate().scaleX(0.8f).scaleY(0.8f).setDuration(70).withEndAction {
                        b.animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
                    }.start()
                    if (HaloService.instance == null) Toast.makeText(this@MainActivity, "Turn the island on first", Toast.LENGTH_SHORT).show() else show()
                }
            }
            row.addView(play, 0, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
            return row
        }
        col.addView(footnote("Tap an icon to see that moment on the island now."))
        col.addView(sectionTitle("Sound"))
        col.addView(group(
            moment(R.drawable.ic_bell_off, Colors.RED, "Ring, vibrate and silent", "The bell and the mode's word", Prefs.EV_RINGER) { Moments.ringer(android.media.AudioManager.RINGER_MODE_SILENT) },
            moment(R.drawable.ic_moon, 0xFF7D7AFF.toInt(), "Do Not Disturb", "A moon and a switch", Prefs.EV_DND) { Moments.toggle("Do Not Disturb", true, 0xFF7D7AFF.toInt(), R.drawable.ic_moon) },
        ))
        col.addView(sectionTitle("Connections"))
        col.addView(group(
            moment(R.drawable.ic_bluetooth, Colors.BLUE, "Bluetooth", "Earbuds, speakers and cars connecting and leaving; Bluetooth on and off", Prefs.EV_BT) { Moments.device("Galaxy Buds", true) },
            moment(R.drawable.ic_headphones, Colors.GREEN, "Earbuds battery", "Their level as a ring, when they share it", Prefs.EV_BUDS) { Moments.buds("Galaxy Buds", 84) },
            moment(R.drawable.ic_wifi, Colors.BLUE, "Wi-Fi", "Connected, and when it really drops", Prefs.EV_WIFI) { Moments.wifi(true) },
            moment(R.drawable.ic_hotspot, Colors.GREEN, "Hotspot", "On and off", Prefs.EV_HOTSPOT) { Moments.toggle("Hotspot", true, Colors.GREEN, R.drawable.ic_hotspot) },
            moment(R.drawable.ic_airplane, Colors.ORANGE, "Airplane mode", "On and off", Prefs.EV_AIRPLANE) { Moments.toggle("Airplane mode", true, Colors.ORANGE, R.drawable.ic_airplane) },
        ))
        col.addView(sectionTitle("Battery"))
        col.addView(group(
            moment(R.drawable.ic_battery_low, Colors.ORANGE, "Low battery", "At 20%, then 10% and 5% in red", Prefs.EV_BATTERY) { Moments.battery(20, critical = false) },
            moment(R.drawable.ic_leaf, Colors.YELLOW, "Power saving", "On and off", Prefs.EV_SAVER) { Moments.toggle("Power saving", true, Colors.YELLOW, R.drawable.ic_leaf) },
            moment(R.drawable.ic_bolt, Colors.GREEN, "Charging", "When you plug in (tap it for the charging card)", Prefs.CHARGING) { Moments.charging(64) },
        ))
        col.addView(footnote("Bluetooth only counts real listening gear (earbuds, headphones, speakers, car kits), so watches and trackers never pop up. Background news like Wi-Fi and earbuds waits while Do Not Disturb is on; things you switch yourself still show."))
    }

    /** How the open music player looks (ideas from DroidBubble and Android's own player). */
    private fun playerPage(col: LinearLayout) {
        pageHeader(col, "Music player", "How the island's player looks when you open it")
        col.addView(group(
            switchRow("Album colour", "The card takes the colour of the cover", Prefs.MP_TINT),
            switchRow("Ambient waves", "Soft waves in the album's colour move with the song: bass, mids and highs each move one", Prefs.MP_AMBIENT),
            switchRow("Wave scrubber", "Like Samsung's: soft hills rise above the played part, with a glowing thumb", Prefs.MP_WAVY),
            switchRow("App's own buttons", "Like, shuffle, add and the rest, when the app offers them; they change the moment you tap", Prefs.MP_EXTRAS),
            switchRow("Where it's playing", "Your earbuds' or speaker's name at the top", Prefs.MP_DEVICE),
        ))
        col.addView(footnote("Play a song and open the island to see it. The waves follow the song when Halo is allowed to hear it (Bars follow the beat, on the island page); otherwise they sway gently. Low-power mode keeps them still."))
    }

    private fun recQualityPage(col: LinearLayout) {
        pageHeader(col, "Quality", "Sharper video means bigger files")
        val p = Prefs.get(this)
        val cur = Quality.of(p.getString(Prefs.REC_QUALITY, Quality.HD.id))
        val b = windowManager.maximumWindowMetrics.bounds
        val about = mapOf(
            Quality.SD to "Small files that are easy to send. Great for sharing on WhatsApp.",
            Quality.HD to "Sharp and smooth. The best balance for most recordings.",
            Quality.UHD to "The sharpest your phone can make. Big files; if your phone can't record 4K, Halo uses the best it can.",
        )
        for (q in Quality.values()) {
            val (w, h, _) = Quality.sizeFor(q, b.width(), b.height())
            col.addView(optionCard(
                R.drawable.ic_record,
                q.label,
                about[q].orEmpty(),
                listOf("about ${q.mbPerMin} MB a minute", "${w} × ${h}"),
                q == cur,
            ) {
                p.edit().putString(Prefs.REC_QUALITY, q.id).apply()
                render()
            })
        }
        col.addView(footnote("Sizes are for a busy screen like a game or a video. Recording chats or menus makes much smaller files."))
    }

    private fun recSoundPage(col: LinearLayout) {
        pageHeader(col, "Sound", "What your recordings hear")
        val p = Prefs.get(this)
        val cur = RecSound.of(p.getString(Prefs.REC_AUDIO, "device"))
        val micOk = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        for (s in RecSound.values()) {
            val facts = if (s != RecSound.NONE && !micOk) listOf("Asks for permission the first time") else emptyList()
            col.addView(optionCard(s.icon, s.title, s.about, facts, s == cur) {
                p.edit().putString(Prefs.REC_AUDIO, s.id).apply()
                render()
            })
        }
    }

    private fun infoRow(title: String, summary: String): View = rowBase(title, summary).apply { isClickable = false }

    private fun switchRow(title: String, summary: String, key: String): View = switchRowDefault(title, summary, key, true)

    /** A title with a three-way choice under it (one tap picks). */
    private fun segRow(title: String, key: String, names: List<String>, def: Int): View {
        val p = Prefs.get(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        box.addView(label(title, 16f, text1, true))
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.argb(20, 255, 255, 255))
                cornerRadius = dp(18).toFloat()
            }
        }
        val cur = p.getInt(key, def)
        val views = ArrayList<TextView>()
        fun paint(sel: Int) = views.forEachIndexed { i, v ->
            v.setTextColor(if (i == sel) Color.BLACK else text1)
            v.background = if (i == sel) GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(15).toFloat()
            } else null
        }
        names.forEachIndexed { i, n ->
            val t = label(n, 13.5f, text1, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(9), 0, dp(9))
                isClickable = true
                setOnClickListener { v ->
                    p.edit().putInt(key, i).apply()
                    paint(i)
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(60).withEndAction {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
                    }.start()
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    HaloService.instance?.reloadPrefs()
                }
            }
            views += t
            seg.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        paint(cur)
        box.addView(seg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        return box
    }

    private fun postDelayedRender() = window.decorView.postDelayed({ render() }, 220L)

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun switchRowDefault(title: String, summary: String, key: String, def: Boolean): View {
        val row = rowBase(title, summary)
        val prefs = Prefs.get(this)
        val sw = Switch(this).apply {
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, Color.rgb(170, 170, 176)))
            trackTintList = ColorStateList(states, intArrayOf(Color.rgb(120, 120, 128), Color.rgb(58, 58, 62)))
            isChecked = prefs.getBoolean(key, def)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                HaloService.instance?.reloadPrefs()
                // These switches show or hide more options below them.
                if (key == Prefs.SYSTEM_POPS || key == Prefs.IN_SHOTS) postDelayedRender()
                if (key == Prefs.BEAT && checked && !Beat.allowed(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Android calls this the microphone. Halo only reads the music playing, never records.", Toast.LENGTH_LONG).show()
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_BEAT)
                }
            }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        return row
    }

    private fun sliderRow(title: String, key: String, def: Int, min: Int, max: Int, unit: String, camera: Boolean = false): View {
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
        val v = label("$value$unit", 14f, accent, true).apply {
            gravity = Gravity.CENTER
            fontFeatureSettings = "tnum"
        }
        // − and + for exact, one-step changes (the slider is for big moves).
        var seekRef: SeekBar? = null
        fun step(d: Int): View = label(if (d < 0) "−" else "+", 18f, text1, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(26, 255, 255, 255))
            }
            isClickable = true
            setOnClickListener { b ->
                val now = (prefs.getInt(key, def) + d).coerceIn(min, max)
                prefs.edit().putInt(key, now).apply()
                seekRef?.progress = now - min
                v.text = "$now$unit"
                b.animate().scaleX(0.82f).scaleY(0.82f).setDuration(60).withEndAction {
                    b.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
                }.start()
                b.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                if (camera) HaloService.instance?.redetectCamera() else HaloService.instance?.reloadPrefs()
            }
        }
        head.addView(step(-1), LinearLayout.LayoutParams(dp(30), dp(30)))
        head.addView(v, LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT))
        head.addView(step(1), LinearLayout.LayoutParams(dp(30), dp(30)))
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
                        if (camera) HaloService.instance?.redetectCamera() else HaloService.instance?.reloadPrefs()
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}

                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        seekRef = seek
        box.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        return box
    }

    companion object {
        private const val REQ_PICTURE = 41
        private const val REQ_BEAT = 42
        private val RUNTIME_PERMS = listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}


/**
 * The download bar: a rounded track that fills smoothly (each step glides
 * rather than jumps) with a soft light sweeping along the filled part.
 */
class UpdateBar(context: android.content.Context) : View(context) {
    private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 255, 255, 255) }
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val shine = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val r = android.graphics.RectF()
    private var shown = 0f
    private var glide: android.animation.ValueAnimator? = null
    private var sweep = 0f
    private val sweeper = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400L
        repeatCount = android.animation.ValueAnimator.INFINITE
        addUpdateListener {
            sweep = it.animatedValue as Float
            invalidate()
        }
    }

    fun set(f: Float, animate: Boolean) {
        val to = f.coerceIn(0f, 1f)
        glide?.cancel()
        if (!animate) {
            shown = to
            invalidate()
            return
        }
        glide = android.animation.ValueAnimator.ofFloat(shown, to).apply {
            duration = 260L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sweeper.start()
    }

    override fun onDetachedFromWindow() {
        sweeper.cancel()
        glide?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: android.graphics.Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        r.set(0f, 0f, w, h)
        c.drawRoundRect(r, h / 2f, h / 2f, track)
        val fw = (w * shown).coerceAtLeast(h)
        if (shown <= 0f) return
        fill.shader = android.graphics.LinearGradient(0f, 0f, fw, 0f, Color.rgb(90, 170, 255), Color.WHITE, android.graphics.Shader.TileMode.CLAMP)
        r.set(0f, 0f, fw, h)
        c.drawRoundRect(r, h / 2f, h / 2f, fill)
        // The light sweeping along the filled part.
        val x = -fw * 0.3f + fw * 1.6f * sweep
        shine.shader = android.graphics.LinearGradient(x - fw * 0.25f, 0f, x + fw * 0.25f, 0f,
            intArrayOf(Color.TRANSPARENT, Color.argb(110, 255, 255, 255), Color.TRANSPARENT), null, android.graphics.Shader.TileMode.CLAMP)
        c.drawRoundRect(r, h / 2f, h / 2f, shine)
    }
}
