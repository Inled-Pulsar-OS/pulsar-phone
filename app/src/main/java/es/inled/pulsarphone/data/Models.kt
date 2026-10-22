package es.inled.pulsarphone.data

import com.google.gson.annotations.SerializedName

data class PcApp(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("comment") val comment: String? = null,
    @SerializedName("exec") val exec: String? = null,
    @SerializedName("icon") val icon: String? = null,
    @SerializedName("iconData") val iconData: String? = null,
    @SerializedName("categories") val categories: List<String> = emptyList()
)

data class ConnectionConfig(
    val pcIp: String,
    val pcPort: Int = 8989,
    val token: String,
    val pcAlias: String = "Pulsar PC",
    val flydropPort: Int = 53317,
    val broadwayPort: Int = 8085
)

data class FsItem(
    @SerializedName("name") val name: String,
    @SerializedName("path") val path: String,
    @SerializedName("isDir") val isDir: Boolean = false,
    @SerializedName("size") val size: Long = 0,
    @SerializedName("modified") val modified: Long = 0,
    @SerializedName("mimeType") val mimeType: String = "application/octet-stream"
)

data class FsRoot(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("path") val path: String,
    @SerializedName("icon") val icon: String? = null
)

data class NotificationPayload(
    @SerializedName("appName") val appName: String,
    @SerializedName("title") val title: String,
    @SerializedName("text") val text: String,
    @SerializedName("packageName") val packageName: String
)
