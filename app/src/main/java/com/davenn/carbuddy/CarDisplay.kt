package com.davenn.carbuddy

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Owns the link between Android Auto's map Surface and the virtual display.
 *
 * Android Auto gives a navigation app one Surface covering the car screen. When it
 * arrives, [DisplayService] creates a virtual display that renders straight into
 * it, so whatever runs on that display is what the car shows. Touches the car
 * reports on the surface are injected back into the same display.
 */
class CarDisplay(private val carContext: CarContext) : SurfaceCallback, DefaultLifecycleObserver {

    var displayId = -1
        private set
    var status: String = "Waiting for the car screen…"
        private set

    /** Called on the main thread whenever [status] or [displayId] changes. */
    var onChange: (() -> Unit)? = null

    private var container: SurfaceContainer? = null
    private val main = Handler(Looper.getMainLooper())
    private val bridgeListener: () -> Unit = { tryCreate() }

    fun attach() {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        ShizukuBridge.addListener(bridgeListener)
        ShizukuBridge.bindIfAllowed()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        ShizukuBridge.removeListener(bridgeListener)
        release()
    }

    // --------------------------------------------------------- SurfaceCallback

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        container = surfaceContainer
        displayId = -1
        tryCreate()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        container = null
        release()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {}
    override fun onStableAreaChanged(stableArea: Rect) {}

    /** A tap on the car screen (car API level 5+), in surface pixels. */
    override fun onClick(x: Float, y: Float) {
        val svc = ShizukuBridge.service ?: return
        if (displayId < 0) return
        endDrag()
        try {
            val down = SystemClock.uptimeMillis()
            svc.touch(MotionEvent.ACTION_DOWN, x, y, down)
            svc.touch(MotionEvent.ACTION_UP, x, y, down)
        } catch (_: Exception) {}
    }

    /**
     * A drag on the car screen. Android Auto only reports how far the finger
     * moved since the last event, not where it is, so a virtual finger is put
     * down in the middle of the screen and moved by the same amounts. That is
     * enough to scroll lists and pages. The finger lifts once events stop.
     */
    override fun onScroll(distanceX: Float, distanceY: Float) {
        val svc = ShizukuBridge.service ?: return
        val c = container ?: return
        if (displayId < 0) return
        try {
            if (dragDownTime == 0L) {
                dragDownTime = SystemClock.uptimeMillis()
                dragX = c.width / 2f
                dragY = c.height / 2f
                svc.touch(MotionEvent.ACTION_DOWN, dragX, dragY, dragDownTime)
            }
            // distance = previous position - new position, as in GestureDetector.
            dragX = (dragX - distanceX).coerceIn(0f, c.width - 1f)
            dragY = (dragY - distanceY).coerceIn(0f, c.height - 1f)
            svc.touch(MotionEvent.ACTION_MOVE, dragX, dragY, dragDownTime)
        } catch (_: Exception) {}
        main.removeCallbacks(liftFinger)
        main.postDelayed(liftFinger, DRAG_END_MS)
    }

    private var dragDownTime = 0L
    private var dragX = 0f
    private var dragY = 0f
    private val liftFinger = Runnable { endDrag() }

    private fun endDrag() {
        main.removeCallbacks(liftFinger)
        if (dragDownTime == 0L) return
        try {
            ShizukuBridge.service?.touch(MotionEvent.ACTION_UP, dragX, dragY, dragDownTime)
        } catch (_: Exception) {}
        dragDownTime = 0L
    }

    // ----------------------------------------------------------------- actions

    fun launch(app: LaunchableApp): String? {
        val svc = ShizukuBridge.service ?: return ShizukuBridge.describe()
        if (displayId < 0) return status
        return try {
            val out = svc.launch(app.component)
            Apps.markOpened(carContext, app)
            // `am start` prints "Error:"/"Exception" lines on failure, and a
            // "Warning:" when it only brought an existing task forward — which
            // usually means the app is open on the phone and stayed there.
            out.lines().firstOrNull {
                it.contains("Error") || it.contains("Exception") || it.startsWith("Warning")
            }
        } catch (e: Exception) {
            "Launch failed: ${e.message}"
        }
    }

    fun key(keyCode: Int) {
        if (displayId < 0) return
        try { ShizukuBridge.service?.key(keyCode) } catch (_: Exception) {}
    }

    fun retry() {
        ShizukuBridge.bindIfAllowed()
        tryCreate()
    }

    // ---------------------------------------------------------------- internal

    private fun tryCreate() {
        val c = container
        val surface = c?.surface
        val svc = ShizukuBridge.service
        when {
            c == null || surface == null -> setStatus("Waiting for the car screen…")
            svc == null -> setStatus(ShizukuBridge.describe())
            displayId >= 0 -> return
            else -> try {
                val id = svc.createDisplay(surface, c.width, c.height, c.dpi)
                if (id >= 0) {
                    displayId = id
                    val note = svc.lastError()
                    setStatus(if (note.isEmpty()) "Running" else note)
                } else {
                    setStatus(svc.lastError().ifEmpty { "Could not create the display." })
                }
            } catch (e: Exception) {
                setStatus("Display service error: ${e.message}")
            }
        }
    }

    private fun release() {
        endDrag()
        if (displayId >= 0) {
            try { ShizukuBridge.service?.releaseDisplay() } catch (_: Exception) {}
        }
        displayId = -1
        setStatus("Waiting for the car screen…")
    }

    private fun setStatus(s: String) {
        status = s
        onChange?.invoke()
    }

    private companion object {
        const val DRAG_END_MS = 150L
    }
}
