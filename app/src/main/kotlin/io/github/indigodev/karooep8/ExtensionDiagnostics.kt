package io.github.indigodev.karooep8

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DiagnosticSnapshot(
    val serviceCreated: Boolean = false,
    val karooScanCount: Int = 0,
    val karooScanActive: Boolean = false,
    val reportedDevices: List<String> = emptyList(),
    val connectRequestCount: Int = 0,
    val activeConsumers: Int = 0,
    val lastConnectUid: String? = null,
    val gattConnected: Boolean = false,
    val lastDisconnectStatus: Int? = null,
    val gattEvents: List<String> = emptyList(),
    val lastError: String? = null
)

object ExtensionDiagnostics {
    private val _state = MutableStateFlow(DiagnosticSnapshot())
    val state: StateFlow<DiagnosticSnapshot> = _state.asStateFlow()

    fun onServiceCreated() {
        _state.update { it.copy(serviceCreated = true) }
    }

    fun onKarooScanStarted() {
        _state.update {
            it.copy(
                karooScanCount = it.karooScanCount + 1,
                karooScanActive = true,
                lastError = null
            )
        }
    }

    fun onKarooScanStopped() {
        _state.update { it.copy(karooScanActive = false) }
    }

    fun onDeviceReported(display: String) {
        _state.update { current ->
            val updated = (current.reportedDevices + display).distinct().takeLast(5)
            current.copy(reportedDevices = updated)
        }
    }

    fun onConnectRequested(uid: String) {
        _state.update {
            it.copy(
                connectRequestCount = it.connectRequestCount + 1,
                lastConnectUid = uid
            )
        }
    }

    fun onConsumersChanged(activeCount: Int) {
        _state.update { it.copy(activeConsumers = activeCount) }
    }

    fun onGattConnected() {
        _state.update {
            val events = (it.gattEvents + "STATE_CONNECTED").takeLast(6)
            it.copy(gattConnected = true, gattEvents = events, lastError = null)
        }
    }

    fun clearError() {
        _state.update { it.copy(lastError = null) }
    }

    fun onGattDisconnected(status: Int) {
        _state.update {
            val events = (it.gattEvents + "DISCONNECTED(status=$status)").takeLast(6)
            it.copy(
                gattConnected = false,
                lastDisconnectStatus = status,
                gattEvents = events
            )
        }
    }

    fun onGattEvent(event: String) {
        _state.update {
            val events = (it.gattEvents + event).takeLast(6)
            it.copy(gattEvents = events)
        }
    }

    fun onError(message: String) {
        _state.update { it.copy(lastError = message) }
    }
}
