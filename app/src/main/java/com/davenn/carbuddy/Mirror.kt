package com.davenn.carbuddy

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Surface
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The screen mirror: one MediaProjection and the virtual display it feeds.
 *
 * The display is created as soon as the user approves screen capture, with no
 * surface yet. Android Auto's surface is attached when the car screen appears
 * and detached when it goes away, without stopping the capture, so switching
 * apps on the car screen does not ask for permission again. (Since Android 14
 * one approval allows exactly one createVirtualDisplay call, so the display is
 * kept and only its surface changes.)
 *
 * The display mirrors the phone's whole screen, scaled to fit the car screen
 * with black bars where the shapes differ. All calls are on the main thread.
 */
object Mirror {

    var projection: MediaProjection? = null
        private set
    private var display: VirtualDisplay? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var surface: Surface? = null
    var width = 0
        private set
    var height = 0
        private set
    private var dpi = 160

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    val isRunning: Boolean get() = display != null

    fun start(context: Context, mp: MediaProjection) {
        stop()
        projection = mp
        // Android 14+ requires a callback to be registered before capturing.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // The user stopped sharing from the status bar, or the system did.
                if (projection === mp) cleanup()
            }
        }, main)
        display = mp.createVirtualDisplay(
            "carBuddy",
            width.coerceAtLeast(1), height.coerceAtLeast(1), dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, main,
        )
        // The car shows a copy of the phone screen, so it goes black whenever the
        // phone screen turns off. Hold it on while mirroring.
        @Suppress("DEPRECATION")
        wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "carBuddy:mirror")
            .apply { acquire() }
        notifyListeners()
    }

    /** Points the mirror at the car's surface. */
    fun attach(s: Surface, w: Int, h: Int, density: Int) {
        surface = s
        width = w
        height = h
        dpi = density
        display?.let {
            it.resize(w, h, density)
            it.surface = s
        }
        notifyListeners()
    }

    fun detach() {
        surface = null
        display?.surface = null
        notifyListeners()
    }

    fun stop() {
        val mp = projection
        cleanup()
        mp?.stop()
    }

    private fun cleanup() {
        display?.release()
        display = null
        projection = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        notifyListeners()
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun notifyListeners() = listeners.forEach { it() }
}
