package es.inled.pulsarphone.net

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import es.inled.pulsarphone.PulsarPhoneApp
import es.inled.pulsarphone.R
import es.inled.pulsarphone.ui.FileReceivedActivity
import es.inled.pulsarphone.ui.TextReceivedActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object FlydropServer {

    private const val TAG = "FlydropServer"
    private const val CHANNEL_ID = "flydrop_transfers"
    private const val MULTICAST_GROUP_IPV4 = "224.0.0.167"
    private const val DEFAULT_PORT = 53317

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var serverSocket: ServerSocket? = null
    private var boundPort = DEFAULT_PORT
    private var isRunning = false
    private lateinit var appContext: Context

    private val fingerprint: String by lazy {
        val sp = appContext.getSharedPreferences("flydrop_prefs", Context.MODE_PRIVATE)
        var fp = sp.getString("device_fingerprint", null)
        if (fp == null) {
            fp = UUID.randomUUID().toString()
            sp.edit().putString("device_fingerprint", fp).apply()
        }
        fp
    }

    private val trustAllClient: OkHttpClient by lazy {
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
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
        }
    }

    private data class IncomingSession(
        val sessionId: String,
        val senderAlias: String,
        val files: Map<String, LocalSendFileMeta>,
        val tokens: Map<String, String>
    )

    private val activeSessions = ConcurrentHashMap<String, IncomingSession>()

    fun start(context: Context) {
        if (isRunning) return
        appContext = context.applicationContext
        isRunning = true

        createNotificationChannel()

        scope.launch {
            startHttpServer()
        }
        scope.launch {
            startUdpDiscoveryListener()
        }
        scope.launch {
            periodicAnnouncementLoop()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = appContext.getString(R.string.flydrop_channel_name)
            val desc = appContext.getString(R.string.flydrop_channel_desc)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = desc
            }
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun getDeviceAlias(): String {
        return "Pulsar Phone (${Build.MODEL})"
    }

    private fun getDeviceInfoMap(): Map<String, Any> {
        return mapOf(
            "alias" to getDeviceAlias(),
            "version" to "2.1",
            "deviceModel" to Build.MODEL,
            "deviceType" to "mobile",
            "fingerprint" to fingerprint,
            "port" to boundPort,
            "protocol" to "http",
            "download" to true,
            "announcement" to true,
            "announce" to true
        )
    }

    private fun startHttpServer() {
        for (port in DEFAULT_PORT..53325) {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", port))
                }
                boundPort = port
                Log.i(TAG, "Flydrop HTTP server listening on 0.0.0.0:$boundPort")
                break
            } catch (e: Exception) {
                Log.w(TAG, "Could not bind port $port: ${e.message}")
            }
        }

        val ss = serverSocket ?: run {
            Log.e(TAG, "Failed to bind any port for Flydrop server")
            return
        }

        while (isRunning && !ss.isClosed) {
            try {
                val client = ss.accept()
                scope.launch {
                    handleClientConnection(client)
                }
            } catch (e: Exception) {
                if (!isRunning) break
                Log.e(TAG, "Error accepting client: ${e.message}")
            }
        }
    }

    private fun readHttpLine(input: InputStream): String? {
        val bos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                if (bos.size() == 0) return null
                break
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                bos.write(b)
            }
        }
        return bos.toString("UTF-8")
    }

    private fun handleClientConnection(socket: Socket) {
        try {
            socket.soTimeout = 30000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val requestLine = readHttpLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val fullPath = parts[1]
            val path = if (fullPath.contains("?")) fullPath.substringBefore("?") else fullPath
            val queryStr = if (fullPath.contains("?")) fullPath.substringAfter("?") else ""

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readHttpLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon != -1) {
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    headers[key] = value
                }
            }

            val queryParams = parseQueryParams(queryStr)

            if (method.equals("OPTIONS", ignoreCase = true)) {
                sendHttpResponse(output, 200, "OK", "", "text/plain")
                return
            }

            when {
                path == "/api/localsend/v2/info" || path == "/api/localsend/v1/info" || path == "/info" -> {
                    val json = gson.toJson(getDeviceInfoMap())
                    sendHttpResponse(output, 200, "OK", json, "application/json; charset=utf-8")
                }
                path == "/api/localsend/v2/prepare-upload" || path == "/api/localsend/v1/prepare-upload" -> {
                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    val bodyBytes = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val r = input.read(bodyBytes, read, contentLength - read)
                        if (r == -1) break
                        read += r
                    }
                    val body = String(bodyBytes, 0, read, Charsets.UTF_8)
                    handlePrepareUpload(body, output)
                }
                path == "/api/localsend/v2/upload" || path == "/api/localsend/v1/upload" -> {
                    handleUpload(queryParams, headers, input, output)
                }
                else -> {
                    sendHttpResponse(output, 404, "Not Found", "{\"message\":\"Not Found\"}", "application/json")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client connection: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun handlePrepareUpload(body: String, output: BufferedOutputStream) {
        try {
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val data: Map<String, Any> = gson.fromJson(body, type)

            val info = data["info"] as? Map<*, *> ?: emptyMap<String, Any>()
            val senderAlias = info["alias"] as? String ?: "Dispositivo"

            val filesMapRaw = data["files"] as? Map<*, *> ?: emptyMap<String, Any>()
            val filesMap = mutableMapOf<String, LocalSendFileMeta>()
            val tokensMap = mutableMapOf<String, String>()

            for ((fid, fval) in filesMapRaw) {
                val fObj = fval as? Map<*, *> ?: continue
                val id = fObj["id"]?.toString() ?: fid.toString()
                val fileName = fObj["fileName"]?.toString() ?: "archivo"
                val size = (fObj["size"] as? Number)?.toLong() ?: 0L
                val fileType = fObj["fileType"]?.toString() ?: "application/octet-stream"
                val preview = fObj["preview"]?.toString()

                filesMap[id] = LocalSendFileMeta(id, fileName, size, fileType, preview)
                tokensMap[id] = UUID.randomUUID().toString()
            }

            val sessionId = UUID.randomUUID().toString()
            val session = IncomingSession(sessionId, senderAlias, filesMap, tokensMap)
            activeSessions[sessionId] = session

            val resp = mapOf(
                "sessionId" to sessionId,
                "files" to tokensMap
            )
            sendHttpResponse(output, 200, "OK", gson.toJson(resp), "application/json; charset=utf-8")
        } catch (e: Exception) {
            Log.e(TAG, "prepare-upload error: ${e.message}")
            sendHttpResponse(output, 400, "Bad Request", "{\"message\":\"Invalid payload\"}", "application/json")
        }
    }

    private fun readStreamTo(input: InputStream, output: OutputStream, headers: Map<String, String>, expectedSize: Long) {
        val isChunked = headers["transfer-encoding"]?.lowercase() == "chunked"
        if (isChunked) {
            while (true) {
                val sizeLine = readHttpLine(input) ?: break
                val chunkSize = sizeLine.trim().split(";")[0].toIntOrNull(16) ?: 0
                if (chunkSize <= 0) {
                    readHttpLine(input)
                    break
                }
                var rem = chunkSize
                val buf = ByteArray(8192)
                while (rem > 0) {
                    val r = input.read(buf, 0, minOf(buf.size, rem))
                    if (r == -1) break
                    output.write(buf, 0, r)
                    rem -= r
                }
                readHttpLine(input)
            }
        } else {
            val length = if (expectedSize > 0) expectedSize else (headers["content-length"]?.toLongOrNull() ?: 0L)
            var bytesRead = 0L
            val buf = ByteArray(16384)
            while (bytesRead < length) {
                val toRead = minOf(buf.size.toLong(), length - bytesRead).toInt()
                val r = input.read(buf, 0, toRead)
                if (r == -1) break
                output.write(buf, 0, r)
                bytesRead += r
            }
        }
        output.flush()
    }

    private fun handleUpload(
        query: Map<String, String>,
        headers: Map<String, String>,
        input: BufferedInputStream,
        output: BufferedOutputStream
    ) {
        val sessionId = query["sessionid"] ?: query["sessionId"] ?: ""
        val fileId = query["fileid"] ?: query["fileId"] ?: ""
        val token = query["token"] ?: ""

        val session = activeSessions[sessionId]
        if (session == null || session.tokens[fileId] != token) {
            sendHttpResponse(output, 403, "Forbidden", "{\"message\":\"Invalid session or token\"}", "application/json")
            return
        }

        val fileMeta = session.files[fileId]
        val fileName = fileMeta?.fileName ?: "flydrop_${fileId}"
        val fileType = fileMeta?.fileType ?: "application/octet-stream"
        val expectedSize = fileMeta?.size ?: (headers["content-length"]?.toLongOrNull() ?: 0L)

        val isText = (fileType == "text" || fileType.startsWith("text/") ||
                fileName.equals("texto.txt", ignoreCase = true) ||
                fileName.equals("text.txt", ignoreCase = true))

        if (isText) {
            val bos = ByteArrayOutputStream()
            readStreamTo(input, bos, headers, expectedSize)
            val textContent = bos.toString("UTF-8")
            onTextReceived(textContent, session.senderAlias)
            sendHttpResponse(output, 200, "OK", "{\"message\":\"OK\"}", "application/json")
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            var targetFile = File(downloadsDir, fileName)

            var counter = 1
            val baseName = fileName.substringBeforeLast(".")
            val ext = if (fileName.contains(".")) "." + fileName.substringAfterLast(".") else ""
            while (targetFile.exists()) {
                targetFile = File(downloadsDir, "$baseName ($counter)$ext")
                counter++
            }

            FileOutputStream(targetFile).use { fos ->
                readStreamTo(input, fos, headers, expectedSize)
            }

            onFileReceived(targetFile, session.senderAlias, fileType)
            sendHttpResponse(output, 200, "OK", "{\"message\":\"OK\"}", "application/json")
        }
    }

    private fun onTextReceived(text: String, senderAlias: String) {
        Log.i(TAG, "Text received from $senderAlias (${text.length} chars)")
        mainHandler.post {
            // Auto copy to clipboard
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("FlyDrop", text))
            Toast.makeText(appContext, appContext.getString(R.string.flydrop_copied_toast), Toast.LENGTH_SHORT).show()

            // Open clean dialog activity (no emojis)
            val intent = Intent(appContext, TextReceivedActivity::class.java).apply {
                putExtra(TextReceivedActivity.EXTRA_TEXT, text)
                putExtra(TextReceivedActivity.EXTRA_SENDER, senderAlias)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            try {
                appContext.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting TextReceivedActivity: ${e.message}")
            }

            // High-priority Heads-up Notification
            val pendingIntent = PendingIntent.getActivity(
                appContext,
                System.currentTimeMillis().toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notif = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(appContext.getString(R.string.flydrop_text_received_title, senderAlias))
                .setContentText(if (text.length > 80) text.substring(0, 80) + "..." else text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true)
                .addAction(
                    0,
                    appContext.getString(R.string.flydrop_copy_action),
                    pendingIntent
                )
                .build()

            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1001, notif)
        }
    }

    private fun onFileReceived(file: File, senderAlias: String, mimeType: String) {
        Log.i(TAG, "File received: ${file.absolutePath} from $senderAlias")

        MediaScannerConnection.scanFile(appContext, arrayOf(file.absolutePath), arrayOf(mimeType), null)

        mainHandler.post {
            val popupIntent = Intent(appContext, FileReceivedActivity::class.java).apply {
                putExtra(FileReceivedActivity.EXTRA_FILE_PATH, file.absolutePath)
                putExtra(FileReceivedActivity.EXTRA_SENDER, senderAlias)
                putExtra(FileReceivedActivity.EXTRA_MIME, mimeType)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            // Launch popup window over screen
            try {
                appContext.startActivity(popupIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting FileReceivedActivity: ${e.message}")
            }

            // Direct open Intent via FileProvider
            val uri: Uri = try {
                FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
            } catch (e: Exception) {
                Uri.fromFile(file)
            }

            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            val viewPendingIntent = PendingIntent.getActivity(
                appContext,
                (System.currentTimeMillis() + 1).toInt(),
                viewIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val dialogPendingIntent = PendingIntent.getActivity(
                appContext,
                System.currentTimeMillis().toInt(),
                popupIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notif = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(appContext.getString(R.string.flydrop_file_received_title, file.name))
                .setContentText(appContext.getString(R.string.flydrop_file_received_desc))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(dialogPendingIntent)
                .setFullScreenIntent(dialogPendingIntent, true)
                .addAction(
                    0,
                    appContext.getString(R.string.flydrop_open_action),
                    viewPendingIntent
                )
                .build()

            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify((System.currentTimeMillis() % 10000).toInt(), notif)
        }
    }

    private fun sendHttpResponse(
        output: BufferedOutputStream,
        statusCode: Int,
        statusText: String,
        body: String,
        contentType: String
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: *\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        if (bytes.isNotEmpty()) {
            output.write(bytes)
        }
        output.flush()
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (query.isBlank()) return map
        for (param in query.split("&")) {
            val pair = param.split("=")
            if (pair.isNotEmpty()) {
                val key = URLDecoder.decode(pair[0], "UTF-8").lowercase()
                val value = if (pair.size > 1) URLDecoder.decode(pair[1], "UTF-8") else ""
                map[key] = value
            }
        }
        return map
    }

    private fun startUdpDiscoveryListener() {
        var mSocket: MulticastSocket? = null
        try {
            mSocket = MulticastSocket(DEFAULT_PORT).apply {
                reuseAddress = true
                joinGroup(InetAddress.getByName(MULTICAST_GROUP_IPV4))
            }
            val buf = ByteArray(8192)
            while (isRunning) {
                val packet = DatagramPacket(buf, buf.size)
                mSocket.receive(packet)
                val msgStr = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val data: Map<String, Any>? = try { gson.fromJson(msgStr, type) } catch (e: Exception) { null }

                if (data != null) {
                    val isAnnouncement = (data["announcement"] as? Boolean) ?: (data["announce"] as? Boolean) ?: true
                    if (!isAnnouncement) {
                        sendUdpAnnouncementTo(packet.address.hostAddress, (data["port"] as? Number)?.toInt() ?: DEFAULT_PORT)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Multicast socket error: ${e.message}")
        } finally {
            mSocket?.close()
        }
    }

    private fun periodicAnnouncementLoop() {
        while (isRunning) {
            try {
                val jsonPayload = gson.toJson(getDeviceInfoMap())
                val bytes = jsonPayload.toByteArray(Charsets.UTF_8)

                // 1. UDP Multicast / Broadcast
                var udpSock: DatagramSocket? = null
                try {
                    udpSock = DatagramSocket()
                    udpSock.broadcast = true

                    val mcPacket = DatagramPacket(bytes, bytes.size, InetAddress.getByName(MULTICAST_GROUP_IPV4), DEFAULT_PORT)
                    udpSock.send(mcPacket)

                    val bcPacket = DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), DEFAULT_PORT)
                    udpSock.send(bcPacket)

                    val cfg = PulsarPhoneApp.instance.getConfig()
                    if (cfg.pcIp.isNotBlank()) {
                        val directPacket = DatagramPacket(bytes, bytes.size, InetAddress.getByName(cfg.pcIp), cfg.flydropPort)
                        udpSock.send(directPacket)
                    }
                } catch (e: Exception) {
                    // Ignore broadcast errors
                } finally {
                    udpSock?.close()
                }

                // 2. Direct HTTPS / HTTP register to PC using trustAllClient
                val cfg = PulsarPhoneApp.instance.getConfig()
                if (cfg.pcIp.isNotBlank()) {
                    val urls = listOf(
                        "https://${cfg.pcIp}:${cfg.flydropPort}/api/localsend/v2/register",
                        "http://${cfg.pcIp}:${cfg.flydropPort}/api/localsend/v2/register"
                    )
                    for (u in urls) {
                        try {
                            val req = Request.Builder()
                                .url(u)
                                .post(jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                                .build()
                            val resp = trustAllClient.newCall(req).execute()
                            resp.close()
                            if (resp.isSuccessful) break
                        } catch (e: Exception) {
                            // Try next
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Announcement error: ${e.message}")
            }
            try {
                Thread.sleep(8000)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun sendUdpAnnouncementTo(ip: String, port: Int) {
        scope.launch {
            try {
                val jsonPayload = gson.toJson(getDeviceInfoMap())
                val bytes = jsonPayload.toByteArray(Charsets.UTF_8)
                val sock = DatagramSocket()
                val packet = DatagramPacket(bytes, bytes.size, InetAddress.getByName(ip), port)
                sock.send(packet)
                sock.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error sending targeted UDP announcement: ${e.message}")
            }
        }
    }
}
