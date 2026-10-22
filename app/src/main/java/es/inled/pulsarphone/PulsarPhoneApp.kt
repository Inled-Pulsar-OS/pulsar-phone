package es.inled.pulsarphone

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import es.inled.pulsarphone.data.ConnectionConfig

class PulsarPhoneApp : Application() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.google.android.material.color.DynamicColors.applyToActivitiesIfAvailable(this)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        es.inled.pulsarphone.net.PulsarVpnManager.init(this)
    }

    fun isConfigured(): Boolean {
        val ip = prefs.getString(KEY_PC_IP, null)
        val token = prefs.getString(KEY_TOKEN, null)
        return !ip.isNullOrBlank() && !token.isNullOrBlank()
    }

    fun getConfig(): ConnectionConfig {
        return ConnectionConfig(
            pcIp = prefs.getString(KEY_PC_IP, "127.0.0.1") ?: "127.0.0.1",
            pcPort = prefs.getInt(KEY_PC_PORT, 8989),
            token = prefs.getString(KEY_TOKEN, "") ?: "",
            pcAlias = prefs.getString(KEY_PC_ALIAS, "Pulsar PC") ?: "Pulsar PC",
            flydropPort = prefs.getInt(KEY_FLYDROP_PORT, 53317),
            broadwayPort = prefs.getInt(KEY_BROADWAY_PORT, 8085)
        )
    }

    fun saveConfig(config: ConnectionConfig) {
        prefs.edit()
            .putString(KEY_PC_IP, config.pcIp)
            .putInt(KEY_PC_PORT, config.pcPort)
            .putString(KEY_TOKEN, config.token)
            .putString(KEY_PC_ALIAS, config.pcAlias)
            .putInt(KEY_FLYDROP_PORT, config.flydropPort)
            .putInt(KEY_BROADWAY_PORT, config.broadwayPort)
            .apply()
    }

    companion object {
        lateinit var instance: PulsarPhoneApp
            private set

        private const val PREFS_NAME = "pulsar_phone_prefs"
        private const val KEY_PC_IP = "pc_ip"
        private const val KEY_PC_PORT = "pc_port"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_PC_ALIAS = "pc_alias"
        private const val KEY_FLYDROP_PORT = "flydrop_port"
        private const val KEY_BROADWAY_PORT = "broadway_port"
    }
}
