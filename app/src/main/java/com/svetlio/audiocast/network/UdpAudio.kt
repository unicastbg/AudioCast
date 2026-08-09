package com.svetlio.audiocast.network

import android.media.AudioFormat
import com.svetlio.audiocast.security.PinAuth

/**
 * Wire details for the UDP live-audio path.
 *
 * Packet layout: [1B type][8B PIN token][4B seq][PCM payload].
 *   - type distinguishes audio from any future control packet,
 *   - token = HMAC(pin, time-window) so the receiver can authenticate each
 *     packet without a handshake (see PinAuth),
 *   - seq lets the receiver drop late/duplicate packets.
 *
 * Format is fixed (matches CaptureService); no negotiation over connectionless UDP.
 */
object UdpAudio {
    const val SAMPLE_RATE = 44100
    const val CHANNELS = 2
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    const val TYPE_AUDIO = 16

    const val TYPE_OFFSET = 0
    const val TOKEN_OFFSET = 1
    const val TOKEN_LEN = PinAuth.TOKEN_LEN // 8
    const val SEQ_OFFSET = TOKEN_OFFSET + TOKEN_LEN // 9
    const val HEADER_SIZE = SEQ_OFFSET + 4 // 13

    // Payload kept under a typical MTU and frame-aligned (stereo 16-bit = 4 B/frame).
    const val MAX_PAYLOAD = 1200
    const val PACKET_SIZE = HEADER_SIZE + MAX_PAYLOAD

    fun writeSeq(dst: ByteArray, offset: Int, seq: Int) {
        dst[offset] = (seq ushr 24).toByte()
        dst[offset + 1] = (seq ushr 16).toByte()
        dst[offset + 2] = (seq ushr 8).toByte()
        dst[offset + 3] = seq.toByte()
    }

    fun readSeq(src: ByteArray, offset: Int): Int =
        ((src[offset].toInt() and 0xFF) shl 24) or
            ((src[offset + 1].toInt() and 0xFF) shl 16) or
            ((src[offset + 2].toInt() and 0xFF) shl 8) or
            (src[offset + 3].toInt() and 0xFF)
}
