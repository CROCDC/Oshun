package com.oshun.gpsbridge.core

import com.oshun.gpsbridge.model.Fix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** User-chosen configuration for a bridge session. */
data class BridgeConfig(
    val port: Int = 2000,          // Navionics default UDP port
    val tcpEnabled: Boolean = true,
    // TCP-only by default: it is the tested path and the one whose reception we can
    // observe. Matches the UI default so a service restarted by the system with no
    // intent behaves like the session the user configured.
    val udpEnabled: Boolean = false,
    val intervalMillis: Long = 1000L,
    val autoOffEnabled: Boolean = true,
    /** Per-fix CSV on disk. On by default: the whole point is to have it when it fails. */
    val rawLogEnabled: Boolean = true,
    /** Test mode: transmit the simulated Río de la Plata track instead of the phone's GPS. */
    val simulated: Boolean = false,
)

/** Live status published by the foreground service and observed by the UI. */
data class BridgeStatus(
    val running: Boolean = false,
    val ipAddress: String? = null,
    val port: Int = 2000,
    val tcpEnabled: Boolean = false,
    val tcpClients: Int = 0,
    val udpEnabled: Boolean = false,
    val autoOffEnabled: Boolean = false,
    val lastFix: Fix? = null,
    val sentencesSent: Long = 0,
    // Diagnostics: without these a stalled bridge looked exactly like a healthy one.
    /** Wall-clock time of the last fix received from the GPS, or null if none yet. */
    val lastFixAtMillis: Long? = null,
    /** Wall-clock time of the last send that had a live consumer (TCP client / UDP socket). */
    val lastSendOkAtMillis: Long? = null,
    /** False once the last fix is old enough that we transmit it flagged as invalid. */
    val fixValid: Boolean = false,
    /** What became of the last batch: delivered, sent to nobody, backed up, blind (UDP). */
    val outcome: DeliveryOutcome? = null,
    /** True while the transmitted position is the simulator's, not the phone's. */
    val simulated: Boolean = false,
    /** How many other vessels are going out on the stream as AIS targets. */
    val aisTargets: Int = 0,
    /** Sentences emitted as heartbeat (a resend of the last fix), included in [sentencesSent]. */
    val heartbeatsSent: Long = 0,
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

    /**
     * Atomic on purpose. Six threads write here — the fix collector, the heartbeat, the
     * accept thread, the battery monitor, the stop path — and a plain read-modify-write
     * loses one of two concurrent updates. Worse, an emission that read the status just
     * before a stop used to write its copy back *after* [reset], resurrecting `running =
     * true` on a bridge that had already stopped: the UI then says "Transmitiendo" over a
     * dead service. The compare-and-set retries against the value that actually won.
     */
    fun update(transform: (BridgeStatus) -> BridgeStatus) {
        _status.update(transform)
    }

    fun reset() {
        _status.value = BridgeStatus()
    }
}
