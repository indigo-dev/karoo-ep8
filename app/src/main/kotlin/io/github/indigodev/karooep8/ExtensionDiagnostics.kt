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
    val lastConnectUid: String? = null,
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
        _state.update { it.copy(lastConnectUid = uid) }
    }

    fun onError(message: String) {
        _state.update { it.copy(lastError = message) }
    }
}
