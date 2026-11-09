package com.davenn.carbuddy

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import kotlin.system.exitProcess

/**
 * The privileged half of carBuddy. Shizuku starts this class in its own process
 * running as the shell user (uid 2000) — the same identity `adb shell` has — and
 * the app talks to it over Binder through [IDisplayService].
 *
 * Nothing in this file runs in the app's process, so it cannot touch app state;
 * everything it needs arrives as AIDL arguments. Hidden-API reflection works here
 * because the process is started by app_process, not by zygote, so the hidden-API
 * blocklist is not enforced.
 *
 * Shizuku calls the (Context) constructor when it exists, and the no-arg one
 * otherwise; both are kept so either Shizuku behaviour works.
 */
class DisplayService(private val base: Context?) : IDisplayService.Stub() {

    constructor() : this(null)

    private var display: VirtualDisplay? = null
    private var lastError: String = ""

    private val shellContext: Context by lazy { ShellContext(base ?: systemContext()) }

    private val displayManager: DisplayManager by lazy {
        // DisplayManager(Context) is hidden. Building one around ShellContext makes
        // the request carry "com.android.shell" as its package, which matches this
        // process's uid; the app's own package name would be rejected.
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(shellContext)
    }

    // ---------------------------------------------------------------- display

    @Synchronized
    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int {
        releaseDisplayLocked()
        val failures = mutableListOf<String>()
        // Try the full flag set first and fall back to plainer ones, so a phone
        // that rejects one of the newer flags still gets a working display.
        for ((label, flags) in flagSets()) {
            try {
                val vd = displayManager.createVirtualDisplay(
                    "carBuddy", width, height, dpi, surface, flags
                ) ?: throw IllegalStateException("createVirtualDisplay returned null")
                display = vd
                lastError = if (failures.isEmpty()) "" else
                    "Display created with \"$label\" flags after: ${failures.joinToString("; ")}"
                return vd.display.displayId
            } catch (t: Throwable) {
                failures += "$label: ${t.javaClass.simpleName}: ${t.message}"
            }
        }
        lastError = "Could not create display. " + failures.joinToString("; ")
        return -1
    }

    @Synchronized
    override fun releaseDisplay() = releaseDisplayLocked()

    private fun releaseDisplayLocked() {
        display?.release()
        display = null
    }

    private fun currentDisplayId(): Int = display?.display?.displayId ?: -1

    // ----------------------------------------------------------------- launch

    @Synchronized
    override fun launch(component: String): String {
        val id = currentDisplayId()
        if (id < 0) return "Error: no car display is active"
        // `am` already runs with shell privileges here, which is what allows
        // starting another app's activity on a display this app does not own.
        // 0x10000000 = FLAG_ACTIVITY_NEW_TASK.
        return exec("am", "start", "--display", id.toString(), "-f", "0x10000000", "-n", component)
    }

    // ------------------------------------------------------------------ input

    private val inputManager: Any by lazy {
        // Android 14 moved injection to InputManagerGlobal; older releases have
        // it on the hidden InputManager.getInstance().
        try {
            Class.forName("android.hardware.input.InputManagerGlobal")
                .getMethod("getInstance").invoke(null)!!
        } catch (_: Throwable) {
            Class.forName("android.hardware.input.InputManager")
                .getDeclaredMethod("getInstance").invoke(null)!!
        }
    }

    private val injectMethod by lazy {
        inputManager.javaClass.getMethod(
            "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
        )
    }

    private val setDisplayIdMethod by lazy {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }

    private fun inject(event: InputEvent, displayId: Int) {
        setDisplayIdMethod.invoke(event, displayId)
        injectMethod.invoke(inputManager, event, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */)
    }

    @Synchronized
    override fun touch(action: Int, x: Float, y: Float, downTime: Long) {
        val id = currentDisplayId()
        if (id < 0) return
        val props = MotionEvent.PointerProperties().apply {
            this.id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
            size = 1f
        }
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, 1,
            arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            inject(event, id)
        } catch (t: Throwable) {
            lastError = "Touch injection failed: ${t.cause ?: t}"
            // Slow but dependable fallback for a plain tap.
            if (action == MotionEvent.ACTION_UP) {
                exec("input", "-d", id.toString(), "tap", x.toInt().toString(), y.toInt().toString())
            }
        } finally {
            event.recycle()
        }
    }

    @Synchronized
    override fun key(keyCode: Int) {
        val id = currentDisplayId()
        if (id < 0) return
        val now = SystemClock.uptimeMillis()
        try {
            for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                inject(
                    KeyEvent(
                        now, now, action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD,
                        0, 0, InputDevice.SOURCE_KEYBOARD
                    ), id
                )
            }
        } catch (t: Throwable) {
            lastError = "Key injection failed: ${t.cause ?: t}"
            exec("input", "-d", id.toString(), "keyevent", keyCode.toString())
        }
    }

    override fun lastError(): String = lastError

    // -------------------------------------------------------------- lifecycle

    override fun destroy() {
        releaseDisplay()
        exitProcess(0)
    }

    // ---------------------------------------------------------------- helpers

    private fun exec(vararg cmd: String): String = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.trim()
    } catch (t: Throwable) {
        "Error: ${t.message}"
    }

    private fun flagSets(): List<Pair<String, Int>> {
        val basic = PUBLIC or PRESENTATION or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or
            DESTROY_CONTENT_ON_REMOVAL
        // TRUSTED lets other apps' windows (and the system's secondary home screen)
        // appear on the display.
        val trusted = basic or TRUSTED or SHOULD_SHOW_SYSTEM_DECORATIONS
        val sets = mutableListOf<Pair<String, Int>>()
        if (Build.VERSION.SDK_INT >= 33) {
            // OWN_DISPLAY_GROUP keeps the car display awake while the phone's own
            // screen is off; ALWAYS_UNLOCKED keeps the lock screen off it.
            var full = trusted or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED
            if (Build.VERSION.SDK_INT >= 34) full = full or OWN_FOCUS
            sets += "full" to full
        }
        sets += "trusted" to trusted
        sets += "basic" to basic
        return sets
    }

    /** Makes system services see this process as the shell package it really is. */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL_PACKAGE
        override fun getOpPackageName(): String = SHELL_PACKAGE
        override fun getApplicationContext(): Context = this
        override fun getAttributionSource(): AttributionSource =
            if (Build.VERSION.SDK_INT >= 31) {
                AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()
            } else {
                super.getAttributionSource()
            }
    }

    private companion object {
        const val SHELL_PACKAGE = "com.android.shell"

        // android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_* — several
        // are hidden, so all are spelled out here for consistency.
        const val PUBLIC = 1 shl 0
        const val PRESENTATION = 1 shl 1
        const val OWN_CONTENT_ONLY = 1 shl 3
        const val SUPPORTS_TOUCH = 1 shl 6
        const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        const val SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
        const val TRUSTED = 1 shl 10
        const val OWN_DISPLAY_GROUP = 1 shl 11
        const val ALWAYS_UNLOCKED = 1 shl 12
        const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
        const val OWN_FOCUS = 1 shl 14

        fun systemContext(): Context {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("currentActivityThread").invoke(null)
                ?: at.getMethod("systemMain").invoke(null)
            return at.getMethod("getSystemContext").invoke(thread) as Context
        }
    }
}
