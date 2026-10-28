package com.lonerxone.halo

import android.app.Activity
import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.content.res.Configuration
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
        night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        cardColor = if (night) Color.rgb(23, 23, 23) else Color.rgb(252, 252, 252)
        bubbleColor = if (night) Color.rgb(44, 44, 48) else Color.rgb(238, 238, 242)
        text1 = if (night) Color.rgb(250, 250, 250) else Color.rgb(1, 1, 1)
        text2 = if (night) Color.rgb(158, 158, 158) else Color.rgb(115, 115, 115)
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
        val title = listener.titleOf(ex).ifEmpty { app }
        val icon = listener.iconBitmap(n.getLargeIcon()) ?: listener.appIcon(sbn.packageName)
        val accent = if (n.color != Notification.COLOR_DEFAULT && Color.alpha(n.color) > 0) {
            HaloNotificationListener.brighten(n.color)
        } else {
            Colors.BLUE
        }
        val replyAction = n.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
        val readAction = n.actions?.firstOrNull { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            t.contains("read") && a.remoteInputs.isNullOrEmpty()
        }

        val root = FrameLayout(this).apply {
            setOnClickListener { close() }
        }

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
        } else {
            for ((sender, text) in messages) list.addView(bubble(sender, text, mine = false))
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
        val maxH = (resources.displayMetrics.heightPixels * 0.42f).toInt()
        s.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, minOf(list.measuredHeight, maxH)))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

        // Reply box
        if (replyAction != null) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
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
                setColorFilter(Color.WHITE)
                val pad = dp(11)
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accent)
                }
            }
            val doSend = {
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    if (sendReply(replyAction, text)) {
                        list.addView(bubble(null, text, mine = true))
                        input.setText("")
                        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                        Toast.makeText(this, "Sent", Toast.LENGTH_SHORT).show()
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

        // Actions
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        fun action(textLabel: String, onClick: () -> Unit) {
            actions.addView(Button(this).apply {
                text = textLabel
                isAllCaps = false
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
        }
        action("Open") {
            try {
                n.contentIntent?.send()
            } catch (_: Exception) {
            }
            close()
        }
        if (readAction != null) {
            action("Mark as read") {
                try {
                    readAction.actionIntent?.send()
                } catch (_: Exception) {
                }
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
        s.animate().translationY(0f).setInterpolator(DecelerateInterpolator(2f)).setDuration(280L).start()
        return root
    }

    private fun bubble(sender: String?, text: CharSequence, mine: Boolean): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(9), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(if (mine) Colors.BLUE else bubbleColor)
                cornerRadius = dp(18).toFloat()
            }
        }
        if (!sender.isNullOrEmpty() && !mine) {
            box.addView(label(sender, 12f, text2, true).apply { setPadding(0, 0, 0, dp(2)) })
        }
        box.addView(label(text, 15f, if (mine) Color.WHITE else text1, false))
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
        return wrap
    }

    /** Last few lines of a chat notification (MessagingStyle), oldest first. */
    @Suppress("DEPRECATION")
    private fun messages(ex: Bundle): List<Pair<String?, CharSequence>> {
        val arr = ex.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
        return arr.mapNotNull { p ->
            val b = p as? Bundle ?: return@mapNotNull null
            val text = b.getCharSequence("text") ?: return@mapNotNull null
            b.getCharSequence("sender")?.toString() to text
        }.takeLast(12)
    }

    private fun sendReply(action: Notification.Action, text: String): Boolean = try {
        val inputs = action.remoteInputs ?: emptyArray()
        val data = Intent()
        val results = Bundle()
        for (ri in inputs) results.putCharSequence(ri.resultKey, text)
        RemoteInput.addResultsToIntent(inputs, data, results)
        action.actionIntent.send(this, 0, data)
        true
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
        s.animate().translationY(s.height.toFloat() + dp(40)).setDuration(200L).withEndAction {
            finish()
        }.start()
        Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) finish() }, 400L)
    }

    companion object {
        const val EXTRA_KEY = "key"
    }
}
