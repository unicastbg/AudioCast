package com.svetlio.audiocast.receiver

import com.svetlio.audiocast.network.PcmMeta
import com.svetlio.audiocast.network.Protocol
import com.svetlio.audiocast.network.UdpAudio
import com.svetlio.audiocast.security.BruteForceGuard
import com.svetlio.audiocast.security.PinAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException

/**
 * Receives a live UDP PCM stream and plays it through the bounded [PcmPlayer].
 *
 * Each packet is authenticated by its per-window PIN token (see PinAuth) — no
 * handshake, so call pauses/resumes need nothing special. Packets with a bad
 * token count toward per-IP brute-force lockout; locked-out IPs are ignored.
 *
 * UDP has no connection, so the first valid packet starts playback and a gap of
 * [IDLE_MS] ends it. Late/duplicate packets (seq <= last) are dropped; losses
 * are just gaps the player fills with silence.
 */
class UdpPcmReceiver(
    private val port: Int = Protocol.DEFAULT_PORT,
    private val pinProvider: () -> String = { "" },
    private val guard: BruteForceGuard = BruteForceGuard(),
) {
    interface Callbacks {
        fun onStreamStart()
        fun onStreamEnd()
        fun onError(message: String)
    }

    @Volatile private var running = false
    @Volatile private var socket: DatagramSocket? = null
    private var player: PcmPlayer? = null

    // Cached valid tokens for the current window (+/-1), recomputed on change.
    private var cacheWindow = Long.MIN_VALUE
    private var cachePin = ""
    private var tokCur = ByteArray(0)
    private var tokPrev = ByteArray(0)
    private var tokNext = ByteArray(0)

    suspend fun run(cb: Callbacks) = withContext(Dispatchers.IO) {
        running = true
        try {
            val sock = DatagramSocket(port)
            sock.soTimeout = SO_TIMEOUT_MS
            socket = sock

            val buf = ByteArray(UdpAudio.PACKET_SIZE)
            val packet = DatagramPacket(buf, buf.size)
            var active = false
            var lastPacketAt = 0L
            var lastSeq = -1

            while (running) {
                packet.setLength(buf.size)
                try {
                    sock.receive(packet)
                } catch (e: SocketTimeoutException) {
                    if (active && System.currentTimeMillis() - lastPacketAt > IDLE_MS) {
                        stopPlayer(cb)
                        active = false
                        lastSeq = -1
                    }
                    continue
                }

                val len = packet.length
                if (len < UdpAudio.HEADER_SIZE) continue
                if (buf[UdpAudio.TYPE_OFFSET].toInt() != UdpAudio.TYPE_AUDIO) continue

                val ip = packet.address?.hostAddress ?: continue
                if (guard.isLockedOut(ip)) continue
                if (!tokenValid(buf)) {
                    guard.recordFailure(ip)
                    continue
                }

                if (!active) {
                    active = true
                    lastSeq = -1
                    player = PcmPlayer(
                        PcmMeta(UdpAudio.SAMPLE_RATE, UdpAudio.CHANNELS, UdpAudio.ENCODING)
                    ).apply { start() }
                    cb.onStreamStart()
                }
                lastPacketAt = System.currentTimeMillis()

                val seq = UdpAudio.readSeq(buf, UdpAudio.SEQ_OFFSET)
                if (lastSeq != -1 && seq <= lastSeq) continue // late/duplicate
                lastSeq = seq

                player?.write(buf, UdpAudio.HEADER_SIZE, len - UdpAudio.HEADER_SIZE)
            }
        } catch (e: Exception) {
            if (running) cb.onError("udp receiver: ${e.message}")
        } finally {
            stopPlayer(cb)
            stop()
        }
    }

    private fun tokenValid(buf: ByteArray): Boolean {
        val pin = pinProvider()
        val w = PinAuth.currentWindow()
        if (w != cacheWindow || pin != cachePin) {
            cacheWindow = w
            cachePin = pin
            tokCur = PinAuth.windowToken(pin, w)
            tokPrev = PinAuth.windowToken(pin, w - 1)
            tokNext = PinAuth.windowToken(pin, w + 1)
        }
        return matches(buf, tokCur) || matches(buf, tokPrev) || matches(buf, tokNext)
    }

    // Constant-time compare of the packet token region against a candidate.
    private fun matches(buf: ByteArray, token: ByteArray): Boolean {
        if (token.size != UdpAudio.TOKEN_LEN) return false
        var diff = 0
        for (i in 0 until UdpAudio.TOKEN_LEN) {
            diff = diff or (buf[UdpAudio.TOKEN_OFFSET + i].toInt() xor token[i].toInt())
        }
        return diff == 0
    }

    private fun stopPlayer(cb: Callbacks) {
        player?.let {
            it.stop()
            player = null
            cb.onStreamEnd()
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        private const val SO_TIMEOUT_MS = 500
        private const val IDLE_MS = 1500
    }
}
