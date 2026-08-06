package com.svetlio.audiocast.receiver

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-level state published by [ReceiverService] and observed by the UI.
 *
 * The receiver's real work (server socket + players) lives in the service so it
 * survives the Activity going away. The UI can't hold that state itself, so the
 * service writes here and the screen reads here — a small shared bridge instead
 * of bound-service boilerplate.
 */
object ReceiverState {
    val advertisedName = MutableStateFlow<String?>(null)
    private val _playback = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playback: StateFlow<PlaybackState> = _playback.asStateFlow()

    /** True while ReceiverService is active; lets the app avoid spurious stops. */
    val running = MutableStateFlow(false)

    internal fun setPlayback(state: PlaybackState) { _playback.value = state }
    internal fun setAdvertisedName(name: String?) { advertisedName.value = name }
    internal fun setRunning(value: Boolean) { running.value = value }

    /** Reset when the service stops, so a stale "Now playing" doesn't linger. */
    internal fun reset() {
        advertisedName.value = null
        _playback.value = PlaybackState.Idle
        running.value = false
    }
}

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data class Receiving(val name: String, val progress: Float?) : PlaybackState
    data class Playing(val name: String) : PlaybackState
    data class Failed(val message: String) : PlaybackState
}
