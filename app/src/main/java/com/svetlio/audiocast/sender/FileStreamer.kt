package com.svetlio.audiocast.sender

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.svetlio.audiocast.network.Frame
import com.svetlio.audiocast.network.FrameType
import com.svetlio.audiocast.network.FileMeta
import com.svetlio.audiocast.security.TcpHandshake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext

/**
 * Streams a local file (chosen via the system file picker) to a receiver as-is:
 * FILE_META, then FILE_DATA chunks, then FILE_END. No decoding/re-encoding —
 * the original mp3/flac/m4a bytes go over the wire. This is the passthrough
 * path (best quality, lowest battery on the sender).
 */
class FileStreamer(context: Context) {

    private val appContext = context.applicationContext

    suspend fun stream(
        host: String,
        port: Int,
        uri: Uri,
        onProgress: (sent: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val meta = queryMeta(resolver, uri)
        val pin = com.svetlio.audiocast.core.AppSettings(appContext).securityPin

        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            val netIn = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            TcpHandshake.clientAuthenticate(netIn, out, pin) // throws on wrong PIN

            Frame.writeFrame(out, FrameType.FILE_META, meta.toBytes())

            resolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(CHUNK)
                var sent = 0L
                while (true) {
                    coroutineContext.ensureActive() // allow cancellation mid-transfer
                    val n = input.read(buf)
                    if (n < 0) break
                    Frame.writeHeader(out, FrameType.FILE_DATA, n)
                    out.write(buf, 0, n)
                    sent += n
                    onProgress(sent, meta.size)
                }
            } ?: throw IOException("Cannot open input stream for $uri")

            Frame.writeFrame(out, FrameType.FILE_END, ByteArray(0))
            out.flush()
        }
    }

    private fun queryMeta(resolver: ContentResolver, uri: Uri): FileMeta {
        var name = "audio"
        var size = -1L
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) c.getString(nameIdx)?.let { name = it }
                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
            }
        }
        val mime = resolver.getType(uri) ?: guessMime(name)
        return FileMeta(name, mime, size)
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "audio/*"
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val CHUNK = 32 * 1024
    }
}
