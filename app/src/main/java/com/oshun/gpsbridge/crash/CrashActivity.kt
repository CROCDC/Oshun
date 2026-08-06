package com.oshun.gpsbridge.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.oshun.gpsbridge.MainActivity
import com.oshun.gpsbridge.R

/** Full-screen crash report with copy / share / restart actions. */
class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = intent.getStringExtra(EXTRA_REPORT)
            ?: CrashStore.read(this)
            ?: getString(R.string.crash_none)

        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    CrashScreen(
                        report = report,
                        modifier = Modifier.padding(padding),
                        onCopy = { copyToClipboard(this, report) },
                        onShare = { shareReport(this, report) },
                        onRestart = { restart() },
                    )
                }
            }
        }
    }

    private fun restart() {
        CrashStore.clear(this)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }

    companion object {
        const val EXTRA_REPORT = "report"
    }
}

private fun copyToClipboard(context: Context, report: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Oshun crash", report))
    Toast.makeText(context, context.getString(R.string.crash_copied), Toast.LENGTH_SHORT).show()
}

private fun shareReport(context: Context, report: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Oshun GPS Bridge crash")
        putExtra(Intent.EXTRA_TEXT, report)
    }
    context.startActivity(Intent.createChooser(intent, null))
}

@Composable
private fun CrashScreen(
    report: String,
    modifier: Modifier = Modifier,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRestart: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.crash_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.crash_subtitle), style = MaterialTheme.typography.bodyMedium)

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Text(
                report,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCopy) { Text(stringResource(R.string.crash_copy)) }
            OutlinedButton(onClick = onShare) { Text(stringResource(R.string.crash_share)) }
        }
        OutlinedButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.crash_restart))
        }
    }
}
