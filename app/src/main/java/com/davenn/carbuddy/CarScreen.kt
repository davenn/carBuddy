package com.davenn.carbuddy

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * carBuddy's screen on the car display.
 *
 * Android Auto runs carBuddy as a "parked app" on its own display at the car
 * screen's size. This screen fills it with a SurfaceView that [Mirror] draws the
 * phone's screen into, scaled to fit and centred. Touches on the picture are
 * mapped to phone coordinates and replayed by [TouchService]; buttons down the
 * sides sit in the black bars a portrait phone leaves.
 */
class CarScreen(private val activity: Activity) {

    private val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
    private val surfaceView = SurfaceView(activity)
    private lateinit var startPanel: LinearLayout
    private lateinit var appsPanel: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var notice: TextView

    private var viewW = 0
    private var viewH = 0
    private var gestureOnPicture = false

    private val mirrorListener: () -> Unit = { activity.runOnUiThread { refresh() } }

    fun create(): View {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                viewW = w
                viewH = h
                Mirror.attach(holder.surface, w, h, activity.resources.displayMetrics.densityDpi)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                TouchService.instance?.end()
                Mirror.detach()
            }
        })
        surfaceView.setOnTouchListener { _, e -> onTouch(e) }
        root.addView(surfaceView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        controls = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(iconButton(R.drawable.ic_back, "Back") { globalAction(AccessibilityService.GLOBAL_ACTION_BACK) })
            addView(iconButton(R.drawable.ic_home, "Home") { globalAction(AccessibilityService.GLOBAL_ACTION_HOME) })
            addView(iconButton(R.drawable.ic_apps, "Apps") { showApps(true) })
            addView(iconButton(R.drawable.ic_stop, "Stop mirroring") { ProjectionService.stop(activity) })
        }
        root.addView(controls, FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT, Gravity.START).also {
            it.marginStart = dp(12)
        })

        startPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(TextView(activity).apply {
                text = "Show your phone on this screen"
                textSize = 26f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            })
            addView(TextView(activity).apply {
                text = "Press Start, then tap Start now on your phone."
                textSize = 18f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(20))
            })
            addView(Button(activity).apply {
                text = "Start"
                textSize = 22f
                setOnClickListener { ProjectionActivity.request(activity) }
            }, LinearLayout.LayoutParams(dp(260), WRAP_CONTENT))
        }
        root.addView(startPanel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        appsPanel = buildAppsPanel()
        root.addView(appsPanel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        notice = TextView(activity).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = rounded(0xCC333333.toInt())
            visibility = View.GONE
        }
        root.addView(notice, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).also { it.bottomMargin = dp(16) })

        Mirror.addListener(mirrorListener)
        refresh()
        return root
    }

    fun destroy() {
        Mirror.removeListener(mirrorListener)
        // carBuddy's car screen is gone (car disconnected or app closed): stop
        // capturing so the phone is not left mirroring with its screen held on.
        ProjectionService.stop(activity)
    }

    private fun refresh() {
        val on = Mirror.isRunning
        startPanel.visibility = if (on) View.GONE else View.VISIBLE
        controls.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) appsPanel.visibility = View.GONE
    }

    // ------------------------------------------------------------------ touch

    private fun onTouch(e: MotionEvent): Boolean {
        if (!Mirror.isRunning) return true
        val t = TouchService.instance
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (t == null) {
                    flash("Touch control is off. Turn it on in carBuddy on your phone.")
                    return true
                }
                val p = toPhone(e.x, e.y)
                gestureOnPicture = p != null
                if (p != null) t.begin(p.first, p.second)
            }
            MotionEvent.ACTION_MOVE -> if (gestureOnPicture) {
                toPhone(e.x, e.y, clamp = true)?.let { t?.moveTo(it.first, it.second) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (gestureOnPicture) {
                toPhone(e.x, e.y, clamp = true)?.let { t?.moveTo(it.first, it.second) }
                t?.end()
                gestureOnPicture = false
            }
        }
        return true
    }

    /**
     * Car-screen point to phone-screen point. Android scales a mirrored display
     * to fit and centres it, so this inverts that. Returns null for a point in
     * the black bars unless [clamp] is set, which pins it to the picture's edge.
     */
    private fun toPhone(x: Float, y: Float, clamp: Boolean = false): Pair<Float, Float>? {
        val phone = TouchService.phoneScreenSize(activity)
        if (viewW <= 0 || viewH <= 0 || phone.x <= 0 || phone.y <= 0) return null
        val scale = minOf(viewW / phone.x.toFloat(), viewH / phone.y.toFloat())
        val px = (x - (viewW - phone.x * scale) / 2f) / scale
        val py = (y - (viewH - phone.y * scale) / 2f) / scale
        if (clamp) return px.coerceIn(0f, phone.x - 1f) to py.coerceIn(0f, phone.y - 1f)
        if (px < 0 || py < 0 || px >= phone.x || py >= phone.y) return null
        return px to py
    }

    private fun globalAction(action: Int) {
        val t = TouchService.instance
        if (t == null) flash("Back and Home need touch control. Turn it on in carBuddy on your phone.")
        else t.performGlobalAction(action)
    }

    // ------------------------------------------------------------------- apps

    private val apps = mutableListOf<LaunchableApp>()

    private val appsAdapter = object : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(i: Int) = apps[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val row = (convert as? LinearLayout) ?: LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                addView(ImageView(activity), LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(TextView(activity).apply {
                    textSize = 20f
                    setTextColor(Color.WHITE)
                    setPadding(dp(16), 0, 0, 0)
                })
            }
            val app = apps[i]
            (row.getChildAt(0) as ImageView).setImageDrawable(
                try { activity.packageManager.getApplicationIcon(app.packageName) } catch (_: Exception) { null }
            )
            (row.getChildAt(1) as TextView).text = app.label
            return row
        }
    }

    /** A list of phone apps; each opens on the phone's own screen, which the mirror shows. */
    private fun buildAppsPanel(): LinearLayout {
        val list = ListView(activity).apply {
            adapter = appsAdapter
            setOnItemClickListener { _, _, i, _ -> openOnPhone(apps[i]) }
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0101010.toInt())
            visibility = View.GONE
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(16), dp(8))
                addView(iconButton(R.drawable.ic_back, "Close") { showApps(false) })
                addView(TextView(activity).apply {
                    text = "Open on your phone"
                    textSize = 22f
                    setTextColor(Color.WHITE)
                    setPadding(dp(12), 0, 0, 0)
                })
            })
            addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
    }

    private fun showApps(show: Boolean) {
        if (show) {
            apps.clear()
            apps += Apps.list(activity)
            appsAdapter.notifyDataSetChanged()
        }
        appsPanel.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun openOnPhone(app: LaunchableApp) {
        showApps(false)
        val intent = activity.packageManager.getLaunchIntentForPackage(app.packageName)
        if (intent == null) {
            flash("${app.label} cannot be opened.")
            return
        }
        try {
            // The phone's display, not the car's: Android Auto blocks other apps
            // here, and the mirror shows the phone anyway.
            activity.startActivity(
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(),
            )
            Apps.markOpened(activity, app)
        } catch (e: Exception) {
            flash("Could not open ${app.label}: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun iconButton(icon: Int, label: String, onClick: () -> Unit) = ImageButton(activity).apply {
        setImageResource(icon)
        contentDescription = label
        background = rounded(0x99333333.toInt(), dp(32).toFloat())
        val pad = dp(14)
        setPadding(pad, pad, pad, pad)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).also { it.setMargins(0, dp(6), 0, dp(6)) }
    }

    private fun rounded(color: Int, radius: Float = dp(12).toFloat()) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private val hideNotice = Runnable { notice.visibility = View.GONE }

    private fun flash(text: String) {
        notice.text = text
        notice.visibility = View.VISIBLE
        notice.removeCallbacks(hideNotice)
        notice.postDelayed(hideNotice, 4000)
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
}
