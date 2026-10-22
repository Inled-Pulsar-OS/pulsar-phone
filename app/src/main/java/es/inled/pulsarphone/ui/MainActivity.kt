package es.inled.pulsarphone.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import es.inled.pulsarphone.PulsarPhoneApp
import es.inled.pulsarphone.R
import es.inled.pulsarphone.data.ConnectionConfig
import es.inled.pulsarphone.data.PcApp
import es.inled.pulsarphone.databinding.ActivityMainBinding
import es.inled.pulsarphone.net.PulsarApiClient
import es.inled.pulsarphone.net.PulsarVpnManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: AppAdapter

    // Camera QR Code Scanner Launcher
    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleScannedUri(result.contents)
        }
    }

    // Android VpnService permission launcher
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startInternalVpn()
        } else {
            Toast.makeText(this, "Permiso de VPN no concedido", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()

        handleDeepLink(intent)
        refreshStatusAndApps()
        updateVpnButtonState()
    }

    override fun onResume() {
        super.onResume()
        updateVpnButtonState()
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

        binding.btnScanQr.setOnClickListener {
            startQrScanner()
        }

        binding.btnToggleVpn.setOnClickListener {
            toggleVpn()
        }

        binding.btnClipboardSync.setOnClickListener {
            syncPhoneClipboardToPc()
        }

        binding.btnMountFiles.setOnClickListener {
            openSystemFileManager()
        }
    }

    private fun startQrScanner() {
        val options = ScanOptions().apply {
            setPrompt("Apunta al código QR en Pulsar VPN en tu PC")
            setBeepEnabled(true)
            setOrientationLocked(false)
            setBarcodeImageEnabled(false)
        }
        qrScannerLauncher.launch(options)
    }

    private fun handleScannedUri(content: String) {
        val uri = Uri.parse(content.trim())
        if (uri.scheme == "pulsar" && uri.host == "pair") {
            val ip = uri.getQueryParameter("ip") ?: ""
            val portStr = uri.getQueryParameter("port") ?: "8989"
            val token = uri.getQueryParameter("token") ?: ""
            val alias = uri.getQueryParameter("alias") ?: "Pulsar PC"

            if (ip.isNotBlank() && token.isNotBlank()) {
                val cfg = ConnectionConfig(
                    pcIp = ip,
                    pcPort = portStr.toIntOrNull() ?: 8989,
                    token = token,
                    pcAlias = alias
                )
                PulsarPhoneApp.instance.saveConfig(cfg)
                Toast.makeText(this, "¡PC vinculado con éxito! ($alias)", Toast.LENGTH_LONG).show()
                refreshStatusAndApps()
                return
            }
        }

        Toast.makeText(this, "Código QR leído: $content", Toast.LENGTH_SHORT).show()
    }

    private fun handleDeepLink(intent: Intent) {
        val data: Uri? = intent.data
        if (data != null) {
            handleScannedUri(data.toString())
        }
    }

    private fun toggleVpn() {
        if (PulsarVpnManager.isVpnRunning()) {
            lifecycleScope.launch {
                PulsarVpnManager.stopTunnel()
                updateVpnButtonState()
                Toast.makeText(this@MainActivity, "VPN Desconectada", Toast.LENGTH_SHORT).show()
            }
        } else {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
            } else {
                startInternalVpn()
            }
        }
    }

    private fun startInternalVpn() {
        val cfg = PulsarPhoneApp.instance.getConfig()
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Iniciando VPN segura...", Toast.LENGTH_SHORT).show()
            val res = PulsarVpnManager.startTunnel(
                serverEndpoint = cfg.pcIp,
                serverPubKey = "PulsarServerPublicKeySampleOrGenerated",
                serverPort = 51820
            )
            updateVpnButtonState()
            if (res.isSuccess) {
                Toast.makeText(this@MainActivity, "VPN Conectada a ${cfg.pcAlias}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateVpnButtonState() {
        val isRunning = PulsarVpnManager.isVpnRunning()
        if (isRunning) {
            binding.btnToggleVpn.text = "VPN: Activa"
        } else {
            binding.btnToggleVpn.text = "VPN: Inactiva"
        }
    }

    private fun refreshStatusAndApps() {
        binding.swipeRefresh.isRefreshing = true
        val cfg = PulsarPhoneApp.instance.getConfig()

        if (!PulsarPhoneApp.instance.isConfigured()) {
            binding.statusIndicator.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.status_disconnected)
            binding.statusText.text = getString(R.string.vpn_disconnected)
            binding.pcInfoText.text = "Pulsa 'Escanear QR' para vincular con tu PC"
            binding.emptyAppsText.visibility = View.VISIBLE
            binding.emptyAppsText.text = "Escanea el código QR de Pulsar VPN en tu PC para ver tus aplicaciones"
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
                binding.pcInfoText.text = "IP: ${cfg.pcIp} • Túnel Seguro Activo"

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
                binding.pcInfoText.text = "Comprueba que Pulsar VPN esté abierto en tu PC (${cfg.pcIp})"
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
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Abre la app 'Archivos' de Android para ver el PC", Toast.LENGTH_LONG).show()
        }
    }
}
