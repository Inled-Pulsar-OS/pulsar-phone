package es.inled.pulsarphone.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import es.inled.pulsarphone.R
import es.inled.pulsarphone.databinding.ActivityFileReceivedBinding
import java.io.File

class FileReceivedActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileReceivedBinding

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_SENDER = "extra_sender"
        const val EXTRA_MIME = "extra_mime"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileReceivedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: ""
        val sender = intent.getStringExtra(EXTRA_SENDER) ?: "FlyDrop"
        val mimeType = intent.getStringExtra(EXTRA_MIME) ?: "*/*"

        val file = File(filePath)

        binding.tvSender.text = getString(R.string.flydrop_text_received_title, sender)
        binding.tvFileName.text = file.name

        val sizeStr = if (file.exists()) {
            val bytes = file.length()
            val mb = bytes / (1024.0 * 1024.0)
            if (mb >= 1.0) String.format("%.1f MB", mb) else String.format("%.0f KB", bytes / 1024.0)
        } else {
            ""
        }
        binding.tvFileDetails.text = "$sizeStr • ${file.parentFile?.name ?: "Download"}"

        binding.btnOpen.setOnClickListener {
            if (file.exists()) {
                try {
                    val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(viewIntent)
                } catch (e: Exception) {
                    Toast.makeText(this, "No se pudo abrir el archivo: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            finish()
        }

        binding.btnClose.setOnClickListener {
            finish()
        }
    }
}
