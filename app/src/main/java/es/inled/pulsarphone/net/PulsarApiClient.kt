package es.inled.pulsarphone.net

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import es.inled.pulsarphone.PulsarPhoneApp
import es.inled.pulsarphone.data.FsItem
import es.inled.pulsarphone.data.FsRoot
import es.inled.pulsarphone.data.NotificationPayload
import es.inled.pulsarphone.data.PcApp
import es.inled.pulsarphone.data.PcMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit

object PulsarApiClient {

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun getBaseUrl(): String {
        val cfg = PulsarPhoneApp.instance.getConfig()
        return "http://${cfg.pcIp}:${cfg.pcPort}"
    }

    private fun getToken(): String {
        return PulsarPhoneApp.instance.getConfig().token
    }

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        val currentCfg = PulsarPhoneApp.instance.getConfig()
        if (rawPing(currentCfg.pcIp, currentCfg.pcPort)) {
            return@withContext true
        }

        // Current IP unreachable - attempt auto-discovery over UDP broadcast
        val discoveredIp = discoverPcIp(currentCfg.token)
        if (discoveredIp != null) {
            if (rawPing(discoveredIp, currentCfg.pcPort)) {
                PulsarPhoneApp.instance.saveConfig(currentCfg.copy(pcIp = discoveredIp))
                return@withContext true
            }
        }
        false
    }

    private fun rawPing(ip: String, port: Int): Boolean {
        if (ip.isBlank()) return false
        return try {
            val req = Request.Builder()
                .url("http://$ip:$port/api/ping")
                .get()
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    private fun discoverPcIp(expectedToken: String): String? {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 1500
            }
            val msg = "PULSAR_DISCOVER".toByteArray()
            val broadcastAddr = InetAddress.getByName("255.255.255.255")
            val packet = DatagramPacket(msg, msg.size, broadcastAddr, 8989)
            socket.send(packet)

            val buf = ByteArray(2048)
            val recvPacket = DatagramPacket(buf, buf.size)
            socket.receive(recvPacket)

            val jsonStr = String(recvPacket.data, 0, recvPacket.length)
            val json = JSONObject(jsonStr)
            val token = json.optString("token")
            val lanIp = json.optString("lanIp")
            val tailscaleIp = json.optString("tailscaleIp")
            val packetIp = recvPacket.address?.hostAddress

            // Check token if we already have one configured
            if (expectedToken.isNotBlank() && token.isNotBlank() && expectedToken != token) {
                return null
            }

            // Test candidate IPs
            val candidates = listOfNotNull(
                lanIp.takeIf { it.isNotBlank() },
                packetIp?.takeIf { it.isNotBlank() },
                tailscaleIp.takeIf { it.isNotBlank() }
            )
            for (candidate in candidates) {
                if (rawPing(candidate, 8989)) {
                    return candidate
                }
            }
        } catch (e: Exception) {
            // Discovery timeout or network unreachable
        } finally {
            socket?.close()
        }
        return null
    }

    suspend fun getMonitors(): Result<List<PcMonitor>> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${getBaseUrl()}/api/stream/pc-to-phone/monitors")
                .header("X-Pulsar-Token", getToken())
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("HTTP ${resp.code}"))
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                val arr = json.optJSONArray("monitors") ?: JSONArray()
                val list = mutableListOf<PcMonitor>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        PcMonitor(
                            connector = obj.optString("connector"),
                            name = obj.optString("name"),
                            isPrimary = obj.optBoolean("isPrimary", false),
                            isBuiltin = obj.optBoolean("isBuiltin", false)
                        )
                    )
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun startPcDesktopStream(monitor: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val bodyMap = if (monitor != null) mapOf("monitor" to monitor) else emptyMap<String, String>()
            val req = Request.Builder()
                .url("${getBaseUrl()}/api/stream/pc-to-phone/start")
                .header("X-Pulsar-Token", getToken())
                .post(gson.toJson(bodyMap).toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                Result.success(resp.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun startPhoneCastToPc(fullscreen: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(mapOf("fullscreen" to fullscreen))
            val req = Request.Builder()
                .url("${getBaseUrl()}/api/stream/phone-to-pc/start")
                .header("X-Pulsar-Token", getToken())
                .post(json.toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                Result.success(resp.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun stopPhoneCastToPc(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${getBaseUrl()}/api/stream/phone-to-pc/stop")
                .header("X-Pulsar-Token", getToken())
                .post("{}".toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                Result.success(resp.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun isPhoneToPcStreaming(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${getBaseUrl()}/api/stream/phone-to-pc/status")
                .header("X-Pulsar-Token", getToken())
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val body = resp.body?.string().orEmpty()
                org.json.JSONObject(body).optBoolean("running", false)
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getApps(): Result<List<PcApp>> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${getBaseUrl()}/api/apps")
                .header("X-Pulsar-Token", getToken())
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${resp.code}: ${resp.message}"))
                }
                val body = resp.body?.string() ?: ""
                val type = object : TypeToken<Map<String, List<PcApp>>>() {}.type
                val map: Map<String, List<PcApp>> = gson.fromJson(body, type)
                Result.success(map["apps"] ?: emptyList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun launchApp(appId: String, darkMode: Boolean = true): Result<String> = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(mapOf("appId" to appId, "darkMode" to darkMode))
            val req = Request.Builder()
                .url("${getBaseUrl()}/api/apps/launch")
                .header("X-Pulsar-Token", getToken())
                .post(json.toRequestBody(jsonMediaType))
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${resp.code}: ${resp.message}"))
                }
                val body = resp.body?.string() ?: ""
                val map: Map<String, Any> = gson.fromJson(body, Map::class.java) as Map<String, Any>
                val url = map["broadwayUrl"] as? String ?: ""
                Result.success(url)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun closeApp(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${getBaseUrl()}/api/apps/close")
                .header("X-Pulsar-Token", getToken())
                .post("{}".toRequestBody(jsonMediaType))
                .build()

            client.newCall(req).execute().use { resp ->
                Result.success(resp.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendNotification(payload: NotificationPayload): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(payload)
            val req = Request.Builder()
                .url("${getBaseUrl()}/api/notify")
                .header("X-Pulsar-Token", getToken())
                .post(json.toRequestBody(jsonMediaType))
                .build()

            client.newCall(req).execute().use { resp ->
                Result.success(resp.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun syncClipboardToPc(text: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(mapOf("text" to text))
            val req = Request.Builder()
                .url("${getBaseUrl()}/api/clipboard")
                .header("X-Pulsar-Token", getToken())
                .post(json.toRequestBody(jsonMediaType))
                .build()

            client.newCall(req).execute().use { resp ->
                Result.success(resp.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getFsRoots(): Result<List<FsRoot>> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${getBaseUrl()}/api/fs/roots")
                .header("X-Pulsar-Token", getToken())
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("HTTP ${resp.code}"))
                val body = resp.body?.string() ?: ""
                val type = object : TypeToken<Map<String, List<FsRoot>>>() {}.type
                val map: Map<String, List<FsRoot>> = gson.fromJson(body, type)
                Result.success(map["roots"] ?: emptyList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getFsChildren(dirPath: String): Result<List<FsItem>> = withContext(Dispatchers.IO) {
        try {
            val url = "${getBaseUrl()}/api/fs/children?path=${java.net.URLEncoder.encode(dirPath, "UTF-8")}"
            val req = Request.Builder()
                .url(url)
                .header("X-Pulsar-Token", getToken())
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("HTTP ${resp.code}"))
                val body = resp.body?.string() ?: ""
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val map: Map<String, Any> = gson.fromJson(body, type)
                val itemsJson = gson.toJson(map["items"])
                val listType = object : TypeToken<List<FsItem>>() {}.type
                val items: List<FsItem> = gson.fromJson(itemsJson, listType)
                Result.success(items)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun openFsFileStream(filePath: String): InputStream? {
        val url = "${getBaseUrl()}/api/fs/file?path=${java.net.URLEncoder.encode(filePath, "UTF-8")}"
        val req = Request.Builder()
            .url(url)
            .header("X-Pulsar-Token", getToken())
            .get()
            .build()
        val resp = client.newCall(req).execute()
        return if (resp.isSuccessful) resp.body?.byteStream() else null
    }

    suspend fun deleteFsPath(filePath: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "${getBaseUrl()}/api/fs/file?path=${java.net.URLEncoder.encode(filePath, "UTF-8")}"
            val req = Request.Builder()
                .url(url)
                .header("X-Pulsar-Token", getToken())
                .delete()
                .build()
            client.newCall(req).execute().use { resp ->
                Result.success(resp.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun startTerminalSession(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${getBaseUrl()}/api/terminal/start")
                .header("X-Pulsar-Token", getToken())
                .post("{}".toRequestBody(jsonMediaType))
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${resp.code}: ${resp.message}"))
                }
                val body = resp.body?.string() ?: ""
                val json = JSONObject(body)
                val port = json.optInt("port", 7681)
                Result.success(port)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun executeShellCommand(command: String, cwd: String? = null): Result<es.inled.pulsarphone.data.ShellResult> = withContext(Dispatchers.IO) {
        try {
            val url = "${getBaseUrl()}/api/shell/exec"
            val json = JSONObject().apply {
                put("command", command)
                if (cwd != null) put("cwd", cwd)
            }
            val req = Request.Builder()
                .url(url)
                .header("X-Pulsar-Token", getToken())
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${resp.code}: $body"))
                }
                val res = gson.fromJson(body, es.inled.pulsarphone.data.ShellResult::class.java)
                Result.success(res)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
