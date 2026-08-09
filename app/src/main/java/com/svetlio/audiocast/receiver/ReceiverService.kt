package com.svetlio.audiocast.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.svetlio.audiocast.MainActivity
import com.svetlio.audiocast.R
import com.svetlio.audiocast.core.AppSettings
import com.svetlio.audiocast.discovery.NsdController
import com.svetlio.audiocast.network.FileMeta
import com.svetlio.audiocast.network.PcmMeta
import com.svetlio.audiocast.network.Protocol
import com.svetlio.audiocast.security.BruteForceGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service (type mediaPlayback) that runs the receiver: advertises via
 * NSD, listens for a sender, and plays incoming audio — files via ExoPlayer,
 * live capture via AudioTrack. Publishes status to [ReceiverState] for the UI.
 *
 * Started from the receiver screen while the app is foreground (so the FGS start
 * is allowed even on Android 12+). START_STICKY keeps it alive; it stops only on
 * an explicit STOP action (role switch away from receiver).
 */
class ReceiverService : Service() {

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var nsd: NsdController
    private lateinit var server: ReceiverServer
    private lateinit var udpReceiver: UdpPcmReceiver
    private val guard = BruteForceGuard()

    private var player: ExoPlayer? = null
    private var pcmPlayer: PcmPlayer? = null
    private var lastFile: File? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nsd = NsdController(this)
        val pinProvider = { AppSettings(this).securityPin }
        server = ReceiverServer(cacheDir, Protocol.DEFAULT_PORT, pinProvider, guard)
        udpReceiver = UdpPcmReceiver(Protocol.DEFAULT_PORT, pinProvider, guard)
        player = ExoPlayer.Builder(this).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            startForegroundInternal()
            ReceiverState.setPlayback(PlaybackState.Idle)
            ReceiverState.setRunning(true)
            AudioLevels.setEnabled(AppSettings(this).visualizerEnabled)
            startAdvertising()
            startServer()
            startUdpReceiver()
        }
        return START_STICKY
    }

    private fun startAdvertising() {
        nsd.registerReceiver(
            port = Protocol.DEFAULT_PORT,
            onRegistered = { name -> ReceiverState.setAdvertisedName(name) },
            onError = { msg -> ReceiverState.setPlayback(PlaybackState.Failed(msg)) },
        )
    }

    private fun startServer() {
        mainScope.launch(Dispatchers.IO) {
            server.run(object : ReceiverServer.Callbacks {
                override fun onReceiving(meta: FileMeta) {
                    ReceiverState.setPlayback(PlaybackState.Receiving(meta.name, 0f))
                }

                override fun onProgress(received: Long, total: Long) {
                    val frac = if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else null
                    val name = (ReceiverState.playback.value as? PlaybackState.Receiving)?.name ?: "audio"
                    ReceiverState.setPlayback(PlaybackState.Receiving(name, frac))
                }

                override fun onFileReady(file: File, meta: FileMeta) {
                    mainScope.launch { playFile(file, meta) }
                }

                override fun onPcmStart(meta: PcmMeta) {
                    mainScope.launch { player?.pause() }
                    pcmPlayer?.stop()
                    pcmPlayer = PcmPlayer(meta).apply { start() }
                    ReceiverState.setPlayback(PlaybackState.Playing("Live audio"))
                }

                override fun onPcmChunk(data: ByteArray, length: Int) {
                    pcmPlayer?.write(data, 0, length)
                }

                override fun onStreamEnded() {
                    pcmPlayer?.stop()
                    pcmPlayer = null
                    ReceiverState.setPlayback(PlaybackState.Idle)
                }

                override fun onError(message: String) {
                    ReceiverState.setPlayback(PlaybackState.Failed(message))
                }
            })
        }
    }

    private fun startUdpReceiver() {
        mainScope.launch(Dispatchers.IO) {
            udpReceiver.run(object : UdpPcmReceiver.Callbacks {
                override fun onStreamStart() {
                    mainScope.launch { player?.pause() }
                    ReceiverState.setPlayback(PlaybackState.Playing("Live audio"))
                }

                override fun onStreamEnd() {
                    ReceiverState.setPlayback(PlaybackState.Idle)
                }

                override fun onError(message: String) {
                    ReceiverState.setPlayback(PlaybackState.Failed(message))
                }
            })
        }
    }

    private fun playFile(file: File, meta: FileMeta) {
        val p = player ?: return
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        p.prepare()
        p.playWhenReady = true
        ReceiverState.setPlayback(PlaybackState.Playing(meta.name))

        lastFile?.let { old -> if (old != file) old.delete() }
        lastFile = file
    }

    private fun stopEverything() {
        server.stop()
        udpReceiver.stop()
        nsd.unregister()
        pcmPlayer?.stop()
        pcmPlayer = null
        player?.release()
        player = null
        lastFile?.delete()
        ReceiverState.reset()
        mainScope.cancel()
        stopForegroundCompat()
        stopSelf()
    }

    // ---- Foreground notification -------------------------------------------

    private fun startForegroundInternal() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Receiver", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }

        val stopPending = PendingIntent.getService(
            this, 1, Intent(this, ReceiverService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val contentPending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AudioCast receiver")
            .setContentText("Ready to receive audio")
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
        // Safety net if the process is torn down without an explicit stop.
        runCatching { server.stop() }
        runCatching { udpReceiver.stop() }
        runCatching { nsd.unregister() }
        runCatching { pcmPlayer?.stop() }
        runCatching { player?.release() }
    }

    companion object {
        private const val CHANNEL_ID = "audiocast_receiver"
        private const val NOTIF_ID = 43

        const val ACTION_STOP = "com.svetlio.audiocast.receiver.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, ReceiverService::class.java)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ReceiverService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
