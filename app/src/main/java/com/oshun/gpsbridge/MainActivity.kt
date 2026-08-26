package com.oshun.gpsbridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oshun.gpsbridge.core.BridgeConfig
import com.oshun.gpsbridge.core.BridgeLogic
import com.oshun.gpsbridge.core.BridgeState
import com.oshun.gpsbridge.core.BridgeStatus
import com.oshun.gpsbridge.crash.CrashActivity
import com.oshun.gpsbridge.crash.CrashStore
import com.oshun.gpsbridge.core.StopReason
import com.oshun.gpsbridge.net.Link
import com.oshun.gpsbridge.net.NetworkRequirements
import com.oshun.gpsbridge.service.GpsBridgeService
import com.oshun.gpsbridge.store.ConfigStore
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    BridgeScreen(Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun BridgeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val status by BridgeState.status.collectAsState()

    // Start from the last configuration the user actually used, not from the defaults.
    val saved = remember { ConfigStore.load(context) }
    var portText by remember { mutableStateOf(saved.port.toString()) }
    var tcpEnabled by remember { mutableStateOf(saved.tcpEnabled) }
    // UDP off by default: TCP is the tested/recommended path and lets the idle
    // auto-off work (UDP reception is not observable). The switch stays available.
    var udpEnabled by remember { mutableStateOf(saved.udpEnabled) }
    var intervalMillis by remember { mutableStateOf(saved.intervalMillis) }
    var autoOffEnabled by remember { mutableStateOf(saved.autoOffEnabled) }
    var rawLogEnabled by remember { mutableStateOf(saved.rawLogEnabled) }
    var simulated by remember { mutableStateOf(saved.simulated) }
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
            GpsBridgeService.start(
                context,
                BridgeConfig(
                    port = portText.toIntOrNull() ?: 2000,
                    tcpEnabled = tcpEnabled,
                    udpEnabled = udpEnabled,
                    intervalMillis = intervalMillis,
                    autoOffEnabled = autoOffEnabled,
                    rawLogEnabled = rawLogEnabled,
                    simulated = simulated,
                ),
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
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

        if (!network.metFor(simulated)) {
            NetworkRequirementsCard(
                network = network,
                simulated = simulated,
                onOpenHotspot = { openHotspotSettings(context) },
                onOpenWifi = { openWifiSettings(context) },
            )
        }

        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter(Char::isDigit).take(5) },
            label = { Text(stringResource(R.string.label_port)) },
            enabled = !status.running,
            modifier = Modifier.fillMaxWidth(),
        )

        SwitchRow(stringResource(R.string.switch_tcp), "switch_tcp", tcpEnabled, enabled = !status.running) { tcpEnabled = it }
        SwitchRow(stringResource(R.string.switch_udp), "switch_udp", udpEnabled, enabled = !status.running) { udpEnabled = it }

        SwitchRow(
            stringResource(R.string.switch_autooff),
            "switch_autooff",
            autoOffEnabled,
            enabled = !status.running,
        ) { autoOffEnabled = it }

        SwitchRow(
            stringResource(R.string.switch_rawlog),
            "switch_rawlog",
            rawLogEnabled,
            enabled = !status.running,
        ) { rawLogEnabled = it }

        IntervalSelector(
            selected = intervalMillis,
            enabled = !status.running,
            onSelect = { intervalMillis = it },
        )

        if (!status.running) {
            Button(
                onClick = { permissionLauncher.launch(requiredPermissions) },
                enabled = (tcpEnabled || udpEnabled) && network.metFor(simulated),
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

        OutlinedButton(
            onClick = { context.startActivity(Intent(context, LogActivity::class.java)) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("open_log"),
        ) { Text(stringResource(R.string.log_open)) }

        TestModeCard(
            enabled = simulated,
            editable = !status.running,
            onChange = { simulated = it },
        )

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

        VersionCard(onDownload = { openReleases(context) })
    }
}

@Composable
private fun SwitchRow(label: String, tag: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            modifier = Modifier.testTag(tag),
        )
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.net_req_title), style = MaterialTheme.typography.titleMedium)
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

/** Opens the Wi-Fi settings so the client radio can be turned off. */
private fun openWifiSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: Exception) {
        openHotspotSettings(context) // the wireless screen is the next best thing
    }
}

/** Opens the tethering/hotspot settings, falling back to the wireless settings screen. */
private fun openHotspotSettings(context: Context) {
    val candidates = listOf(
        Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
    )
    for (intent in candidates) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {
            // Try the next candidate.
        }
    }
}

/**
 * Test mode: a simulated boat instead of the phone's GPS. Called out loudly, because a
 * chart plotter showing a position that is not yours is worse than one showing none.
 */
@Composable
private fun TestModeCard(enabled: Boolean, editable: Boolean, onChange: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.sim_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.sim_body), style = MaterialTheme.typography.bodyMedium)
            SwitchRow(stringResource(R.string.switch_sim), "switch_sim", enabled, editable, onChange)
        }
    }
}

/** The bridge shut itself down for lack of clients: say so, it explains a frozen chart. */
@Composable
private fun IdleStopBanner(onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.idle_banner_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.idle_banner_body), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onDismiss, modifier = Modifier.testTag("idle_dismiss")) {
                Text(stringResource(R.string.idle_banner_dismiss))
            }
        }
    }
}

/** Battery optimization is on: Android may freeze the app once the screen goes off. */
@Composable
private fun BatteryOptimizationBanner(onFix: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.battery_banner_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.battery_banner_body), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onFix, modifier = Modifier.testTag("battery_fix")) {
                Text(stringResource(R.string.battery_banner_action))
            }
        }
    }
}

/** True when the system won't throttle us in the background (or the API isn't available). */
private fun isIgnoringBatteryOptimizations(context: Context): Boolean = try {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    pm.isIgnoringBatteryOptimizations(context.packageName)
} catch (e: Exception) {
    true // never nag when we can't tell
}

/** Asks for the exemption, falling back to the system list when the direct request is blocked. */
@Suppress("BatteryLife") // a sideloaded navigation bridge is exactly the exempt-worthy case
private fun openBatteryOptimizationSettings(context: Context) {
    val candidates = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:" + context.packageName)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )
    for (intent in candidates) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {
            // Try the next candidate.
        }
    }
}

@Composable
private fun CrashBanner(onView: () -> Unit, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.crash_banner_title), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onView) { Text(stringResource(R.string.crash_banner_view)) }
                OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.crash_banner_dismiss)) }
            }
        }
    }
}

@Composable
private fun StatusCard(status: BridgeStatus, link: Link?, nowMillis: Long) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.status_title), style = MaterialTheme.typography.titleMedium)
            if (status.simulated) {
                Text(
                    stringResource(R.string.status_simulated),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            KeyValue(stringResource(R.string.status_ip), status.ipAddress ?: stringResource(R.string.status_no_wifi))
            link?.let { KeyValue(stringResource(R.string.status_link), linkText(it)) }
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
}

/** "hace 12 s" for a known instant, "nunca" when it never happened. */
@Composable
private fun ageLabel(nowMillis: Long, thenMillis: Long?): String {
    val token = BridgeLogic.ageToken(nowMillis, thenMillis) ?: return stringResource(R.string.status_never)
    return stringResource(R.string.status_age, token)
}

/**
 * The app is sideloaded, so nothing tells you a newer build exists — and the version name
 * does not move between debug builds. The commit does, so it is what gets shown.
 */
@Composable
private fun VersionCard(onDownload: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.version_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.version_value, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(stringResource(R.string.version_hint), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = onDownload,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("download_update"),
            ) { Text(stringResource(R.string.version_download)) }
        }
    }
}

/** Opens the Releases page, where every green build publishes a fresh APK. */
private fun openReleases(context: Context) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.RELEASES_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: Exception) {
        // No browser to handle it: nothing useful to do, and never worth crashing over.
    }
}

@Composable
private fun InstructionsCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.instructions_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.instructions_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Spacer(Modifier.height(0.dp))
}
