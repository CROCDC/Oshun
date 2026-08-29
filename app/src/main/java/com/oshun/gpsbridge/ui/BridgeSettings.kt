package com.oshun.gpsbridge.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.oshun.gpsbridge.core.BridgeConfig
import com.oshun.gpsbridge.store.ConfigStore

/**
 * The configuration the user is editing, hoisted above any single screen.
 *
 * It has to live above them now that the test-mode switch has a screen of its own: turning
 * the simulated boat on relaxes the network requirement the bridge screen enforces, and the
 * bridge screen is the one that hands the whole config to the service. One holder, two
 * screens reading and writing it, no copy to keep in sync.
 */
@Stable
class BridgeSettings(saved: BridgeConfig) {
    /** Text, not an Int: the field is empty for a keystroke while the port is being retyped. */
    var portText by mutableStateOf(saved.port.toString())
    var tcpEnabled by mutableStateOf(saved.tcpEnabled)
    // UDP off by default: TCP is the tested/recommended path and lets the idle auto-off
    // work (UDP reception is not observable). The switch stays available.
    var udpEnabled by mutableStateOf(saved.udpEnabled)
    var intervalMillis by mutableStateOf(saved.intervalMillis)
    var autoOffEnabled by mutableStateOf(saved.autoOffEnabled)
    var rawLogEnabled by mutableStateOf(saved.rawLogEnabled)
    var simulated by mutableStateOf(saved.simulated)
    // The internet AIS feed. Its API key is deliberately not here: the config rides in
    // intents and names itself in the CSV header, and a credential belongs in neither.
    var aisEnabled by mutableStateOf(saved.aisEnabled)

    /** What the service is started with. An unfinished port falls back to the default. */
    fun toConfig(): BridgeConfig = BridgeConfig(
        port = portText.toIntOrNull() ?: DEFAULTS.port,
        tcpEnabled = tcpEnabled,
        udpEnabled = udpEnabled,
        intervalMillis = intervalMillis,
        autoOffEnabled = autoOffEnabled,
        rawLogEnabled = rawLogEnabled,
        simulated = simulated,
        aisEnabled = aisEnabled,
    )

    private companion object {
        val DEFAULTS = BridgeConfig()
    }
}

/** Starts from the last configuration the user actually used, not from the defaults. */
@Composable
fun rememberBridgeSettings(): BridgeSettings {
    val context = LocalContext.current
    return remember { BridgeSettings(ConfigStore.load(context)) }
}
