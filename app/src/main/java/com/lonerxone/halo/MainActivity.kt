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
    }

    private var page = Page.HOME
    private var shownPage: Page? = null
    private var scroll: ScrollView? = null

    private fun go(p: Page) {
        page = p
        render()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (page != Page.HOME) {
            go(Page.HOME)
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
        }

        // Same page re-rendered (a status change): keep the scroll position.
        val samePage = shownPage == page
        val keepY = if (samePage) scroll?.scrollY ?: 0 else 0
        val sv = ScrollView(this).apply {
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
        if (samePage) {
            sv.post { sv.scrollTo(0, keepY) }
        } else {
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
                setOnClickListener { go(Page.HOME) }
            }
            box.addView(back, LinearLayout.LayoutParams(dp(42), dp(42)).apply { bottomMargin = dp(34) })
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
            iconRow(R.drawable.ic_info, Colors.GRAPHITE, "About", "Creator, privacy, diagnostics") { go(Page.ABOUT) },
        ))
        credit(col)
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
            switchRow("Calls", "Caller, timer, end-call button", Prefs.CALLS),
            switchRow("Timers & stopwatch", "Countdown from the Clock app", Prefs.TIMERS),
            switchRow("Downloads & installs", "Progress ring for files and apps", Prefs.TRANSFERS),
            switchRow("Navigation", "Next turn from Google Maps", Prefs.NAV),
            switchRow("Flashlight", "Stays on the island while it's on", Prefs.TORCH),
        ))
        col.addView(sectionTitle("Alerts"))
        col.addView(group(
            switchRow("Messages", "WhatsApp, SMS and app alerts", Prefs.NOTIFS),
            switchRowDefault("Hide message text", "Show \"New message\" instead", Prefs.HIDE_TEXT, false),
            switchRow("Charging", "Pop when you plug in", Prefs.CHARGING),
            switchRow("System pops", "Low battery, full, silent, earbuds, DND", Prefs.SYSTEM_POPS),
            switchRow("Connections & modes", "Wi-Fi, airplane mode, power saving", Prefs.CONNECTIVITY),
        ))
        gap(col, 16)
        col.addView(group(
            iconRow(R.drawable.ic_apps, Colors.GRAPHITE, "Choose apps", "Which apps can pop up") {
                startActivity(Intent(this, AppsActivity::class.java))
            },
        ))
    }

    private fun lookPage(col: LinearLayout) {
        pageHeader(col, "Look & feel", "Make it yours")
        col.addView(sectionTitle("Island"))
        col.addView(group(
            switchRow("Camera circle", "Rests on the camera; tap it for notifications", Prefs.IDLE),
            sliderRow("Size", Prefs.SCALE, 100, 80, 130, "%"),
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
        ))
    }

    // ---------------------------------------------------------------------
    // Pictures
    // ---------------------------------------------------------------------

    private fun picturesPage(col: LinearLayout) {
        pageHeader(col, "Pictures", "Make the island yours")
        col.addView(sectionTitle("Island"))
        col.addView(pictureCard(Prefs.ISLAND_PIC, "Fills the island when it opens. Wide (landscape) pictures work best.", landscape = true))
        col.addView(sectionTitle("Notification center"))
        col.addView(pictureCard(Prefs.PANEL_PIC, "Sits behind your notifications. Tall (portrait) pictures work best.", landscape = false))
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
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(actionButton(if (has) "Change" else "Choose a picture") { pickPicture(key) }, LinearLayout.LayoutParams(0, dp(46), 1f))
        if (has) {
            buttons.addView(label("Remove", 14f, text1, true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Colors.GRAPHITE)
                    cornerRadius = dp(24).toFloat()
                }
                isClickable = true
                setOnClickListener {
                    Prefs.get(this@MainActivity).edit().remove(key).apply()
                    file.delete()
                    HaloService.instance?.reloadPrefs()
                    render()
                }
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(10) })
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

    private fun setupPage(col: LinearLayout) {
        pageHeader(col, "Permissions", "What Halo needs to work")
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

    private fun updatesPage(col: LinearLayout) {
        pageHeader(col, "Updates", "Straight from GitHub")
        col.addView(updatesCard())
    }

    private fun aboutPage(col: LinearLayout) {
        pageHeader(col, "About", null)
        col.addView(group(
            infoRow("Creator", "LONERXONE"),
            infoRow("Works on", "Android 11 and newer. Tuned for Samsung One UI, where it feels most at home."),
            infoRow("Privacy", "Halo never reads your screen. Everything stays on your phone, except the update check with GitHub."),
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

    private fun infoRow(title: String, summary: String): View = rowBase(title, summary).apply { isClickable = false }

    private fun switchRow(title: String, summary: String, key: String): View = switchRowDefault(title, summary, key, true)

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
                        if (camera) HaloService.instance?.redetectCamera() else HaloService.instance?.reloadPrefs()
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
        private const val REQ_PICTURE = 41
        private val RUNTIME_PERMS = listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}
