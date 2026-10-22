package es.inled.pulsarphone.net

import android.content.Context
import android.util.Log
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

object PulsarVpnManager {

    private const val TAG = "PulsarVpnManager"
    private var backend: Backend? = null

    private class PulsarTunnel(private val name: String) : Tunnel {
        override fun getName() = name
        override fun onStateChange(state: Tunnel.State) {
            Log.d(TAG, "WireGuard Tunnel state changed to: $state")
        }
    }

    private val tunnel = PulsarTunnel("pulsar-wg")

    fun init(context: Context) {
        if (backend == null) {
            backend = GoBackend(context.applicationContext)
        }
    }

    fun isVpnRunning(): Boolean {
        return try {
            backend?.getState(tunnel) == Tunnel.State.UP
        } catch (e: Exception) {
            false
        }
    }

    suspend fun startTunnel(
        serverEndpoint: String,
        serverPubKey: String,
        serverPort: Int = 51820,
        clientIp: String = "10.88.0.2/24",
        allowedIps: String = "10.88.0.0/24, 100.64.0.0/10"
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val keyPair = KeyPair()
            val clientPrivKey = keyPair.privateKey.toBase64()

            val wgConfigText = """
                [Interface]
                PrivateKey = $clientPrivKey
                Address = $clientIp
                DNS = 1.1.1.1

                [Peer]
                PublicKey = $serverPubKey
                Endpoint = $serverEndpoint:$serverPort
                AllowedIPs = $allowedIps
                PersistentKeepalive = 25
            """.trimIndent()

            val config = Config.parse(ByteArrayInputStream(wgConfigText.toByteArray(StandardCharsets.UTF_8)))
            backend?.setState(tunnel, Tunnel.State.UP, config)
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting WireGuard tunnel: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun stopTunnel(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            backend?.setState(tunnel, Tunnel.State.DOWN, null)
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WireGuard tunnel: ${e.message}", e)
            Result.failure(e)
        }
    }
}
