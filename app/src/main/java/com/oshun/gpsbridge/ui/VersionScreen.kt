package com.oshun.gpsbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.oshun.gpsbridge.BuildConfig
import com.oshun.gpsbridge.R

/**
 * Which build is on the phone, and where a newer one comes from.
 *
 * The app is sideloaded, so nothing tells you a newer build exists — and the version name
 * does not move between debug builds. The commit does, so it is what gets shown, next to
 * the page where every green build publishes a fresh APK.
 */
@Composable
fun VersionScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(stringResource(R.string.version_title)) {
            Text(
                stringResource(R.string.version_value, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA),
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(stringResource(R.string.version_hint), style = MaterialTheme.typography.bodyMedium)
        }

        SectionCard(stringResource(R.string.version_download_title)) {
            Text(stringResource(R.string.version_download_body), style = MaterialTheme.typography.bodyMedium)
            // The address in plain sight: the button needs a browser, and a phone that has
            // none still leaves you able to type this on the tablet.
            Text(
                BuildConfig.RELEASES_URL,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            OutlinedButton(
                onClick = { openReleases(context) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("download_update"),
            ) { Text(stringResource(R.string.version_download)) }
        }
    }
}
