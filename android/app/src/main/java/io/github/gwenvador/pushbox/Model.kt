package io.github.gwenvador.pushbox

import org.json.JSONObject

data class Push(
    val id: String,
    val type: String,
    val source: String?,
    val target: String?,
    val title: String?,
    val body: String?,
    val url: String?,
    val fileName: String?,
    val fileType: String?,
    val fileSize: Long,
    val fileUrl: String?,
    val createdAt: Long,
    val raw: JSONObject,
) {
    val isImage get() = type == "file" && fileType?.startsWith("image/") == true

    companion object {
        fun from(o: JSONObject) = Push(
            id = o.optString("id"),
            type = o.optString("type"),
            source = o.str("source_device"),
            target = o.str("target_device"),
            title = o.str("title"),
            body = o.str("body"),
            url = o.str("url"),
            fileName = o.str("file_name"),
            fileType = o.str("file_type"),
            fileSize = o.optLong("file_size", -1),
            fileUrl = o.str("file_url"),
            createdAt = o.optLong("created_at"),
            raw = o,
        )
    }
}

data class Device(
    val id: String,
    val name: String,
    val kind: String,
    val lastSeen: Long,
    val pushEnabled: Boolean,
) {
    companion object {
        fun from(o: JSONObject) = Device(
            id = o.optString("id"),
            name = o.optString("name"),
            kind = o.optString("kind"),
            lastSeen = o.optLong("last_seen"),
            pushEnabled = o.optBoolean("push_enabled"),
        )
    }
}

internal fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }
