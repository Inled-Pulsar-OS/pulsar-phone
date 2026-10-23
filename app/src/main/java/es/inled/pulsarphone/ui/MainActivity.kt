package es.inled.pulsarphone.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import es.inled.pulsarphone.PulsarPhoneApp
import es.inled.pulsarphone.R
import es.inled.pulsarphone.data.ConnectionConfig
import es.inled.pulsarphone.data.PcApp
import es.inled.pulsarphone.databinding.ActivityMainBinding
import es.inled.pulsarphone.net.FlydropClient
import es.inled.pulsarphone.net.PulsarApiClient
import es.inled.pulsarphone.net.PulsarVpnManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: AppAdapter
    private var allApps: List<PcApp> = emptyList()

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
            Toast.makeText(this, getString(R.string.vpn_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    // Direct In-App File Selector for FlyDrop streaming
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            sendSelectedFilesToPc(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        setupVpnMonitor()

        handleDeepLink(intent)
        refreshStatusAndApps()
        updateVpnButtonState()
    }

    override fun onResume() {
        super.onResume()
        updateVpnButtonState()
        updateNotificationSyncButton()
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
            filePickerLauncher.launch(arrayOf("*/*"))
        }

        binding.btnWatchPcScreen.setOnClickListener {
            val intent = Intent(this, DesktopStreamActivity::class.java)
            startActivity(intent)
        }

        binding.btnCastPhoneToPc.setOnClickListener {
            castPhoneScreenToPc()
        }

        binding.searchAppsInput.doAfterTextChanged { text ->
            val query = text?.toString().orEmpty()
            binding.btnClearSearch.visibility = if (query.isNotBlank()) View.VISIBLE else View.GONE
            filterApps(query)
        }

        binding.btnClearSearch.setOnClickListener {
            binding.searchAppsInput.text?.clear()
        }

        updateNotificationSyncButton()
    }

    private fun isNotificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(packageName)
    }

    private fun updateNotificationSyncButton() {
        val granted = isNotificationAccessGranted()
        binding.btnNotificationSync.text = getString(R.string.btn_notifications)
        if (granted) {
            binding.btnNotificationSync.setIconResource(android.R.drawable.ic_dialog_info)
            binding.btnNotificationSync.setOnClickListener {
                lifecycleScope.launch {
                    val res = PulsarApiClient.sendNotification(
                        es.inled.pulsarphone.data.NotificationPayload(
                            appName = "Pulsar Phone",
                            title = "Pulsar Phone",
                            text = getString(R.string.test_notification_sent),
                            packageName = packageName
                        )
                    )
                    if (res.isSuccess) {
                        Toast.makeText(this@MainActivity, getString(R.string.test_notification_sent), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, getString(R.string.error_sending, res.exceptionOrNull()?.message ?: ""), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            binding.btnNotificationSync.setIconResource(android.R.drawable.ic_lock_idle_alarm)
            binding.btnNotificationSync.setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                Toast.makeText(this, getString(R.string.grant_notification_permission), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startQrScanner() {
        val options = ScanOptions().apply {
            setPrompt(getString(R.string.scan_qr_prompt))
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
                Toast.makeText(this, getString(R.string.pc_paired_success, alias), Toast.LENGTH_LONG).show()
                refreshStatusAndApps()
                return
            }
        }

        Toast.makeText(this, content, Toast.LENGTH_SHORT).show()
    }

    private fun handleDeepLink(intent: Intent) {
        val data: Uri? = intent.data
        if (data != null) {
            handleScannedUri(data.toString())
        }
    }

    private fun isRealVpnActive(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun setupVpnMonitor() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .build()
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runOnUiThread { updateVpnButtonState() }
                }
                override fun onLost(network: Network) {
                    runOnUiThread { updateVpnButtonState() }
                }
            })
        } catch (e: Exception) {
            // fallback handled by onResume
        }
    }

    private fun toggleVpn() {
        val isRealVpn = isRealVpnActive() || PulsarVpnManager.isVpnRunning()
        if (isRealVpn) {
            // If internal WireGuard tunnel is active, stop it
            if (PulsarVpnManager.isVpnRunning()) {
                lifecycleScope.launch {
                    PulsarVpnManager.stopTunnel()
                    updateVpnButtonState()
                    Toast.makeText(this@MainActivity, getString(R.string.vpn_disconnected_toast), Toast.LENGTH_SHORT).show()
                }
            } else {
                // If system Tailscale VPN is active, open Tailscale to toggle
                val tsIntent = packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
                if (tsIntent != null) {
                    startActivity(tsIntent)
                } else {
                    try {
                        startActivity(Intent("android.net.vpn.SETTINGS"))
                    } catch (e: Exception) {
                        Toast.makeText(this, getString(R.string.vpn_active_system), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            // Not connected: open Tailscale for 1-click connect/login if installed
            val tsIntent = packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
            if (tsIntent != null) {
                startActivity(tsIntent)
            } else {
                // Otherwise start internal WireGuard service
                val vpnIntent = VpnService.prepare(this)
                if (vpnIntent != null) {
                    vpnPermissionLauncher.launch(vpnIntent)
                } else {
                    startInternalVpn()
                }
            }
        }
    }

    private fun startInternalVpn() {
        val cfg = PulsarPhoneApp.instance.getConfig()
        if (!PulsarPhoneApp.instance.isConfigured()) {
            Toast.makeText(this, getString(R.string.pair_first_prompt), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, getString(R.string.starting_vpn), Toast.LENGTH_SHORT).show()
            val res = PulsarVpnManager.startTunnel(
                serverEndpoint = cfg.pcIp,
                serverPubKey = "",
                serverPort = 51820
            )
            updateVpnButtonState()
            if (res.isSuccess) {
                Toast.makeText(this@MainActivity, getString(R.string.vpn_connected_to, cfg.pcAlias), Toast.LENGTH_SHORT).show()
            } else {
                val err = res.exceptionOrNull()?.message ?: ""
                Toast.makeText(this@MainActivity, getString(R.string.vpn_start_error, err), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateVpnButtonState() {
        val isRealVpn = isRealVpnActive() || PulsarVpnManager.isVpnRunning()
        if (isRealVpn) {
            binding.btnToggleVpn.text = getString(R.string.vpn_status_active)
        } else {
            binding.btnToggleVpn.text = getString(R.string.vpn_status_inactive)
        }
    }

    private fun refreshStatusAndApps() {
        binding.swipeRefresh.isRefreshing = true
        val cfg = PulsarPhoneApp.instance.getConfig()

        if (!PulsarPhoneApp.instance.isConfigured()) {
            binding.statusIndicator.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.status_disconnected)
            binding.statusText.text = getString(R.string.vpn_disconnected)
            binding.pcInfoText.text = getString(R.string.scan_qr_to_pair)
            binding.emptyAppsText.visibility = View.VISIBLE
            binding.emptyAppsText.text = getString(R.string.scan_qr_to_see_apps)
            binding.appsCount.text = getString(R.string.apps_count_format, 0)
            adapter.setApps(emptyList())
            binding.swipeRefresh.isRefreshing = false
            return
        }

        lifecycleScope.launch {
            val isOnline = PulsarApiClient.ping()
            if (isOnline) {
                binding.statusIndicator.backgroundTintList =
                    ContextCompat.getColorStateList(this@MainActivity, R.color.status_connected)
                binding.statusText.text = getString(R.string.pc_connected_status, cfg.pcAlias)
                binding.pcInfoText.text = getString(R.string.secure_tunnel_active, cfg.pcIp)

                val appsResult = PulsarApiClient.getApps()
                binding.swipeRefresh.isRefreshing = false

                if (appsResult.isSuccess) {
                    allApps = appsResult.getOrDefault(emptyList())
                    val currentQuery = binding.searchAppsInput.text?.toString().orEmpty()
                    filterApps(currentQuery)
                } else {
                    allApps = emptyList()
                    filterApps("")
                    binding.emptyAppsText.visibility = View.VISIBLE
                    binding.emptyAppsText.text = getString(R.string.error_fetching_apps)
                }
            } else {
                binding.statusIndicator.backgroundTintList =
                    ContextCompat.getColorStateList(this@MainActivity, R.color.status_disconnected)
                binding.statusText.text = getString(R.string.pc_unresponsive_status, cfg.pcAlias)
                binding.pcInfoText.text = getString(R.string.check_pulsar_vpn_open, cfg.pcIp)
                binding.swipeRefresh.isRefreshing = false
                allApps = emptyList()
                filterApps("")
            }
        }
    }

    private fun filterApps(query: String) {
        val trimmed = query.trim()
        val filtered = if (trimmed.isBlank()) {
            allApps
        } else {
            allApps.filter {
                it.name.contains(trimmed, ignoreCase = true) ||
                (it.comment ?: "").contains(trimmed, ignoreCase = true)
            }
        }
        adapter.setApps(filtered)
        binding.appsCount.text = getString(R.string.apps_count_format, filtered.size)
        binding.emptyAppsText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        if (filtered.isEmpty() && allApps.isNotEmpty()) {
            binding.emptyAppsText.text = getString(R.string.no_apps_found_for, trimmed)
        } else if (allApps.isEmpty()) {
            binding.emptyAppsText.text = getString(R.string.apps_empty)
        }
    }

    private fun launchPcApp(app: PcApp) {
        val isDarkMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        Toast.makeText(this, getString(R.string.opening_app, app.name), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val res = PulsarApiClient.launchApp(app.id, darkMode = isDarkMode)
            if (res.isSuccess) {
                val broadwayUrl = res.getOrNull()
                val intent = Intent(this@MainActivity, BroadwayActivity::class.java).apply {
                    putExtra(BroadwayActivity.EXTRA_APP_NAME, app.name)
                    putExtra(BroadwayActivity.EXTRA_BROADWAY_URL, broadwayUrl)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this@MainActivity, getString(R.string.error_launching_app, res.exceptionOrNull()?.message ?: ""), Toast.LENGTH_LONG).show()
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
                        Toast.makeText(this@MainActivity, getString(R.string.clipboard_sent_to_pc), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, getString(R.string.clipboard_sync_error), Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this, getString(R.string.clipboard_empty), Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, getString(R.string.clipboard_empty), Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendSelectedFilesToPc(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val total = uris.size
        Toast.makeText(this, getString(R.string.sending_multiple_files, total), Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            var successCount = 0
            for ((index, uri) in uris.withIndex()) {
                var fileName = "archivo_${index + 1}"
                var fileSize = 0L

                try {
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            if (nameIndex != -1) fileName = cursor.getString(nameIndex) ?: fileName
                            if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                        }
                    }
                } catch (e: Exception) {
                    fileName = uri.lastPathSegment ?: fileName
                }

                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                val res = FlydropClient.sendFileToFlydrop(
                    context = this@MainActivity,
                    uri = uri,
                    fileName = fileName,
                    mimeType = mimeType,
                    fileSize = fileSize
                )

                if (res.isSuccess) {
                    successCount++
                }
            }

            if (successCount == total) {
                Toast.makeText(this@MainActivity, getString(R.string.multiple_files_sent_success, successCount, total), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, getString(R.string.multiple_files_sent_success, successCount, total), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun castPhoneScreenToPc() {
        val cfg = PulsarPhoneApp.instance.getConfig()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val client = OkHttpClient()
                    val req = Request.Builder()
                        .url("http://${cfg.pcIp}:${cfg.pcPort}/api/stream/phone-to-pc/start")
                        .addHeader("X-Pulsar-Token", cfg.token)
                        .post("{}".toRequestBody("application/json".toMediaType()))
                        .build()
                    client.newCall(req).execute().close()
                }
                Toast.makeText(this@MainActivity, getString(R.string.phone_cast_started), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.error_sending, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
