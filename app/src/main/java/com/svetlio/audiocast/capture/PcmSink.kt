package com.svetlio.audiocast.capture

/**
 * Where captured PCM goes. Two implementations (TCP, UDP) so the capture loop
 * doesn't care which transport is in use.
 */
interface PcmSink {
    /** Connect/prepare and, for TCP, announce the stream format. */
    fun open()

    /** Send one buffer of PCM (frame-aligned bytes, starting at offset 0). */
    fun send(buf: ByteArray, length: Int)

    fun close()
}
