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
            Toast.makeText(this, getString(es.inled.pulsarphone.R.string.pc_not_paired), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (PulsarPhoneApp.instance.isConfigured()) {
            handleShareIntent(intent)
        }
    }

    private fun handleShareIntent(intent: Intent) {
        val action = intent.action
        val type = intent.type
        android.util.Log.i("ShareActivity", "handleShareIntent action=$action, type=$type")

        when (action) {
            Intent.ACTION_SEND -> {
                if ("text/plain" == type && intent.getStringExtra(Intent.EXTRA_TEXT) != null) {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                    sendText(sharedText)
                } else {
                    val fileUri: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
                        ?: intent.clipData?.let { if (it.itemCount > 0) it.getItemAt(0).uri else null }
                        ?: intent.data
                    android.util.Log.i("ShareActivity", "handleShareIntent fileUri=$fileUri")
                    if (fileUri != null) {
                        val resolvedMime = contentResolver.getType(fileUri) ?: type ?: "application/octet-stream"
                        sendSingleFile(fileUri, resolvedMime)
                    } else {
                        android.util.Log.w("ShareActivity", "No fileUri found in intent, finishing")
                        finish()
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val fileUris: List<Uri> = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    ?: run {
                        val list = mutableListOf<Uri>()
                        intent.clipData?.let { cd ->
                            for (i in 0 until cd.itemCount) {
                                cd.getItemAt(i).uri?.let { list.add(it) }
                            }
                        }
                        list
                    }
                if (fileUris.isNotEmpty()) {
                    sendMultipleFiles(fileUris)
                } else {
                    finish()
                }
            }
            else -> finish()
        }
    }

    private fun sendText(text: String) {
        binding.shareSubtitle.text = getString(es.inled.pulsarphone.R.string.sending_text_clipboard)
        lifecycleScope.launch {
            val res = FlydropClient.sendTextToFlydrop(text)
            if (res.isSuccess) {
                Toast.makeText(this@ShareActivity, getString(es.inled.pulsarphone.R.string.text_sent_success), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@ShareActivity, getString(es.inled.pulsarphone.R.string.error_sending, res.exceptionOrNull()?.message ?: ""), Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    private fun sendSingleFile(uri: Uri, mimeType: String) {
        val (name, size) = getFileMeta(uri)
        android.util.Log.i("ShareActivity", "sendSingleFile name=$name, size=$size, mimeType=$mimeType, uri=$uri")
        binding.shareSubtitle.text = getString(es.inled.pulsarphone.R.string.sending_file, name, formatSize(size))

        lifecycleScope.launch {
            val res = FlydropClient.sendFileToFlydrop(
                context = this@ShareActivity,
                uri = uri,
                fileName = name,
                mimeType = mimeType,
                fileSize = size
            ) { progress ->
                runOnUiThread {
                    binding.shareProgress.isIndeterminate = false
                    binding.shareProgress.progress = (progress * 100).toInt()
                }
            }

            android.util.Log.i("ShareActivity", "sendFileToFlydrop result=${res.isSuccess}, error=${res.exceptionOrNull()}")

            if (res.isSuccess) {
                Toast.makeText(this@ShareActivity, getString(es.inled.pulsarphone.R.string.file_sent_success, name), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@ShareActivity, getString(es.inled.pulsarphone.R.string.error_sending, res.exceptionOrNull()?.message ?: ""), Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    private fun sendMultipleFiles(uris: List<Uri>) {
        binding.shareSubtitle.text = getString(es.inled.pulsarphone.R.string.sending_multiple_files, uris.size)
        lifecycleScope.launch {
            var sent = 0
            for (uri in uris) {
                val (name, size) = getFileMeta(uri)
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                binding.shareSubtitle.text = getString(es.inled.pulsarphone.R.string.sending_file, name, formatSize(size))
                val res = FlydropClient.sendFileToFlydrop(
                    context = this@ShareActivity,
                    uri = uri,
                    fileName = name,
                    mimeType = mime,
                    fileSize = size
                ) { progress ->
                    runOnUiThread {
                        binding.shareProgress.isIndeterminate = false
                        binding.shareProgress.progress = (progress * 100).toInt()
                    }
                }
                if (res.isSuccess) sent++
            }
            Toast.makeText(this@ShareActivity, getString(es.inled.pulsarphone.R.string.multiple_files_sent_success, sent, uris.size), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun getFileMeta(uri: Uri): Pair<String, Long> {
        var name = "archivo"
        var size = 0L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ShareActivity", "contentResolver.query error", e)
        }

        if (size <= 0L) {
            try {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    val len = afd.length
                    if (len > 0L) size = len
                }
            } catch (e: Exception) {
                android.util.Log.e("ShareActivity", "openAssetFileDescriptor error", e)
            }
        }

        if (name == "archivo") {
            uri.lastPathSegment?.let { segment ->
                val clean = segment.substringAfterLast("/")
                if (clean.isNotBlank()) name = clean
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
