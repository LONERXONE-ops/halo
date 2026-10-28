package com.lonerxone.halo

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Choose which apps may show on the island (banking apps start switched off). */
class AppsActivity : Activity() {

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    @Suppress("UseSwitchCompatOrMaterialCode")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val night = true
        val text1 = Color.rgb(245, 245, 247)
        val text2 = Color.rgb(150, 150, 158)
        val bgTop = Color.BLACK
        val bgBottom = Color.BLACK

        val prefs = Prefs.get(this)
        val blocked = Prefs.blocked(this).toMutableSet()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(24), dp(12), dp(24))
        }
        col.addView(TextView(this).apply {
            text = "Apps on the island"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTextColor(text1)
            setPadding(dp(14), dp(40), dp(14), dp(6))
        })
        col.addView(TextView(this).apply {
            text = "Switched-off apps never pop up or appear in the panel."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(text2)
            setPadding(dp(14), 0, dp(14), dp(18))
        })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.rgb(18, 18, 20))
                setStroke(dp(1), Color.argb(22, 255, 255, 255))
                cornerRadius = dp(26).toFloat()
            }
            setPadding(0, dp(6), 0, dp(6))
        }

        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

        for (info in apps) {
            val pkg = info.packageName
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(10), dp(16), dp(10))
            }
            row.addView(ImageView(this).apply { setImageDrawable(pm.getApplicationIcon(info)) }, LinearLayout.LayoutParams(dp(34), dp(34)))
            row.addView(TextView(this).apply {
                text = pm.getApplicationLabel(info)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(text1)
                setPadding(dp(14), 0, dp(8), 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val sw = Switch(this).apply {
                isChecked = pkg !in blocked
                setOnCheckedChangeListener { _, on ->
                    if (on) blocked.remove(pkg) else blocked.add(pkg)
                    prefs.edit().putStringSet(Prefs.BLOCKED, HashSet(blocked)).apply()
                }
            }
            row.addView(sw)
            row.setOnClickListener { sw.toggle() }
            card.addView(row)
        }
        col.addView(card)

        val scroll = ScrollView(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(bgTop, bgBottom))
            addView(col)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        }
        setContentView(scroll)
        window.decorView.setBackgroundColor(bgBottom)
        if (!night) {
            window.insetsController?.setSystemBarsAppearance(
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            )
        }
    }
}
