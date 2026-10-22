package es.inled.pulsarphone.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import es.inled.pulsarphone.PulsarPhoneApp
import es.inled.pulsarphone.R
import es.inled.pulsarphone.data.ConnectionConfig
import es.inled.pulsarphone.data.PcApp
import es.inled.pulsarphone.databinding.ActivityMainBinding
import es.inled.pulsarphone.net.PulsarApiClient
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: AppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()

        // Check if opened via pulsar://pair?ip=... deep link
        handleDeepLink(intent)

        refreshStatusAndApps()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleDeepLink(it) }
    }

    private fun setupRecyclerView() {
        adapter = AppAdapter { app ->
            launchPcApp(app)
        }
        binding.appsRecyclerView.layoutManager = GridLayoutManager(this, 3)
        binding.appsRecyclerView.adapter = adapter
    }

    private fun setupListeners() {
        binding.swipeRefresh.setOnRefreshListener {
            refreshStatusAndApps()
        }

        binding.btnPair.setOnClickListener {
            showPairDialog()
        }

        binding.btnClipboardSync.setOnClickListener {
            syncPhoneClipboardToPc()
        }

        binding.btnMountFiles.setOnClickListener {
            openSystemFileManager()
        }
    }

    private fun handleDeepLink(intent: Intent) {
        val data: Uri? = intent.data
        if (data != null && data.scheme == "pulsar" && data.host == "pair") {
            val ip = data.getQueryParameter("ip") ?: ""
            val portStr = data.getQueryParameter("port") ?: "8989"
            val token = data.getQueryParameter("token") ?: ""
            val alias = data.getQueryParameter("alias") ?: "Pulsar PC"

            if (ip.isNotBlank() && token.isNotBlank()) {
                val cfg = ConnectionConfig(
                    pcIp = ip,
                    pcPort = portStr.toIntOrNull() ?: 8989,
                    token = token,
                    pcAlias = alias
                )
                PulsarPhoneApp.instance.saveConfig(cfg)
                Toast.makeText(this, "Vinculado con $alias ($ip)", Toast.LENGTH_SHORT).show()
                refreshStatusAndApps()
            }
        }
    }

    private fun refreshStatusAndApps() {
        binding.swipeRefresh.isRefreshing = true
        val cfg = PulsarPhoneApp.instance.getConfig()

        if (!PulsarPhoneApp.instance.isConfigured()) {
            binding.statusIndicator.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.status_disconnected)
            binding.statusText.text = getString(R.string.vpn_disconnected)
            binding.pcInfoText.text = "Pulsa 'Vincular' para escanear o ingresar el PC"
            binding.emptyAppsText.visibility = View.VISIBLE
            binding.emptyAppsText.text = "Vincula tu PC para ver y abrir sus aplicaciones"
            binding.appsCount.text = "0 apps"
            adapter.setApps(emptyList())
            binding.swipeRefresh.isRefreshing = false
            return
        }

        lifecycleScope.launch {
            val isOnline = PulsarApiClient.ping()
            if (isOnline) {
                binding.statusIndicator.backgroundTintList =
                    ContextCompat.getColorStateList(this@MainActivity, R.color.status_connected)
                binding.statusText.text = "${cfg.pcAlias} (Conectado)"
                binding.pcInfoText.text = "IP: ${cfg.pcIp} • WireGuard Seguro (Tailscale)"

                // Fetch apps
                val appsResult = PulsarApiClient.getApps()
                binding.swipeRefresh.isRefreshing = false

                if (appsResult.isSuccess) {
                    val apps = appsResult.getOrDefault(emptyList())
                    adapter.setApps(apps)
                    binding.appsCount.text = "${apps.size} apps"
                    binding.emptyAppsText.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
                } else {
                    binding.emptyAppsText.visibility = View.VISIBLE
                    binding.emptyAppsText.text = "Error al obtener aplicaciones del PC"
                }
            } else {
                binding.statusIndicator.backgroundTintList =
                    ContextCompat.getColorStateList(this@MainActivity, R.color.status_disconnected)
                binding.statusText.text = "${cfg.pcAlias} (Sin respuesta)"
                binding.pcInfoText.text = "Comprueba que Tailscale esté activo en tu PC (${cfg.pcIp})"
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun launchPcApp(app: PcApp) {
        Toast.makeText(this, "Abriendo ${app.name} en el PC...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val res = PulsarApiClient.launchApp(app.id)
            if (res.isSuccess) {
                val broadwayUrl = res.getOrNull()
                val intent = Intent(this@MainActivity, BroadwayActivity::class.java).apply {
                    putExtra(BroadwayActivity.EXTRA_APP_NAME, app.name)
                    putExtra(BroadwayActivity.EXTRA_BROADWAY_URL, broadwayUrl)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this@MainActivity, "Error al lanzar app: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun syncPhoneClipboardToPc() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val text = clipData.getItemAt(0).text?.toString() ?: ""
            if (text.isNotBlank()) {
                lifecycleScope.launch {
                    val res = PulsarApiClient.syncClipboardToPc(text)
                    if (res.isSuccess) {
                        Toast.makeText(this@MainActivity, "Portapapeles enviado al PC", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Error al sincronizar portapapeles", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this, "El portapapeles está vacío", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "El portapapeles está vacío", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSystemFileManager() {
        try {
            // Opens Android's Storage Access Framework file picker
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Abre la app 'Archivos' de Android para ver el PC", Toast.LENGTH_LONG).show()
        }
    }

    private fun showPairDialog() {
        val current = PulsarPhoneApp.instance.getConfig()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }

        val inputUri = EditText(this).apply {
            hint = "Pega aquí pulsar://pair?ip=... o IP del PC"
            setText(if (current.pcIp != "127.0.0.1") current.pcIp else "")
        }
        val inputToken = EditText(this).apply {
            hint = "Token de seguridad (X-Pulsar-Token)"
            setText(current.token)
        }

        layout.append(inputUri, inputToken)

        AlertDialog.Builder(this)
            .setTitle("Vincular con PC")
            .setMessage("Pega el enlace de emparejamiento generado en Pulsar VPN o ingresa la IP y token:")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                val raw = inputUri.text.toString().trim()
                if (raw.startsWith("pulsar://")) {
                    handleDeepLink(Intent(Intent.ACTION_VIEW, Uri.parse(raw)))
                } else {
                    val newCfg = ConnectionConfig(
                        pcIp = raw.ifBlank { "127.0.0.1" },
                        token = inputToken.text.toString().trim(),
                        pcAlias = current.pcAlias
                    )
                    PulsarPhoneApp.instance.saveConfig(newCfg)
                    refreshStatusAndApps()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun LinearLayout.append(vararg views: View) {
        for (v in views) addView(v)
    }
}
