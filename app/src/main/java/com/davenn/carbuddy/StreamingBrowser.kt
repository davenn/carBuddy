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
 * The two get different layouts: on the car, large buttons down the side of a
 * short, wide screen; on the phone ([phone] = true), a slim toolbar on top and
 * one site per row, for signing in.
 */
class StreamingBrowser(
    private val activity: Activity,
    private val phone: Boolean = false,
    private val onClose: () -> Unit,
) {

    private val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
    private val content = FrameLayout(activity)
    private lateinit var sitesPanel: LinearLayout
    private lateinit var address: EditText
    private var web: WebView? = null
    private var pageTitle: TextView? = null

    /** Phone only: present as desktop Chrome, as the car does. Off = the phone's own mobile pages. */
    private var desktopSite = true

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var exitFullscreen: ImageButton

    fun create(): View {
        if (phone) return createPhone()

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
        addFullscreenExit()
        return root
    }

    /** Phone layout: a slim toolbar above the page, sized for one hand. */
    private fun createPhone(): View {
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1E1E1E.toInt())
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(toolButton(R.drawable.ic_back, "Back") { if (!back()) onClose() })
            pageTitle = TextView(activity).apply {
                textSize = 15f
                setTextColor(Color.WHITE)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(8), 0, dp(8), 0)
                text = "Sign in"
            }
            addView(pageTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(toolButton(R.drawable.ic_apps, "Sites") { showSites(true) })
            addView(toolButton(R.drawable.ic_refresh, "Reload") { web?.reload() })
            addView(toolButton(R.drawable.ic_close, "Close") { onClose() })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        column.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        sitesPanel = buildPhoneSitesPanel()
        content.addView(sitesPanel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addFullscreenExit()
        return root
    }

    private fun addFullscreenExit() {
        exitFullscreen = iconButton(R.drawable.ic_close, "Exit full screen") { leaveFullscreen() }.apply {
            visibility = View.GONE
            alpha = 0.6f
        }
        root.addView(exitFullscreen, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.START)
            .also { it.setMargins(dp(12), dp(12), 0, 0) })
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
        // Secure pages may still pull a few plain-http images or map tiles; show
        // those rather than leaving holes, as Chrome does.
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        if (desktopSite) applyDesktopIdentity(this)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Sites try to hand off to their apps (intent://, market://). Stay
                // in the browser: those apps are blocked on the car screen.
                val scheme = request.url.scheme ?: return false
                return scheme != "http" && scheme != "https"
            }

            override fun onPageFinished(view: WebView, url: String?) {
                pageTitle?.text = view.title?.takeIf { it.isNotBlank() } ?: hostOf(url)
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

    /** Present as desktop Chrome on Linux: streaming sites send phones to their apps. */
    private fun applyDesktopIdentity(w: WebView) {
        w.settings.userAgentString = desktopUserAgent(w.settings.userAgentString)
        // Keep the client hints consistent with the user agent: sites read both.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            WebSettingsCompat.setUserAgentMetadata(
                w.settings,
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
    }

    /** Phone only: switch between desktop and mobile pages; reloads the current page. */
    private fun setDesktopSite(on: Boolean) {
        if (on == desktopSite) return
        desktopSite = on
        val w = web ?: return
        // Simplest reliable way to drop the old identity: start a fresh WebView.
        // Cookies and site storage are shared, so sign-ins carry over.
        val url = w.url
        content.removeView(w)
        w.destroy()
        web = null
        if (url != null && url != "about:blank") open(url)
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

    /** Phone sites panel: one site per row, full width, with an explanation. */
    private fun buildPhoneSitesPanel(): LinearLayout {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        column.addView(TextView(activity).apply {
            text = "Sign in to streaming sites"
            textSize = 22f
            setTextColor(Color.WHITE)
        })
        column.addView(TextView(activity).apply {
            text = "Sign in here once. The car's Streaming screen uses the same sign-in."
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(6), 0, dp(12))
        })
        for ((name, url) in SITES) {
            column.addView(Button(activity).apply {
                text = name
                textSize = 18f
                isAllCaps = false
                setOnClickListener { open(url) }
            }, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).also { it.setMargins(0, dp(4), 0, dp(4)) })
        }

        address = EditText(activity).apply {
            hint = "Or type a web address"
            textSize = 16f
            isSingleLine = true
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, _, _ -> go(); true }
        }
        column.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(address, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(Button(activity).apply {
                text = "Go"
                setOnClickListener { go() }
            })
        })

        column.addView(android.widget.Switch(activity).apply {
            text = "Desktop site (matches the car; turn off if pages are too small)"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            isChecked = desktopSite
            setPadding(0, dp(16), 0, 0)
            setOnCheckedChangeListener { _, on -> setDesktopSite(on) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101010.toInt())
            addView(android.widget.ScrollView(activity).apply { addView(column) },
                LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    private fun showSites(show: Boolean) {
        sitesPanel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) pageTitle?.text = "Sign in"
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

    /** Phone toolbar button: 48dp touch target, plain background. */
    private fun toolButton(icon: Int, label: String, onClick: () -> Unit) = ImageButton(activity).apply {
        setImageResource(icon)
        contentDescription = label
        setBackgroundColor(Color.TRANSPARENT)
        val pad = dp(12)
        setPadding(pad, pad, pad, pad)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    }

    private fun hostOf(url: String?): String =
        url?.let { android.net.Uri.parse(it).host } ?: ""

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
