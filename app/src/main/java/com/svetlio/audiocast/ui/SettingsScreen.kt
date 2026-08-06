package com.svetlio.audiocast.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import com.svetlio.audiocast.core.Role

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentRole: Role,
    onRoleChange: (Role) -> Unit,
    onBack: () -> Unit,
) {
    // Role change is significant (it will later stop/start services), so confirm it.
    var pendingRole by remember { mutableStateOf<Role?>(null) }

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
                .padding(16.dp)
        ) {
            Text("Device role", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            RoleOption(
                label = "Sender",
                selected = currentRole == Role.SENDER,
                onSelect = { if (currentRole != Role.SENDER) pendingRole = Role.SENDER },
            )
            RoleOption(
                label = "Receiver",
                selected = currentRole == Role.RECEIVER,
                onSelect = { if (currentRole != Role.RECEIVER) pendingRole = Role.RECEIVER },
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
private fun RoleOption(label: String, selected: Boolean, onSelect: () -> Unit) {
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
