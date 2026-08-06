package com.svetlio.audiocast.receiver

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin view over [ReceiverState]. The real work (server socket + players) lives
 * in [ReceiverService]; the service is started/stopped by role changes in
 * AppRoot, so this ViewModel only surfaces state to the screen.
 */
class ReceiverViewModel(app: Application) : AndroidViewModel(app) {
    val advertisedName: StateFlow<String?> = ReceiverState.advertisedName
    val playback: StateFlow<PlaybackState> = ReceiverState.playback
}
