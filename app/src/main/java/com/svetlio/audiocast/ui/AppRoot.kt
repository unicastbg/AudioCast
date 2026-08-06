package com.svetlio.audiocast.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.svetlio.audiocast.AudioCastApp
import com.svetlio.audiocast.core.Role
import com.svetlio.audiocast.receiver.ReceiverService
import com.svetlio.audiocast.receiver.ReceiverState

/**
 * Top-level router. Decides between the first-run role picker, the active role
 * screen (Sender/Receiver), and Settings. No navigation library yet — the app
 * has three destinations, so a couple of state flags are clearer than a NavHost.
 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as AudioCastApp).settings }

    var role by remember { mutableStateOf(settings.role) }
    var showSettings by remember { mutableStateOf(false) }

    // The receiver runs as a foreground service. Start it while in receiver role,
    // stop it when leaving. Runs on role changes and on first composition (e.g.
    // when the box autostarts straight into receiver mode).
    LaunchedEffect(role) {
        if (role == Role.RECEIVER) {
            ReceiverService.start(context)
        } else if (ReceiverState.running.value) {
            ReceiverService.stop(context)
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        when {
            role == Role.UNSET -> RoleSelectScreen(
                onRoleChosen = { chosen ->
                    settings.role = chosen
                    role = chosen
                }
            )

            showSettings -> SettingsScreen(
                currentRole = role,
                onRoleChange = { chosen ->
                    settings.role = chosen
                    role = chosen
                    showSettings = false
                },
                onBack = { showSettings = false }
            )

            role == Role.SENDER -> SenderScreen(onOpenSettings = { showSettings = true })

            role == Role.RECEIVER -> ReceiverScreen(onOpenSettings = { showSettings = true })
        }
    }
}
