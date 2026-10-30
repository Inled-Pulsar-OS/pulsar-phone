package es.inled.pulsarphone.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
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
import org.json.JSONObject

class ShellActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShellBinding
    private var terminalUrl: String? = null
    private var currentFontSize: Int = 20
    private var isCtrlLatched: Boolean = false
    private var isAltLatched: Boolean = false
    private var isWinLatched: Boolean = false

    inner class WebAppInterface {
        @JavascriptInterface
        fun resetModifiers() {
            runOnUiThread {
                setCtrlLatch(false)
                setAltLatch(false)
                setWinLatch(false)
            }
        }
    }

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
                view?.postDelayed({
                    applyFontSizeToWebView()
                    injectTerminalHelperScript()
                }, 300)
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

        wv.addJavascriptInterface(WebAppInterface(), "PulsarNative")
        wv.webChromeClient = WebChromeClient()
    }

    private fun injectTerminalHelperScript() {
        val js = """
            (function() {
                window._pulsarCtrlActive = ${isCtrlLatched};
                window._pulsarAltActive = ${isAltLatched};
                window._pulsarWinActive = ${isWinLatched};

                window._pulsarSetCtrlActive = function(val) { window._pulsarCtrlActive = !!val; };
                window._pulsarSetAltActive = function(val) { window._pulsarAltActive = !!val; };
                window._pulsarSetWinActive = function(val) { window._pulsarWinActive = !!val; };

                function hookTerminal() {
                    if (!window.term || !window.term._core || !window.term._core.coreService) {
                        setTimeout(hookTerminal, 100);
                        return;
                    }
                    if (window._pulsarHooked) return;
                    window._pulsarHooked = true;

                    var cs = window.term._core.coreService;
                    var origTrigger = cs.triggerDataEvent.bind(cs);

                    cs.triggerDataEvent = function(data, wasUserInput) {
                        if (window._pulsarCtrlActive) {
                            window._pulsarCtrlActive = false;
                            if (window.PulsarNative && typeof window.PulsarNative.resetModifiers === 'function') {
                                window.PulsarNative.resetModifiers();
                            }
                            if (typeof data === 'string' && data.length > 0) {
                                if (data === '\u001b[A') return origTrigger('\u001b[1;5A', wasUserInput);
                                if (data === '\u001b[B') return origTrigger('\u001b[1;5B', wasUserInput);
                                if (data === '\u001b[C') return origTrigger('\u001b[1;5C', wasUserInput);
                                if (data === '\u001b[D') return origTrigger('\u001b[1;5D', wasUserInput);

                                var ch = data.charAt(0);
                                var code = ch.toUpperCase().charCodeAt(0);
                                if (code >= 64 && code <= 95) {
                                    var ctrlChar = String.fromCharCode(code - 64);
                                    return origTrigger(ctrlChar + data.slice(1), wasUserInput);
                                } else if (code >= 97 && code <= 122) {
                                    var ctrlChar = String.fromCharCode(code - 96);
                                    return origTrigger(ctrlChar + data.slice(1), wasUserInput);
                                }
                            }
                        } else if (window._pulsarAltActive) {
                            window._pulsarAltActive = false;
                            if (window.PulsarNative && typeof window.PulsarNative.resetModifiers === 'function') {
                                window.PulsarNative.resetModifiers();
                            }
                            if (typeof data === 'string' && data.length > 0) {
                                if (data === '\u001b[A') return origTrigger('\u001b[1;3A', wasUserInput);
                                if (data === '\u001b[B') return origTrigger('\u001b[1;3B', wasUserInput);
                                if (data === '\u001b[C') return origTrigger('\u001b[1;3C', wasUserInput);
                                if (data === '\u001b[D') return origTrigger('\u001b[1;3D', wasUserInput);
                                return origTrigger('\u001b' + data, wasUserInput);
                            }
                        } else if (window._pulsarWinActive) {
                            window._pulsarWinActive = false;
                            if (window.PulsarNative && typeof window.PulsarNative.resetModifiers === 'function') {
                                window.PulsarNative.resetModifiers();
                            }
                            if (typeof data === 'string' && data.length > 0) {
                                return origTrigger('\u001b' + data, wasUserInput);
                            }
                        }
                        return origTrigger(data, wasUserInput);
                    };

                    var ta = document.querySelector('.xterm-helper-textarea');
                    if (ta) {
                        ta.addEventListener('beforeinput', function(e) {
                            if ((window._pulsarCtrlActive || window._pulsarAltActive || window._pulsarWinActive) && e.data) {
                                e.preventDefault();
                                var d = e.data;
                                if (window.term && typeof window.term.input === 'function') {
                                    window.term.input(d);
                                }
                            }
                        }, true);
                    }
                }

                hookTerminal();
            })();
        """.trimIndent()
        binding.webViewTerminal.evaluateJavascript(js, null)
    }

    private fun setCtrlLatch(active: Boolean) {
        isCtrlLatched = active
        if (active) {
            if (isAltLatched) setAltLatch(false)
            if (isWinLatched) setWinLatch(false)
            binding.btnModCtrl.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1F6FEB"))
            binding.btnModCtrl.setTextColor(Color.WHITE)
            binding.btnModCtrl.strokeColor = ColorStateList.valueOf(Color.parseColor("#58A6FF"))
            binding.btnModCtrl.strokeWidth = 3
            Toast.makeText(this, getString(R.string.modifier_ctrl_active), Toast.LENGTH_SHORT).show()
        } else {
            binding.btnModCtrl.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#21262D"))
            binding.btnModCtrl.setTextColor(Color.parseColor("#C9D1D9"))
            binding.btnModCtrl.strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
            binding.btnModCtrl.strokeWidth = 0
        }
        binding.webViewTerminal.evaluateJavascript(
            "if (typeof window._pulsarSetCtrlActive === 'function') window._pulsarSetCtrlActive($active);",
            null
        )
    }

    private fun setAltLatch(active: Boolean) {
        isAltLatched = active
        if (active) {
            if (isCtrlLatched) setCtrlLatch(false)
            if (isWinLatched) setWinLatch(false)
            binding.btnModAlt.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#A371F7"))
            binding.btnModAlt.setTextColor(Color.WHITE)
            binding.btnModAlt.strokeColor = ColorStateList.valueOf(Color.parseColor("#BC8CFF"))
            binding.btnModAlt.strokeWidth = 3
            Toast.makeText(this, getString(R.string.modifier_alt_active), Toast.LENGTH_SHORT).show()
        } else {
            binding.btnModAlt.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#21262D"))
            binding.btnModAlt.setTextColor(Color.parseColor("#C9D1D9"))
            binding.btnModAlt.strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
            binding.btnModAlt.strokeWidth = 0
        }
        binding.webViewTerminal.evaluateJavascript(
            "if (typeof window._pulsarSetAltActive === 'function') window._pulsarSetAltActive($active);",
            null
        )
    }

    private fun setWinLatch(active: Boolean) {
        isWinLatched = active
        if (active) {
            if (isCtrlLatched) setCtrlLatch(false)
            if (isAltLatched) setAltLatch(false)
            binding.btnModWin.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#238636"))
            binding.btnModWin.setTextColor(Color.WHITE)
            binding.btnModWin.strokeColor = ColorStateList.valueOf(Color.parseColor("#3FB950"))
            binding.btnModWin.strokeWidth = 3
            Toast.makeText(this, getString(R.string.modifier_win_active), Toast.LENGTH_SHORT).show()
        } else {
            binding.btnModWin.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#21262D"))
            binding.btnModWin.setTextColor(Color.parseColor("#C9D1D9"))
            binding.btnModWin.strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
            binding.btnModWin.strokeWidth = 0
        }
        binding.webViewTerminal.evaluateJavascript(
            "if (typeof window._pulsarSetWinActive === 'function') window._pulsarSetWinActive($active);",
            null
        )
    }

    private fun setupAccessoryKeys() {
        val wv = binding.webViewTerminal

        // Initial visual states for modifier buttons
        setCtrlLatch(false)
        setAltLatch(false)
        setWinLatch(false)

        // Toggle Expandable Modifiers Toolbar
        binding.btnToggleModifiers.setOnClickListener {
            val isVisible = binding.scrollModifiersBar.visibility == View.VISIBLE
            binding.scrollModifiersBar.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.btnToggleModifiers.text = if (isVisible) "MOD ▾" else "MOD ▴"
            if (!isVisible) {
                binding.btnToggleModifiers.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#30363D"))
                binding.btnToggleModifiers.setTextColor(Color.WHITE)
            } else {
                binding.btnToggleModifiers.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#21262D"))
                binding.btnToggleModifiers.setTextColor(Color.parseColor("#C9D1D9"))
            }
        }

        // Latch Modifiers
        binding.btnModCtrl.setOnClickListener {
            setCtrlLatch(!isCtrlLatched)
        }
        binding.btnModCtrl.setOnLongClickListener {
            showCtrlCombinationsDialog()
            true
        }

        binding.btnModAlt.setOnClickListener {
            setAltLatch(!isAltLatched)
        }
        binding.btnModAlt.setOnLongClickListener {
            showAltCombinationsDialog()
            true
        }

        binding.btnModWin.setOnClickListener {
            setWinLatch(!isWinLatched)
        }

        binding.btnModFn.setOnClickListener {
            showFunctionKeysDialog()
        }

        // Common Combinations
        binding.btnModCtrlZ.setOnClickListener { sendInputToTerminal("\u001a") }
        binding.btnModCtrlD.setOnClickListener { sendInputToTerminal("\u0004") }
        binding.btnModCtrlL.setOnClickListener { sendInputToTerminal("\u000c") }
        binding.btnModCtrlA.setOnClickListener { sendInputToTerminal("\u0001") }
        binding.btnModCtrlE.setOnClickListener { sendInputToTerminal("\u0005") }

        // Symbols
        binding.btnModPipe.setOnClickListener { sendInputToTerminal("|") }
        binding.btnModTilde.setOnClickListener { sendInputToTerminal("~") }
        binding.btnModSlash.setOnClickListener { sendInputToTerminal("/") }
        binding.btnModBackslash.setOnClickListener { sendInputToTerminal("\\") }

        // Home / End / Page Navigation
        binding.btnModHome.setOnClickListener { sendInputToTerminal("\u001b[H") }
        binding.btnModEnd.setOnClickListener { sendInputToTerminal("\u001b[F") }
        binding.btnModPgUp.setOnClickListener { sendInputToTerminal("\u001b[5~") }
        binding.btnModPgDn.setOnClickListener { sendInputToTerminal("\u001b[6~") }

        // Primary Row Keys
        binding.btnKeyEsc.setOnClickListener {
            sendInputToTerminal("\u001b")
        }

        binding.btnKeyTab.setOnClickListener {
            sendInputToTerminal("\t")
        }

        binding.btnKeyCtrlC.setOnClickListener {
            sendInputToTerminal("\u0003")
        }

        binding.btnKeyUp.setOnClickListener {
            sendInputToTerminal("\u001b[A")
        }

        binding.btnKeyDown.setOnClickListener {
            sendInputToTerminal("\u001b[B")
        }

        binding.btnKeyLeft.setOnClickListener {
            sendInputToTerminal("\u001b[D")
        }

        binding.btnKeyRight.setOnClickListener {
            sendInputToTerminal("\u001b[C")
        }

        binding.btnToggleKeyboard.setOnClickListener {
            wv.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
        }

        // Intercept keys from soft/hard keyboard when Ctrl or Alt is latched
        wv.setOnKeyListener { _, _, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (isCtrlLatched) {
                    val unicode = event.unicodeChar
                    if ((unicode in 97..122) || (unicode in 65..90)) {
                        val ctrlCode = Character.toUpperCase(unicode.toChar()).code - 64
                        sendInputToTerminal(ctrlCode.toChar().toString())
                        setCtrlLatch(false)
                        return@setOnKeyListener true
                    }
                }
                if (isAltLatched) {
                    val c = event.unicodeChar.toChar()
                    if (c.isLetterOrDigit()) {
                        sendInputToTerminal("\u001b$c")
                        setAltLatch(false)
                        return@setOnKeyListener true
                    }
                }
                if (isWinLatched) {
                    val c = event.unicodeChar.toChar()
                    if (c.isLetterOrDigit()) {
                        sendInputToTerminal("\u001b$c")
                        setWinLatch(false)
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
    }

    private fun showFunctionKeysDialog() {
        val fKeys = Array(12) { "F${it + 1}" }
        val fSequences = arrayOf(
            "\u001bOP", "\u001bOQ", "\u001bOR", "\u001bOS",
            "\u001b[15~", "\u001b[17~", "\u001b[18~", "\u001b[19~",
            "\u001b[20~", "\u001b[21~", "\u001b[23~", "\u001b[24~"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Function Keys (Fn)")
            .setItems(fKeys) { _, which ->
                sendInputToTerminal(fSequences[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCtrlCombinationsDialog() {
        val items = arrayOf(
            "^C (Interrupt / SIGINT)" to "\u0003",
            "^Z (Suspend / SIGTSTP)" to "\u001a",
            "^D (EOF / Exit)" to "\u0004",
            "^L (Clear screen)" to "\u000c",
            "^A (Start of line)" to "\u0001",
            "^E (End of line)" to "\u0005",
            "^K (Kill to end of line)" to "\u000b",
            "^U (Kill line backward)" to "\u0015",
            "^W (Kill word backward)" to "\u0017",
            "^R (Reverse history search)" to "\u0012",
            "^T (Transpose characters)" to "\u0014",
            "^X (Extra command prefix)" to "\u0018",
            "^\\ (Quit / SIGQUIT)" to "\u001c"
        )
        val labels = items.map { it.first }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Control Combinations (CTRL+)")
            .setItems(labels) { _, which ->
                sendInputToTerminal(items[which].second)
                setCtrlLatch(false)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAltCombinationsDialog() {
        val items = arrayOf(
            "Alt+B (Backward word)" to "\u001bb",
            "Alt+F (Forward word)" to "\u001bf",
            "Alt+D (Kill word forward)" to "\u001bd",
            "Alt+Backspace (Kill word backward)" to "\u001b\u007f",
            "Alt+. (Previous argument)" to "\u001b.",
            "Alt+C (Capitalize word)" to "\u001bc",
            "Alt+U (Uppercase word)" to "\u001bu",
            "Alt+L (Lowercase word)" to "\u001bl"
        )
        val labels = items.map { it.first }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Alt Combinations (ALT+)")
            .setItems(labels) { _, which ->
                sendInputToTerminal(items[which].second)
                setAltLatch(false)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sendInputToTerminal(data: String) {
        val wv = binding.webViewTerminal
        val json = JSONObject.quote(data)
        val js = """
            (function() {
                var d = $json;
                if (window.term) {
                    if (typeof window.term.input === 'function') {
                        window.term.input(d);
                    } else if (window.term._core && window.term._core.coreService) {
                        window.term._core.coreService.triggerDataEvent(d, true);
                    } else if (typeof window.term.paste === 'function') {
                        window.term.paste(d);
                    }
                }
            })();
        """.trimIndent()
        wv.post {
            wv.evaluateJavascript(js, null)
        }
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
