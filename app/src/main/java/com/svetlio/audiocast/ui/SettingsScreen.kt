package com.svetlio.audiocast.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.svetlio.audiocast.core.AppSettings
import com.svetlio.audiocast.core.Role
import com.svetlio.audiocast.core.Transport
import com.svetlio.audiocast.receiver.AudioLevels

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentRole: Role,
    onRoleChange: (Role) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }

    // Role change is significant (it stops/starts services), so confirm it.
    var pendingRole by remember { mutableStateOf<Role?>(null) }
    // Transport is low-stakes; apply immediately (takes effect on the next cast).
    var transport by remember { mutableStateOf(settings.liveTransport) }
    var visualizer by remember { mutableStateOf(settings.visualizerEnabled) }
    var pin by remember { mutableStateOf(settings.securityPin) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("Device role", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            RadioRow(
                label = "Sender",
                selected = currentRole == Role.SENDER,
                onSelect = { if (currentRole != Role.SENDER) pendingRole = Role.SENDER },
            )
            RadioRow(
                label = "Receiver",
                selected = currentRole == Role.RECEIVER,
                onSelect = { if (currentRole != Role.RECEIVER) pendingRole = Role.RECEIVER },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Live casting transport", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Only affects live capture (file sending always uses TCP). " +
                    "Takes effect the next time you start casting.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))

            RadioRow(
                label = "TCP — reliable (default)",
                selected = transport == Transport.TCP,
                onSelect = {
                    settings.liveTransport = Transport.TCP
                    transport = Transport.TCP
                },
            )
            RadioRow(
                label = "UDP — lower-latency, tolerates drops",
                selected = transport == Transport.UDP,
                onSelect = {
                    settings.liveTransport = Transport.UDP
                    transport = Transport.UDP
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Receiver", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Show visualizer", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "A simple level meter that follows live audio.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = visualizer,
                    onCheckedChange = {
                        settings.visualizerEnabled = it
                        AudioLevels.setEnabled(it)
                        visualizer = it
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Security", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Set the same PIN on both devices. Leave empty for no PIN. " +
                    "On the sender it takes effect the next time you cast.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { new ->
                    val filtered = new.filter { it.isDigit() }.take(8)
                    pin = filtered
                    settings.securityPin = filtered
                },
                label = { Text("PIN") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    val target = pendingRole
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingRole = null },
            title = { Text("Switch role?") },
            text = { Text("Change this device to ${target.name.lowercase()} mode?") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRole = null
                    onRoleChange(target)
                }) { Text("Switch") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRole = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
