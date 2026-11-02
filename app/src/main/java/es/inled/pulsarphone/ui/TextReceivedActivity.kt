package es.inled.pulsarphone.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import es.inled.pulsarphone.R
import es.inled.pulsarphone.databinding.ActivityTextReceivedBinding

class TextReceivedActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTextReceivedBinding

    companion object {
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_SENDER = "extra_sender"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTextReceivedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        val sender = intent.getStringExtra(EXTRA_SENDER) ?: "FlyDrop"

        binding.tvSender.text = getString(R.string.flydrop_text_received_title, sender)
        binding.tvContent.text = text

        binding.btnCopy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("FlyDrop", text))
            Toast.makeText(this, getString(R.string.flydrop_copied_toast), Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnClose.setOnClickListener {
            finish()
        }
    }
}
