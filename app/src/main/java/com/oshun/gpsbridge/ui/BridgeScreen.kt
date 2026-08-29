package com.oshun.gpsbridge.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oshun.gpsbridge.NetworkGate
import com.oshun.gpsbridge.R
import com.oshun.gpsbridge.core.BridgeLogic
import com.oshun.gpsbridge.core.BridgeState
import com.oshun.gpsbridge.core.BridgeStatus
import com.oshun.gpsbridge.core.StopReason
import com.oshun.gpsbridge.crash.CrashActivity
import com.oshun.gpsbridge.crash.CrashStore
import com.oshun.gpsbridge.net.Link
import com.oshun.gpsbridge.net.NetworkRequirements
import com.oshun.gpsbridge.outcomeLabel
import com.oshun.gpsbridge.service.GpsBridgeService
import com.oshun.gpsbridge.store.ConfigStore
import kotlinx.coroutines.delay

/**
 * The working screen: what you look at while casting off. Network first, then the transport
 * settings, then Start — and once it is running, the status that tells a live bridge from a
 * stalled one. Everything you consult rather than operate lives behind the side menu.
 */
@Composable
fun BridgeScreen(
    settings: BridgeSettings,
    onOpenTestData: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val status by BridgeState.status.collectAsState()

    var lastCrash by remember { mutableStateOf(CrashStore.read(context)) }
    // Why the bridge stopped last time; an idle shutdown is otherwise invisible.
    var lastStop by remember { mutableStateOf(ConfigStore.readStopReason(context)) }

    // The bridge only transmits over the phone's own hotspot. Poll so the requirements
    // clear the moment the user turns it on.
    var network by remember { mutableStateOf(NetworkGate.state(context)) }
    // Drives the "hace N s" counters, so a stalled bridge is visible without touching anything.
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            network = NetworkGate.state(context)
            nowMillis = System.currentTimeMillis()
            batteryUnrestricted = isIgnoringBatteryOptimizations(context)
            delay(1000)
        }
    }

    val requiredPermissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (granted) {
            GpsBridgeService.start(context, settings.toConfig())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.app_subtitle),
            style = MaterialTheme.typography.bodyMedium,
        )

        lastCrash?.let { report ->
            CrashBanner(
                onView = {
                    context.startActivity(
                        Intent(context, CrashActivity::class.java)
                            .putExtra(CrashActivity.EXTRA_REPORT, report),
                    )
                },
                onDismiss = {
                    CrashStore.clear(context)
                    lastCrash = null
                },
            )
        }

        if (!network.metFor(settings.simulated)) {
            NetworkRequirementsCard(
                network = network,
                simulated = settings.simulated,
                onOpenHotspot = { openHotspotSettings(context) },
                onOpenWifi = { openWifiSettings(context) },
            )
        }

        // Test mode now lives on its own screen, so the one thing that must never be
        // discovered late — that the position going out is not yours — is announced here.
        if (settings.simulated) {
            TestModeActiveBanner(onOpen = onOpenTestData)
        }

        OutlinedTextField(
            value = settings.portText,
            onValueChange = { settings.portText = it.filter(Char::isDigit).take(5) },
            label = { Text(stringResource(R.string.label_port)) },
            enabled = !status.running,
            modifier = Modifier.fillMaxWidth(),
        )

        SwitchRow(stringResource(R.string.switch_tcp), "switch_tcp", settings.tcpEnabled, enabled = !status.running) {
            settings.tcpEnabled = it
        }
        SwitchRow(stringResource(R.string.switch_udp), "switch_udp", settings.udpEnabled, enabled = !status.running) {
            settings.udpEnabled = it
        }

        SwitchRow(
            stringResource(R.string.switch_autooff),
            "switch_autooff",
            settings.autoOffEnabled,
            enabled = !status.running,
        ) { settings.autoOffEnabled = it }

        SwitchRow(
            stringResource(R.string.switch_rawlog),
            "switch_rawlog",
            settings.rawLogEnabled,
            enabled = !status.running,
        ) { settings.rawLogEnabled = it }

        IntervalSelector(
            selected = settings.intervalMillis,
            enabled = !status.running,
            onSelect = { settings.intervalMillis = it },
        )

        if (!status.running) {
            Button(
                onClick = { permissionLauncher.launch(requiredPermissions) },
                enabled = (settings.tcpEnabled || settings.udpEnabled) && network.metFor(settings.simulated),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("action_button"),
            ) { Text(stringResource(R.string.action_start)) }
        } else {
            Button(
                onClick = { GpsBridgeService.stop(context) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("action_button"),
            ) { Text(stringResource(R.string.action_stop)) }
        }

        // Advisory banners live below the action button: they explain and suggest, and
        // pushing the primary action off screen to show them is the wrong trade.
        if (lastStop == StopReason.IDLE_TIMEOUT && !status.running) {
            IdleStopBanner(onDismiss = {
                ConfigStore.saveStopReason(context, null)
                lastStop = null
            })
        }

        if (!batteryUnrestricted) {
            BatteryOptimizationBanner(onFix = { openBatteryOptimizationSettings(context) })
        }

        if (status.running) StatusCard(status, network.link, nowMillis)

        InstructionsCard()
    }
}

private val INTERVAL_OPTIONS = listOf(500L, 1000L, 2000L, 5000L)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntervalSelector(selected: Long, enabled: Boolean, onSelect: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.label_interval), style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            INTERVAL_OPTIONS.forEach { millis ->
                FilterChip(
                    selected = selected == millis,
                    onClick = { onSelect(millis) },
                    enabled = enabled,
                    label = { Text(formatInterval(millis)) },
                )
            }
        }
    }
}

/** Formats an interval in whole or fractional seconds, e.g. 500 -> "0.5 s", 2000 -> "2 s". */
private fun formatInterval(millis: Long): String {
    val seconds = millis / 1000.0
    val text = if (millis % 1000L == 0L) seconds.toInt().toString() else seconds.toString()
    return "$text s"
}

/**
 * The pre-flight check. Transmitting over the marina's Wi-Fi looks fine at the mooring and
 * dies a few metres out, so the bridge will not start until the phone is serving its own
 * hotspot — the only link that casts off with the boat.
 */
@Composable
private fun NetworkRequirementsCard(
    network: NetworkRequirements,
    simulated: Boolean,
    onOpenHotspot: () -> Unit,
    onOpenWifi: () -> Unit,
) {
    SectionCard(stringResource(R.string.net_req_title)) {
        Text(
            stringResource(if (simulated) R.string.net_req_body_sim else R.string.net_req_body),
            style = MaterialTheme.typography.bodyMedium,
        )

        if (simulated) {
            // A simulated run never leaves the desk: any network both devices share works.
            RequirementRow(
                met = network.anyLocalNetwork,
                label = stringResource(R.string.net_req_any),
                actionLabel = stringResource(R.string.net_open_hotspot),
                tag = "fix_hotspot",
                onFix = onOpenHotspot,
            )
        } else {
            // The cable is enough on its own; the hotspot rows below are the alternative.
            RequirementRow(
                met = network.cableUp,
                label = stringResource(R.string.net_req_cable),
                actionLabel = stringResource(R.string.net_open_usb),
                tag = "fix_usb",
                onFix = onOpenHotspot, // the tethering screen is where USB tethering lives
            )
            Text(
                stringResource(R.string.net_req_cable_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.net_req_or),
                style = MaterialTheme.typography.bodySmall,
            )
            RequirementRow(
                met = network.hotspotUp,
                label = stringResource(R.string.net_req_hotspot),
                actionLabel = stringResource(R.string.net_open_hotspot),
                tag = "fix_hotspot",
                onFix = onOpenHotspot,
            )
            RequirementRow(
                met = network.wifiOff,
                label = stringResource(R.string.net_req_wifi_off),
                actionLabel = stringResource(R.string.net_open_wifi),
                tag = "fix_wifi",
                onFix = onOpenWifi,
            )
        }
    }
}

/** What to call the link the advertised address belongs to. */
@Composable
private fun linkText(link: Link): String = stringResource(
    when (link) {
        Link.CABLE -> R.string.link_cable
        Link.HOTSPOT -> R.string.link_hotspot
        Link.OTHER -> R.string.link_other
    },
)

@Composable
private fun RequirementRow(
    met: Boolean,
    label: String,
    actionLabel: String,
    tag: String,
    onFix: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            stringResource(if (met) R.string.net_req_ok else R.string.net_req_missing, label),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (!met) {
            Button(onClick = onFix, modifier = Modifier.testTag(tag)) { Text(actionLabel) }
        }
    }
}

/**
 * Test mode is on and its screen is elsewhere: say so where the Start button is, because a
 * chart plotter showing a position that is not yours is worse than one showing none.
 */
@Composable
private fun TestModeActiveBanner(onOpen: () -> Unit) {
    SectionCard(stringResource(R.string.sim_active_title)) {
        Text(stringResource(R.string.sim_active_body), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onOpen, modifier = Modifier.testTag("open_test_data")) {
            Text(stringResource(R.string.sim_active_action))
        }
    }
}

/** The bridge shut itself down for lack of clients: say so, it explains a frozen chart. */
@Composable
private fun IdleStopBanner(onDismiss: () -> Unit) {
    SectionCard(stringResource(R.string.idle_banner_title)) {
        Text(stringResource(R.string.idle_banner_body), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onDismiss, modifier = Modifier.testTag("idle_dismiss")) {
            Text(stringResource(R.string.idle_banner_dismiss))
        }
    }
}

/** Battery optimization is on: Android may freeze the app once the screen goes off. */
@Composable
private fun BatteryOptimizationBanner(onFix: () -> Unit) {
    SectionCard(stringResource(R.string.battery_banner_title)) {
        Text(stringResource(R.string.battery_banner_body), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onFix, modifier = Modifier.testTag("battery_fix")) {
            Text(stringResource(R.string.battery_banner_action))
        }
    }
}

@Composable
private fun CrashBanner(onView: () -> Unit, onDismiss: () -> Unit) {
    SectionCard(stringResource(R.string.crash_banner_title)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onView) { Text(stringResource(R.string.crash_banner_view)) }
            OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.crash_banner_dismiss)) }
        }
    }
}

@Composable
private fun StatusCard(status: BridgeStatus, link: Link?, nowMillis: Long) {
    SectionCard(stringResource(R.string.status_title)) {
        if (status.simulated) {
            Text(
                stringResource(R.string.status_simulated),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        KeyValue(stringResource(R.string.status_ip), status.ipAddress ?: stringResource(R.string.status_no_wifi))
        link?.let { KeyValue(stringResource(R.string.status_link), linkText(it)) }
        if (status.aisTargets > 0) {
            KeyValue(stringResource(R.string.status_ais), status.aisTargets.toString())
        }
        KeyValue(stringResource(R.string.status_port), status.port.toString())
        val protocols = BridgeLogic.enabledProtocols(status)
            .joinToString(" + ")
            .ifEmpty { stringResource(R.string.status_protocols_none) }
        KeyValue(stringResource(R.string.status_protocols), protocols)
        if (status.tcpEnabled) KeyValue(stringResource(R.string.status_tcp_clients), status.tcpClients.toString())
        KeyValue(stringResource(R.string.status_delivery), outcomeLabel(status.outcome))
        KeyValue(stringResource(R.string.status_sentences), status.sentencesSent.toString())
        KeyValue(stringResource(R.string.status_heartbeats), status.heartbeatsSent.toString())
        status.lastFix?.let { fix ->
            KeyValue(stringResource(R.string.status_last_position), "%.5f, %.5f".format(fix.latitude, fix.longitude))
        }
        // The two rows that tell a stalled bridge apart from a healthy one: is the GPS
        // still feeding us, and is anything still reaching the tablet?
        KeyValue(stringResource(R.string.status_last_fix), ageLabel(nowMillis, status.lastFixAtMillis))
        KeyValue(stringResource(R.string.status_last_send), ageLabel(nowMillis, status.lastSendOkAtMillis))
        if (status.lastFix != null && !status.fixValid) {
            Text(
                stringResource(R.string.status_fix_stale),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        status.batteryPercent?.let { KeyValue(stringResource(R.string.status_battery), "$it%") }
        status.currentDrawMilliAmp?.let { KeyValue(stringResource(R.string.status_draw), "≈ $it mA") }
        status.batteryDrainPerHour?.let {
            KeyValue(stringResource(R.string.status_drain), "%.1f %%/h".format(it))
        }
    }
}

/** "hace 12 s" for a known instant, "nunca" when it never happened. */
@Composable
private fun ageLabel(nowMillis: Long, thenMillis: Long?): String {
    val token = BridgeLogic.ageToken(nowMillis, thenMillis) ?: return stringResource(R.string.status_never)
    return stringResource(R.string.status_age, token)
}

@Composable
private fun InstructionsCard() {
    SectionCard(stringResource(R.string.instructions_title)) {
        Text(
            stringResource(R.string.instructions_body),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
