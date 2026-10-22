package es.inled.pulsarphone.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBroadwayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appName = intent.getStringExtra(EXTRA_APP_NAME) ?: "Pulsar App"
        val cfg = PulsarPhoneApp.instance.getConfig()
        broadwayUrl = intent.getStringExtra(EXTRA_BROADWAY_URL)
            ?: "http://${cfg.pcIp}:${cfg.broadwayPort}/"

        binding.broadwayAppTitle.text = appName

        setupWebView()
        setupControls()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                closeAndExit()
            }
        })

        binding.broadwayWebView.loadUrl(broadwayUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.broadwayWebView
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        webView.webViewClient = object : WebViewClient() {
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

    private fun setupControls() {
        binding.btnCloseBroadway.setOnClickListener {
            closeAndExit()
        }

        binding.btnToggleKeyboard.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            binding.broadwayWebView.requestFocus()
            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
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
