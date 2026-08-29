package com.oshun.gpsbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.oshun.gpsbridge.R
import com.oshun.gpsbridge.core.BridgeState
import com.oshun.gpsbridge.store.AisKeyStore

/**
 * The internet AIS feed: opt in, and say plainly what it is not.
 *
 * The warning is not decoration. Targets arrive delayed, only vessels that transmit AIS are
 * in them at all, and on this river most of what can hit you transmits nothing — a chart
 * that looks empty because the feed is thin is the most dangerous thing this feature could
 * produce. It gets a screen of its own so the warning is read, not scrolled past.
 */
@Composable
fun AisFeedScreen(settings: BridgeSettings, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val status by BridgeState.status.collectAsState()
    // The key lives in its own store, never in the config: config rides in intents, lands in
    // SharedPreferences as one string and names itself in the CSV header.
    var apiKey by remember { mutableStateOf(AisKeyStore.load(context)) }
    val editable = !status.running

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(stringResource(R.string.ais_title)) {
            Text(stringResource(R.string.ais_body), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.ais_warning), style = MaterialTheme.typography.bodyMedium)
            SwitchRow(
                label = stringResource(R.string.switch_ais),
                tag = "switch_ais",
                checked = settings.aisEnabled,
                enabled = editable,
                onChange = { settings.aisEnabled = it },
            )
            if (!editable) {
                Text(stringResource(R.string.sim_locked), style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionCard(stringResource(R.string.ais_key_label)) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    AisKeyStore.save(context, it)
                },
                label = { Text(stringResource(R.string.ais_key_label)) },
                enabled = editable,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ais_key"),
            )
            Text(stringResource(R.string.ais_key_hint), style = MaterialTheme.typography.bodySmall)
            if (settings.aisEnabled && apiKey.isBlank()) {
                Text(
                    stringResource(R.string.ais_key_missing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
