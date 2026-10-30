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
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import es.inled.pulsarphone.PulsarPhoneApp
import es.inled.pulsarphone.R
import es.inled.pulsarphone.data.ConnectionConfig
import es.inled.pulsarphone.data.PcApp
import es.inled.pulsarphone.databinding.ActivityMainBinding
import es.inled.pulsarphone.databinding.ItemSavedDeviceBinding
import es.inled.pulsarphone.net.FlydropClient
import es.inled.pulsarphone.net.PulsarApiClient
import es.inled.pulsarphone.net.PulsarVpnManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: AppAdapter
    private var allApps: List<PcApp> = emptyList()

    companion object {
        const val TAB_APPS = 0
        const val TAB_TOOLS = 1
        const val TAB_DEVICES = 2
    }

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

        setupNavigation()
        setupRecyclerView()
        setupListeners()
        setupVpnMonitor()

        handleDeepLink(intent)
        refreshStatusAndApps()
        updateVpnButtonState()
        refreshDevicesTab()
    }

    override fun onResume() {
        super.onResume()
        updateVpnButtonState()
        updateNotificationSyncButton()
        checkPhoneCastStatus()
        refreshDevicesTab()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleDeepLink(it) }
    }

    private fun setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_apps -> {
                    showTab(TAB_APPS)
                    true
                }
                R.id.nav_tools -> {
                    showTab(TAB_TOOLS)
                    true
                }
                R.id.nav_devices -> {
                    showTab(TAB_DEVICES)
                    refreshDevicesTab()
                    true
                }
                else -> false
            }
        }
    }

    private fun showTab(tab: Int) {
        binding.tabAppsContainer.visibility = if (tab == TAB_APPS) View.VISIBLE else View.GONE
        binding.tabToolsContainer.visibility = if (tab == TAB_TOOLS) View.VISIBLE else View.GONE
        binding.tabDevicesContainer.visibility = if (tab == TAB_DEVICES) View.VISIBLE else View.GONE
    }

    private fun setupRecyclerView() {
        adapter = AppAdapter { app ->
            launchPcApp(app)
        }
        binding.tabApps.appsRecyclerView.layoutManager = GridLayoutManager(this, 3)
        binding.tabApps.appsRecyclerView.adapter = adapter
    }

    private fun setupListeners() {
        // --- TAB 1: APPS LISTENERS ---
        binding.tabApps.swipeRefresh.setOnRefreshListener {
            refreshStatusAndApps()
        }

        binding.tabApps.searchAppsInput.doAfterTextChanged { text ->
            val query = text?.toString().orEmpty()
            binding.tabApps.btnClearSearch.visibility = if (query.isNotBlank()) View.VISIBLE else View.GONE
            filterApps(query)
        }

        binding.tabApps.btnClearSearch.setOnClickListener {
            binding.tabApps.searchAppsInput.text?.clear()
        }

        // --- TAB 2: TOOLS LISTENERS ---
        binding.tabTools.btnToggleVpn.setOnClickListener {
            toggleVpn()
        }

        binding.tabTools.cardWatchPc.setOnClickListener {
            val intent = Intent(this, DesktopStreamActivity::class.java)
            startActivity(intent)
        }

        binding.tabTools.cardCastPhone.setOnClickListener {
            togglePhoneCastToPc()
        }

        binding.tabTools.cardRemoteShell.setOnClickListener {
            if (!PulsarPhoneApp.instance.isConfigured()) {
                Toast.makeText(this, getString(R.string.pc_not_paired), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, ShellActivity::class.java))
        }

        binding.tabTools.cardClipboardSync.setOnClickListener {
            syncPhoneClipboardToPc()
        }

        binding.tabTools.cardSendFiles.setOnClickListener {
            filePickerLauncher.launch(arrayOf("*/*"))
        }

        // --- TAB 3: DEVICES LISTENERS ---
        binding.tabDevices.cardScanQrDevices.setOnClickListener {
            startQrScanner()
        }

        binding.tabDevices.btnManualConnect.setOnClickListener {
            val alias = binding.tabDevices.etManualAlias.text.toString().trim().ifEmpty { "Pulsar PC" }
            val ip = binding.tabDevices.etManualIp.text.toString().trim()
            val token = binding.tabDevices.etManualToken.text.toString().trim()

            if (ip.isEmpty() || token.isEmpty()) {
                Toast.makeText(this, "Introduce la IP y el Token del PC", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val cfg = ConnectionConfig(
                pcIp = ip,
                pcPort = 8989,
                token = token,
                pcAlias = alias
            )
            PulsarPhoneApp.instance.saveDevice(cfg, setActive = true)
            binding.tabDevices.etManualIp.text?.clear()
            binding.tabDevices.etManualToken.text?.clear()
            binding.tabDevices.etManualAlias.text?.clear()

            Toast.makeText(this, getString(R.string.pc_paired_success, alias), Toast.LENGTH_SHORT).show()
            refreshStatusAndApps()
            refreshDevicesTab()
        }

        updateNotificationSyncButton()
    }

    private fun isNotificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(packageName)
    }

    private fun updateNotificationSyncButton() {
        val granted = isNotificationAccessGranted()
        if (granted) {
            binding.tabTools.ivNotificationIcon.setImageResource(android.R.drawable.ic_dialog_info)
            binding.tabTools.tvNotificationTitle.text = getString(R.string.btn_notifications) + " (Activo)"
            binding.tabTools.tvNotificationSubtitle.text = "Toca para enviar una notificación de prueba al PC"
            binding.tabTools.cardNotificationSync.setOnClickListener {
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
            binding.tabTools.ivNotificationIcon.setImageResource(android.R.drawable.ic_lock_idle_alarm)
            binding.tabTools.tvNotificationTitle.text = getString(R.string.btn_notifications) + " (Desactivado)"
            binding.tabTools.tvNotificationSubtitle.text = "Toca para conceder permisos y reenviar avisos al PC"
            binding.tabTools.cardNotificationSync.setOnClickListener {
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

    private fun refreshDevicesTab() {
        val cfg = PulsarPhoneApp.instance.getConfig()
        val devices = PulsarPhoneApp.instance.getSavedDevices()
        val activeId = PulsarPhoneApp.instance.getActiveDeviceId()

        binding.tabDevices.tvActivePcName.text = cfg.pcAlias
        binding.tabDevices.tvActivePcDetails.text = "IP: ${cfg.pcIp} • Puerto: ${cfg.pcPort}"

        binding.tabDevices.containerSavedDevices.removeAllViews()

        if (devices.isEmpty()) {
            val emptyTv = android.widget.TextView(this).apply {
                text = getString(R.string.scan_qr_prompt)
                setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
                setPadding(16, 24, 16, 24)
            }
            binding.tabDevices.containerSavedDevices.addView(emptyTv)
            return
        }

        val inflater = LayoutInflater.from(this)
        for (dev in devices) {
            val itemBinding = ItemSavedDeviceBinding.inflate(inflater, binding.tabDevices.containerSavedDevices, false)
            itemBinding.tvDeviceAlias.text = dev.pcAlias
            itemBinding.tvDeviceIp.text = "${dev.pcIp}:${dev.pcPort}"

            val isActive = dev.id == activeId || (activeId == null && dev == devices.first())
            if (isActive) {
                itemBinding.btnConnectThisDevice.text = "ACTIVO"
                itemBinding.btnConnectThisDevice.isEnabled = false
                itemBinding.btnConnectThisDevice.setIconResource(android.R.drawable.checkbox_on_background)
            } else {
                itemBinding.btnConnectThisDevice.text = getString(R.string.btn_connect_device)
                itemBinding.btnConnectThisDevice.isEnabled = true
                itemBinding.btnConnectThisDevice.icon = null
                itemBinding.btnConnectThisDevice.setOnClickListener {
                    PulsarPhoneApp.instance.setActiveDevice(dev.id)
                    Toast.makeText(this, getString(R.string.device_switched, dev.pcAlias), Toast.LENGTH_SHORT).show()
                    refreshStatusAndApps()
                    refreshDevicesTab()
                }
            }

            itemBinding.btnDeleteDevice.setOnClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.remove_device)
                    .setMessage(getString(R.string.remove_device_confirm, dev.pcAlias))
                    .setPositiveButton(R.string.btn_remove) { _, _ ->
                        PulsarPhoneApp.instance.removeDevice(dev.id)
                        Toast.makeText(this, "PC eliminado", Toast.LENGTH_SHORT).show()
                        refreshStatusAndApps()
                        refreshDevicesTab()
                    }
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show()
            }

            binding.tabDevices.containerSavedDevices.addView(itemBinding.root)
        }
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
                refreshDevicesTab()
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
        } catch (e: Exception) {}
    }

    private fun toggleVpn() {
        val isRealVpn = isRealVpnActive() || PulsarVpnManager.isVpnRunning()
        if (isRealVpn) {
            if (PulsarVpnManager.isVpnRunning()) {
                lifecycleScope.launch {
                    PulsarVpnManager.stopTunnel()
                    updateVpnButtonState()
                    Toast.makeText(this@MainActivity, getString(R.string.vpn_disconnected_toast), Toast.LENGTH_SHORT).show()
                }
            } else {
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
            val tsIntent = packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
            if (tsIntent != null) {
                startActivity(tsIntent)
            } else {
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
            binding.tabTools.btnToggleVpn.text = getString(R.string.vpn_status_active)
        } else {
            binding.tabTools.btnToggleVpn.text = getString(R.string.vpn_status_inactive)
        }
    }

    private fun refreshStatusAndApps() {
        binding.tabApps.swipeRefresh.isRefreshing = true
        val cfg = PulsarPhoneApp.instance.getConfig()

        binding.topAppBar.subtitle = "${cfg.pcAlias} (${cfg.pcIp})"

        if (!PulsarPhoneApp.instance.isConfigured()) {
            binding.tabTools.toolsStatusIndicator.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.status_disconnected)
            binding.tabDevices.activePcStatusDot.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.status_disconnected)
            binding.tabTools.toolsStatusTitle.text = getString(R.string.vpn_disconnected)
            binding.tabTools.toolsStatusSubtitle.text = getString(R.string.scan_qr_to_pair)

            binding.tabApps.emptyAppsText.visibility = View.VISIBLE
            binding.tabApps.emptyAppsText.text = getString(R.string.scan_qr_to_see_apps)
            binding.tabApps.appsCount.text = getString(R.string.apps_count_format, 0)
            adapter.setApps(emptyList())
            binding.tabApps.swipeRefresh.isRefreshing = false
            return
        }

        lifecycleScope.launch {
            val isOnline = PulsarApiClient.ping()
            if (isOnline) {
                binding.tabTools.toolsStatusIndicator.backgroundTintList =
                    ContextCompat.getColorStateList(this@MainActivity, R.color.status_connected)
                binding.tabDevices.activePcStatusDot.backgroundTintList =
                    ContextCompat.getColorStateList(this@MainActivity, R.color.status_connected)
                binding.tabTools.toolsStatusTitle.text = getString(R.string.pc_connected_status, cfg.pcAlias)
                binding.tabTools.toolsStatusSubtitle.text = getString(R.string.secure_tunnel_active, cfg.pcIp)

                val appsResult = PulsarApiClient.getApps()
                binding.tabApps.swipeRefresh.isRefreshing = false

                if (appsResult.isSuccess) {
                    allApps = appsResult.getOrDefault(emptyList())
                    val currentQuery = binding.tabApps.searchAppsInput.text?.toString().orEmpty()
                    filterApps(currentQuery)
                } else {
                    allApps = emptyList()
                    filterApps("")
                    binding.tabApps.emptyAppsText.visibility = View.VISIBLE
                    binding.tabApps.emptyAppsText.text = getString(R.string.error_fetching_apps)
                }
            } else {
                binding.tabTools.toolsStatusIndicator.backgroundTintList =
                    ContextCompat.getColorStateList(this@MainActivity, R.color.status_disconnected)
                binding.tabDevices.activePcStatusDot.backgroundTintList =
                    ContextCompat.getColorStateList(this@MainActivity, R.color.status_disconnected)
                binding.tabTools.toolsStatusTitle.text = getString(R.string.pc_unresponsive_status, cfg.pcAlias)
                binding.tabTools.toolsStatusSubtitle.text = getString(R.string.check_pulsar_vpn_open, cfg.pcIp)
                binding.tabApps.swipeRefresh.isRefreshing = false
                allApps = emptyList()
                filterApps("")
            }
        }
    }

    private fun filterApps(query: String) {
        val trimmed = query.trim()
        val filtered = if (trimmed.isEmpty()) {
            allApps
        } else {
            allApps.filter { app ->
                app.name.contains(trimmed, ignoreCase = true) ||
                app.categories.any { it.contains(trimmed, ignoreCase = true) }
            }
        }

        adapter.setApps(filtered)
        binding.tabApps.appsCount.text = getString(R.string.apps_count_format, filtered.size)

        if (filtered.isEmpty() && allApps.isNotEmpty()) {
            binding.tabApps.emptyAppsText.visibility = View.VISIBLE
            binding.tabApps.emptyAppsText.text = getString(R.string.no_apps_found_for, trimmed)
        } else if (allApps.isEmpty()) {
            binding.tabApps.emptyAppsText.visibility = View.VISIBLE
            binding.tabApps.emptyAppsText.text = getString(R.string.scan_qr_to_see_apps)
        } else {
            binding.tabApps.emptyAppsText.visibility = View.GONE
        }
    }

    private fun launchPcApp(app: PcApp) {
        val cfg = PulsarPhoneApp.instance.getConfig()
        val broadwayUrl = "http://${cfg.pcIp}:${cfg.broadwayPort}"

        lifecycleScope.launch {
            val res = PulsarApiClient.launchApp(app.id)
            if (res.isSuccess) {
                val intent = Intent(this@MainActivity, BroadwayActivity::class.java).apply {
                    putExtra("APP_URL", broadwayUrl)
                    putExtra("APP_NAME", app.name)
                }
                startActivity(intent)
            } else {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.error_launching_app, res.exceptionOrNull()?.message ?: ""),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun syncPhoneClipboardToPc() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).text?.toString()
        } else null

        if (text.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.clipboard_empty), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val res = PulsarApiClient.syncClipboardToPc(text)
            if (res.isSuccess) {
                Toast.makeText(this@MainActivity, getString(R.string.clipboard_sent_to_pc), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, getString(R.string.clipboard_sync_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun togglePhoneCastToPc() {
        lifecycleScope.launch {
            val isStreaming = PulsarApiClient.isPhoneToPcStreaming()
            if (isStreaming) {
                val res = PulsarApiClient.stopPhoneCastToPc()
                if (res.isSuccess) {
                    Toast.makeText(this@MainActivity, getString(R.string.phone_cast_stopped), Toast.LENGTH_SHORT).show()
                    binding.tabTools.tvCastPhoneTitle.text = getString(R.string.btn_cast_to_pc)
                }
            } else {
                val res = PulsarApiClient.startPhoneCastToPc(fullscreen = false)
                if (res.isSuccess) {
                    Toast.makeText(this@MainActivity, getString(R.string.phone_cast_started), Toast.LENGTH_SHORT).show()
                    binding.tabTools.tvCastPhoneTitle.text = getString(R.string.btn_stop_cast)
                } else {
                    Toast.makeText(this@MainActivity, "Error: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun checkPhoneCastStatus() {
        lifecycleScope.launch {
            val isStreaming = PulsarApiClient.isPhoneToPcStreaming()
            if (isStreaming) {
                binding.tabTools.tvCastPhoneTitle.text = getString(R.string.btn_stop_cast)
            } else {
                binding.tabTools.tvCastPhoneTitle.text = getString(R.string.btn_cast_to_pc)
            }
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

    private fun getFileName(uri: Uri): String {
        var name = "file"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(idx)
            }
        }
        return name
    }
}
