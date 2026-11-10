package com.davenn.carbuddy

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowInsets

/**
 * The streaming browser on the phone. Its main use is signing in: the phone has
 * a proper keyboard, and the browser on the car screen shares its cookies. It
 * uses the browser's phone layout (a slim toolbar, one site per row).
 */
class BrowserActivity : Activity() {

    private lateinit var browser: StreamingBrowser

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No title bar: the browser has its own toolbar.
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        browser = StreamingBrowser(this, phone = true) { finish() }
        setContentView(browser.create().also { keepClearOfBarsAndKeyboard(it) })
    }

    /**
     * Pads the page clear of the status bar, navigation bar and keyboard. Android 15
     * draws apps edge to edge, so without this the toolbar sits under the status
     * bar and the keyboard covers sign-in fields.
     */
    private fun keepClearOfBarsAndKeyboard(view: View) {
        if (Build.VERSION.SDK_INT >= 30) {
            view.setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
                )
                v.setPadding(i.left, i.top, i.right, i.bottom)
                WindowInsets.CONSUMED
            }
        } else {
            view.fitsSystemWindows = true
        }
    }

    @Deprecated("Back is handled here to step back through the browser first")
    override fun onBackPressed() {
        if (!browser.back()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        browser.destroy()
        super.onDestroy()
    }
}
