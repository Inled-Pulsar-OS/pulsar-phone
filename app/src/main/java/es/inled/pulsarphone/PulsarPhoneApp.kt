package es.inled.pulsarphone

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import es.inled.pulsarphone.data.ConnectionConfig

class PulsarPhoneApp : Application() {

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.google.android.material.color.DynamicColors.applyToActivitiesIfAvailable(this)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        migrateSingleConfigIfNeeded()
        es.inled.pulsarphone.net.PulsarVpnManager.init(this)
    }

    private fun migrateSingleConfigIfNeeded() {
        val devicesJson = prefs.getString(KEY_DEVICES_JSON, null)
        if (devicesJson.isNullOrBlank()) {
            val ip = prefs.getString(KEY_PC_IP, null)
            val token = prefs.getString(KEY_TOKEN, null)
            if (!ip.isNullOrBlank() && !token.isNullOrBlank()) {
                val dev = ConnectionConfig(
                    pcIp = ip,
                    pcPort = prefs.getInt(KEY_PC_PORT, 8989),
                    token = token,
                    pcAlias = prefs.getString(KEY_PC_ALIAS, "Pulsar PC") ?: "Pulsar PC",
                    flydropPort = prefs.getInt(KEY_FLYDROP_PORT, 53317),
                    broadwayPort = prefs.getInt(KEY_BROADWAY_PORT, 8085)
                )
                saveDevice(dev, setActive = true)
            }
        }
    }

    fun isConfigured(): Boolean {
        val list = getSavedDevices()
        if (list.isNotEmpty()) return true
        val ip = prefs.getString(KEY_PC_IP, null)
        val token = prefs.getString(KEY_TOKEN, null)
        return !ip.isNullOrBlank() && !token.isNullOrBlank()
    }

    fun getSavedDevices(): List<ConnectionConfig> {
        val json = prefs.getString(KEY_DEVICES_JSON, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ConnectionConfig>>() {}.type
            gson.fromJson<List<ConnectionConfig>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getActiveDeviceId(): String? {
        return prefs.getString(KEY_ACTIVE_DEVICE_ID, null)
    }

    fun getConfig(): ConnectionConfig {
        val devices = getSavedDevices()
        val activeId = getActiveDeviceId()
        val activeDev = devices.find { it.id == activeId } ?: devices.firstOrNull()

        if (activeDev != null) {
            return activeDev
        }

        return ConnectionConfig(
            pcIp = prefs.getString(KEY_PC_IP, "127.0.0.1") ?: "127.0.0.1",
            pcPort = prefs.getInt(KEY_PC_PORT, 8989),
            token = prefs.getString(KEY_TOKEN, "") ?: "",
            pcAlias = prefs.getString(KEY_PC_ALIAS, "Pulsar PC") ?: "Pulsar PC",
            flydropPort = prefs.getInt(KEY_FLYDROP_PORT, 53317),
            broadwayPort = prefs.getInt(KEY_BROADWAY_PORT, 8085)
        )
    }

    fun setActiveDevice(deviceId: String) {
        val devices = getSavedDevices().toMutableList()
        val idx = devices.indexOfFirst { it.id == deviceId }
        if (idx >= 0) {
            val updated = devices[idx].copy(lastUsed = System.currentTimeMillis())
            devices[idx] = updated
            val json = gson.toJson(devices)
            prefs.edit()
                .putString(KEY_DEVICES_JSON, json)
                .putString(KEY_ACTIVE_DEVICE_ID, deviceId)
                .putString(KEY_PC_IP, updated.pcIp)
                .putInt(KEY_PC_PORT, updated.pcPort)
                .putString(KEY_TOKEN, updated.token)
                .putString(KEY_PC_ALIAS, updated.pcAlias)
                .putInt(KEY_FLYDROP_PORT, updated.flydropPort)
                .putInt(KEY_BROADWAY_PORT, updated.broadwayPort)
                .apply()
        }
    }

    fun saveDevice(config: ConnectionConfig, setActive: Boolean = true) {
        val devices = getSavedDevices().toMutableList()
        val existingIdx = devices.indexOfFirst { it.id == config.id || (it.pcIp == config.pcIp && it.pcPort == config.pcPort) }
        val devToSave = if (existingIdx >= 0) {
            config.copy(id = devices[existingIdx].id, lastUsed = System.currentTimeMillis())
        } else {
            config.copy(lastUsed = System.currentTimeMillis())
        }

        if (existingIdx >= 0) {
            devices[existingIdx] = devToSave
        } else {
            devices.add(devToSave)
        }

        val json = gson.toJson(devices)
        val editor = prefs.edit()
            .putString(KEY_DEVICES_JSON, json)

        if (setActive || devices.size == 1) {
            editor.putString(KEY_ACTIVE_DEVICE_ID, devToSave.id)
                .putString(KEY_PC_IP, devToSave.pcIp)
                .putInt(KEY_PC_PORT, devToSave.pcPort)
                .putString(KEY_TOKEN, devToSave.token)
                .putString(KEY_PC_ALIAS, devToSave.pcAlias)
                .putInt(KEY_FLYDROP_PORT, devToSave.flydropPort)
                .putInt(KEY_BROADWAY_PORT, devToSave.broadwayPort)
        }
        editor.apply()
    }

    fun removeDevice(deviceId: String) {
        val devices = getSavedDevices().toMutableList()
        devices.removeAll { it.id == deviceId }
        val json = gson.toJson(devices)
        val activeId = getActiveDeviceId()
        val editor = prefs.edit().putString(KEY_DEVICES_JSON, json)
        if (activeId == deviceId) {
            val nextDev = devices.firstOrNull()
            if (nextDev != null) {
                editor.putString(KEY_ACTIVE_DEVICE_ID, nextDev.id)
                    .putString(KEY_PC_IP, nextDev.pcIp)
                    .putInt(KEY_PC_PORT, nextDev.pcPort)
                    .putString(KEY_TOKEN, nextDev.token)
                    .putString(KEY_PC_ALIAS, nextDev.pcAlias)
                    .putInt(KEY_FLYDROP_PORT, nextDev.flydropPort)
                    .putInt(KEY_BROADWAY_PORT, nextDev.broadwayPort)
            } else {
                editor.remove(KEY_ACTIVE_DEVICE_ID)
                    .remove(KEY_PC_IP)
                    .remove(KEY_TOKEN)
            }
        }
        editor.apply()
    }

    fun saveConfig(config: ConnectionConfig) {
        saveDevice(config, setActive = true)
    }

    companion object {
        lateinit var instance: PulsarPhoneApp
            private set

        private const val PREFS_NAME = "pulsar_phone_prefs"
        private const val KEY_DEVICES_JSON = "saved_devices_json"
        private const val KEY_ACTIVE_DEVICE_ID = "active_device_id"
        private const val KEY_PC_IP = "pc_ip"
        private const val KEY_PC_PORT = "pc_port"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_PC_ALIAS = "pc_alias"
        private const val KEY_FLYDROP_PORT = "flydrop_port"
        private const val KEY_BROADWAY_PORT = "broadway_port"
    }
}
