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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
        Text("Oshun GPS Bridge", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Comparte el GPS de este teléfono con Navionics en la tablet, por Wi-Fi.",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter(Char::isDigit).take(5) },
            label = { Text("Puerto") },
            enabled = !status.running,
            modifier = Modifier.fillMaxWidth(),
        )

        SwitchRow("TCP (servidor, 1-a-1 fiable)", tcpEnabled, enabled = !status.running) { tcpEnabled = it }
        SwitchRow("UDP (broadcast, varios clientes)", udpEnabled, enabled = !status.running) { udpEnabled = it }

        if (!status.running) {
            Button(
                onClick = { permissionLauncher.launch(requiredPermissions) },
                enabled = tcpEnabled || udpEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Iniciar transmisión") }
        } else {
            Button(
                onClick = { GpsBridgeService.stop(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Detener") }
        }

        if (status.running) StatusCard(status)

        InstructionsCard()
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun StatusCard(status: BridgeStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Transmitiendo", style = MaterialTheme.typography.titleMedium)
            KeyValue("IP del teléfono", status.ipAddress ?: "sin Wi-Fi")
            KeyValue("Puerto", status.port.toString())
            val protocols = buildList {
                if (status.tcpEnabled) add("TCP")
                if (status.udpEnabled) add("UDP")
            }.joinToString(" + ").ifEmpty { "ninguno" }
            KeyValue("Protocolos", protocols)
            if (status.tcpEnabled) KeyValue("Clientes TCP", status.tcpClients.toString())
            KeyValue("Sentencias enviadas", status.sentencesSent.toString())
            status.lastFix?.let { fix ->
                KeyValue("Última posición", "%.5f, %.5f".format(fix.latitude, fix.longitude))
            }
        }
    }
}

@Composable
private fun InstructionsCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("En la tablet (Navionics)", style = MaterialTheme.typography.titleMedium)
            Text(
                "1. Conecta la tablet a la misma red Wi-Fi que este teléfono " +
                    "(o al hotspot del teléfono).\n" +
                    "2. Navionics → Menú → Paired Devices → Add device manually.\n" +
                    "3. Host/IP = la IP de arriba, Port = el puerto, Protocol = TCP o UDP.\n" +
                    "4. Guarda: la posición del teléfono aparece en la carta.",
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
