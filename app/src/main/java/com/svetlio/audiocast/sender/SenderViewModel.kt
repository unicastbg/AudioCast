package com.svetlio.audiocast.sender

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.svetlio.audiocast.discovery.DiscoveredReceiver
import com.svetlio.audiocast.discovery.NsdController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SenderViewModel(app: Application) : AndroidViewModel(app) {

    private val nsd = NsdController(app)
    private val streamer = FileStreamer(app)

    private val _receivers = MutableStateFlow<List<DiscoveredReceiver>>(emptyList())
    val receivers: StateFlow<List<DiscoveredReceiver>> = _receivers.asStateFlow()

    private val _selected = MutableStateFlow<DiscoveredReceiver?>(null)
    val selected: StateFlow<DiscoveredReceiver?> = _selected.asStateFlow()

    private val _sendState = MutableStateFlow<SendState>(SendState.Idle)
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        startScanning()
    }

    private fun startScanning() {
        _error.value = null
        nsd.startDiscovery(
            onFound = { receiver ->
                _receivers.update { current ->
                    val without = current.filterNot { it.serviceName == receiver.serviceName }
                    (without + receiver).sortedBy { it.serviceName }
                }
                // Auto-select if it's the only one and nothing is selected yet.
                if (_selected.value == null && _receivers.value.size == 1) {
                    _selected.value = receiver
                }
            },
            onLost = { lostName ->
                _receivers.update { it.filterNot { r -> r.serviceName == lostName } }
                if (_selected.value?.serviceName == lostName) _selected.value = null
            },
            onError = { msg -> _error.value = msg },
        )
    }

    fun select(receiver: DiscoveredReceiver) {
        _selected.value = receiver
    }

    fun sendFile(uri: Uri) {
        val target = _selected.value ?: run {
            _sendState.value = SendState.Failed("No receiver selected")
            return
        }
        viewModelScope.launch {
            _sendState.value = SendState.Sending(0f)
            try {
                streamer.stream(
                    host = target.host,
                    port = target.port,
                    uri = uri,
                    onProgress = { sent, total ->
                        val frac = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else null
                        _sendState.value = SendState.Sending(frac)
                    },
                )
                _sendState.value = SendState.Sent
            } catch (e: Exception) {
                _sendState.value = SendState.Failed(e.message ?: "Send failed")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        nsd.stopDiscovery()
    }
}

sealed interface SendState {
    data object Idle : SendState
    data class Sending(val progress: Float?) : SendState
    data object Sent : SendState
    data class Failed(val message: String) : SendState
}
