package com.svetlio.audiocast.capture

import com.svetlio.audiocast.network.UdpAudio
import com.svetlio.audiocast.security.PinAuth
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Unreliable transport: fire-and-forget UDP datagrams. Each packet is
 * [type][PIN token][seq][PCM]; a large read is split across MTU-sized datagrams.
 * No connection, no retransmit — a dropped packet is simply lost. The per-packet
 * token (rotated per time window) is how the receiver authenticates without a
 * handshake, so call pauses/resumes need no re-auth.
 */
class UdpPcmSink(
    private val host: String,
    private val port: Int,
    private val pin: String,
) : PcmSink {

    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
    private var seq = 0
    private val packetBuf = ByteArray(UdpAudio.PACKET_SIZE)

    private var cachedWindow = Long.MIN_VALUE
    private var cachedToken = ByteArray(UdpAudio.TOKEN_LEN)

    override fun open() {
        socket = DatagramSocket()
        address = InetAddress.getByName(host)
    }

    override fun send(buf: ByteArray, length: Int) {
        val sock = socket ?: return
        val addr = address ?: return
        val token = currentToken()
        var offset = 0
        while (offset < length) {
            val chunk = minOf(UdpAudio.MAX_PAYLOAD, length - offset)
            packetBuf[UdpAudio.TYPE_OFFSET] = UdpAudio.TYPE_AUDIO.toByte()
            System.arraycopy(token, 0, packetBuf, UdpAudio.TOKEN_OFFSET, UdpAudio.TOKEN_LEN)
            UdpAudio.writeSeq(packetBuf, UdpAudio.SEQ_OFFSET, seq++)
            System.arraycopy(buf, offset, packetBuf, UdpAudio.HEADER_SIZE, chunk)
            sock.send(DatagramPacket(packetBuf, UdpAudio.HEADER_SIZE + chunk, addr, port))
            offset += chunk
        }
    }

    private fun currentToken(): ByteArray {
        val w = PinAuth.currentWindow()
        if (w != cachedWindow) {
            cachedToken = PinAuth.windowToken(pin, w)
            cachedWindow = w
        }
        return cachedToken
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }
}
