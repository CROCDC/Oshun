package com.oshun.gpsbridge.core

import com.oshun.gpsbridge.model.Fix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-chosen configuration for a bridge session. */
data class BridgeConfig(
    val port: Int = 2000,          // Navionics default UDP port
    val tcpEnabled: Boolean = true,
    val udpEnabled: Boolean = true,
    val intervalMillis: Long = 1000L,
)

/** Live status published by the foreground service and observed by the UI. */
data class BridgeStatus(
    val running: Boolean = false,
    val ipAddress: String? = null,
    val port: Int = 2000,
    val tcpEnabled: Boolean = false,
    val tcpClients: Int = 0,
    val udpEnabled: Boolean = false,
    val lastFix: Fix? = null,
    val sentencesSent: Long = 0,
    val batteryPercent: Int? = null,
    val currentDrawMilliAmp: Int? = null,
    val batteryDrainPerHour: Double? = null,
)

/**
 * Process-wide holder so the Compose UI can observe the service without binding.
 * Simple and sufficient for a single-activity app.
 */
object BridgeState {
    private val _status = MutableStateFlow(BridgeStatus())
    val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    fun update(transform: (BridgeStatus) -> BridgeStatus) {
        _status.value = transform(_status.value)
    }

    fun reset() {
        _status.value = BridgeStatus()
    }
}
