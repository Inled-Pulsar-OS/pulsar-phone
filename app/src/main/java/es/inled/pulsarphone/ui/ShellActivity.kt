package es.inled.pulsarphone.ui

import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import es.inled.pulsarphone.PulsarPhoneApp
import es.inled.pulsarphone.R
import es.inled.pulsarphone.databinding.ActivityShellBinding
import es.inled.pulsarphone.net.PulsarApiClient
import kotlinx.coroutines.launch

class ShellActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShellBinding
    private var isExecuting: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShellBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val cfg = PulsarPhoneApp.instance.getConfig()
        binding.toolbarShell.subtitle = "${cfg.pcAlias} (${cfg.pcIp})"

        binding.toolbarShell.setNavigationOnClickListener {
            finish()
        }

        binding.toolbarShell.inflateMenu(R.menu.menu_shell)
        binding.toolbarShell.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_clear -> {
                    binding.tvTerminalOutput.text = getString(R.string.terminal_empty)
                    true
                }
                else -> false
            }
        }

        setupQuickChips()
        setupInput()
    }

    private fun setupQuickChips() {
        binding.chipLs.setOnClickListener { execute("ls -la") }
        binding.chipTop.setOnClickListener { execute("top -bn1 | head -n 25") }
        binding.chipDf.setOnClickListener { execute("df -h") }
        binding.chipUptime.setOnClickListener { execute("uptime") }
        binding.chipSystemctl.setOnClickListener { execute("systemctl list-units --state=running | head -n 30") }
        binding.chipWhoami.setOnClickListener { execute("whoami && uname -a") }
        binding.chipFree.setOnClickListener { execute("free -h") }
    }

    private fun setupInput() {
        binding.btnExecCommand.setOnClickListener {
            val cmd = binding.etCommandInput.text.toString().trim()
            if (cmd.isNotEmpty()) {
                execute(cmd)
            }
        }

        binding.etCommandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                val cmd = binding.etCommandInput.text.toString().trim()
                if (cmd.isNotEmpty()) {
                    execute(cmd)
                }
                true
            } else {
                false
            }
        }
    }

    private fun execute(command: String) {
        if (isExecuting) {
            Toast.makeText(this, "A command is already executing...", Toast.LENGTH_SHORT).show()
            return
        }

        isExecuting = true
        binding.btnExecCommand.isEnabled = false
        binding.etCommandInput.setText("")

        appendTerminalCommand(command)

        lifecycleScope.launch {
            val res = PulsarApiClient.executeShellCommand(command)
            isExecuting = false
            binding.btnExecCommand.isEnabled = true

            if (res.isSuccess) {
                val out = res.getOrNull()
                if (out != null) {
                    appendTerminalOutput(out.stdout, out.stderr, out.exitCode)
                }
            } else {
                val err = res.exceptionOrNull()?.message ?: "Unknown error"
                appendTerminalError("Connection error: $err")
            }
        }
    }

    private fun appendTerminalCommand(command: String) {
        val currentText = binding.tvTerminalOutput.text
        val ssb = if (currentText.toString() == getString(R.string.terminal_empty)) {
            SpannableStringBuilder()
        } else {
            SpannableStringBuilder(currentText).append("\n\n")
        }

        val prompt = "$ "
        val promptStart = ssb.length
        ssb.append(prompt)
        ssb.setSpan(
            ForegroundColorSpan(0xFF2EA043.toInt()), // Green
            promptStart,
            ssb.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        val cmdStart = ssb.length
        ssb.append(command)
        ssb.setSpan(
            ForegroundColorSpan(0xFFFFFFFF.toInt()), // White
            cmdStart,
            ssb.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        binding.tvTerminalOutput.text = ssb
        scrollToBottom()
    }

    private fun appendTerminalOutput(stdout: String, stderr: String, exitCode: Int) {
        val ssb = SpannableStringBuilder(binding.tvTerminalOutput.text)

        if (stdout.isNotEmpty()) {
            ssb.append("\n")
            val start = ssb.length
            ssb.append(stdout.trimEnd())
            ssb.setSpan(
                ForegroundColorSpan(0xFFE6EDF3.toInt()), // Light gray
                start,
                ssb.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        if (stderr.isNotEmpty()) {
            ssb.append("\n")
            val start = ssb.length
            ssb.append(stderr.trimEnd())
            ssb.setSpan(
                ForegroundColorSpan(0xFFF85149.toInt()), // Red
                start,
                ssb.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        if (exitCode != 0) {
            ssb.append("\n")
            val start = ssb.length
            ssb.append("[Process exited with code $exitCode]")
            ssb.setSpan(
                ForegroundColorSpan(0xFFD29922.toInt()), // Amber
                start,
                ssb.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        binding.tvTerminalOutput.text = ssb
        scrollToBottom()
    }

    private fun appendTerminalError(error: String) {
        val ssb = SpannableStringBuilder(binding.tvTerminalOutput.text)
        ssb.append("\n")
        val start = ssb.length
        ssb.append(error)
        ssb.setSpan(
            ForegroundColorSpan(0xFFF85149.toInt()),
            start,
            ssb.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.tvTerminalOutput.text = ssb
        scrollToBottom()
    }

    private fun scrollToBottom() {
        binding.scrollTerminal.post {
            binding.scrollTerminal.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }
}
