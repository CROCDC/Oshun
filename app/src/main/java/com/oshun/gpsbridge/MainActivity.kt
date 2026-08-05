package com.oshun.gpsbridge

import android.Manifest
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.oshun.gpsbridge.service.GpsBridgeService

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

    var portText by remember { mutableStateOf("2000") }
    var tcpEnabled by remember { mutableStateOf(true) }
    var udpEnabled by remember { mutableStateOf(true) }
    var intervalMillis by remember { mutableStateOf(1000L) }

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

        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter(Char::isDigit).take(5) },
            label = { Text(stringResource(R.string.label_port)) },
            enabled = !status.running,
            modifier = Modifier.fillMaxWidth(),
        )

        SwitchRow(stringResource(R.string.switch_tcp), "switch_tcp", tcpEnabled, enabled = !status.running) { tcpEnabled = it }
        SwitchRow(stringResource(R.string.switch_udp), "switch_udp", udpEnabled, enabled = !status.running) { udpEnabled = it }

        IntervalSelector(
            selected = intervalMillis,
            enabled = !status.running,
            onSelect = { intervalMillis = it },
        )

        if (!status.running) {
            Button(
                onClick = { permissionLauncher.launch(requiredPermissions) },
                enabled = tcpEnabled || udpEnabled,
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

        if (status.running) StatusCard(status)

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
private fun StatusCard(status: BridgeStatus) {
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
            status.lastFix?.let { fix ->
                KeyValue(stringResource(R.string.status_last_position), "%.5f, %.5f".format(fix.latitude, fix.longitude))
            }
            status.batteryPercent?.let { KeyValue(stringResource(R.string.status_battery), "$it%") }
            status.currentDrawMilliAmp?.let { KeyValue(stringResource(R.string.status_draw), "≈ $it mA") }
            status.batteryDrainPerHour?.let {
                KeyValue(stringResource(R.string.status_drain), "%.1f %%/h".format(it))
            }
        }
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
