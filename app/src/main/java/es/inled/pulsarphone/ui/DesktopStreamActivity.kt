package es.inled.pulsarphone.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDesktopStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBackStream.setOnClickListener {
            finish()
        }

        startPcStream()
    }

    private fun startPcStream() {
        val cfg = PulsarPhoneApp.instance.getConfig()
        val streamUrl = "http://${cfg.pcIp}:${cfg.pcPort}/api/stream/pc-to-phone/live.ts?token=${cfg.token}"

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

            binding.desktopVideoView.setOnPreparedListener { mp ->
                binding.streamLoadingLayout.visibility = View.GONE
                mp.isLooping = true
                mp.start()
            }

            binding.desktopVideoView.setOnErrorListener { _, what, extra ->
                Toast.makeText(
                    this@DesktopStreamActivity,
                    "Error al reproducir transmisión ($what, $extra)",
                    Toast.LENGTH_SHORT
                ).show()
                binding.streamLoadingLayout.visibility = View.GONE
                true
            }

            binding.desktopVideoView.setVideoURI(Uri.parse(streamUrl))
        }
    }

    override fun onDestroy() {
        try {
            binding.desktopVideoView.stopPlayback()
        } catch (e: Exception) {}
        super.onDestroy()
    }
}
