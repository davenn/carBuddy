package com.davenn.carbuddy

import android.app.Activity
import android.os.Bundle

/**
 * The streaming browser on the phone. Its main use is signing in: the phone has
 * a proper keyboard, and the browser on the car screen shares its cookies.
 */
class BrowserActivity : Activity() {

    private lateinit var browser: StreamingBrowser

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        browser = StreamingBrowser(this) { finish() }
        setContentView(browser.create())
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
