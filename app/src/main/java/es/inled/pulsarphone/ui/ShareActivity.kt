package es.inled.pulsarphone.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import es.inled.pulsarphone.PulsarPhoneApp
import es.inled.pulsarphone.databinding.ActivityShareBinding
import es.inled.pulsarphone.net.FlydropClient
import kotlinx.coroutines.launch

class ShareActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShareBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCancelShare.setOnClickListener {
            finish()
        }

        if (!PulsarPhoneApp.instance.isConfigured()) {
            Toast.makeText(this, "Pulsar Phone no está vinculado a ningún PC", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent) {
        val action = intent.action
        val type = intent.type

        when (action) {
            Intent.ACTION_SEND -> {
                if ("text/plain" == type && intent.getStringExtra(Intent.EXTRA_TEXT) != null) {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                    sendText(sharedText)
                } else {
                    val fileUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    if (fileUri != null) {
                        sendSingleFile(fileUri, type ?: "application/octet-stream")
                    } else {
                        finish()
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val fileUris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (!fileUris.isNullOrEmpty()) {
                    sendMultipleFiles(fileUris)
                } else {
                    finish()
                }
            }
            else -> finish()
        }
    }

    private fun sendText(text: String) {
        binding.shareSubtitle.text = "Enviando texto al portapapeles de FlyDrop..."
        lifecycleScope.launch {
            val res = FlydropClient.sendTextToFlydrop(text)
            if (res.isSuccess) {
                Toast.makeText(this@ShareActivity, "Texto enviado a Pulsar OS", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@ShareActivity, "Error al enviar: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    private fun sendSingleFile(uri: Uri, mimeType: String) {
        val (name, size) = getFileMeta(uri)
        binding.shareSubtitle.text = "Enviando $name (${formatSize(size)})..."

        lifecycleScope.launch {
            val res = FlydropClient.sendFileToFlydrop(
                context = this@ShareActivity,
                uri = uri,
                fileName = name,
                mimeType = mimeType,
                fileSize = size
            ) { progress ->
                binding.shareProgress.isIndeterminate = false
                binding.shareProgress.progress = (progress * 100).toInt()
            }

            if (res.isSuccess) {
                Toast.makeText(this@ShareActivity, "Archivo '$name' enviado a FlyDrop", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@ShareActivity, "Error en transferencia: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    private fun sendMultipleFiles(uris: List<Uri>) {
        binding.shareSubtitle.text = "Enviando ${uris.size} archivos..."
        lifecycleScope.launch {
            var sent = 0
            for (uri in uris) {
                val (name, size) = getFileMeta(uri)
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                binding.shareSubtitle.text = "Enviando ($sent/${uris.size}) $name..."
                val res = FlydropClient.sendFileToFlydrop(this@ShareActivity, uri, name, mime, size)
                if (res.isSuccess) sent++
            }
            Toast.makeText(this@ShareActivity, "$sent/${uris.size} archivos enviados a FlyDrop", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun getFileMeta(uri: Uri): Pair<String, Long> {
        var name = "archivo"
        var size = 0L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
            }
        }
        return Pair(name, size)
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}
