package com.svetlio.audiocast.security

import com.svetlio.audiocast.network.Frame
import com.svetlio.audiocast.network.FrameType
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * The TCP PIN handshake, run before any file/PCM frames. Shared by the sender
 * (client) and receiver (server). Runs even when the PIN is empty (both sides
 * use the same empty key, so it just always succeeds = "open").
 */
object TcpHandshake {

    /** Sender side: prove knowledge of the PIN. Throws IOException on rejection. */
    fun clientAuthenticate(input: DataInputStream, out: DataOutputStream, pin: String) {
        val challenge = Frame.readHeader(input) ?: throw IOException("No challenge from receiver")
        if (challenge.type != FrameType.AUTH_CHALLENGE) {
            throw IOException("Unexpected frame ${challenge.type} during auth")
        }
        val nonce = ByteArray(challenge.length)
        input.readFully(nonce)

        Frame.writeFrame(out, FrameType.AUTH_RESPONSE, PinAuth.response(pin, nonce))
        out.flush()

        val reply = Frame.readHeader(input) ?: throw IOException("PIN rejected (connection closed)")
        when (reply.type) {
            FrameType.AUTH_OK -> if (reply.length > 0) input.readFully(ByteArray(reply.length))
            FrameType.AUTH_FAIL -> throw IOException("PIN rejected by receiver")
            else -> throw IOException("Unexpected auth reply ${reply.type}")
        }
    }

    /**
     * Receiver side: challenge the client and verify. Returns true on success.
     * Sends AUTH_OK / AUTH_FAIL so the sender gets a clear result. Brute-force
     * accounting is handled by the caller (it owns the client IP).
     */
    fun serverAuthenticate(input: DataInputStream, out: DataOutputStream, pin: String): Boolean {
        val nonce = PinAuth.newNonce()
        Frame.writeFrame(out, FrameType.AUTH_CHALLENGE, nonce)
        out.flush()

        val header = Frame.readHeader(input) ?: return false
        if (header.type != FrameType.AUTH_RESPONSE) return false
        val response = ByteArray(header.length)
        input.readFully(response)

        val ok = PinAuth.verifyResponse(pin, nonce, response)
        Frame.writeFrame(out, if (ok) FrameType.AUTH_OK else FrameType.AUTH_FAIL, ByteArray(0))
        out.flush()
        return ok
    }
}
