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

    private val cardColor = Color.rgb(14, 14, 16)
    private val glassFill = Color.argb(150, 30, 30, 34)
    private val bubbleColor = Color.argb(215, 34, 34, 38)
    private val text1 = Color.rgb(245, 245, 247)
    private val text2 = Color.rgb(170, 170, 178)

    private var sheet: LinearLayout? = null
    private var thread: LinearLayout? = null
    private var threadScroll: ScrollView? = null
    private var closing = false
    private var root: FrameLayout? = null
    private var input: EditText? = null
    private var current: StatusBarNotification? = null
    private val io = java.util.concurrent.Executors.newFixedThreadPool(2)
    private val ui = Handler(Looper.getMainLooper())

    /** One line of a conversation: who, what, and a photo if it has one. */
    class Msg(val sender: String?, val text: CharSequence, val uri: android.net.Uri?, val mime: String?)

    /** Springy ease: overshoots a touch and settles (1 - e^-kt cos wt). */
    private class Springy(bounce: Float = 0.4f) : android.view.animation.Interpolator {
        private val k = 7.5f - 2.5f * bounce
        private val w = 8.5f + 3f * bounce
        override fun getInterpolation(t: Float): Float =
            if (t >= 1f) 1f else (1.0 - Math.exp((-k * t).toDouble()) * Math.cos((w * t).toDouble())).toFloat()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

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
        current = sbn
        setContentView(build(sbn, listener!!))
    }

    private fun label(t: CharSequence, sp: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun pill(color: Int, radius: Float, stroke: Boolean = true) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke) setStroke(dp(1), Color.argb(30, 255, 255, 255))
    }

    private fun circle(color: Int, stroke: Boolean = true) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (stroke) setStroke(dp(1), Color.argb(30, 255, 255, 255))
    }

    /** Shrinks on touch, springs back on release. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun View.pressy() {
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90L).setInterpolator(DecelerateInterpolator()).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(380L).setInterpolator(Springy(0.6f)).start()
            }
            false
        }
    }

    /**
     * The sheet's background: the notification center's picture (Halo
     * settings → Notification center picture), cropped to fit and dimmed so
     * text stays readable; plain black when none is set.
     */
    private inner class PicSheet(private val radius: Float) : android.graphics.drawable.Drawable() {
        var pic: android.graphics.Bitmap? = null
            set(v) {
                field = v
                invalidateSelf()
            }
        var fade = 1f
            set(v) {
                field = v
                invalidateSelf()
            }
        var dim = 0.5f
        private val path = android.graphics.Path()
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        private val src = android.graphics.Rect()
        private val dst = android.graphics.RectF()

        override fun draw(c: android.graphics.Canvas) {
            val b = bounds
            path.reset()
            dst.set(b)
            path.addRoundRect(dst, floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f), android.graphics.Path.Direction.CW)
            c.save()
            c.clipPath(path)
            c.drawColor(cardColor)
            val p = pic
            if (p != null && fade > 0f) {
                // Centre-crop.
                val sw = p.width.toFloat()
                val sh = p.height.toFloat()
                val scale = maxOf(b.width() / sw, b.height() / sh)
                val w = (b.width() / scale).toInt()
                val h = (b.height() / scale).toInt()
                val l = ((sw - w) / 2).toInt()
                val t = ((sh - h) / 2).toInt()
                src.set(l, t, l + w, t + h)
                paint.shader = null
                paint.alpha = (255 * fade).toInt()
                c.drawBitmap(p, src, dst, paint)
                paint.alpha = 255
                c.drawColor(Color.argb((255 * dim * fade).toInt(), 0, 0, 0))
                // Darker towards the bottom, where the reply box and buttons sit.
                paint.shader = android.graphics.LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), Color.argb(0, 0, 0, 0), Color.argb((150 * fade).toInt(), 0, 0, 0), android.graphics.Shader.TileMode.CLAMP)
                c.drawRect(dst, paint)
                paint.shader = null
            }
            c.restore()
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun loadPanelPic(bg: PicSheet) {
        val p = Prefs.get(this)
        if (p.getLong(Prefs.PANEL_PIC, 0L) == 0L) return
        bg.dim = (p.getInt(Prefs.PIC_DIM, 45).coerceIn(0, 90) / 100f).coerceAtLeast(0.35f)
        val f = Prefs.picFile(this, Prefs.PANEL_PIC)
        io.execute {
            val bmp = try {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
                var sample = 1
                while (o.outWidth / (sample * 2) >= resources.displayMetrics.widthPixels / 2) sample *= 2
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (_: Throwable) {
                null
            } ?: return@execute
            ui.post {
                bg.fade = 0f
                bg.pic = bmp
                android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 260L
                    addUpdateListener { bg.fade = it.animatedValue as Float }
                    start()
                }
            }
        }
    }

    /** Group chats: each person's name gets its own colour, like WhatsApp. */
    private fun senderColor(name: String): Int {
        val c = intArrayOf(0xFF7FD3FF.toInt(), 0xFFFF9F7A.toInt(), 0xFFB3A6FF.toInt(), 0xFF7FE0A8.toInt(), 0xFFFFD36B.toInt(), 0xFFFF8FB8.toInt(), 0xFF6FE3E0.toInt())
        return c[(name.hashCode() and 0x7fffffff) % c.size]
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun build(sbn: StatusBarNotification, listener: HaloNotificationListener): View {
        val n = sbn.notification
        val ex = n.extras ?: Bundle()
        val app = listener.appName(sbn.packageName)
        val title = listener.richOf(sbn, false).title.ifEmpty { app }
        val avatar = listener.iconBitmap(n.getLargeIcon())
        val appIcon = listener.appIcon(sbn.packageName)
        val replyAction = n.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
        val readAction = n.actions?.firstOrNull { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            t.contains("read") && a.remoteInputs.isNullOrEmpty()
        }
        val anim = mutableListOf<View>()

        val root = FrameLayout(this).apply { setOnClickListener { close() } }
        this.root = root

        val bg = PicSheet(dpf(30f))
        val s = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = bg
            elevation = dpf(12f)
        }
        sheet = s
        loadPanelPic(bg)
        val basePad = dp(18)

        // Grab handle (drag the top of the sheet down to close).
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        top.addView(View(this).apply { background = pill(Color.argb(120, 255, 255, 255), dpf(2f), stroke = false) }, LinearLayout.LayoutParams(dp(38), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(10)
            bottomMargin = dp(14)
        })

        // Header: picture (with the app's icon on it), name, app and time, ✕.
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pic = FrameLayout(this)
        pic.addView(ImageView(this).apply {
            setImageBitmap(avatar ?: appIcon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    if (avatar != null) outline.setOval(0, 0, v.width, v.height) else outline.setRoundRect(0, 0, v.width, v.height, dpf(12f))
                }
            }
            clipToOutline = true
        }, FrameLayout.LayoutParams(dp(46), dp(46)))
        if (avatar != null && appIcon != null) {
            pic.addView(ImageView(this).apply {
                setImageBitmap(appIcon)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: Outline) {
                        outline.setRoundRect(0, 0, v.width, v.height, dpf(6f))
                    }
                }
                clipToOutline = true
            }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.BOTTOM or Gravity.END))
        }
        head.addView(pic, LinearLayout.LayoutParams(dp(52), dp(50)))
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        names.addView(label(title, 18f, text1, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val ago = DateUtils.getRelativeTimeSpanString(sbn.postTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        names.addView(label("$app · $ago", 12.5f, text2, false).apply { setPadding(0, dp(2), 0, 0) })
        head.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(head)
        s.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = basePad
            marginEnd = basePad
        })
        anim += head

        // Conversation (or the full text). Takes whatever room is left and scrolls.
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(basePad, dp(8), basePad, dp(6))
        }
        thread = list
        val messages = messages(ex)
        if (messages.isEmpty()) {
            val body = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: ex.getCharSequence(Notification.EXTRA_TEXT)
                ?: ""
            // Posts with a picture (X, Instagram, news…): the picture sits in the bubble.
            val big = picture(ex)
            if (body.isNotBlank() || big != null) list.addView(bubble(Msg(null, body, null, null), mine = false, pic = big))
        } else {
            for (m in messages) list.addView(bubble(m, mine = false))
            picture(ex)?.let { list.addView(bubble(Msg(null, "", null, null), mine = false, pic = it)) }
        }
        for (i in 0 until list.childCount) anim += list.getChildAt(i)
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = false
            addView(list)
            // Grows/shrinks with the keyboard: always keep the newest message in view.
            addOnLayoutChangeListener { v, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot) (v as ScrollView).post { v.fullScroll(View.FOCUS_DOWN) } }
        }
        threadScroll = scroll
        s.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { topMargin = dp(10) })
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

        // Reply box: photo · text · send.
        if (replyAction != null) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
            }
            val input = EditText(this).apply {
                hint = "Message"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                setTextColor(text1)
                setHintTextColor(text2)
                maxLines = 5
                minHeight = dp(46)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                imeOptions = EditorInfo.IME_ACTION_SEND
                background = pill(glassFill, dpf(23f))
                setPadding(dp(18), dp(12), dp(18), dp(12))
            }
            val send = ImageView(this).apply {
                setImageResource(R.drawable.ic_send)
                setColorFilter(Color.BLACK)
                val pad = dp(12)
                setPadding(pad, pad, pad, pad)
                background = circle(Colors.INK, stroke = false)
                contentDescription = "Send"
                pressy()
                alpha = 0.45f
                scaleX = 0.86f
                scaleY = 0.86f
            }
            this.input = input
            // The send button wakes up when there's something to send.
            input.addTextChangedListener(object : android.text.TextWatcher {
                var on = false
                override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) {
                    val now = !e.isNullOrBlank()
                    if (now == on) return
                    on = now
                    send.animate().alpha(if (now) 1f else 0.45f).scaleX(if (now) 1f else 0.86f).scaleY(if (now) 1f else 0.86f).setDuration(320L).setInterpolator(Springy(0.6f)).start()
                }
            })
            val doSend = {
                val typed = input.text?.toString()?.trim().orEmpty()
                if (typed.isNotEmpty()) {
                    if (sendReply(replyAction, typed)) {
                        val mine = bubble(Msg(null, typed, null, null), mine = true)
                        list.addView(mine)
                        // Your message pops up from the reply box.
                        mine.alpha = 0f
                        mine.translationY = dpf(30f)
                        mine.scaleX = 0.85f
                        mine.scaleY = 0.85f
                        mine.pivotX = (resources.displayMetrics.widthPixels - basePad).toFloat()
                        mine.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(420L).setInterpolator(Springy(0.45f)).start()
                        input.setText("")
                        scroll.post { scroll.smoothScrollTo(0, list.height) }
                        send.animate().translationX(dpf(14f)).alpha(0.2f).setDuration(120L).withEndAction {
                            send.translationX = -dpf(10f)
                            send.animate().translationX(0f).alpha(0.45f).setDuration(320L).setInterpolator(Springy(0.5f)).start()
                        }.start()
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
            row.addView(send, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(8) })
            s.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
                marginStart = basePad
                marginEnd = basePad
            })
            anim += row
        }

        // Buttons: one floating bar. "Open" is the big white pill; the rest are
        // icons with a small label, evenly spaced inside the same bar.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = pill(Color.argb(170, 22, 22, 26), dpf(32f))
        }
        val tiles = mutableListOf<View>()
        val openPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = pill(Colors.INK, dpf(26f), stroke = false)
            addView(ImageView(this@NotificationActivity).apply {
                setImageResource(R.drawable.ic_open)
                setColorFilter(Color.BLACK)
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
            addView(label("Open", 15f, Color.BLACK, true))
            pressy()
            setOnClickListener {
                // Opens the exact chat or post; falls back to the app itself.
                if (!Launch.send(this@NotificationActivity, n.contentIntent)) Launch.openApp(this@NotificationActivity, sbn.packageName)
                if ((n.flags and Notification.FLAG_AUTO_CANCEL) != 0) listener.dismiss(sbn.key)
                close()
            }
        }
        bar.addView(openPill, LinearLayout.LayoutParams(0, dp(52), 1.5f))
        tiles += openPill
        fun tile(icon: Int, name: String, onClick: () -> Unit) {
            val t = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener { onClick() }
            }
            t.addView(ImageView(this).apply {
                setImageResource(icon)
                setColorFilter(text1)
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            t.addView(label(name, 10.5f, text2, false).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(2), dp(4), dp(2), 0)
            })
            t.pressy()
            bar.addView(t, LinearLayout.LayoutParams(0, dp(52), 1f))
            tiles += t
        }
        if (readAction != null) {
            tile(R.drawable.ic_check, "Read") {
                Launch.send(this, readAction.actionIntent)
                close()
            }
        }
        // The app's own buttons (Mute, Like, Repost, Archive, Call back...).
        val extras = n.actions?.filter { a ->
            a !== replyAction && a !== readAction && a.actionIntent != null && !a.title.isNullOrBlank()
        }.orEmpty()
        for (a in extras.take(2)) {
            val name = a.title.toString()
            val l = name.lowercase()
            val icon = when {
                l.contains("mute") || l.contains("silence") -> R.drawable.ic_bell_off
                l.contains("call") -> R.drawable.ic_call
                l.contains("like") || l.contains("love") || l.contains("heart") -> R.drawable.ic_heart
                l.contains("repost") || l.contains("retweet") || l.contains("share") -> R.drawable.ic_repost
                l.contains("archive") || l.contains("save") || l.contains("download") -> R.drawable.ic_download
                l.contains("done") || l.contains("ok") || l.contains("accept") -> R.drawable.ic_check
                else -> R.drawable.ic_bolt
            }
            tile(icon, name.replaceFirstChar { it.uppercase() }) {
                Launch.send(this, a.actionIntent)
                close()
            }
        }
        tile(R.drawable.ic_delete, "Clear") {
            listener.dismiss(sbn.key)
            close()
        }
        s.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
            marginStart = dp(14)
            marginEnd = dp(14)
        })

        root.addView(s, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // Room for the status bar on top; the navigation bar or keyboard below.
        // The keyboard is followed frame by frame, so the chat rises with it.
        var bars = 0
        var ime = 0
        var animatingIme = false
        fun pad() {
            s.setPadding(0, 0, 0, dp(14) + maxOf(bars, ime))
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val sb = insets.getInsets(WindowInsets.Type.systemBars())
            root.setPadding(0, sb.top + dp(36), 0, 0)
            bars = sb.bottom
            if (!animatingIme) {
                ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                pad()
            }
            insets
        }
        root.setWindowInsetsAnimationCallback(object : android.view.WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onPrepare(a: android.view.WindowInsetsAnimation) {
                if (a.typeMask and WindowInsets.Type.ime() != 0) animatingIme = true
            }

            override fun onProgress(insets: WindowInsets, running: MutableList<android.view.WindowInsetsAnimation>): WindowInsets {
                if (animatingIme) {
                    ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                    pad()
                }
                return insets
            }

            override fun onEnd(a: android.view.WindowInsetsAnimation) {
                if (a.typeMask and WindowInsets.Type.ime() != 0) {
                    animatingIme = false
                    root.rootWindowInsets?.let {
                        ime = it.getInsets(WindowInsets.Type.ime()).bottom
                        pad()
                    }
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        })

        // Drag the top of the sheet down to close it.
        var downY = 0f
        var dragging = false
        var vt: android.view.VelocityTracker? = null
        top.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    dragging = false
                    vt?.recycle()
                    vt = android.view.VelocityTracker.obtain()
                    vt?.addMovement(e)
                    s.animate().cancel()
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(e)
                    val dy = e.rawY - downY
                    if (dy > dp(6)) dragging = true
                    s.translationY = if (dy > 0) dy else dy * 0.15f
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    vt?.addMovement(e)
                    vt?.computeCurrentVelocity(1000)
                    val v = vt?.yVelocity ?: 0f
                    vt?.recycle()
                    vt = null
                    if (dragging && (s.translationY > s.height * 0.25f || v > 1200f)) close() else s.animate().translationY(0f).setDuration(380L).setInterpolator(Springy(0.4f)).start()
                }
            }
            true
        }

        // In: the sheet springs up, then everything inside follows.
        s.translationY = resources.displayMetrics.heightPixels * 0.7f
        s.animate().translationY(0f).setInterpolator(Springy(0.3f)).setDuration(460L).start()
        for ((i, v) in anim.withIndex()) {
            v.alpha = 0f
            v.translationY = dpf(18f)
            v.animate().alpha(1f).translationY(0f).setStartDelay(90L + i * 32L).setDuration(380L).setInterpolator(Springy(0.35f)).start()
        }
        for ((i, t) in tiles.withIndex()) {
            t.alpha = 0f
            t.scaleX = 0.6f
            t.scaleY = 0.6f
            t.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(160L + i * 45L).setDuration(420L).setInterpolator(Springy(0.55f)).start()
        }
        return root
    }

    private fun bubble(m: Msg, mine: Boolean, pic: android.graphics.Bitmap? = null): View {
        val r = dpf(20f)
        val tail = dpf(6f)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(9), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(if (mine) Colors.INK else bubbleColor)
                // A small "tail" corner on the speaker's side.
                cornerRadii = if (mine) floatArrayOf(r, r, r, r, tail, tail, r, r) else floatArrayOf(r, r, r, r, r, r, tail, tail)
                if (!mine) setStroke(dp(1), Color.argb(22, 255, 255, 255))
            }
        }
        if (!m.sender.isNullOrEmpty() && !mine) {
            box.addView(label(m.sender, 12.5f, senderColor(m.sender), true).apply { setPadding(0, 0, 0, dp(3)) })
        }
        // A photo in the message: shown right here; tap to see it full screen.
        if (m.uri != null && (m.mime?.startsWith("image/") != false)) {
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = pill(Color.argb(40, 255, 255, 255), dpf(14f), stroke = false)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: Outline) {
                        outline.setRoundRect(0, 0, v.width, v.height, dpf(14f))
                    }
                }
                clipToOutline = true
            }
            box.addView(img, LinearLayout.LayoutParams(dp(230), dp(230)).apply { bottomMargin = dp(6) })
            io.execute {
                val bmp = loadImage(m.uri)
                ui.post {
                    if (bmp != null) {
                        img.setImageBitmap(bmp)
                        img.alpha = 0f
                        img.scaleX = 1.06f
                        img.scaleY = 1.06f
                        img.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300L).start()
                        img.setOnClickListener { viewFull(bmp) }
                    } else {
                        img.visibility = View.GONE
                        box.addView(label("📷 Photo (open the chat to see it)", 13f, text2, false), if (box.childCount > 1) 1 else 0)
                    }
                }
            }
        }
        if (pic != null) box.addView(photo(pic), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)).apply { bottomMargin = if (m.text.isNotBlank()) dp(6) else 0 })
        if (m.text.isNotBlank()) {
            box.addView(label(m.text, 15.5f, if (mine) Color.BLACK else text1, false).apply { setLineSpacing(0f, 1.12f) })
            // Hold a message to copy it.
            box.setOnLongClickListener { v ->
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("message", m.text))
                v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).withEndAction {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(Springy(0.6f)).start()
                }.start()
                if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                true
            }
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (mine) Gravity.END else Gravity.START
        }
        wrap.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (mine) marginStart = dp(56) else marginEnd = dp(56)
        })
        wrap.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        }
        return wrap
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
        val r = window.decorView as? ViewGroup ?: return
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

    /** The picture attached to a notification (a post's photo, a big-picture alert), if any. */
    @Suppress("DEPRECATION")
    private fun picture(ex: Bundle): android.graphics.Bitmap? {
        (ex.get(Notification.EXTRA_PICTURE) as? android.graphics.Bitmap)?.let { return it }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            (ex.get(Notification.EXTRA_PICTURE_ICON) as? android.graphics.drawable.Icon)?.let { ic ->
                try {
                    return HaloNotificationListener.drawableToBitmap(ic.loadDrawable(this))
                } catch (_: Exception) {
                }
            }
        }
        return null
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
        currentFocus?.let { getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(it.windowToken, 0) }
        s.animate().translationY(s.height.toFloat() + dp(40)).setDuration(200L).setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f)).withEndAction {
            finish()
        }.start()
        Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) finish() }, 450L)
    }

    companion object {
        const val EXTRA_KEY = "key"
    }
}
