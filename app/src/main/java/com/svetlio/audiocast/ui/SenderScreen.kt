package com.svetlio.audiocast.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.svetlio.audiocast.capture.CaptureService
import com.svetlio.audiocast.discovery.DiscoveredReceiver
import com.svetlio.audiocast.sender.SendState
import com.svetlio.audiocast.sender.SenderViewModel

private enum class SenderMode { FILE, CAPTURE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SenderScreen(
    onOpenSettings: () -> Unit,
    viewModel: SenderViewModel = viewModel(),
) {
    val context = LocalContext.current
    val receivers by viewModel.receivers.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val sendState by viewModel.sendState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var mode by remember { mutableStateOf(SenderMode.FILE) }
    var isCapturing by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }

    val mpm = remember { context.getSystemService(MediaProjectionManager::class.java) }

    // File picker (file mode).
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.sendFile(uri) }

    // MediaProjection consent result (capture mode) -> start the foreground service.
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val target = selected
        if (result.resultCode == Activity.RESULT_OK && data != null && target != null) {
            ContextCompat.startForegroundService(
                context,
                CaptureService.startIntent(context, result.resultCode, data, target.host, target.port),
            )
            isCapturing = true
            captureError = null
        } else {
            captureError = "Capture permission denied"
        }
    }

    // Runtime permissions (mic mandatory; notifications for the ongoing banner).
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val micGranted = perms[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (micGranted) {
            mpm?.let { projectionLauncher.launch(it.createScreenCaptureIntent()) }
        } else {
            captureError = "Microphone permission is required to capture audio"
        }
    }

    fun startCapture() {
        if (selected == null) return
        captureError = null
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) {
            mpm?.let { projectionLauncher.launch(it.createScreenCaptureIntent()) }
        } else {
            permLauncher.launch(needed.toTypedArray())
        }
    }

    fun stopCapture() {
        context.startService(CaptureService.stopIntent(context))
        isCapturing = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sender") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == SenderMode.FILE,
                    onClick = { if (!isCapturing) mode = SenderMode.FILE },
                    label = { Text("Send file") },
                )
                FilterChip(
                    selected = mode == SenderMode.CAPTURE,
                    onClick = { mode = SenderMode.CAPTURE },
                    label = { Text("Live capture") },
                )
            }
            Spacer(Modifier.height(12.dp))

            Text("Receivers on the network", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            Box(modifier = Modifier.weight(1f)) {
                when {
                    error != null -> Text(
                        text = error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    receivers.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "Searching for receivers…\nMake sure the receiver is on and joined to this hotspot.",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(receivers, key = { it.serviceName }) { receiver ->
                            ReceiverRow(
                                receiver = receiver,
                                selected = receiver.serviceName == selected?.serviceName,
                                onClick = { if (!isCapturing) viewModel.select(receiver) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            when (mode) {
                SenderMode.FILE -> {
                    SendStatus(sendState)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { picker.launch(arrayOf("audio/*")) },
                        enabled = selected != null && sendState !is SendState.Sending,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (selected == null) "Select a receiver first" else "Choose file & cast")
                    }
                }

                SenderMode.CAPTURE -> {
                    Text(
                        text = "Casts this phone's audio (any app — music, YouTube, browser). " +
                            "Tip: turn this phone's media volume down so you only hear the receiver.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    captureError?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (isCapturing) {
                        Text(
                            "Casting live audio…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { stopCapture() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Stop casting")
                        }
                    } else {
                        Button(
                            onClick = { startCapture() },
                            enabled = selected != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (selected == null) "Select a receiver first" else "Start casting")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceiverRow(
    receiver: DiscoveredReceiver,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        border = border,
        colors = CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(receiver.serviceName, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${receiver.host}:${receiver.port}${if (selected) "  •  selected" else ""}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SendStatus(state: SendState) {
    when (state) {
        is SendState.Idle -> {}
        is SendState.Sending -> {
            val p = state.progress
            if (p != null) {
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(4.dp))
            Text("Casting…", style = MaterialTheme.typography.bodySmall)
        }
        is SendState.Sent -> Text(
            "Sent — playing on the receiver.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        is SendState.Failed -> Text(
            state.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
