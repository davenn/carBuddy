package com.davenn.carbuddy

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * carBuddy's only activity, with two faces:
 *  - on the car screen (Android Auto runs it there as a parked app, on its own
 *    display), it is the mirror itself: see [CarScreen];
 *  - on the phone, it is setup: whether mirroring and touch control are on, with
 *    buttons that open the right Settings pages.
 */
class MainActivity : Activity() {

    private var carScreen: CarScreen? = null

    private lateinit var mirrorStatus: TextView
    private lateinit var mirrorButton: Button
    private lateinit var touchStatus: TextView
    private lateinit var touchButton: Button
    private lateinit var restrictedButton: Button

    private val mirrorListener: () -> Unit = { runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (onCarScreen()) {
            carScreen = CarScreen(this).also { setContentView(it.create()) }
            return
        }
        setContentView(buildUi())
        Mirror.addListener(mirrorListener)
        // Lets the "tap to start mirroring" notification show if the car cannot
        // open the capture prompt directly.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        if (carScreen == null) refresh()
    }

    override fun onDestroy() {
        carScreen?.destroy()
        Mirror.removeListener(mirrorListener)
        super.onDestroy()
    }

    @Deprecated("Back is handled here so the car screen can step back first")
    override fun onBackPressed() {
        if (carScreen?.back() == true) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    /** True when Android Auto is showing this activity on the car's display. */
    private fun onCarScreen(): Boolean {
        val d = if (Build.VERSION.SDK_INT >= 30) display else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }
        return d != null && d.displayId != Display.DEFAULT_DISPLAY
    }

    private fun refresh() {
        if (Mirror.isRunning) {
            mirrorStatus.text = "Mirroring: on"
            mirrorStatus.setTextColor(GREEN)
            mirrorButton.text = "Stop mirroring"
        } else {
            mirrorStatus.text = "Mirroring: off. Open carBuddy in the car and press Start."
            mirrorStatus.setTextColor(GREY)
            mirrorButton.text = "Start mirroring now"
        }

        val touchOn = TouchService.isEnabled(this)
        touchStatus.text = if (touchOn) "Touch control: on" else
            "Touch control: off. Optional; lets you tap and scroll on the car screen."
        touchStatus.setTextColor(if (touchOn) GREEN else GREY)
        touchButton.text = if (touchOn) "Accessibility settings" else "Turn on touch control"
        // Only sideloaded apps on Android 13+ hit "restricted setting".
        restrictedButton.visibility =
            if (!touchOn && Build.VERSION.SDK_INT >= 33) View.VISIBLE else View.GONE
    }

    // --------------------------------------------------------------------- UI

    private fun buildUi(): View {
        val pad = dp(16)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        column.addView(TextView(this).apply {
            text = "carBuddy"
            textSize = 24f
        })
        column.addView(TextView(this).apply {
            text = "Shows this phone's screen on the Android Auto car screen while parked."
            textSize = 14f
            setPadding(0, dp(4), 0, dp(16))
        })

        mirrorStatus = TextView(this).apply { textSize = 16f }
        column.addView(mirrorStatus)
        mirrorButton = Button(this).apply {
            setOnClickListener {
                if (Mirror.isRunning) ProjectionService.stop(this@MainActivity)
                else startActivity(ProjectionActivity.intent(this@MainActivity))
            }
        }
        column.addView(mirrorButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        touchStatus = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(16), 0, 0)
        }
        column.addView(touchStatus)
        touchButton = Button(this).apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        column.addView(touchButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        restrictedButton = Button(this).apply {
            text = "Says \"Restricted setting\"? Allow it here"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        }
        column.addView(restrictedButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        column.addView(TextView(this).apply {
            text = "Streaming: sign in here once and the car's Streaming screen stays signed in. Phone screen: " +
                StreamingBrowser.displaySummary(window.decorView.display ?: windowManager.defaultDisplay) + "."
            textSize = 16f
            setTextColor(GREY)
            setPadding(0, dp(16), 0, 0)
        })
        column.addView(Button(this).apply {
            text = "Sign in to streaming sites"
            setOnClickListener { startActivity(Intent(this@MainActivity, BrowserActivity::class.java)) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        column.addView(TextView(this).apply {
            text = SETUP
            textSize = 14f
            setPadding(0, dp(20), 0, 0)
        })

        return ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        val GREEN = Color.rgb(0x2E, 0x9E, 0x4F)
        val GREY = Color.rgb(0x80, 0x80, 0x80)

        const val SETUP = """Setup (once)
1. Android Auto: Settings → tap Version about 10 times → ⋮ → Developer settings → turn on Unknown sources.
2. Optional, for touch: tap "Turn on touch control", pick carBuddy and switch it on. If Android says it is a restricted setting, use the button below it, then ⋮ → Allow restricted settings, and try again.

Each time
While parked, open carBuddy from Android Auto's launcher (it may be with games or parked apps). Choose Mirror phone (then tap Start now on the phone) or Streaming.

Tips
• Turn the phone sideways (auto-rotate on) to fill the car screen.
• Use the apps button on the car screen to open phone apps; they open on the phone and show in the mirror.
• Parked only: Android Auto closes parked apps when the car moves.
• Netflix, Disney+ and similar apps show black in the mirror: they block screen capture. Use Streaming for those instead."""
    }
}
