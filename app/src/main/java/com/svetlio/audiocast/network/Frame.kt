package com.svetlio.audiocast.network

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/** Frame type tags. */
object FrameType {
    const val FILE_META = 1
    const val FILE_DATA = 2
    const val FILE_END = 3

    // Phase 2 — universal capture (raw PCM stream).
    const val PCM_META = 16
    const val PCM_DATA = 17
}

data class FrameHeader(val type: Int, val length: Int)

/**
 * Minimal length-prefixed framing over a TCP stream:
 *
 *   [4B magic "ACS1"][1B type][4B big-endian payload length][payload...]
 *
 * DataInput/OutputStream read/write ints big-endian, which is what we want.
 */
object Frame {
    /** ASCII "ACS1". */
    const val MAGIC = 0x41435331

    /** Sanity cap so a corrupt length can't make us allocate gigabytes. */
    const val MAX_PAYLOAD = 1 shl 20 // 1 MiB

    fun writeHeader(out: DataOutputStream, type: Int, length: Int) {
        out.writeInt(MAGIC)
        out.writeByte(type)
        out.writeInt(length)
    }

    /** Write a complete frame whose payload is already in memory. */
    fun writeFrame(out: DataOutputStream, type: Int, payload: ByteArray) {
        writeHeader(out, type, payload.size)
        if (payload.isNotEmpty()) out.write(payload)
    }

    /**
     * Read the next frame header, or null on a clean end-of-stream.
     * Throws IOException on a corrupt/misaligned stream.
     */
    fun readHeader(input: DataInputStream): FrameHeader? {
        val magic = try {
            input.readInt()
        } catch (e: EOFException) {
            return null
        }
        if (magic != MAGIC) throw IOException("Bad frame magic: 0x${magic.toString(16)}")
        val type = input.readUnsignedByte()
        val length = input.readInt()
        if (length < 0 || length > MAX_PAYLOAD) throw IOException("Bad frame length: $length")
        return FrameHeader(type, length)
    }
}
