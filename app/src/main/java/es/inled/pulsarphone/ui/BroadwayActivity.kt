package es.inled.pulsarphone.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import es.inled.pulsarphone.PulsarPhoneApp
import es.inled.pulsarphone.R
import es.inled.pulsarphone.databinding.ActivityBroadwayBinding
import es.inled.pulsarphone.net.PulsarApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class BroadwayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBroadwayBinding
    private var appName: String = "App Broadway"
    private var broadwayUrl: String = ""
    private var isPanMode = false
    private var zoomStep = 0 // 0: Fit, 1: 1.3x, 2: 1.7x
    private var appLoaded = false

    @Volatile
    private var cachedBroadwayJs: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBroadwayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appName = intent.getStringExtra(EXTRA_APP_NAME) ?: getString(R.string.app_name)
        val cfg = PulsarPhoneApp.instance.getConfig()
        val rawUrl = intent.getStringExtra(EXTRA_BROADWAY_URL) ?: ""

        val targetPort = try {
            val u = android.net.Uri.parse(rawUrl)
            if (u.port > 0) u.port else cfg.broadwayPort
        } catch (e: Exception) {
            cfg.broadwayPort
        }
        broadwayUrl = "http://${cfg.pcIp}:$targetPort/"

        binding.broadwayAppTitle.text = appName
        binding.loadingAppTitle.text = getString(R.string.starting_app, appName)

        setupWebView()
        setupControls()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                closeAndExit()
            }
        })

        // Safety fallback: reveal webview after 5s if callback delayed
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            revealAppView()
        }, 5000)

        // Pre-fetch broadway.js in background
        lifecycleScope.launch(Dispatchers.IO) {
            fetchAndPatchBroadwayJs("${broadwayUrl}broadway.js")
        }

        binding.broadwayWebView.loadUrl(broadwayUrl)
    }

    private fun revealAppView() {
        if (!appLoaded) {
            appLoaded = true
            runOnUiThread {
                binding.loadingLayout.animate()
                    .alpha(0f)
                    .setDuration(250)
                    .withEndAction {
                        binding.loadingLayout.visibility = View.GONE
                        binding.broadwayWebView.visibility = View.VISIBLE
                    }
                    .start()
            }
        }
    }

    inner class BroadwayJsBridge {
        @JavascriptInterface
        fun onAppLoaded() {
            revealAppView()
        }

        @JavascriptInterface
        fun onConnectionState(connected: Boolean) {
            runOnUiThread {
                if (connected) {
                    revealAppView()
                } else {
                    binding.loadingAppTitle.text = getString(R.string.connecting_broadway)
                    binding.loadingLayout.visibility = View.VISIBLE
                    binding.loadingLayout.alpha = 1f
                }
            }
        }

        @JavascriptInterface
        fun onKeyboardStatus(show: Boolean) {
            runOnUiThread {
                if (show) {
                    showNativeInputBar()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.broadwayWebView
        WebView.setWebContentsDebuggingEnabled(true)

        val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bgColorHex = if (isDarkMode) "#121212" else "#F8F9FA"

        webView.setBackgroundColor(Color.parseColor(bgColorHex))

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.addJavascriptInterface(BroadwayJsBridge(), "BroadwayBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                val path = url.path ?: ""

                // 1. Intercept root HTML with controlled viewport bounds
                if (path == "/" || path.isEmpty() || path == "/index.html") {
                    val html = buildMobileBroadwayHtml(bgColorHex)
                    val stream: InputStream = ByteArrayInputStream(html.toByteArray(StandardCharsets.UTF_8))
                    return WebResourceResponse("text/html", "UTF-8", stream)
                }

                // 2. Intercept broadway.js to enable auto-reconnect, on-screen keyboard, and clean fit
                if (path.endsWith("broadway.js")) {
                    val js = fetchAndPatchBroadwayJs(url.toString())
                    if (js.isNotEmpty()) {
                        val stream: InputStream = ByteArrayInputStream(js.toByteArray(StandardCharsets.UTF_8))
                        return WebResourceResponse("application/javascript", "UTF-8", stream)
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }
        }

        webView.webChromeClient = WebChromeClient()
    }

    private fun buildMobileBroadwayHtml(bgColor: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta http-equiv="Content-Type" content="text/html; charset=utf8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=0.8, maximum-scale=2.5, user-scalable=yes">
            <title>Pulsar Broadway Mobile</title>
            <style type="text/css">
            * {
                box-sizing: border-box;
            }
            html, body {
                margin: 0 !important;
                padding: 0 !important;
                width: 100% !important;
                min-height: 100% !important;
                overflow: auto !important;
                -webkit-overflow-scrolling: touch !important;
                background-color: $bgColor !important;
                user-select: none !important;
                -webkit-user-select: none !important;
                touch-action: pan-x pan-y pinch-zoom !important;
            }
            </style>
            <script type="text/javascript" src="broadway.js"></script>
            </head>
            <body onload="connect()" onclick="if (!document.hasFocus() && window.topElement !== window.self) { window.focus(); }">
            </body>
            </html>
        """.trimIndent()
    }

    private fun fetchAndPatchBroadwayJs(urlStr: String): String {
        cachedBroadwayJs?.let { return it }

        var rawJs = ""
        try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"
            if (conn.responseCode == 200) {
                rawJs = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (rawJs.isEmpty()) return ""

        var patched = rawJs

        // Patch 1: Auto-reconnect on WebSocket disconnect (No frozen blank screen!)
        val oncloseTarget = "if (inputSocket != null)\n            alert (\"disconnected\");\n        inputSocket = null;"
        val oncloseReplacement = """
            inputSocket = null;
            if (window.BroadwayBridge && window.BroadwayBridge.onConnectionState) {
                window.BroadwayBridge.onConnectionState(false);
            }
            setTimeout(function() {
                if (!inputSocket) connect();
            }, 1200);
        """.trimIndent()
        if (patched.contains(oncloseTarget)) {
            patched = patched.replace(oncloseTarget, oncloseReplacement)
        }

        val onopenTarget = "inputSocket = ws;"
        val onopenReplacement = """
            inputSocket = ws;
            if (window.BroadwayBridge && window.BroadwayBridge.onConnectionState) {
                window.BroadwayBridge.onConnectionState(true);
            }
        """.trimIndent()
        if (patched.contains(onopenTarget)) {
            patched = patched.replace(onopenTarget, onopenReplacement)
        }

        // Patch 2: On-screen fakeInput so Android WebView / Chromium can trigger the soft keyboard!
        val fakeInputTarget = "fakeInput.style.position = \"absolute\";\n        fakeInput.style.left = \"-1000px\";\n        fakeInput.style.top = \"-1000px\";"
        val fakeInputReplacement = """
            fakeInput.style.position = "fixed";
            fakeInput.style.left = "0px";
            fakeInput.style.bottom = "0px";
            fakeInput.style.width = "1px";
            fakeInput.style.height = "1px";
            fakeInput.style.opacity = "0.01";
            fakeInput.style.zIndex = "999";
            fakeInput.style.pointerEvents = "none";
        """.trimIndent()
        if (patched.contains(fakeInputTarget)) {
            patched = patched.replace(fakeInputTarget, fakeInputReplacement)
        }

        // Patch 3: Pan Mode vs Touch Mode in touch events
        // In Pan Mode: do NOT preventDefault, letting browser pan and scroll smoothly with 1 finger!
        val touchStartTarget = "function onTouchStart(ev) {\n    ev.preventDefault();"
        val touchStartReplacement = """
            function onTouchStart(ev) {
                if (window.pulsarPanModeActive || (ev.touches && ev.touches.length > 1)) {
                    return;
                }
                ev.preventDefault();
        """.trimIndent()

        val touchMoveTarget = "function onTouchMove(ev) {\n    ev.preventDefault();"
        val touchMoveReplacement = """
            function onTouchMove(ev) {
                if (window.pulsarPanModeActive || (ev.touches && ev.touches.length > 1)) {
                    return;
                }
                ev.preventDefault();
        """.trimIndent()

        val touchEndTarget = "function onTouchEnd(ev) {\n    ev.preventDefault();"
        val touchEndReplacement = """
            function onTouchEnd(ev) {
                if (window.pulsarPanModeActive || (ev.touches && ev.touches.length > 0)) {
                    return;
                }
                ev.preventDefault();
        """.trimIndent()

        if (patched.contains(touchStartTarget)) {
            patched = patched.replace(touchStartTarget, touchStartReplacement)
        }
        if (patched.contains(touchMoveTarget)) {
            patched = patched.replace(touchMoveTarget, touchMoveReplacement)
        }
        if (patched.contains(touchEndTarget)) {
            patched = patched.replace(touchEndTarget, touchEndReplacement)
        }

        // Patch 4: Coordinate scaling and offset mapping in getPositionsFromEvent
        val posTarget = "function getPositionsFromEvent(ev, relativeId) {\n    var absX, absY;\n    absX = ev.pageX;\n    absY = ev.pageY;"
        val posReplacement = """
            function getPositionsFromEvent(ev, relativeId) {
                var scale = window.broadwayScale || 1.0;
                var offX = window.broadwayOffsetX || 0;
                var offY = window.broadwayOffsetY || 0;
                var rawX = (ev.pageX !== undefined ? ev.pageX : (ev.clientX || 0));
                var rawY = (ev.pageY !== undefined ? ev.pageY : (ev.clientY || 0));
                var absX = (rawX - offX) / scale;
                var absY = (rawY - offY) / scale;
        """.trimIndent()
        if (patched.contains(posTarget)) {
            patched = patched.replace(posTarget, posReplacement)
        }

        // Patch 5: Hook BROADWAY_OP_SET_SHOW_KEYBOARD
        val kbTarget = "case BROADWAY_OP_SET_SHOW_KEYBOARD:\n            showKeyboard = cmd.get_16() != 0;\n            showKeyboardChanged = true;\n            break;"
        val kbReplacement = """
            case BROADWAY_OP_SET_SHOW_KEYBOARD:
                showKeyboard = cmd.get_16() != 0;
                showKeyboardChanged = true;
                if (typeof updateKeyboardStatus === 'function') updateKeyboardStatus();
                if (window.BroadwayBridge && typeof window.BroadwayBridge.onKeyboardStatus === 'function') {
                    window.BroadwayBridge.onKeyboardStatus(showKeyboard);
                }
                break;
        """.trimIndent()
        if (patched.contains(kbTarget)) {
            patched = patched.replace(kbTarget, kbReplacement)
        }

        // Patch 6: Hook DISPLAY_OP_SHOW_SURFACE and DISPLAY_OP_RESIZE_NODE
        val showSurfaceTarget = "div.style[\"visibility\"] = \"visible\";\n            break;"
        val showSurfaceReplacement = "div.style[\"visibility\"] = \"visible\";\n            if (window.onPulsarSurfaceUpdated) window.onPulsarSurfaceUpdated();\n            break;"
        if (patched.contains(showSurfaceTarget)) {
            patched = patched.replace(showSurfaceTarget, showSurfaceReplacement)
        }

        // Patch 7: Clean native layout adapter preserving GTK aesthetics
        val mobileHelperScript = """
            
            // --- Pulsar Clean Layout & Input Adapter ---
            window.pulsarPanModeActive = false;
            window.broadwayScale = 1.0;
            window.broadwayOffsetX = 0;
            window.broadwayOffsetY = 0;

            window.setPulsarPanMode = function(active) {
                window.pulsarPanModeActive = !!active;
            };

            window.triggerBroadwayKeyboard = function() {
                if (typeof fakeInput !== 'undefined' && fakeInput) {
                    fakeInput.focus();
                }
            };

            window.sendTextToBroadway = function(text) {
                if (!text || typeof sendInput !== 'function') return;
                for (var i = 0; i < text.length; i++) {
                    var ch = text.charCodeAt(i);
                    var sym = ch;
                    if (ch === 10 || ch === 13) {
                        sym = 65293; // Return / Enter
                    } else if (ch >= 32 && ch <= 126) {
                        sym = ch;
                    } else {
                        sym = text.codePointAt(i);
                        if (sym > 0xff) {
                            sym = sym | 0x01000000;
                        }
                    }
                    sendInput(BROADWAY_EVENT_KEY_PRESS, [sym, 0]);
                    sendInput(BROADWAY_EVENT_KEY_RELEASE, [sym, 0]);
                }
            };

            window.sendBackspaceToBroadway = function() {
                if (typeof sendInput === 'function') {
                    sendInput(BROADWAY_EVENT_KEY_PRESS, [65288, 0]);
                    sendInput(BROADWAY_EVENT_KEY_RELEASE, [65288, 0]);
                }
            };

            window.onPulsarSurfaceUpdated = function() {
                var vw = window.innerWidth || document.documentElement.clientWidth;
                var vh = window.innerHeight || document.documentElement.clientHeight;
                if (!vw || typeof surfaces === 'undefined' || !surfaces) return;

                var root = null;
                var maxArea = 0;
                for (var id in surfaces) {
                    var s = surfaces[id];
                    if (s && s.div && (s.transientParent === 0 || !s.transientParent)) {
                        var area = (s.width || 0) * (s.height || 0);
                        if (area >= maxArea) {
                            maxArea = area;
                            root = s;
                        }
                    }
                }

                if (!root) {
                    for (var id in surfaces) {
                        if (surfaces[id] && surfaces[id].div) {
                            root = surfaces[id];
                            break;
                        }
                    }
                }

                if (root && root.div) {
                    var baseW = root.width || root.div.offsetWidth || 800;
                    var baseH = root.height || root.div.offsetHeight || 600;

                    // Clean proportional fit: fits full screen width like Linux Mobile (Phosh)
                    var scale = 1.0;
                    if (baseW > 0) {
                        scale = vw / baseW;
                        scale = Math.min(1.2, Math.max(0.35, scale));
                    }
                    window.broadwayScale = scale;

                    var renderedW = Math.round(baseW * scale);
                    var renderedH = Math.round(baseH * scale);
                    var offsetX = Math.max(0, Math.round((vw - renderedW) / 2));
                    var offsetY = 0;
                    window.broadwayOffsetX = offsetX;
                    window.broadwayOffsetY = offsetY;

                    // Keep native GTK window aesthetics (do not strip shadows or borders!)
                    root.div.style.position = 'absolute';
                    root.div.style.transformOrigin = '0 0';
                    root.div.style.transform = (scale !== 1.0) ? ('scale(' + scale + ')') : 'none';
                    root.div.style.left = offsetX + 'px';
                    root.div.style.top = offsetY + 'px';

                    document.body.style.minHeight = Math.max(vh, offsetY + renderedH) + 'px';
                    document.body.style.width = Math.max(vw, offsetX + renderedW) + 'px';

                    // Position child popups/dialogs proportionally
                    for (var id in surfaces) {
                        var child = surfaces[id];
                        if (child && child !== root && child.div) {
                            child.div.style.transformOrigin = '0 0';
                            child.div.style.transform = (scale !== 1.0) ? ('scale(' + scale + ')') : 'none';
                            if (child.x !== undefined && child.y !== undefined) {
                                child.div.style.left = Math.round(offsetX + child.x * scale) + 'px';
                                child.div.style.top = Math.round(offsetY + child.y * scale) + 'px';
                            }
                        }
                    }

                    if (window.BroadwayBridge && typeof window.BroadwayBridge.onAppLoaded === 'function') {
                        window.BroadwayBridge.onAppLoaded();
                    }
                }
            };

            window.addEventListener('resize', window.onPulsarSurfaceUpdated);
        """.trimIndent()

        patched += "\n" + mobileHelperScript
        cachedBroadwayJs = patched
        return patched
    }

    private fun setupControls() {
        binding.btnCloseBroadway.setOnClickListener {
            closeAndExit()
        }

        binding.btnToggleMode.setOnClickListener {
            toggleInteractionMode()
        }

        binding.btnToggleZoom.setOnClickListener {
            cycleZoom()
        }

        binding.btnToggleKeyboard.setOnClickListener {
            toggleNativeInputBar()
        }

        binding.btnCloseInputBar.setOnClickListener {
            hideNativeInputBar()
        }

        binding.btnSendText.setOnClickListener {
            commitNativeInput()
        }

        binding.etNativeInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                commitNativeInput()
                true
            } else {
                false
            }
        }
    }

    private fun showNativeInputBar() {
        binding.nativeInputBar.visibility = View.VISIBLE
        binding.etNativeInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.etNativeInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideNativeInputBar() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etNativeInput.windowToken, 0)
        binding.nativeInputBar.visibility = View.GONE
        binding.broadwayWebView.requestFocus()
    }

    private fun toggleNativeInputBar() {
        if (binding.nativeInputBar.visibility == View.VISIBLE) {
            hideNativeInputBar()
        } else {
            showNativeInputBar()
        }
    }

    private fun commitNativeInput() {
        val text = binding.etNativeInput.text?.toString().orEmpty()
        if (text.isNotEmpty()) {
            val jsonText = org.json.JSONObject.quote(text)
            binding.broadwayWebView.evaluateJavascript("if (window.sendTextToBroadway) window.sendTextToBroadway($jsonText);", null)
            binding.etNativeInput.text?.clear()
        }
    }

    private fun toggleInteractionMode() {
        isPanMode = !isPanMode
        if (isPanMode) {
            binding.btnToggleMode.setImageResource(R.drawable.ic_pan_hand)
            binding.broadwayWebView.evaluateJavascript("window.setPulsarPanMode(true);", null)
            Toast.makeText(this, getString(R.string.mode_pan_toast), Toast.LENGTH_SHORT).show()
        } else {
            binding.btnToggleMode.setImageResource(R.drawable.ic_touch_pointer)
            binding.broadwayWebView.evaluateJavascript("window.setPulsarPanMode(false);", null)
            Toast.makeText(this, getString(R.string.mode_touch_toast), Toast.LENGTH_SHORT).show()
        }
    }

    private fun cycleZoom() {
        zoomStep = (zoomStep + 1) % 3
        when (zoomStep) {
            0 -> {
                // Reset to Natural Fit
                binding.broadwayWebView.zoomOut()
                binding.broadwayWebView.evaluateJavascript("if (window.onPulsarSurfaceUpdated) window.onPulsarSurfaceUpdated();", null)
                Toast.makeText(this, getString(R.string.zoom_fit_toast), Toast.LENGTH_SHORT).show()
            }
            1 -> {
                binding.broadwayWebView.zoomIn()
                Toast.makeText(this, getString(R.string.zoom_in_toast), Toast.LENGTH_SHORT).show()
            }
            2 -> {
                binding.broadwayWebView.zoomIn()
                Toast.makeText(this, getString(R.string.zoom_detail_toast), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun closeAndExit() {
        Toast.makeText(this, getString(R.string.closing_app, appName), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            PulsarApiClient.closeApp()
            finish()
        }
    }

    override fun onDestroy() {
        binding.broadwayWebView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_BROADWAY_URL = "extra_broadway_url"
    }
}
