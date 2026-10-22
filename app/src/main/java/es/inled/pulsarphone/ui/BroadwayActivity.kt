package es.inled.pulsarphone.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import es.inled.pulsarphone.PulsarPhoneApp
import es.inled.pulsarphone.databinding.ActivityBroadwayBinding
import es.inled.pulsarphone.net.PulsarApiClient
import kotlinx.coroutines.launch

class BroadwayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBroadwayBinding
    private var appName: String = "App Broadway"
    private var broadwayUrl: String = ""

    private var appLoaded = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBroadwayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appName = intent.getStringExtra(EXTRA_APP_NAME) ?: "Pulsar App"
        val cfg = PulsarPhoneApp.instance.getConfig()
        val rawUrl = intent.getStringExtra(EXTRA_BROADWAY_URL) ?: ""

        // Always resolve target host to cfg.pcIp so it works in emulator (10.0.2.2), Wi-Fi, or VPN
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

        // Fallback: reveal webview after 4 seconds even if JS callback is delayed
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            revealAppView()
        }, 4000)

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
        @android.webkit.JavascriptInterface
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
        settings.useWideViewPort = false
        settings.loadWithOverviewMode = false
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.addJavascriptInterface(BroadwayJsBridge(), "BroadwayBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectBroadwayMobileExperience(view, bgColorHex)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
            }
        }

        webView.webChromeClient = WebChromeClient()
    }

    private fun injectBroadwayMobileExperience(view: WebView?, bgColor: String) {
        val css = """
            html, body {
                background-color: $bgColor !important;
                margin: 0 !important;
                padding: 0 !important;
                width: 100vw !important;
                height: 100vh !important;
                overflow: hidden !important;
                user-select: none !important;
                -webkit-user-select: none !important;
            }
        """.trimIndent()

        val js = """
            (function() {
                // 1. Configure viewport meta to enable pinch-to-zoom
                var meta = document.querySelector('meta[name="viewport"]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.name = 'viewport';
                    document.head.appendChild(meta);
                }
                meta.setAttribute('content', 'width=device-width, initial-scale=1.0, minimum-scale=0.5, maximum-scale=5.0, user-scalable=yes');

                // 2. Inject responsive CSS
                var style = document.createElement('style');
                style.type = 'text/css';
                style.innerHTML = `${css}`;
                document.head.appendChild(style);

                // Notify GTK of initial screen size
                if (typeof sendScreenSizeChanged === 'function') {
                    sendScreenSizeChanged();
                }

                // 3. Multi-touch gesture handling: allow native pinch-to-zoom
                if (typeof onTouchStart === 'function' && !window._origOnTouchStart) {
                    window._origOnTouchStart = onTouchStart;
                    onTouchStart = function(ev) {
                        if (ev.touches && ev.touches.length > 1) {
                            return; // Allow native zoom gesture!
                        }
                        return window._origOnTouchStart(ev);
                    };
                }
                if (typeof onTouchMove === 'function' && !window._origOnTouchMove) {
                    window._origOnTouchMove = onTouchMove;
                    onTouchMove = function(ev) {
                        if (ev.touches && ev.touches.length > 1) {
                            return; // Allow native zoom gesture!
                        }
                        return window._origOnTouchMove(ev);
                    };
                }

                // 4. Adapt surfaces to fill both Width AND Height without event loops
                function adaptSurfacesToMobile() {
                    var w = window.innerWidth;
                    var h = window.innerHeight;
                    if (typeof surfaces === 'undefined' || !surfaces) return;

                    var rootSurface = null;
                    var maxArea = 0;
                    for (var id in surfaces) {
                        var s = surfaces[id];
                        if (s && s.div) {
                            var area = (s.width || 0) * (s.height || 0);
                            if (s.transientParent === 0 || area > maxArea) {
                                maxArea = area;
                                rootSurface = s;
                            }
                        }
                    }

                    if (rootSurface && rootSurface.div) {
                        var origW = rootSurface.width || 800;
                        var origH = rootSurface.height || 600;
                        var scale = w / origW;
                        window.broadwayScale = scale;

                        // Apply responsive scaling transform to root surface
                        rootSurface.div.style.position = 'absolute';
                        rootSurface.div.style.transformOrigin = 'top left';
                        rootSurface.div.style.transform = 'scale(' + scale + ')';
                        rootSurface.div.style.left = '0px';
                        rootSurface.div.style.top = '0px';
                        rootSurface.div.style.borderRadius = '0px';
                        rootSurface.div.style.boxShadow = 'none';
                        rootSurface.div.style.border = 'none';

                        // Notify Android bridge that Broadway app is rendered and ready!
                        if (window.BroadwayBridge && typeof window.BroadwayBridge.onAppLoaded === 'function') {
                            window.BroadwayBridge.onAppLoaded();
                        }

                        // Position popup children proportionally
                        for (var childId in surfaces) {
                            var child = surfaces[childId];
                            if (child && child !== rootSurface && child.div) {
                                child.div.style.transformOrigin = 'top left';
                                child.div.style.transform = 'scale(' + scale + ')';
                                if (child.x !== undefined && child.y !== undefined) {
                                    child.div.style.left = (child.x * scale) + 'px';
                                    child.div.style.top = (child.y * scale) + 'px';
                                }
                            }
                        }
                    }
                }
                    }
                }

                // 5. Touch and pointer coordinate mapping through scale
                if (!window._origGetPositionsFromEvent && typeof getPositionsFromEvent === 'function') {
                    window._origGetPositionsFromEvent = getPositionsFromEvent;
                    getPositionsFromEvent = function(ev, relativeId) {
                        var scale = window.broadwayScale || 1.0;
                        var absX = (ev.pageX !== undefined ? ev.pageX : (ev.clientX || 0)) / scale;
                        var absY = (ev.pageY !== undefined ? ev.pageY : (ev.clientY || 0)) / scale;
                        var res = getPositionsFromAbsCoord(absX, absY, relativeId);
                        lastX = res.rootX;
                        lastY = res.rootY;
                        return res;
                    };
                }

                adaptSurfacesToMobile();
                setInterval(adaptSurfacesToMobile, 250);
                window.addEventListener('resize', adaptSurfacesToMobile);
            })();
        """.trimIndent()

        view?.evaluateJavascript(js, null)
    }

    private fun setupControls() {
        binding.btnCloseBroadway.setOnClickListener {
            closeAndExit()
        }

        binding.btnToggleKeyboard.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            binding.broadwayWebView.requestFocus()
            imm.showSoftInput(binding.broadwayWebView, InputMethodManager.SHOW_IMPLICIT)
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
