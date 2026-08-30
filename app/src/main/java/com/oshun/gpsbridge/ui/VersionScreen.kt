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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.oshun.gpsbridge.BuildConfig
import com.oshun.gpsbridge.R
import com.oshun.gpsbridge.net.UpdateStatus
import com.oshun.gpsbridge.release.LatestApk
import kotlinx.coroutines.launch

/**
 * Which build is on the phone, and where a newer one comes from.
 *
 * The app is sideloaded, so nothing tells you a newer build exists — and the version name
 * does not move between debug builds. The commit does, so it is what gets shown, next to the
 * button that downloads the newest one.
 *
 * The screen asks GitHub what the newest build is on the way in, so a phone that already has
 * it is told so instead of being offered a download that would change nothing.
 */
@Composable
fun VersionScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Null until GitHub has answered: there is nothing to say about a download yet, so
    // nothing about one is on the screen.
    var status by remember { mutableStateOf<UpdateStatus?>(null) }
    // Only the lookup the button does for itself, when the one on the way in came back with
    // nothing: without this it would look broken for as long as that takes.
    var looking by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        status = UpdateStatus.of(LatestApk.latest(), BuildConfig.VERSION_CODE)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(stringResource(R.string.version_title)) {
            Text(
                stringResource(
                    R.string.version_value,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                    BuildConfig.GIT_SHA,
                ),
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(stringResource(R.string.version_hint), style = MaterialTheme.typography.bodyMedium)
        }

        SectionCard(stringResource(R.string.version_download_title)) {
            val found = status
            when {
                found == null -> Text(
                    stringResource(R.string.version_checking),
                    style = MaterialTheme.typography.bodyMedium,
                )

                found is UpdateStatus.UpToDate -> Text(
                    stringResource(R.string.version_up_to_date),
                    style = MaterialTheme.typography.bodyMedium,
                )

                else -> {
                    val newer = (found as? UpdateStatus.Available)?.build
                    Text(
                        if (newer != null) stringResource(R.string.version_update_available, newer)
                        else stringResource(R.string.version_check_failed),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(stringResource(R.string.version_download_body), style = MaterialTheme.typography.bodyMedium)
                    // The address in plain sight: the button needs a browser, and a phone that
                    // has none still leaves you able to type this on the tablet.
                    Text(
                        BuildConfig.RELEASES_URL,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    OutlinedButton(
                        enabled = !looking,
                        onClick = {
                            val known = (found as? UpdateStatus.Available)?.url
                            if (known != null) {
                                // Seconds old at most, so the tap is the download.
                                openUrl(context, known)
                            } else {
                                looking = true
                                scope.launch {
                                    openLatestApk(context) // asks again, and has a page to fall back on
                                    looking = false
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("download_update"),
                    ) {
                        Text(
                            stringResource(
                                if (looking) R.string.version_download_looking else R.string.version_download,
                            ),
                        )
                    }
                }
            }
        }
    }
}
