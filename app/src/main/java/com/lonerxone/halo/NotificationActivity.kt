package com.lonerxone.halo

import android.app.Activity
import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.text.InputType
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Full view of one notification, as a One UI bottom sheet over whatever app is
 * open: the conversation, a reply box (when the app supports replies), and
 * Open / Mark as read / Dismiss. It is a real screen, so the keyboard works.
 */
class NotificationActivity : Activity() {

    private var night = false
    private var cardColor = 0
    private var bubbleColor = 0
    private var text1 = 0
    private var text2 = 0

    private var sheet: LinearLayout? = null
    private var thread: LinearLayout? = null
    private var threadScroll: ScrollView? = null
    private var closing = false
    private var root: FrameLayout? = null
    private var input: EditText? = null
    /** The message you swiped to answer (quoted in your reply). */
    private var quoting: Msg? = null
    private var quoteChip: LinearLayout? = null
    private var current: StatusBarNotification? = null
    private val io = java.util.concurrent.Executors.newFixedThreadPool(2)
    private val ui = Handler(Looper.getMainLooper())

    /** One line of a conversation: who, what, and a photo if it has one. */
    class Msg(val sender: String?, val text: CharSequence, val uri: android.net.Uri?, val mime: String?)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        load(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        closing = false
        load(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        close()
    }

    private fun load(i: Intent?) {
        val key = i?.getStringExtra(EXTRA_KEY)
        val listener = HaloNotificationListener.instance
        val sbn = if (key == null || listener == null) null else listener.find(key)
        if (sbn == null) {
            Toast.makeText(this, "That notification is gone", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // Same black as the island.
        night = true
        cardColor = Color.rgb(14, 14, 16)
        bubbleColor = Color.rgb(36, 36, 40)
        text1 = Color.rgb(245, 245, 247)
        text2 = Color.rgb(150, 150, 158)
        current = sbn
        quoting = null
        setContentView(build(sbn, listener!!))
    }

    private fun label(t: CharSequence, sp: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun build(sbn: StatusBarNotification, listener: HaloNotificationListener): View {
        val n = sbn.notification
        val ex = n.extras ?: Bundle()
        val app = listener.appName(sbn.packageName)
        val title = listener.richOf(sbn, false).title.ifEmpty { app }
        val icon = listener.iconBitmap(n.getLargeIcon()) ?: listener.appIcon(sbn.packageName)
        // Halo's palette: white on black, whatever colour the app uses.
        val accent = Colors.INK
        val replyAction = n.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
        val readAction = n.actions?.firstOrNull { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            t.contains("read") && a.remoteInputs.isNullOrEmpty()
        }

        val root = FrameLayout(this).apply {
            setOnClickListener { close() }
        }
        this.root = root

        val s = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = GradientDrawable().apply {
                setColor(cardColor)
                val r = dp(28).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        sheet = s
        val basePad = dp(20)
        s.setPadding(basePad, dp(10), basePad, basePad)

        // Grab handle
        s.addView(View(this).apply {
            background = GradientDrawable().apply {
                setColor(text2)
                cornerRadius = dp(2).toFloat()
                alpha = 110
            }
        }, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(14)
        })

        // App row
        val appRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        appRow.addView(ImageView(this).apply {
            if (icon != null) setImageBitmap(icon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, dp(6).toFloat())
                }
            }
            clipToOutline = true
        }, LinearLayout.LayoutParams(dp(22), dp(22)))
        val ago = DateUtils.getRelativeTimeSpanString(sbn.postTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        appRow.addView(label("$app · $ago", 13f, text2, false).apply { setPadding(dp(8), 0, 0, 0) })
        s.addView(appRow)

        s.addView(label(title, 20f, text1, true).apply { setPadding(0, dp(10), 0, dp(10)) })

        // Conversation (or the full text)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        thread = list
        val messages = messages(ex)
        if (messages.isEmpty()) {
            val body = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: ex.getCharSequence(Notification.EXTRA_TEXT)
                ?: ""
            list.addView(label(body, 15.5f, text1, false).apply { setLineSpacing(0f, 1.15f) })
            // A picture notification (BigPicture): show the picture.
            @Suppress("DEPRECATION")
            val pic = ex.getParcelable<android.graphics.Bitmap>(Notification.EXTRA_PICTURE)
            if (pic != null) list.addView(photo(pic), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)).apply { topMargin = dp(10) })
        } else {
            for ((i, m) in messages.withIndex()) {
                val b = bubble(m, mine = false, canQuote = replyAction != null)
                list.addView(b)
                // Messages rise in one after another.
                b.alpha = 0f
                b.translationY = dp(14).toFloat()
                b.animate().alpha(1f).translationY(0f).setStartDelay(60L + i * 28L).setDuration(220L).setInterpolator(DecelerateInterpolator(2f)).start()
            }
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        threadScroll = scroll
        list.measure(
            View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.widthPixels - basePad * 2, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val maxH = (resources.displayMetrics.heightPixels * 0.5f).toInt()
        val hasPhotos = messages.any { it.uri != null }
        s.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (hasPhotos) maxH else minOf(list.measuredHeight, maxH)))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

        // Reply box
        if (replyAction != null) {
            // "Replying to…" chip, shown after you swipe a message.
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(8), dp(8))
                background = GradientDrawable().apply {
                    setColor(bubbleColor)
                    cornerRadius = dp(16).toFloat()
                }
                visibility = View.GONE
            }
            quoteChip = chip
            s.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            // Send a photo into this chat.
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_image)
                setColorFilter(text1)
                val pad = dp(11)
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(bubbleColor)
                }
                setOnClickListener { pickPhoto() }
            }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) })
            val input = EditText(this).apply {
                hint = "Reply to $title"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(text1)
                setHintTextColor(text2)
                maxLines = 4
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                imeOptions = EditorInfo.IME_ACTION_SEND
                background = GradientDrawable().apply {
                    setColor(bubbleColor)
                    cornerRadius = dp(22).toFloat()
                }
                setPadding(dp(16), dp(11), dp(16), dp(11))
            }
            val send = ImageView(this).apply {
                setImageResource(R.drawable.ic_send)
                setColorFilter(Color.BLACK)
                val pad = dp(11)
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accent)
                }
            }
            this.input = input
            val doSend = {
                val typed = input.text?.toString()?.trim().orEmpty()
                if (typed.isNotEmpty()) {
                    // A swiped message is quoted with WhatsApp's "> " quote formatting.
                    val q = quoting
                    val text = if (q != null) "> " + listOfNotNull(q.sender, q.text.toString().take(80)).joinToString(": ") + "\n" + typed else typed
                    if (sendReply(replyAction, text)) {
                        val mineBubble = bubble(Msg(null, typed, null, null), mine = true, canQuote = false)
                        list.addView(mineBubble)
                        // Your message pops in from the send button.
                        mineBubble.scaleX = 0.6f
                        mineBubble.scaleY = 0.6f
                        mineBubble.alpha = 0f
                        mineBubble.pivotX = mineBubble.width.toFloat()
                        mineBubble.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(240L).setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
                        input.setText("")
                        setQuote(null)
                        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                        send.animate().rotation(send.rotation + 360f).setDuration(380L).start()
                    } else {
                        Toast.makeText(this, "Couldn't send. Open the app to reply.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            send.setOnClickListener { doSend() }
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    doSend()
                    true
                } else {
                    false
                }
            }
            row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(send, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(10) })
            s.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        }

        // The app's other buttons (Like, Mute, Archive, Call back...).
        val extras = n.actions?.filter { a ->
            a !== replyAction && a !== readAction && a.actionIntent != null && !a.title.isNullOrBlank()
        }.orEmpty()
        if (extras.isNotEmpty()) {
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (a in extras.take(3)) {
                chips.addView(label(a.title.toString(), 13.5f, text1, true).apply {
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    background = GradientDrawable().apply {
                        setColor(bubbleColor)
                        cornerRadius = dp(16).toFloat()
                    }
                    setOnClickListener {
                        Launch.send(this@NotificationActivity, a.actionIntent)
                        close()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
            }
            s.addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        }

        // Actions
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        fun action(textLabel: String, onClick: () -> Unit) {
            // First button is the main one: white with black text; the rest graphite.
            val primary = actions.childCount == 0
            actions.addView(label(textLabel, 14f, if (primary) Color.BLACK else text1, true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(if (primary) Colors.INK else Colors.GRAPHITE)
                    cornerRadius = dp(22).toFloat()
                }
                isClickable = true
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
        }
        action("Open") {
            // Opens the exact chat; falls back to the app itself.
            if (!Launch.send(this, n.contentIntent)) Launch.openApp(this, sbn.packageName)
            // Tapping a notification in the shade clears it; do the same here.
            if ((n.flags and Notification.FLAG_AUTO_CANCEL) != 0) listener.dismiss(sbn.key)
            close()
        }
        if (readAction != null) {
            action("Mark as read") {
                Launch.send(this, readAction.actionIntent)
                close()
            }
        }
        action("Dismiss") {
            listener.dismiss(sbn.key)
            close()
        }
        s.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        root.addView(s, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // Keep the sheet above the navigation bar and the keyboard.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            s.setPadding(basePad, dp(10), basePad, basePad + maxOf(bars.bottom, ime.bottom))
            insets
        }

        // Slide up.
        s.translationY = resources.displayMetrics.heightPixels * 0.6f
        s.animate().translationY(0f).setInterpolator(android.view.animation.PathInterpolator(0.16f, 1.12f, 0.3f, 1f)).setDuration(340L).start()
        return root
    }

    private fun bubble(m: Msg, mine: Boolean, canQuote: Boolean): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(9), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(if (mine) Colors.INK else bubbleColor)
                cornerRadius = dp(18).toFloat()
            }
        }
        if (!m.sender.isNullOrEmpty() && !mine) {
            box.addView(label(m.sender, 12f, text2, true).apply { setPadding(0, 0, 0, dp(2)) })
        }
        // A photo in the message: shown right here; tap to see it full screen.
        if (m.uri != null && (m.mime?.startsWith("image/") != false)) {
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    setColor(Color.argb(40, 255, 255, 255))
                    cornerRadius = dp(12).toFloat()
                }
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: Outline) {
                        outline.setRoundRect(0, 0, v.width, v.height, dp(12).toFloat())
                    }
                }
                clipToOutline = true
            }
            box.addView(img, LinearLayout.LayoutParams(dp(220), dp(220)).apply { bottomMargin = dp(6) })
            io.execute {
                val bmp = loadImage(m.uri)
                ui.post {
                    if (bmp != null) {
                        img.setImageBitmap(bmp)
                        img.alpha = 0f
                        img.animate().alpha(1f).setDuration(200L).start()
                        img.setOnClickListener { viewFull(bmp) }
                    } else {
                        img.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        img.visibility = View.GONE
                        box.addView(label("📷 Photo (open the chat to see it)", 13f, text2, false), 0)
                    }
                }
            }
        }
        if (m.text.isNotBlank()) box.addView(label(m.text, 15f, if (mine) Color.BLACK else text1, false))
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (mine) Gravity.END else Gravity.START
        }
        wrap.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (mine) marginStart = dp(48) else marginEnd = dp(48)
        })
        wrap.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        }
        if (canQuote && !mine) swipeToQuote(box, m)
        return wrap
    }

    /** Swipe a message to the right to reply to it, like WhatsApp. */
    private fun swipeToQuote(v: View, m: Msg) {
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var dragging = false
        var buzzed = false
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                    buzzed = false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    if (!dragging && dx > slop && dx > kotlin.math.abs(e.rawY - downY) * 1.3f) {
                        dragging = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        // Resists a little, like a rubber band.
                        view.translationX = (dx * 0.55f).coerceIn(0f, dp(90).toFloat())
                        if (!buzzed && view.translationX > dp(56)) {
                            buzzed = true
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        }
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (dragging && view.translationX > dp(56)) setQuote(m)
                    view.animate().translationX(0f).setDuration(260L).setInterpolator(android.view.animation.OvershootInterpolator(1.8f)).start()
                    dragging = false
                }
            }
            // Keep getting this touch (so a swipe can start); scrolling still wins vertically.
            e.actionMasked == android.view.MotionEvent.ACTION_DOWN || dragging
        }
    }

    private fun setQuote(m: Msg?) {
        quoting = m
        val chip = quoteChip ?: return
        chip.removeAllViews()
        if (m == null) {
            chip.animate().alpha(0f).setDuration(120L).withEndAction { chip.visibility = View.GONE }.start()
            return
        }
        chip.addView(View(this).apply {
            background = GradientDrawable().apply {
                setColor(Colors.INK)
                cornerRadius = dp(2).toFloat()
            }
        }, LinearLayout.LayoutParams(dp(3), dp(30)))
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        t.addView(label("Replying to " + (m.sender ?: "this message"), 12f, text1, true))
        t.addView(label(if (m.text.isBlank()) "📷 Photo" else m.text, 13f, text2, false).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        chip.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        chip.addView(label("✕", 15f, text2, true).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { setQuote(null) }
        })
        chip.visibility = View.VISIBLE
        chip.alpha = 0f
        chip.translationY = dp(10).toFloat()
        chip.animate().alpha(1f).translationY(0f).setDuration(200L).start()
        input?.requestFocus()
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showSoftInput(input, 0)
    }

    private fun photo(b: android.graphics.Bitmap): View = ImageView(this).apply {
        setImageBitmap(b)
        scaleType = ImageView.ScaleType.CENTER_CROP
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, dp(14).toFloat())
            }
        }
        clipToOutline = true
        setOnClickListener { viewFull(b) }
    }

    /** A photo, full screen; tap to close. */
    private fun viewFull(b: android.graphics.Bitmap) {
        val r = root ?: return
        val f = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        f.addView(ImageView(this).apply {
            setImageBitmap(b)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        f.setOnClickListener {
            f.animate().alpha(0f).setDuration(150L).withEndAction { r.removeView(f) }.start()
        }
        r.addView(f, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        f.alpha = 0f
        f.scaleX = 0.92f
        f.scaleY = 0.92f
        f.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200L).start()
    }

    /** Reads a photo the app attached to its notification (sized for the screen). */
    private fun loadImage(uri: android.net.Uri): android.graphics.Bitmap? = try {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 1080) sample *= 2
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) }
    } catch (_: Throwable) {
        null
    }

    /** Last few lines of a chat notification (MessagingStyle), oldest first, with any photos. */
    @Suppress("DEPRECATION")
    private fun messages(ex: Bundle): List<Msg> {
        val arr = ex.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
        return arr.mapNotNull { p ->
            val b = p as? Bundle ?: return@mapNotNull null
            val text = b.getCharSequence("text") ?: ""
            val uri = b.getParcelable<android.net.Uri>("uri")
            if (text.isBlank() && uri == null) return@mapNotNull null
            Msg(b.getCharSequence("sender")?.toString(), text, uri, b.getString("type"))
        }.takeLast(12)
    }

    // ----- sending a photo -----

    private fun pickPhoto() {
        val i = if (android.os.Build.VERSION.SDK_INT >= 33) Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).setType("image/*") else Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, PICK)
        } catch (_: Exception) {
            Toast.makeText(this, "No photo picker found", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val sbn = current ?: return
        val mime = contentResolver.getType(uri) ?: "image/jpeg"
        val reply = sbn.notification.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
        // 1. Apps that accept pictures in a notification reply (some do): send it right here.
        val ri = reply?.remoteInputs?.firstOrNull { r -> r.allowedDataTypes?.any { t -> t == mime || t == "image/*" } == true }
        if (reply != null && ri != null) {
            try {
                val fill = Intent()
                RemoteInput.addDataResultToIntent(ri, fill, mapOf(mime to uri))
                fill.clipData = android.content.ClipData.newRawUri("", uri)
                fill.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (Launch.send(this, reply.actionIntent, fill)) {
                    Toast.makeText(this, "Photo sent", Toast.LENGTH_SHORT).show()
                    return
                }
            } catch (_: Exception) {
            }
        }
        // 2. WhatsApp and most chat apps: open that same chat with the photo
        //    ready to send (one tap on their send button).
        val share = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri("", uri)
            setPackage(sbn.packageName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            sbn.notification.shortcutId?.let { id ->
                putExtra(Intent.EXTRA_SHORTCUT_ID, id)
                // WhatsApp's chat id ("234…@s.whatsapp.net") picks the chat directly.
                if (sbn.packageName.startsWith("com.whatsapp")) putExtra("jid", id)
            }
        }
        try {
            startActivity(share)
            close()
        } catch (_: Exception) {
            try {
                startActivity(Intent.createChooser(share.setPackage(null), "Send photo").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                Toast.makeText(this, "Couldn't send the photo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendReply(action: Notification.Action, text: String): Boolean = try {
        val inputs = action.remoteInputs ?: emptyArray()
        val data = Intent()
        val results = Bundle()
        for (ri in inputs) results.putCharSequence(ri.resultKey, text)
        RemoteInput.addResultsToIntent(inputs, data, results)
        Launch.send(this, action.actionIntent, data)
    } catch (_: Exception) {
        false
    }

    private fun close() {
        if (closing) return
        closing = true
        val s = sheet
        if (s == null) {
            finish()
            return
        }
        s.animate().translationY(s.height.toFloat() + dp(40)).setDuration(170L).setInterpolator(android.view.animation.AccelerateInterpolator(1.4f)).withEndAction {
            finish()
        }.start()
        Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) finish() }, 400L)
    }

    companion object {
        const val EXTRA_KEY = "key"
        private const val PICK = 7
    }
}
