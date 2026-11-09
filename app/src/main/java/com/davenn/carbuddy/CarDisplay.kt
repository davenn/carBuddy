package com.davenn.carbuddy

import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Owns the link between Android Auto's map Surface and the screen mirror.
 *
 * Android Auto gives a navigation app one Surface covering the car screen; it is
 * handed to [Mirror], which draws the phone's screen into it. Touches the car
 * reports on the surface are mapped back to phone-screen coordinates and replayed
 * by [TouchService] when touch control is on.
 */
class CarDisplay(private val carContext: CarContext) : SurfaceCallback, DefaultLifecycleObserver {

    /** Called on the main thread whenever what the car should show changes. */
    var onChange: (() -> Unit)? = null

    private var container: SurfaceContainer? = null
    private var askedThisSession = false
    private var warnedNoTouch = false
    private val main = Handler(Looper.getMainLooper())
    private val mirrorListener: () -> Unit = { onChange?.invoke() }

    val isMirroring: Boolean get() = Mirror.isRunning

    fun attach() {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        Mirror.addListener(mirrorListener)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        // The car disconnected: stop capturing the phone.
        Mirror.removeListener(mirrorListener)
        Mirror.detach()
        ProjectionService.stop(carContext)
    }

    // --------------------------------------------------------- SurfaceCallback

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        container = surfaceContainer
        val surface = surfaceContainer.surface ?: return
        Mirror.attach(surface, surfaceContainer.width, surfaceContainer.height, surfaceContainer.dpi)
        // Ask once automatically when the car screen first appears; after that
        // only when the Start button is pressed.
        if (!Mirror.isRunning && !askedThisSession) {
            askedThisSession = true
            ProjectionActivity.request(carContext)
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        container = null
        touch()?.endDrag()
        Mirror.detach()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {}
    override fun onStableAreaChanged(stableArea: Rect) {}

    /** A tap on the car screen (car API level 5+), in surface pixels. */
    override fun onClick(x: Float, y: Float) {
        val t = touch() ?: return
        val p = toPhone(x, y) ?: return // in the black bars
        t.tap(p.first, p.second)
    }

    /**
     * A drag on the car screen, as how far the finger moved since the last event
     * (previous minus new position, as in GestureDetector). The finger lifts once
     * events stop for [DRAG_END_MS].
     */
    override fun onScroll(distanceX: Float, distanceY: Float) {
        val t = touch() ?: return
        val g = geometry() ?: return
        val phone = TouchService.phoneScreenSize(carContext)
        t.dragBy(
            -distanceX / g.scale, -distanceY / g.scale,
            phone.x / 2f, phone.y / 2f, phone.x - 1f, phone.y - 1f,
        )
        main.removeCallbacks(liftFinger)
        main.postDelayed(liftFinger, DRAG_END_MS)
    }

    private val liftFinger = Runnable { touch()?.endDrag() }

    // ----------------------------------------------------------------- actions

    fun start() = ProjectionActivity.request(carContext)

    fun back() {
        touch()?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
    }

    fun home() {
        touch()?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
    }

    fun launch(app: LaunchableApp): String? {
        val intent = carContext.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return "${app.label} cannot be opened"
        return try {
            carContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Apps.markOpened(carContext, app)
            null
        } catch (e: Exception) {
            "Could not open ${app.label}: ${e.message}"
        }
    }

    // ---------------------------------------------------------------- internal

    /** Touch control, or null after telling the driver once that it is off. */
    private fun touch(): TouchService? {
        val t = TouchService.instance
        if (t == null && !warnedNoTouch) {
            warnedNoTouch = true
            CarToast.makeText(
                carContext, "Touch control is off. Turn it on in carBuddy on the phone.",
                CarToast.LENGTH_LONG,
            ).show()
        }
        return t
    }

    private class Geometry(val scale: Float, val offsetX: Float, val offsetY: Float)

    /**
     * How the phone screen sits inside the car screen: scaled to fit and centred,
     * which is how Android lays out a mirrored display.
     */
    private fun geometry(): Geometry? {
        val c = container ?: return null
        val phone = TouchService.phoneScreenSize(carContext)
        if (phone.x <= 0 || phone.y <= 0) return null
        val scale = minOf(c.width / phone.x.toFloat(), c.height / phone.y.toFloat())
        return Geometry(
            scale,
            (c.width - phone.x * scale) / 2f,
            (c.height - phone.y * scale) / 2f,
        )
    }

    private fun toPhone(x: Float, y: Float): Pair<Float, Float>? {
        val g = geometry() ?: return null
        val phone = TouchService.phoneScreenSize(carContext)
        val px = (x - g.offsetX) / g.scale
        val py = (y - g.offsetY) / g.scale
        if (px < 0 || py < 0 || px >= phone.x || py >= phone.y) return null
        return px to py
    }

    private companion object {
        const val DRAG_END_MS = 150L
    }
}
