package com.davenn.carbuddy

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Phone-side setup screen: Shizuku status, the permission grant, and a preview
 * that runs the same virtual display inside this screen, so the privileged part
 * can be tested without a car or the Desktop Head Unit.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var grantButton: Button
    private lateinit var previewButton: Button
    private lateinit var openAppButton: Button
    private lateinit var previewInfo: TextView
    private lateinit var surfaceView: SurfaceView

    private var previewDisplayId = -1
    private var surfaceReady = false

    private val bridgeListener: () -> Unit = { runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        ShizukuBridge.addListener(bridgeListener)
        ShizukuBridge.bindIfAllowed()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        ShizukuBridge.bindIfAllowed()
        refresh()
    }

    override fun onDestroy() {
        ShizukuBridge.removeListener(bridgeListener)
        stopPreview()
        super.onDestroy()
    }

    private fun refresh() {
        val state = ShizukuBridge.state()
        statusView.text = "Shizuku: " + ShizukuBridge.describe(state)
        statusView.setTextColor(
            if (state == ShizukuBridge.State.READY) Color.rgb(0x2E, 0x9E, 0x4F) else Color.rgb(0xD9, 0x6C, 0x00)
        )
        grantButton.visibility = when (state) {
            ShizukuBridge.State.NEEDS_PERMISSION, ShizukuBridge.State.NOT_RUNNING -> View.VISIBLE
            else -> View.GONE
        }
        grantButton.text = if (state == ShizukuBridge.State.NOT_RUNNING) "Get Shizuku" else "Grant Shizuku access"
        previewButton.isEnabled = state == ShizukuBridge.State.READY && surfaceReady
        previewButton.text = if (previewDisplayId >= 0) "Stop preview" else "Start preview"
        openAppButton.isEnabled = previewDisplayId >= 0
    }

    // ---------------------------------------------------------------- preview

    private fun togglePreview() {
        if (previewDisplayId >= 0) {
            stopPreview()
            refresh()
            return
        }
        val svc = ShizukuBridge.service ?: return
        val holder = surfaceView.holder
        val w = surfaceView.width
        val h = surfaceView.height
        // A car-like density, so apps lay out roughly as they will in the car.
        val dpi = 160
        try {
            val id = svc.createDisplay(holder.surface, w, h, dpi)
            if (id >= 0) {
                previewDisplayId = id
                val note = svc.lastError()
                previewInfo.text = "Display $id, ${w}×$h at $dpi dpi. Tap and drag inside it." +
                    if (note.isNotEmpty()) "\n$note" else ""
            } else {
                previewInfo.text = svc.lastError()
            }
        } catch (e: Exception) {
            previewInfo.text = "Display service error: ${e.message}"
        }
        refresh()
    }

    private fun stopPreview() {
        if (previewDisplayId < 0) return
        try { ShizukuBridge.service?.releaseDisplay() } catch (_: Exception) {}
        previewDisplayId = -1
        previewInfo.text = "Preview stopped."
    }

    private fun pickApp() {
        val apps = Apps.list(this)
        AlertDialog.Builder(this)
            .setTitle("Open on the preview display")
            .setItems(apps.map { it.label }.toTypedArray()) { _, i ->
                val app = apps[i]
                val out = try {
                    ShizukuBridge.service?.launch(app.component) ?: "Not connected"
                } catch (e: Exception) {
                    "Launch failed: ${e.message}"
                }
                Apps.markOpened(this, app)
                if (out.contains("Error") || out.contains("Exception") || out.contains("Warning")) {
                    previewInfo.text = out
                }
            }
            .show()
    }

    /** Forwards single-finger touches on the preview to the virtual display. */
    private fun forwardTouch(e: MotionEvent): Boolean {
        if (previewDisplayId < 0) return false
        val action = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> e.actionMasked
            MotionEvent.ACTION_CANCEL -> MotionEvent.ACTION_UP
            else -> return true
        }
        try {
            ShizukuBridge.service?.touch(action, e.x, e.y, e.downTime)
        } catch (_: Exception) {}
        return true
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

        statusView = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(8), 0, dp(8))
        }
        column.addView(statusView)

        grantButton = Button(this).apply {
            setOnClickListener {
                if (ShizukuBridge.state() == ShizukuBridge.State.NOT_RUNNING) {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
                    )
                } else {
                    ShizukuBridge.requestPermission()
                }
            }
        }
        column.addView(grantButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        column.addView(TextView(this).apply {
            text = SETUP
            textSize = 14f
            setPadding(0, dp(12), 0, dp(12))
        })

        column.addView(TextView(this).apply {
            text = "Preview on this phone"
            textSize = 18f
            setPadding(0, dp(8), 0, dp(4))
        })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        previewButton = Button(this).apply { setOnClickListener { togglePreview() } }
        openAppButton = Button(this).apply {
            text = "Open app…"
            setOnClickListener { pickApp() }
        }
        buttons.addView(previewButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        buttons.addView(openAppButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        column.addView(buttons)

        previewInfo = TextView(this).apply {
            textSize = 13f
            text = "Shows a car-shaped virtual display here, to check the Shizuku side works."
            setPadding(0, dp(4), 0, dp(8))
        }
        column.addView(previewInfo)

        surfaceView = SurfaceView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    surfaceReady = true
                    refresh()
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    surfaceReady = false
                    stopPreview()
                    refresh()
                }
            })
            setOnTouchListener { v, e ->
                // Keep the ScrollView from stealing drags meant for the preview.
                if (previewDisplayId >= 0) v.parent.requestDisallowInterceptTouchEvent(true)
                forwardTouch(e)
            }
        }
        // Wide car-screen shape (roughly 1920×720).
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        frame.addView(surfaceView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        column.addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0).also {
            it.height = (resources.displayMetrics.widthPixels - 2 * pad) * 3 / 8
        })

        return ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val SETUP = """Setup
1. Install and start Shizuku (Wireless debugging mode). Restart it after each phone reboot.
2. Grant carBuddy access above.
3. Android Auto: Settings → tap Version about 10 times → ⋮ → Developer settings → turn on Unknown sources.
4. Connect to the car and open carBuddy from the app launcher. Use the grid button to pick an app."""
    }
}
