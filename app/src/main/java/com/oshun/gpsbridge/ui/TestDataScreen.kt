package com.oshun.gpsbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oshun.gpsbridge.R
import com.oshun.gpsbridge.core.BridgeState

/**
 * Test data: the simulated boat and its AIS targets, on a screen of their own.
 *
 * They are deliberately out of the way. Turning the simulator on makes the app transmit a
 * position that is not yours, which is the worst thing a navigation instrument can do by
 * accident — so it is somewhere you go on purpose, never something you brush against while
 * reaching for Start.
 */
@Composable
fun TestDataScreen(settings: BridgeSettings, modifier: Modifier = Modifier) {
    val status by BridgeState.status.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(stringResource(R.string.sim_title)) {
            Text(stringResource(R.string.sim_body), style = MaterialTheme.typography.bodyMedium)
            SwitchRow(
                label = stringResource(R.string.switch_sim),
                tag = "switch_sim",
                checked = settings.simulated,
                // The running session was started with whatever this said at the time;
                // flipping it mid-flight would only disagree with what is on the air.
                enabled = !status.running,
                onChange = { settings.simulated = it },
            )
            if (status.running) {
                Text(stringResource(R.string.sim_locked), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(stringResource(R.string.sim_hint), style = MaterialTheme.typography.bodySmall)
            }
            if (settings.simulated) {
                Text(
                    stringResource(R.string.status_simulated),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        SectionCard(stringResource(R.string.sim_ais_title)) {
            Text(stringResource(R.string.sim_ais), style = MaterialTheme.typography.bodyMedium)
        }

        SectionCard(stringResource(R.string.sim_network_title)) {
            Text(stringResource(R.string.net_req_body_sim), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
