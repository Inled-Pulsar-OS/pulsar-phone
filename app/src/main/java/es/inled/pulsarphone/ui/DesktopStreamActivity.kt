package es.inled.pulsarphone.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import es.inled.pulsarphone.PulsarPhoneApp
import es.inled.pulsarphone.databinding.ActivityDesktopStreamBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class DesktopStreamActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDesktopStreamBinding
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDesktopStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBackStream.setOnClickListener {
            finish()
        }

        setupWebView()
        startPcStream()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.desktopWebView
        webView.setBackgroundColor(0xFF000000.toInt())
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.cacheMode = WebSettings.LOAD_NO_CACHE

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.streamLoadingLayout.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    Toast.makeText(
                        this@DesktopStreamActivity,
                        "Error al conectar con la transmisión del PC",
                        Toast.LENGTH_SHORT
                    ).show()
                    binding.streamLoadingLayout.visibility = View.GONE
                }
            }
        }
    }

    private fun startPcStream() {
        val cfg = PulsarPhoneApp.instance.getConfig()
        val viewUrl = "http://${cfg.pcIp}:${cfg.pcPort}/api/stream/pc-to-phone/view?token=${cfg.token}"

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val req = Request.Builder()
                        .url("http://${cfg.pcIp}:${cfg.pcPort}/api/stream/pc-to-phone/start")
                        .addHeader("X-Pulsar-Token", cfg.token)
                        .post("{}".toRequestBody("application/json".toMediaType()))
                        .build()
                    client.newCall(req).execute().close()
                }
            } catch (e: Exception) {
                // Ignore, server will start on request
            }

            binding.desktopWebView.loadUrl(viewUrl)
        }
    }

    override fun onDestroy() {
        try {
            binding.desktopWebView.loadUrl("about:blank")
            binding.desktopWebView.destroy()
        } catch (e: Exception) {}
        super.onDestroy()
    }
}
