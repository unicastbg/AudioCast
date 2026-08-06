package com.svetlio.audiocast.receiver

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.svetlio.audiocast.network.PcmMeta

/**
 * Plays a live raw-PCM stream on the receiver with BOUNDED latency.
 *
 * The naive approach (blocking AudioTrack.write straight from the network) lets
 * latency grow without limit: TCP never drops, AudioTrack never skips, and any
 * clock drift between phone and box accumulates as an ever-growing delay.
 *
 * Instead we decouple network from playback with a ring buffer:
 *   - Producer (network thread) calls [write] — never blocks; if the ring is
 *     full (we're drifting ahead of real time) it drops the OLDEST audio,
 *     frame-aligned, to snap back toward the target latency.
 *   - Consumer (our own thread) prebuffers ~PREBUFFER_MS, then feeds AudioTrack.
 *     On underrun it writes a little silence so the track never stalls.
 *
 * Result: steady latency around PREBUFFER_MS, capped at MAX_LATENCY_MS, with an
 * occasional tiny drop/silence to absorb drift — instead of seconds of creep.
 * All ring math is in whole frames so channels never desync.
 */
class PcmPlayer(meta: PcmMeta) {

    private val channelMask =
        if (meta.channelCount >= 2) AudioFormat.CHANNEL_OUT_STEREO
        else AudioFormat.CHANNEL_OUT_MONO

    private val bytesPerFrame = (if (meta.channelCount >= 2) 2 else 1) * 2 // 16-bit samples
    private val bytesPerSecond = meta.sampleRate * bytesPerFrame

    private val minBuf = AudioTrack.getMinBufferSize(meta.sampleRate, channelMask, meta.encoding)
    private val trackBufBytes = maxOf(minBuf, msToBytes(TRACK_BUF_MS))

    private val prebufferBytes = msToBytes(PREBUFFER_MS)
    private val capacityBytes = msToBytes(MAX_LATENCY_MS)

    private val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(meta.encoding)
                .setSampleRate(meta.sampleRate)
                .setChannelMask(channelMask)
                .build()
        )
        .setBufferSizeInBytes(trackBufBytes)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    // Ring buffer (single producer, single consumer).
    private val ring = ByteArray(capacityBytes)
    private var head = 0 // read index
    private var tail = 0 // write index
    private var count = 0 // bytes currently buffered
    private val lock = Any()

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({ playLoop() }, "PcmPlayer").also { it.start() }
    }

    /** Producer: enqueue captured audio; drop oldest if we're running ahead. */
    fun write(data: ByteArray, length: Int) {
        val len = length - (length % bytesPerFrame) // defensive frame-align
        if (len <= 0) return
        synchronized(lock) {
            val free = ring.size - count
            if (len > free) {
                // Overflow => we're ahead of real time. Drop oldest to catch up.
                val drop = len - free
                head = (head + drop) % ring.size
                count -= drop
            }
            val firstPart = minOf(len, ring.size - tail)
            System.arraycopy(data, 0, ring, tail, firstPart)
            if (len > firstPart) {
                System.arraycopy(data, firstPart, ring, 0, len - firstPart)
            }
            tail = (tail + len) % ring.size
            count += len
        }
    }

    private fun playLoop() {
        // Prebuffer so playback starts with a cushion (kills startup underruns).
        while (running && occupancy() < prebufferBytes) {
            try {
                Thread.sleep(5)
            } catch (e: InterruptedException) {
                return
            }
        }
        if (!running) return

        track.play()
        val chunk = ByteArray(msToBytes(CHUNK_MS))
        val silence = ByteArray(chunk.size)
        while (running) {
            val n = readFromRing(chunk)
            if (n > 0) {
                track.write(chunk, 0, n)
            } else {
                // Ran dry (network hiccup): keep the track alive with brief silence.
                track.write(silence, 0, silence.size)
            }
        }
    }

    private fun occupancy(): Int = synchronized(lock) { count }

    private fun readFromRing(dst: ByteArray): Int = synchronized(lock) {
        var n = minOf(dst.size, count)
        n -= n % bytesPerFrame
        if (n <= 0) return 0
        val firstPart = minOf(n, ring.size - head)
        System.arraycopy(ring, head, dst, 0, firstPart)
        if (n > firstPart) {
            System.arraycopy(ring, 0, dst, firstPart, n - firstPart)
        }
        head = (head + n) % ring.size
        count -= n
        n
    }

    fun stop() {
        running = false
        thread?.let {
            it.interrupt()
            runCatching { it.join(200) }
        }
        thread = null
        runCatching { track.stop() }
        runCatching { track.flush() }
        runCatching { track.release() }
    }

    private fun msToBytes(ms: Int): Int {
        val raw = (bytesPerSecond.toLong() * ms / 1000).toInt()
        return raw - (raw % bytesPerFrame)
    }

    companion object {
        private const val TAG = "PcmPlayer"

        /** Cushion before playback starts, and roughly the steady-state latency. */
        private const val PREBUFFER_MS = 200

        /** Hard cap on buffered audio; past this we drop oldest to catch up. */
        private const val MAX_LATENCY_MS = 500

        /** AudioTrack's own internal buffer. */
        private const val TRACK_BUF_MS = 120

        /** Player-thread write granularity. */
        private const val CHUNK_MS = 20
    }
}
