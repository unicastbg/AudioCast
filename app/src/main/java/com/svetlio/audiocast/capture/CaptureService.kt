package com.svetlio.audiocast.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.svetlio.audiocast.MainActivity
import com.svetlio.audiocast.R
import com.svetlio.audiocast.network.Frame
import com.svetlio.audiocast.network.FrameType
import com.svetlio.audiocast.network.PcmMeta
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Foreground service that captures system audio via MediaProjection +
 * AudioPlaybackCaptureConfiguration and streams it as raw PCM to a receiver.
 *
 * Lifecycle (must be in this order on Android 14):
 *   1. Activity obtains the MediaProjection consent token.
 *   2. Activity starts this service with the token + target host/port.
 *   3. Service calls startForeground(type=mediaProjection) BEFORE it touches
 *      the projection, then builds the capture and streams on a worker thread.
 *
 * Captured usages: MEDIA, GAME, UNKNOWN — the categories playback capture is
 * allowed to see. Calls (VOICE_COMMUNICATION) and apps that flag their audio
 * non-capturable are silently excluded by the platform.
 */
class CaptureService : Service() {

    private var projection: MediaProjection? = null
    @Volatile private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.getParcelableExtraCompat(EXTRA_RESULT_DATA, Intent::class.java)
        val host = intent?.getStringExtra(EXTRA_HOST)
        val port = intent?.getIntExtra(EXTRA_PORT, 0) ?: 0

        if (resultData == null || host == null || port == 0) {
            Log.e(TAG, "Missing start extras; stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        // Must be foreground (typed) before creating/using the projection.
        startForegroundInternal()

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, resultData)
        if (mp == null) {
            Log.e(TAG, "getMediaProjection returned null")
            stopEverything()
            return START_NOT_STICKY
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopEverything()
            }
        }, null)

        startCapture(mp, host, port)
        return START_STICKY
    }

    private fun startCapture(mp: MediaProjection, host: String, port: Int) {
        running = true
        worker = Thread { streamLoop(mp, host, port) }.also { it.start() }
    }

    private fun buildAudioRecord(mp: MediaProjection): AudioRecord? {
        val config = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        return try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE))
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: SecurityException) {
            // RECORD_AUDIO not granted.
            Log.e(TAG, "AudioRecord build failed: ${e.message}")
            null
        }
    }

    private fun streamLoop(mp: MediaProjection, host: String, port: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var socket: Socket? = null
        var paused = false
        try {
            socket = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            }
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            Frame.writeFrame(
                out, FrameType.PCM_META,
                PcmMeta(SAMPLE_RATE, 2, AudioFormat.ENCODING_PCM_16BIT).toBytes(),
            )

            val record = buildAudioRecord(mp) ?: run { stopEverything(); return }
            audioRecord = record
            record.startRecording()

            val buf = ByteArray(READ_CHUNK)
            while (running) {
                if (isInCall(am)) {
                    // Phone call: a call preempts the capture path and leaves
                    // AudioRecord dead, so release it and pause streaming (the
                    // receiver rides it out on silence). Rebuild on the way back.
                    if (!paused) {
                        paused = true
                        runCatching { audioRecord?.stop() }
                        runCatching { audioRecord?.release() }
                        audioRecord = null
                    }
                    Thread.sleep(150)
                    continue
                }
                if (paused) {
                    // Call ended: rebuild capture fresh so whatever the phone
                    // plays next is streamed again — no manual stop/restart.
                    paused = false
                    val fresh = buildAudioRecord(mp) ?: break
                    audioRecord = fresh
                    fresh.startRecording()
                }
                val rec = audioRecord ?: break
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                Frame.writeHeader(out, FrameType.PCM_DATA, n)
                out.write(buf, 0, n)
            }
            out.flush()
        } catch (e: Exception) {
            Log.e(TAG, "stream loop ended: ${e.message}")
        } finally {
            runCatching { socket?.close() }
            stopEverything()
        }
    }

    private fun isInCall(am: AudioManager): Boolean = when (am.mode) {
        AudioManager.MODE_IN_CALL,
        AudioManager.MODE_IN_COMMUNICATION,
        AudioManager.MODE_RINGTONE -> true
        else -> false
    }

    private fun stopEverything() {
        running = false
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { projection?.stop() }
        projection = null
        worker = null
        stopForegroundCompat()
        stopSelf()
    }

    // ---- Foreground notification -------------------------------------------

    private fun startForegroundInternal() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Casting", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            nm.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, CaptureService::class.java).apply { action = ACTION_STOP }
        val stopPending = android.app.PendingIntent.getService(
            this, 1, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val contentPending = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AudioCast is casting")
            .setContentText("Streaming this phone's audio to the receiver")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(contentPending)
            .addAction(0, "Stop", stopPending)
            .build()

        startForeground(NOTIF_ID, notification)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
    }

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "audiocast_casting"
        private const val NOTIF_ID = 42
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val SAMPLE_RATE = 44100
        private const val READ_CHUNK = 8 * 1024

        const val ACTION_STOP = "com.svetlio.audiocast.capture.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"

        fun startIntent(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            host: String,
            port: Int,
        ): Intent = Intent(context, CaptureService::class.java).apply {
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_RESULT_DATA, resultData)
            putExtra(EXTRA_HOST, host)
            putExtra(EXTRA_PORT, port)
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, CaptureService::class.java).apply { action = ACTION_STOP }
    }
}

/** Version-safe parcelable extra retrieval. */
@Suppress("UNCHECKED_CAST", "DEPRECATION")
private fun <T> Intent.getParcelableExtraCompat(key: String, clazz: Class<T>): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, clazz)
    } else {
        getParcelableExtra(key) as? T
    }
