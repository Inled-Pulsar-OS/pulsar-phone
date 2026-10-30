package es.inled.pulsarphone.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
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
import es.inled.pulsarphone.databinding.ActivityShellBinding
import es.inled.pulsarphone.net.PulsarApiClient
import kotlinx.coroutines.launch

class ShellActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShellBinding
    private var terminalUrl: String? = null
    private var currentFontSize: Int = 20

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShellBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentFontSize = getSharedPreferences("pulsar_terminal_prefs", Context.MODE_PRIVATE)
            .getInt("font_size", 20)

        val cfg = PulsarPhoneApp.instance.getConfig()
        binding.toolbarShell.subtitle = "${cfg.pcAlias} (${cfg.pcIp})"

        binding.toolbarShell.setNavigationOnClickListener {
            finish()
        }

        binding.toolbarShell.inflateMenu(R.menu.menu_shell)
        binding.toolbarShell.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_font_decrease -> {
                    changeFontSize(-2)
                    true
                }
                R.id.action_font_increase -> {
                    changeFontSize(+2)
                    true
                }
                R.id.action_font_size -> {
                    showFontSizeDialog()
                    true
                }
                R.id.action_reload -> {
                    loadTerminal()
                    true
                }
                else -> false
            }
        }

        setupWebView()
        setupAccessoryKeys()
        loadTerminal()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val wv = binding.webViewTerminal
        val settings = wv.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        wv.requestFocus()

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.pbTerminalLoading.visibility = View.VISIBLE
                binding.tvTerminalError.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.pbTerminalLoading.visibility = View.GONE
                wv.requestFocus()
                view?.postDelayed({ applyFontSizeToWebView() }, 300)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    binding.pbTerminalLoading.visibility = View.GONE
                    binding.tvTerminalError.visibility = View.VISIBLE
                    binding.tvTerminalError.text = "Error loading terminal: ${error?.description}\n\nEnsure ttyd is running on PC."
                }
            }
        }

        wv.webChromeClient = WebChromeClient()
    }

    private fun setupAccessoryKeys() {
        val wv = binding.webViewTerminal

        binding.btnKeyEsc.setOnClickListener {
            sendKeyToTerminal(KeyEvent.KEYCODE_ESCAPE, "\\u001b")
        }

        binding.btnKeyTab.setOnClickListener {
            sendKeyToTerminal(KeyEvent.KEYCODE_TAB, "\\t")
        }

        binding.btnKeyCtrlC.setOnClickListener {
            // Sends SIGINT (0x03 / Ctrl+C) to active shell process in xterm
            wv.evaluateJavascript(
                """
                (function() {
                    if (window.term) {
                        window.term.write('\x03');
                    } else {
                        var e = new KeyboardEvent('keydown', {key: 'c', ctrlKey: true, keyCode: 67});
                        document.dispatchEvent(e);
                    }
                })();
                """.trimIndent(), null
            )
        }

        binding.btnKeyUp.setOnClickListener {
            sendKeyToTerminal(KeyEvent.KEYCODE_DPAD_UP, "\\u001b[A")
        }

        binding.btnKeyDown.setOnClickListener {
            sendKeyToTerminal(KeyEvent.KEYCODE_DPAD_DOWN, "\\u001b[B")
        }

        binding.btnKeyLeft.setOnClickListener {
            sendKeyToTerminal(KeyEvent.KEYCODE_DPAD_LEFT, "\\u001b[D")
        }

        binding.btnKeyRight.setOnClickListener {
            sendKeyToTerminal(KeyEvent.KEYCODE_DPAD_RIGHT, "\\u001b[C")
        }

        binding.btnToggleKeyboard.setOnClickListener {
            wv.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
        }
    }

    private fun sendKeyToTerminal(keyCode: Int, ansiSequence: String) {
        val wv = binding.webViewTerminal
        wv.requestFocus()

        // 1. Try dispatching native Android key events
        wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))

        // 2. Also inject directly into xterm instance if available
        wv.evaluateJavascript(
            """
            (function() {
                if (window.term) {
                    window.term.write('$ansiSequence');
                }
            })();
            """.trimIndent(), null
        )
    }

    private fun changeFontSize(delta: Int) {
        val newSize = (currentFontSize + delta).coerceIn(12, 38)
        setFontSize(newSize)
    }

    private fun setFontSize(size: Int) {
        currentFontSize = size
        getSharedPreferences("pulsar_terminal_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("font_size", currentFontSize)
            .apply()

        applyFontSizeToWebView()
        Toast.makeText(this, getString(R.string.font_size_toast, currentFontSize), Toast.LENGTH_SHORT).show()
    }

    private fun applyFontSizeToWebView() {
        val js = """
            (function() {
                if (window.term) {
                    window.term.options.fontSize = $currentFontSize;
                    if (typeof window.term.fit === 'function') {
                        window.term.fit();
                    }
                }
            })();
        """.trimIndent()
        binding.webViewTerminal.evaluateJavascript(js, null)
    }

    private fun showFontSizeDialog() {
        val sizes = arrayOf(14, 16, 18, 20, 22, 24, 28, 32)
        val labels = sizes.map { "${it}px" }.toTypedArray()
        val checkedItem = sizes.indexOf(currentFontSize).let { if (it >= 0) it else 3 }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.font_size)
            .setSingleChoiceItems(labels, checkedItem) { dialog, which ->
                setFontSize(sizes[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loadTerminal() {
        binding.pbTerminalLoading.visibility = View.VISIBLE
        binding.tvTerminalError.visibility = View.GONE

        val cfg = PulsarPhoneApp.instance.getConfig()
        lifecycleScope.launch {
            val res = PulsarApiClient.startTerminalSession()
            if (res.isSuccess) {
                val port = res.getOrDefault(7681)
                terminalUrl = "http://${cfg.pcIp}:$port"
                binding.webViewTerminal.loadUrl(terminalUrl!!)
            } else {
                binding.pbTerminalLoading.visibility = View.GONE
                binding.tvTerminalError.visibility = View.VISIBLE
                val err = res.exceptionOrNull()?.message ?: "Unknown error"
                binding.tvTerminalError.text = "Failed to connect to PC terminal service:\n$err"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.webViewTerminal.destroy()
    }
}
