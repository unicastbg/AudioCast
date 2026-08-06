package com.svetlio.audiocast.network

import org.json.JSONObject

/** Metadata for a file being cast, sent in the FILE_META frame as UTF-8 JSON. */
data class FileMeta(
    val name: String,
    val mime: String,
    val size: Long,
) {
    fun toBytes(): ByteArray =
        JSONObject()
            .put("name", name)
            .put("mime", mime)
            .put("size", size)
            .toString()
            .toByteArray(Charsets.UTF_8)

    companion object {
        fun fromBytes(bytes: ByteArray): FileMeta {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            return FileMeta(
                name = o.optString("name", "audio"),
                mime = o.optString("mime", "audio/*"),
                size = o.optLong("size", -1L),
            )
        }
    }
}
