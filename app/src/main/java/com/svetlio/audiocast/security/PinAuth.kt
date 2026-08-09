package com.svetlio.audiocast.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Shared-PIN authentication. The PIN itself never crosses the wire.
 *
 * TCP (file + live): challenge-response. The receiver sends a random nonce; the
 * sender returns HMAC-SHA256(pin, nonce); the receiver verifies. A fresh nonce
 * per connection prevents replay.
 *
 * UDP (live): connectionless, so instead each packet carries a short token =
 * HMAC(pin, time-window). The receiver accepts tokens for the current window
 * and ±1 (so it tolerates ~30-60s of clock skew between the two devices).
 *
 * An empty PIN uses a fixed key, so two devices with no PIN still interoperate —
 * that's the "open" (no security) case, preserving prior behavior.
 */
object PinAuth {
    const val NONCE_LEN = 16
    const val TOKEN_LEN = 8
    private const val WINDOW_MS = 30_000L

    private val random = SecureRandom()

    private fun key(pin: String): SecretKeySpec {
        val raw = pin.toByteArray(Charsets.UTF_8)
        val material = if (raw.isEmpty()) ByteArray(1) else raw
        return SecretKeySpec(material, "HmacSHA256")
    }

    private fun hmac(pin: String, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key(pin))
        return mac.doFinal(data)
    }

    // ---- TCP challenge-response ----

    fun newNonce(): ByteArray = ByteArray(NONCE_LEN).also { random.nextBytes(it) }

    fun response(pin: String, nonce: ByteArray): ByteArray = hmac(pin, nonce)

    /** Constant-time verification of a challenge response. */
    fun verifyResponse(pin: String, nonce: ByteArray, response: ByteArray): Boolean =
        MessageDigest.isEqual(hmac(pin, nonce), response)

    // ---- UDP windowed token ----

    fun currentWindow(): Long = System.currentTimeMillis() / WINDOW_MS

    fun windowToken(pin: String, window: Long): ByteArray {
        val wb = ByteArray(8)
        var w = window
        for (i in 7 downTo 0) {
            wb[i] = (w and 0xFF).toByte()
            w = w ushr 8
        }
        return hmac(pin, wb).copyOf(TOKEN_LEN)
    }
}
