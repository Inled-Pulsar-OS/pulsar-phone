package es.inled.pulsarphone.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
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
import kotlinx.coroutines.withContext
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
    private var zoomStep = 0 // 0: Fit (Phosh), 1: 1.5x, 2: 2.0x
    private var appLoaded = false

    @Volatile
    private var cachedBroadwayJs: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBroadwayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appName = intent.getStringExtra(EXTRA_APP_NAME) ?: "Pulsar App"
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
        binding.loadingAppTitle.text = "Iniciando $appName..."

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

                // 1. Intercept root HTML to supply scalable, Phosh-compatible viewport
                if (path == "/" || path.isEmpty() || path == "/index.html") {
                    val html = buildMobileBroadwayHtml(bgColorHex)
                    val stream: InputStream = ByteArrayInputStream(html.toByteArray(StandardCharsets.UTF_8))
                    return WebResourceResponse("text/html", "UTF-8", stream)
                }

                // 2. Intercept broadway.js to enable multi-touch, pan, and adaptive scaling
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
            <meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=0.25, maximum-scale=5.0, user-scalable=yes">
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

        // Patch 1: Multi-touch and pan support in onTouchStart, onTouchMove, onTouchEnd
        // Do not call preventDefault on multi-touch (>1 fingers) so browser handles pinch zoom and pan!
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

        var patched = rawJs
        if (patched.contains(touchStartTarget)) {
            patched = patched.replace(touchStartTarget, touchStartReplacement)
        }
        if (patched.contains(touchMoveTarget)) {
            patched = patched.replace(touchMoveTarget, touchMoveReplacement)
        }
        if (patched.contains(touchEndTarget)) {
            patched = patched.replace(touchEndTarget, touchEndReplacement)
        }

        // Patch 2: Coordinate scaling in getPositionsFromEvent
        val posTarget = "function getPositionsFromEvent(ev, relativeId) {\n    var absX, absY;\n    absX = ev.pageX;\n    absY = ev.pageY;"
        val posReplacement = """
            function getPositionsFromEvent(ev, relativeId) {
                var scale = window.broadwayScale || 1.0;
                var absX, absY;
                absX = (ev.pageX !== undefined ? ev.pageX : (ev.clientX || 0)) / scale;
                absY = (ev.pageY !== undefined ? ev.pageY : (ev.clientY || 0)) / scale;
        """.trimIndent()
        if (patched.contains(posTarget)) {
            patched = patched.replace(posTarget, posReplacement)
        }

        // Patch 3: Hook DISPLAY_OP_SHOW_SURFACE and DISPLAY_OP_RESIZE_NODE
        val showSurfaceTarget = "div.style[\"visibility\"] = \"visible\";\n            break;"
        val showSurfaceReplacement = "div.style[\"visibility\"] = \"visible\";\n            if (window.onPulsarSurfaceUpdated) window.onPulsarSurfaceUpdated();\n            break;"
        if (patched.contains(showSurfaceTarget)) {
            patched = patched.replace(showSurfaceTarget, showSurfaceReplacement)
        }

        // Patch 4: Append mobile Phosh-style adapter script
        val mobileHelperScript = """
            
            // --- Pulsar Phosh-style Linux Mobile Adapter ---
            window.pulsarPanModeActive = false;
            window.broadwayScale = 1.0;

            window.setPulsarPanMode = function(active) {
                window.pulsarPanModeActive = !!active;
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

                    // Phosh-style mobile width fit
                    var scale = vw / baseW;
                    window.broadwayScale = scale;

                    root.div.style.position = 'absolute';
                    root.div.style.transformOrigin = '0 0';
                    root.div.style.transform = 'scale(' + scale + ')';
                    root.div.style.left = '0px';
                    root.div.style.top = '0px';
                    root.div.style.boxShadow = 'none';
                    root.div.style.borderRadius = '0px';
                    root.div.style.border = 'none';

                    var contentHeight = Math.ceil(baseH * scale);
                    document.body.style.minHeight = Math.max(vh, contentHeight) + 'px';
                    document.body.style.width = vw + 'px';

                    // Scale child popups/dialogs proportionally
                    for (var id in surfaces) {
                        var child = surfaces[id];
                        if (child && child !== root && child.div) {
                            child.div.style.transformOrigin = '0 0';
                            child.div.style.transform = 'scale(' + scale + ')';
                            if (child.x !== undefined && child.y !== undefined) {
                                child.div.style.left = Math.round(child.x * scale) + 'px';
                                child.div.style.top = Math.round(child.y * scale) + 'px';
                            }
                        }
                    }

                    if (window.BroadwayBridge && typeof window.BroadwayBridge.onAppLoaded === 'function') {
                        window.BroadwayBridge.onAppLoaded();
                    }
                }
            };

            window.addEventListener('resize', window.onPulsarSurfaceUpdated);
            setInterval(window.onPulsarSurfaceUpdated, 400);
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
            togglePanMode()
        }

        binding.btnToggleZoom.setOnClickListener {
            cycleZoom()
        }

        binding.btnToggleKeyboard.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            binding.broadwayWebView.requestFocus()
            imm.showSoftInput(binding.broadwayWebView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun togglePanMode() {
        isPanMode = !isPanMode
        if (isPanMode) {
            binding.btnToggleMode.setImageResource(R.drawable.ic_pan_hand)
            binding.broadwayWebView.evaluateJavascript("window.setPulsarPanMode(true);", null)
            Toast.makeText(this, "Modo desplazamiento • Arrastra con 1 dedo para moverte", Toast.LENGTH_SHORT).show()
        } else {
            binding.btnToggleMode.setImageResource(R.drawable.ic_touch_pointer)
            binding.broadwayWebView.evaluateJavascript("window.setPulsarPanMode(false);", null)
            Toast.makeText(this, "Modo táctil • Toca para interactuar (2 dedos para zoom)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cycleZoom() {
        zoomStep = (zoomStep + 1) % 3
        when (zoomStep) {
            0 -> {
                // Reset to Phosh Mobile Fit
                binding.broadwayWebView.zoomOut()
                binding.broadwayWebView.evaluateJavascript("if (window.onPulsarSurfaceUpdated) window.onPulsarSurfaceUpdated();", null)
                Toast.makeText(this, "Ajuste móvil Phosh (100% ancho)", Toast.LENGTH_SHORT).show()
            }
            1 -> {
                binding.broadwayWebView.zoomIn()
                Toast.makeText(this, "Zoom 1.5x • Usa 2 dedos para explorar", Toast.LENGTH_SHORT).show()
            }
            2 -> {
                binding.broadwayWebView.zoomIn()
                Toast.makeText(this, "Zoom 2.0x • Detalle", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun closeAndExit() {
        Toast.makeText(this, "Cerrando $appName...", Toast.LENGTH_SHORT).show()
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
