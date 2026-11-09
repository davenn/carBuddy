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
 * Optional touch control: replays taps and drags from the car screen on the
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

    fun tap(x: Float, y: Float) {
        endDrag()
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS))
                .build(),
            null, null,
        )
    }

    // ------------------------------------------------------------------ drags
    //
    // Android Auto reports a drag as a series of "moved by" amounts with no start
    // position, so the finger is put down mid-screen and moved by those amounts.
    // A gesture is sent as one continuing stroke, one segment at a time: each new
    // segment goes out when the previous one finishes, heading to wherever the
    // finger should be by then, so the stroke never falls behind the car.

    private var stroke: GestureDescription.StrokeDescription? = null
    private var fingerX = 0f
    private var fingerY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var inFlight = false
    private var ending = false

    fun dragBy(dx: Float, dy: Float, startX: Float, startY: Float, maxX: Float, maxY: Float) {
        if (stroke == null && !inFlight) {
            fingerX = startX
            fingerY = startY
            targetX = startX
            targetY = startY
            ending = false
            val path = Path().apply { moveTo(startX, startY) }
            send(GestureDescription.StrokeDescription(path, 0, SEGMENT_MS, true))
        }
        targetX = (targetX + dx).coerceIn(0f, maxX)
        targetY = (targetY + dy).coerceIn(0f, maxY)
    }

    fun endDrag() {
        if (stroke == null && !inFlight) return
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
        val willContinue = !ending
        val next = prev.continueStroke(path, 0, SEGMENT_MS, willContinue)
        if (willContinue) send(next) else {
            stroke = null
            dispatchGesture(GestureDescription.Builder().addStroke(next).build(), null, null)
        }
    }

    private fun send(s: GestureDescription.StrokeDescription) {
        stroke = s
        inFlight = true
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(s).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    inFlight = false
                    sendNext()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
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
        private const val TAP_MS = 40L
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
