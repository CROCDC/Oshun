package com.oshun.gpsbridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.oshun.gpsbridge.core.AisReport
import com.oshun.gpsbridge.core.BridgeState
import com.oshun.gpsbridge.core.EventLog
import com.oshun.gpsbridge.core.LogEvent
import com.oshun.gpsbridge.core.TrackLogFormatter
import com.oshun.gpsbridge.store.ConfigStore
import com.oshun.gpsbridge.store.TrackLogWriter
import kotlinx.coroutines.delay

/**
 * The session history: what changed and when. Reading this on the phone is the whole
 * point — after a bad trip you are on a boat, not next to a laptop with adb.
 */
class LogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    LogScreen(Modifier.padding(padding))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class) // FlowRow; stable in every Compose this app has shipped on
@Composable
private fun LogScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val events by EventLog.events.collectAsState()
    val status by BridgeState.status.collectAsState()
    var sizeBytes by remember { mutableStateOf(TrackLogWriter.sizeBytes(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            sizeBytes = TrackLogWriter.sizeBytes(context)
            delay(1000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.log_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.log_subtitle), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.log_file_size, formatSize(sizeBytes)),
            style = MaterialTheme.typography.bodyMedium,
        )

        // Wrapping, not a Row: three labels this long overflow a 360 dp phone, and a Row
        // does not clip visibly — it lays the last button out past the edge, where it still
        // answers every question except a finger.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { shareLog(context) },
                modifier = Modifier.testTag("log_share"),
            ) { Text(stringResource(R.string.log_share)) }
            OutlinedButton(
                onClick = { copyAis(context, status.aisSnapshot) },
                modifier = Modifier.testTag("log_copy_ais"),
            ) { Text(stringResource(R.string.log_copy_ais)) }
            OutlinedButton(
                onClick = { clearLog(context) },
                modifier = Modifier.testTag("log_clear"),
            ) { Text(stringResource(R.string.log_clear)) }
        }

        if (events.isEmpty()) {
            Text(stringResource(R.string.log_empty), style = MaterialTheme.typography.bodyMedium)
        } else {
            // Newest first: the last thing that happened is what you opened this for.
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("log_list"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(events.reversed()) { event -> EventRow(event) }
            }
        }
    }
}

@Composable
private fun EventRow(event: LogEvent) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                TrackLogFormatter.timeOfDay(event.atMillis),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(eventLabel(event), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * The traffic behind the last batch, onto the clipboard.
 *
 * The counter on the status card says how many vessels went out and nothing else, which is
 * the wrong end of the question when the plotter draws none of them: what is needed is every
 * vessel the feed reported, how far each one is, which ones the filters ate, and the exact
 * sentences that left the phone. That is too much to read on a phone and exactly right to
 * paste into a message from the boat.
 */
private fun copyAis(context: android.content.Context, snapshot: AisReport.Snapshot?) {
    if (snapshot == null) {
        Toast.makeText(context, R.string.log_copy_ais_empty, Toast.LENGTH_SHORT).show()
        return
    }
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("Oshun AIS", AisReport.format(snapshot)))
    Toast.makeText(context, R.string.log_copy_ais_done, Toast.LENGTH_SHORT).show()
}

private fun shareLog(context: android.content.Context) {
    val uri = TrackLogWriter.shareUri(context)
    if (uri == null) {
        Toast.makeText(context, R.string.log_share_empty, Toast.LENGTH_SHORT).show()
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, context.getString(R.string.log_share))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** Wipes both the on-screen events and the CSV, reopening the file if a session is live. */
private fun clearLog(context: android.content.Context) {
    TrackLogWriter.clear(context)
    EventLog.clear()
    if (BridgeState.status.value.running) {
        TrackLogWriter.open(
            context,
            TrackLogFormatter.sessionHeader(System.currentTimeMillis(), ConfigStore.load(context)),
        )
    }
}
