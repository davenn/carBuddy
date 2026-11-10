package com.davenn.carbuddy

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * A browser for streaming sites, shown inside carBuddy's own screen.
 *
 * Android Auto lets only carBuddy onto the car display, and screen capture can't
 * carry protected video. A web player running in carBuddy itself can: the site
 * decrypts the video with the phone's Widevine through WebView, and the picture
 * goes straight to whatever display carBuddy is on. Whether the car display
 * accepts protected video is up to Android Auto (see [displaySummary]).
 *
 * The browser presents itself as desktop Chrome on Linux, because streaming
 * sites send phones to their apps instead of playing in the browser.
 *
 * Used on the car screen ([CarScreen]) and on the phone ([BrowserActivity]),
 * which share one cookie store, so signing in on the phone also signs in the car.
 */
class StreamingBrowser(
    private val activity: Activity,
    private val onClose: () -> Unit,
) {

    private val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
    private val content = FrameLayout(activity)
    private lateinit var sitesPanel: LinearLayout
    private lateinit var address: EditText
    private var web: WebView? = null

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var exitFullscreen: ImageButton

    fun create(): View {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            addView(iconButton(R.drawable.ic_back, "Back") { back() })
            addView(iconButton(R.drawable.ic_apps, "Sites") { showSites(true) })
            addView(iconButton(R.drawable.ic_refresh, "Reload") { web?.reload() })
            addView(iconButton(R.drawable.ic_close, "Close browser") { onClose() })
        }, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        row.addView(content, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        root.addView(row, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        sitesPanel = buildSitesPanel()
        content.addView(sitesPanel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        exitFullscreen = iconButton(R.drawable.ic_close, "Exit full screen") { leaveFullscreen() }.apply {
            visibility = View.GONE
            alpha = 0.6f
        }
        root.addView(exitFullscreen, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.START)
            .also { it.setMargins(dp(12), dp(12), 0, 0) })
        return root
    }

    fun destroy() {
        leaveFullscreen()
        web?.let {
            it.stopLoading()
            it.loadUrl("about:blank")
            (it.parent as? FrameLayout)?.removeView(it)
            it.destroy()
        }
        web = null
        CookieManager.getInstance().flush()
    }

    /** Handles a back press; false when there is nothing left to go back from. */
    fun back(): Boolean {
        if (fullscreenView != null) { leaveFullscreen(); return true }
        if (sitesPanel.visibility == View.VISIBLE && web != null) { showSites(false); return true }
        val w = web
        if (w != null && w.canGoBack()) { w.goBack(); return true }
        if (sitesPanel.visibility != View.VISIBLE) { showSites(true); return true }
        return false
    }

    fun open(url: String) {
        val w = web ?: newWebView().also {
            web = it
            content.addView(it, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        showSites(false)
        w.loadUrl(url)
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView = WebView(activity).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.userAgentString = desktopUserAgent(settings.userAgentString)
        // Keep the client hints consistent with the user agent: sites read both.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            WebSettingsCompat.setUserAgentMetadata(
                settings,
                UserAgentMetadata.Builder()
                    .setPlatform("Linux")
                    .setPlatformVersion("")
                    .setMobile(false)
                    .setArchitecture("x86")
                    .setBitness(64)
                    .setModel("")
                    .build(),
            )
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Sites try to hand off to their apps (intent://, market://). Stay
                // in the browser: those apps are blocked on the car screen.
                val scheme = request.url.scheme ?: return false
                return scheme != "http" && scheme != "https"
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // The page's process died (often out of memory on video sites).
                // Drop this WebView and go back to the sites list instead of
                // taking carBuddy down with it.
                if (web === view) {
                    leaveFullscreen()
                    content.removeView(view)
                    view.destroy()
                    web = null
                    showSites(true)
                }
                return true
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // Protected media (Widevine) is what the streaming players need;
                // nothing else (camera, microphone) is granted.
                val drm = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                activity.runOnUiThread {
                    if (drm.isEmpty()) request.deny() else request.grant(drm.toTypedArray())
                }
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                leaveFullscreen()
                fullscreenView = view
                fullscreenCallback = callback
                root.addView(view, root.indexOfChild(exitFullscreen),
                    FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                exitFullscreen.visibility = View.VISIBLE
            }

            override fun onHideCustomView() {
                fullscreenView?.let { root.removeView(it) }
                fullscreenView = null
                fullscreenCallback = null
                exitFullscreen.visibility = View.GONE
            }
        }
    }

    private fun leaveFullscreen() {
        val cb = fullscreenCallback ?: return
        cb.onCustomViewHidden()
        fullscreenView?.let { root.removeView(it) }
        fullscreenView = null
        fullscreenCallback = null
        exitFullscreen.visibility = View.GONE
    }

    // ------------------------------------------------------------------ sites

    private fun buildSitesPanel(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(0xFF101010.toInt())
        setPadding(dp(16), dp(16), dp(16), dp(16))

        addView(TextView(activity).apply {
            text = "Streaming"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        })

        for (rowSites in SITES.chunked(3)) {
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                for ((name, url) in rowSites) {
                    addView(Button(activity).apply {
                        text = name
                        textSize = 20f
                        isAllCaps = false
                        setOnClickListener { open(url) }
                    }, LinearLayout.LayoutParams(dp(200), dp(64)).also { it.setMargins(dp(6), dp(6), dp(6), dp(6)) })
                }
            })
        }

        address = EditText(activity).apply {
            hint = "Or type a web address"
            textSize = 18f
            isSingleLine = true
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> go(); true }
        }
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(address, LinearLayout.LayoutParams(dp(480), WRAP_CONTENT))
            addView(Button(activity).apply {
                text = "Go"
                setOnClickListener { go() }
            })
        })
    }

    private fun go() {
        val text = address.text.toString().trim()
        if (text.isEmpty()) return
        open(
            when {
                text.startsWith("http://") || text.startsWith("https://") -> text
                text.contains('.') && !text.contains(' ') -> "https://$text"
                else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(text, "UTF-8")
            }
        )
    }

    private fun showSites(show: Boolean) {
        sitesPanel.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- helpers

    private fun iconButton(icon: Int, label: String, onClick: () -> Unit) = ImageButton(activity).apply {
        setImageResource(icon)
        contentDescription = label
        background = GradientDrawable().apply {
            setColor(0x99333333.toInt())
            cornerRadius = dp(32).toFloat()
        }
        val pad = dp(14)
        setPadding(pad, pad, pad, pad)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).also { it.setMargins(0, dp(6), 0, dp(6)) }
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    companion object {
        val SITES = listOf(
            "Netflix" to "https://www.netflix.com/browse",
            "Disney+" to "https://www.disneyplus.com/",
            "Prime Video" to "https://www.primevideo.com/",
            "YouTube" to "https://www.youtube.com/",
            "HBO Max" to "https://play.hbomax.com/",
            "Hulu" to "https://www.hulu.com/",
        )

        /** WebView's own user agent, rewritten as desktop Chrome on Linux. */
        fun desktopUserAgent(webViewAgent: String): String {
            val chrome = Regex("Chrome/([\\d.]+)").find(webViewAgent)?.groupValues?.get(1) ?: "140.0.0.0"
            return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$chrome Safari/537.36"
        }

        /**
         * Whether a display can show protected (DRM) video, from its flags.
         * Streaming sites only play on displays that support protected buffers.
         */
        fun displaySummary(display: android.view.Display?): String {
            if (display == null) return "Display unknown"
            val f = display.flags
            val secure = f and android.view.Display.FLAG_SECURE != 0
            val protected = f and android.view.Display.FLAG_SUPPORTS_PROTECTED_BUFFERS != 0
            return "This screen: " + (if (secure) "secure" else "not secure") +
                ", protected video " + (if (protected) "supported" else "not supported")
        }
    }
}
