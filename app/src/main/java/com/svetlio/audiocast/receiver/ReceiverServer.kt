package com.svetlio.audiocast.receiver

import com.svetlio.audiocast.network.Frame
import com.svetlio.audiocast.network.FrameType
import com.svetlio.audiocast.network.FileMeta
import com.svetlio.audiocast.network.PcmMeta
import com.svetlio.audiocast.network.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

/**
 * Listens on [port] and accepts one sender connection at a time. The first
 * frame decides the mode:
 *   - FILE_META  -> receive a whole file to cache, then play it (Phase 1).
 *   - PCM_META   -> play a live raw-PCM stream until the sender disconnects
 *                   (Phase 2 capture).
 *
 * onPcmChunk hands back the shared read buffer; the callback must consume it
 * synchronously (AudioTrack.write does), which lets us avoid per-chunk allocs.
 */
class ReceiverServer(
    private val cacheDir: File,
    private val port: Int = Protocol.DEFAULT_PORT,
) {
    interface Callbacks {
        // File transfer (Phase 1)
        fun onReceiving(meta: FileMeta)
        fun onProgress(received: Long, total: Long)
        fun onFileReady(file: File, meta: FileMeta)

        // Live PCM (Phase 2)
        fun onPcmStart(meta: PcmMeta)
        fun onPcmChunk(data: ByteArray, length: Int)
        fun onStreamEnded()

        fun onError(message: String)
    }

    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null

    suspend fun run(cb: Callbacks) = withContext(Dispatchers.IO) {
        running = true
        try {
            val server = ServerSocket(port)
            serverSocket = server
            while (running && !server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    if (running) cb.onError("accept failed: ${e.message}")
                    break
                }
                try {
                    runCatching { client.tcpNoDelay = true }
                    handleClient(client, cb)
                } catch (e: Exception) {
                    cb.onError("transfer failed: ${e.message}")
                } finally {
                    runCatching { client.close() }
                }
            }
        } catch (e: IOException) {
            if (running) cb.onError("server failed: ${e.message}")
        } finally {
            stop()
        }
    }

    private fun handleClient(client: Socket, cb: Callbacks) {
        val input = DataInputStream(BufferedInputStream(client.getInputStream()))
        val first = Frame.readHeader(input) ?: return
        when (first.type) {
            FrameType.FILE_META -> handleFile(input, first.length, cb)
            FrameType.PCM_META -> handlePcm(input, first.length, cb)
            else -> throw IOException("Unexpected first frame type ${first.type}")
        }
    }

    private fun handleFile(input: DataInputStream, metaLen: Int, cb: Callbacks) {
        val metaBytes = ByteArray(metaLen)
        input.readFully(metaBytes)
        val meta = FileMeta.fromBytes(metaBytes)
        cb.onReceiving(meta)

        val outFile = File(cacheDir, "incoming_${System.currentTimeMillis()}_${sanitize(meta.name)}")
        BufferedOutputStream(FileOutputStream(outFile)).use { fout ->
            val buf = ByteArray(64 * 1024)
            var received = 0L
            while (true) {
                val h = Frame.readHeader(input) ?: throw IOException("Unexpected end of stream")
                when (h.type) {
                    FrameType.FILE_DATA -> {
                        var remaining = h.length
                        while (remaining > 0) {
                            val n = input.read(buf, 0, minOf(buf.size, remaining))
                            if (n < 0) throw IOException("End of stream mid-chunk")
                            fout.write(buf, 0, n)
                            remaining -= n
                            received += n
                        }
                        cb.onProgress(received, meta.size)
                    }

                    FrameType.FILE_END -> {
                        fout.flush()
                        cb.onFileReady(outFile, meta)
                        return
                    }

                    else -> throw IOException("Unexpected frame type ${h.type}")
                }
            }
        }
    }

    private fun handlePcm(input: DataInputStream, metaLen: Int, cb: Callbacks) {
        val metaBytes = ByteArray(metaLen)
        input.readFully(metaBytes)
        val meta = PcmMeta.fromBytes(metaBytes)
        cb.onPcmStart(meta)

        val buf = ByteArray(64 * 1024)
        try {
            while (true) {
                // Clean EOF (null) means the sender stopped casting.
                val h = Frame.readHeader(input) ?: break
                when (h.type) {
                    FrameType.PCM_DATA -> {
                        var remaining = h.length
                        while (remaining > 0) {
                            val n = input.read(buf, 0, minOf(buf.size, remaining))
                            if (n < 0) throw IOException("End of stream mid-chunk")
                            cb.onPcmChunk(buf, n)
                            remaining -= n
                        }
                    }

                    else -> throw IOException("Unexpected frame type ${h.type} during PCM")
                }
            }
        } finally {
            cb.onStreamEnded()
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
}
