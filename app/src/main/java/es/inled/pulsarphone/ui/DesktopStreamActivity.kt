package es.inled.pulsarphone.ui

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import es.inled.pulsarphone.PulsarPhoneApp
import es.inled.pulsarphone.R
import es.inled.pulsarphone.data.PcMonitor
import es.inled.pulsarphone.databinding.ActivityDesktopStreamBinding
import es.inled.pulsarphone.net.PulsarApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

class DesktopStreamActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDesktopStreamBinding
    private var availableMonitors: List<PcMonitor> = emptyList()
    private var currentConnector: String? = null
    private var pcmAudioJob: Job? = null

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
        loadMonitors()
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
                        getString(R.string.check_pulsar_vpn_open, PulsarPhoneApp.instance.getConfig().pcIp),
                        Toast.LENGTH_SHORT
                    ).show()
                    binding.streamLoadingLayout.visibility = View.GONE
                }
            }
        }
    }

    private fun startPcStream() {
        val cfg = PulsarPhoneApp.instance.getConfig()
        val monParam = if (currentConnector != null) "&monitor=$currentConnector" else ""
        val viewUrl = "http://${cfg.pcIp}:${cfg.pcPort}/api/stream/pc-to-phone/view?token=${cfg.token}$monParam&noaudio=1"

        lifecycleScope.launch {
            try {
                PulsarApiClient.startPcDesktopStream(currentConnector)
            } catch (e: Exception) {
                // Ignore, server starts on request
            }

            binding.desktopWebView.loadUrl(viewUrl)
            startPcmAudioStream()
        }
    }

    private fun startPcmAudioStream() {
        stopPcmAudioStream()
        val cfg = PulsarPhoneApp.instance.getConfig()
        val audioUrl = "http://${cfg.pcIp}:${cfg.pcPort}/api/stream/pc-to-phone/audio.pcm?token=${cfg.token}"

        pcmAudioJob = lifecycleScope.launch(Dispatchers.IO) {
            var audioTrack: AudioTrack? = null
            var connection: HttpURLConnection? = null
            try {
                val sampleRate = 48000
                val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                val bufferSize = (minBufSize * 2).coerceAtLeast(4096)

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()

                audioTrack.play()

                val url = URL(audioUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 0
                    doInput = true
                    setRequestProperty("X-Pulsar-Token", cfg.token)
                }
                connection.connect()

                val inputStream = BufferedInputStream(connection.inputStream, 4096)
                val chunk = ByteArray(1920) // 10ms of 48kHz stereo s16le

                while (isActive) {
                    var offset = 0
                    while (offset < chunk.size && isActive) {
                        val r = inputStream.read(chunk, offset, chunk.size - offset)
                        if (r < 0) break
                        offset += r
                    }
                    if (offset <= 0) break
                    audioTrack.write(chunk, 0, offset)
                }
            } catch (e: Exception) {
                // Connection closed or error
            } finally {
                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                } catch (e: Exception) {}
                try {
                    connection?.disconnect()
                } catch (e: Exception) {}
            }
        }
    }

    private fun stopPcmAudioStream() {
        pcmAudioJob?.cancel()
        pcmAudioJob = null
    }

    override fun onResume() {
        super.onResume()
        if (pcmAudioJob == null) {
            startPcmAudioStream()
        }
    }

    override fun onPause() {
        super.onPause()
        stopPcmAudioStream()
    }

    private fun loadMonitors() {
        lifecycleScope.launch {
            val res = PulsarApiClient.getMonitors()
            if (res.isSuccess) {
                availableMonitors = res.getOrDefault(emptyList())
                if (availableMonitors.size > 1) {
                    binding.btnSelectMonitor.visibility = View.VISIBLE
                    binding.btnSelectMonitor.setOnClickListener {
                        showMonitorSelectionDialog()
                    }
                    val primary = availableMonitors.find { it.isPrimary } ?: availableMonitors.firstOrNull()
                    if (primary != null && currentConnector == null) {
                        currentConnector = primary.connector
                        binding.tvStreamTitle.text = primary.name
                    }
                }
            }
        }
    }

    private fun showMonitorSelectionDialog() {
        if (availableMonitors.isEmpty()) return
        val items = availableMonitors.map { m ->
            if (m.isPrimary) {
                getString(R.string.monitor_primary_badge, m.name)
            } else {
                m.name
            }
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_monitor_title)
            .setItems(items) { _, which ->
                val selected = availableMonitors[which]
                switchMonitor(selected)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun switchMonitor(monitor: PcMonitor) {
        currentConnector = monitor.connector
        binding.tvStreamTitle.text = monitor.name
        Toast.makeText(this, getString(R.string.monitor_switched, monitor.name), Toast.LENGTH_SHORT).show()
        binding.streamLoadingLayout.visibility = View.VISIBLE

        val cfg = PulsarPhoneApp.instance.getConfig()
        lifecycleScope.launch {
            PulsarApiClient.startPcDesktopStream(monitor.connector)
            val viewUrl = "http://${cfg.pcIp}:${cfg.pcPort}/api/stream/pc-to-phone/view?token=${cfg.token}&monitor=${monitor.connector}&noaudio=1&t=${System.currentTimeMillis()}"
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
