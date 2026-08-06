package com.svetlio.audiocast.network

import android.media.AudioFormat
import org.json.JSONObject

/**
 * Format descriptor for a raw PCM stream (Phase 2 capture), sent in the
 * PCM_META frame as UTF-8 JSON before the PCM_DATA frames begin.
 *
 * encoding is an AudioFormat.ENCODING_* constant (we use PCM_16BIT).
 */
data class PcmMeta(
    val sampleRate: Int,
    val channelCount: Int,
    val encoding: Int = AudioFormat.ENCODING_PCM_16BIT,
) {
    fun toBytes(): ByteArray =
        JSONObject()
            .put("sampleRate", sampleRate)
            .put("channels", channelCount)
            .put("encoding", encoding)
            .toString()
            .toByteArray(Charsets.UTF_8)

    companion object {
        fun fromBytes(bytes: ByteArray): PcmMeta {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            return PcmMeta(
                sampleRate = o.optInt("sampleRate", 44100),
                channelCount = o.optInt("channels", 2),
                encoding = o.optInt("encoding", AudioFormat.ENCODING_PCM_16BIT),
            )
        }
    }
}
