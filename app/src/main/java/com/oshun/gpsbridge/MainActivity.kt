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
import com.oshun.gpsbridge.net.NetworkUtils
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
    var lastCrash by remember { mutableStateOf(CrashStore.read(context)) }
    // Why the bridge stopped last time; an idle shutdown is otherwise invisible.
    var lastStop by remember { mutableStateOf(ConfigStore.readStopReason(context)) }

    // No local IPv4 means neither Wi-Fi nor the hotspot is up, so the tablet can't
    // reach us. Poll so the banner clears as soon as the user turns the hotspot on.
    var hasNetwork by remember { mutableStateOf(NetworkUtils.localIpAddress() != null) }
    // Drives the "hace N s" counters, so a stalled bridge is visible without touching anything.
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            hasNetwork = NetworkUtils.localIpAddress() != null
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

        if (!hasNetwork) {
            NoNetworkBanner(onOpenHotspot = { openHotspotSettings(context) })
        }

        if (lastStop == StopReason.IDLE_TIMEOUT && !status.running) {
            IdleStopBanner(onDismiss = {
                ConfigStore.saveStopReason(context, null)
                lastStop = null
            })
        }

        if (!batteryUnrestricted) {
            BatteryOptimizationBanner(onFix = { openBatteryOptimizationSettings(context) })
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

        IntervalSelector(
            selected = intervalMillis,
            enabled = !status.running,
            onSelect = { intervalMillis = it },
        )

        if (!status.running) {
            Button(
                onClick = { permissionLauncher.launch(requiredPermissions) },
                enabled = (tcpEnabled || udpEnabled) && hasNetwork,
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

        if (status.running) StatusCard(status, nowMillis)

        InstructionsCard()
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

@Composable
private fun NoNetworkBanner(onOpenHotspot: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.net_none_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.net_none_body), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onOpenHotspot) { Text(stringResource(R.string.net_open_hotspot)) }
        }
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
private fun StatusCard(status: BridgeStatus, nowMillis: Long) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.status_title), style = MaterialTheme.typography.titleMedium)
            KeyValue(stringResource(R.string.status_ip), status.ipAddress ?: stringResource(R.string.status_no_wifi))
            KeyValue(stringResource(R.string.status_port), status.port.toString())
            val protocols = BridgeLogic.enabledProtocols(status)
                .joinToString(" + ")
                .ifEmpty { stringResource(R.string.status_protocols_none) }
            KeyValue(stringResource(R.string.status_protocols), protocols)
            if (status.tcpEnabled) KeyValue(stringResource(R.string.status_tcp_clients), status.tcpClients.toString())
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
