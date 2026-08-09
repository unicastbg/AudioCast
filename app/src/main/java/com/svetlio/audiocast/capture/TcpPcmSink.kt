package com.svetlio.audiocast.capture

import android.media.AudioFormat
import com.svetlio.audiocast.network.Frame
import com.svetlio.audiocast.network.FrameType
import com.svetlio.audiocast.network.PcmMeta
import com.svetlio.audiocast.security.TcpHandshake
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Reliable transport: a framed TCP stream (PIN handshake, PCM_META, PCM_DATA). */
class TcpPcmSink(
    private val host: String,
    private val port: Int,
    private val sampleRate: Int,
    private val connectTimeoutMs: Int,
    private val pin: String,
) : PcmSink {

    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private var input: DataInputStream? = null

    override fun open() {
        val s = Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(host, port), connectTimeoutMs)
        }
        socket = s
        val i = DataInputStream(BufferedInputStream(s.getInputStream()))
        val o = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
        input = i

        TcpHandshake.clientAuthenticate(i, o, pin) // throws on wrong PIN

        Frame.writeFrame(
            o, FrameType.PCM_META,
            PcmMeta(sampleRate, 2, AudioFormat.ENCODING_PCM_16BIT).toBytes(),
        )
        out = o
    }

    override fun send(buf: ByteArray, length: Int) {
        val o = out ?: return
        Frame.writeHeader(o, FrameType.PCM_DATA, length)
        o.write(buf, 0, length)
    }

    override fun close() {
        runCatching { out?.flush() }
        runCatching { socket?.close() }
        out = null
        input = null
        socket = null
    }
}
