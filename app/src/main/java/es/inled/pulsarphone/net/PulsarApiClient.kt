package es.inled.pulsarphone.net

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import es.inled.pulsarphone.PulsarPhoneApp
import es.inled.pulsarphone.data.FsItem
import es.inled.pulsarphone.data.FsRoot
import es.inled.pulsarphone.data.NotificationPayload
import es.inled.pulsarphone.data.PcApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.util.concurrent.TimeUnit

object PulsarApiClient {

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
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
        try {
            val req = Request.Builder()
                .url("${getBaseUrl()}/api/ping")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                resp.isSuccessful
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

    suspend fun launchApp(appId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(mapOf("appId" to appId))
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
}
