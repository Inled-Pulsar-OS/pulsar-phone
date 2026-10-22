package es.inled.pulsarphone.net

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import es.inled.pulsarphone.PulsarPhoneApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class LocalSendFileMeta(
    val id: String,
    val fileName: String,
    val size: Long,
    val fileType: String,
    val preview: String? = null
)

object FlydropClient {

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, trustAllCerts, SecureRandom())
            }
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build()
        }
    }

    private fun getCandidateFlydropUrls(): List<String> {
        val cfg = PulsarPhoneApp.instance.getConfig()
        // FlyDrop runs HTTPS by default with self-signed certificate on port 53317
        return listOf(
            "https://${cfg.pcIp}:${cfg.flydropPort}/api/localsend/v2",
            "http://${cfg.pcIp}:${cfg.flydropPort}/api/localsend/v2"
        )
    }

    suspend fun sendTextToFlydrop(text: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val fileId = UUID.randomUUID().toString()
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val fsize = textBytes.size.toLong()

        val filesMap = mapOf(
            fileId to mapOf(
                "id" to fileId,
                "fileName" to "texto.txt",
                "size" to fsize,
                "fileType" to "text/plain",
                "preview" to if (text.length > 80) text.substring(0, 80) else text
            )
        )

        val sessionPayload = mapOf(
            "info" to mapOf(
                "alias" to "Pulsar Phone",
                "version" to "2.1",
                "deviceModel" to "Android Phone",
                "deviceType" to "mobile",
                "fingerprint" to UUID.randomUUID().toString(),
                "port" to 53317,
                "protocol" to "https",
                "download" to true
            ),
            "files" to filesMap
        )

        var lastError: Exception = Exception("No se pudo conectar con FlyDrop")

        for (baseUrl in getCandidateFlydropUrls()) {
            try {
                // Step 1: prepare-upload
                val prepReq = Request.Builder()
                    .url("$baseUrl/prepare-upload")
                    .post(gson.toJson(sessionPayload).toRequestBody(jsonMediaType))
                    .build()

                val prepResp = client.newCall(prepReq).execute()
                if (!prepResp.isSuccessful) {
                    lastError = Exception("FlyDrop error de preparación: ${prepResp.code}")
                    continue
                }

                val prepBody = prepResp.body?.string() ?: ""
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val prepData: Map<String, Any> = gson.fromJson(prepBody, type)
                val sessionId = prepData["sessionId"] as? String ?: ""
                val tokens = prepData["files"] as? Map<String, String> ?: emptyMap()
                val token = tokens[fileId] ?: ""

                // Step 2: upload
                val uploadUrl = "$baseUrl/upload?sessionId=$sessionId&fileId=$fileId&token=$token"
                val uploadReq = Request.Builder()
                    .url(uploadUrl)
                    .addHeader("Content-Type", "text/plain; charset=utf-8")
                    .addHeader("Content-Length", fsize.toString())
                    .post(textBytes.toRequestBody("text/plain".toMediaType()))
                    .build()

                val uploadResp = client.newCall(uploadReq).execute()
                if (uploadResp.isSuccessful) {
                    return@withContext Result.success(true)
                } else {
                    lastError = Exception("Error al subir archivo: ${uploadResp.code}")
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        Result.failure(lastError)
    }

    suspend fun sendFileToFlydrop(
        context: Context,
        uri: Uri,
        fileName: String,
        mimeType: String,
        fileSize: Long,
        onProgress: ((Float) -> Unit)? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val fileId = UUID.randomUUID().toString()
        val filesMap = mapOf(
            fileId to mapOf(
                "id" to fileId,
                "fileName" to fileName,
                "size" to fileSize,
                "fileType" to mimeType,
                "preview" to null
            )
        )

        val sessionPayload = mapOf(
            "info" to mapOf(
                "alias" to "Pulsar Phone",
                "version" to "2.1",
                "deviceModel" to "Android Phone",
                "deviceType" to "mobile",
                "fingerprint" to UUID.randomUUID().toString(),
                "port" to 53317,
                "protocol" to "https",
                "download" to true
            ),
            "files" to filesMap
        )

        var lastError: Exception = Exception("No se pudo conectar con FlyDrop")

        for (baseUrl in getCandidateFlydropUrls()) {
            try {
                // Step 1: prepare-upload
                val prepReq = Request.Builder()
                    .url("$baseUrl/prepare-upload")
                    .post(gson.toJson(sessionPayload).toRequestBody(jsonMediaType))
                    .build()

                val prepResp = client.newCall(prepReq).execute()
                if (!prepResp.isSuccessful) {
                    lastError = Exception("FlyDrop rechazó o no respondió: ${prepResp.code}")
                    continue
                }

                val prepBody = prepResp.body?.string() ?: ""
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val prepData: Map<String, Any> = gson.fromJson(prepBody, type)
                val sessionId = prepData["sessionId"] as? String ?: ""
                val tokens = prepData["files"] as? Map<String, String> ?: emptyMap()
                val token = tokens[fileId] ?: ""

                // Step 2: stream upload from content resolver
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext Result.failure(Exception("No se pudo leer el archivo local"))

                val requestBody = object : RequestBody() {
                    override fun contentType() = mimeType.toMediaType()
                    override fun contentLength() = fileSize

                    override fun writeTo(sink: BufferedSink) {
                        val buffer = ByteArray(65536)
                        var uploaded = 0L
                        inputStream.use { input ->
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                sink.write(buffer, 0, read)
                                uploaded += read
                                if (fileSize > 0) {
                                    onProgress?.invoke(uploaded.toFloat() / fileSize)
                                }
                            }
                        }
                    }
                }

                val uploadUrl = "$baseUrl/upload?sessionId=$sessionId&fileId=$fileId&token=$token"
                val uploadReq = Request.Builder()
                    .url(uploadUrl)
                    .addHeader("Content-Type", mimeType)
                    .addHeader("Content-Length", fileSize.toString())
                    .post(requestBody)
                    .build()

                val uploadResp = client.newCall(uploadReq).execute()
                if (uploadResp.isSuccessful) {
                    return@withContext Result.success(true)
                } else {
                    lastError = Exception("FlyDrop error al subir: ${uploadResp.code}")
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        Result.failure(lastError)
    }
}
