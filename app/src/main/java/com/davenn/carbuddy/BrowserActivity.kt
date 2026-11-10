package com.davenn.carbuddy

import android.app.Activity
import android.os.Bundle
import android.view.Window

/**
 * The streaming browser on the phone. Its main use is signing in: the phone has
 * a proper keyboard, and the browser on the car screen shares its cookies.
 */
class BrowserActivity : Activity() {

    private lateinit var browser: StreamingBrowser

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No title bar: the browser has its own buttons, and the bar covered pages.
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        browser = StreamingBrowser(this) { finish() }
        // Without the title bar, keep pages clear of the status bar themselves.
        setContentView(browser.create().apply { fitsSystemWindows = true })
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
