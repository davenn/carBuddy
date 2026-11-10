package com.davenn.carbuddy

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent

/**
 * Optional touch control: replays touches on carBuddy's car screen on the
 * phone, and presses Back/Home. An accessibility service is the only way a
 * regular app can do this, and the user switches it on once in Settings.
 *
 * It reads nothing from the screen and handles no accessibility events.
 */
class TouchService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------------------------------------------------------------- strokes
    //
    // A finger on the car screen becomes one continuing gesture stroke on the
    // phone, sent a short segment at a time: each segment goes out when the
    // previous one finishes, heading to wherever the finger is by then. A quick
    // tap becomes a ~30 ms stroke, a held finger a long-press, a drag a swipe.
    //
    // Each gesture gets a generation number, so callbacks from a gesture that a
    // newer one has replaced are ignored.

    private var stroke: GestureDescription.StrokeDescription? = null
    private var fingerX = 0f
    private var fingerY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var inFlight = false
    private var ending = false
    private var generation = 0

    /** Finger down at (x, y) in phone-screen pixels. */
    fun begin(x: Float, y: Float) {
        generation++
        fingerX = x
        fingerY = y
        targetX = x
        targetY = y
        ending = false
        val path = Path().apply { moveTo(x, y) }
        send(GestureDescription.StrokeDescription(path, 0, SEGMENT_MS, true))
    }

    /** Finger moved to (x, y). */
    fun moveTo(x: Float, y: Float) {
        targetX = x
        targetY = y
    }

    /** Finger lifted. */
    fun end() {
        if (stroke == null) return
        ending = true
        if (!inFlight) sendNext()
    }

    private fun sendNext() {
        val prev = stroke ?: return
        val path = Path().apply {
            moveTo(fingerX, fingerY)
            if (targetX != fingerX || targetY != fingerY) lineTo(targetX, targetY)
        }
        fingerX = targetX
        fingerY = targetY
        if (ending) {
            stroke = null
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(prev.continueStroke(path, 0, SEGMENT_MS, false)).build(),
                null, null,
            )
        } else {
            send(prev.continueStroke(path, 0, SEGMENT_MS, true))
        }
    }

    private fun send(s: GestureDescription.StrokeDescription) {
        val gen = generation
        stroke = s
        inFlight = true
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(s).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (gen != generation) return
                    inFlight = false
                    sendNext()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (gen != generation) return
                    inFlight = false
                    stroke = null
                }
            },
            null,
        )
        if (!ok) {
            inFlight = false
            stroke = null
        }
    }

    companion object {
        private const val SEGMENT_MS = 16L

        /** The running service, or null when touch control is switched off. */
        @Volatile
        var instance: TouchService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = "${context.packageName}/${TouchService::class.java.name}"
            val short = "${context.packageName}/.${TouchService::class.java.simpleName}"
            return enabled.split(':').any { it.equals(me, true) || it.equals(short, true) }
        }

        /** The phone screen's current size in pixels, following rotation. */
        fun phoneScreenSize(context: Context): Point {
            val d = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            @Suppress("DEPRECATION")
            return Point().also { d.getRealSize(it) }
        }
    }
}
