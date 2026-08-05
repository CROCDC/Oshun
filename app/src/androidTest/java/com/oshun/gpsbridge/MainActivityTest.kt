package com.oshun.gpsbridge

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.oshun.gpsbridge.location.FixProvider
import com.oshun.gpsbridge.model.Fix
import com.oshun.gpsbridge.service.GpsBridgeService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val sampleFix = Fix(
        latitude = 48.1173,
        longitude = 11.5167,
        speedMetersPerSecond = 5.0,
        bearingDegrees = 90.0,
        altitudeMeters = 100.0,
        satellites = 8,
        timeUtcMillis = 0L,
    )

    @Before
    fun useFakeGps() {
        // Avoid depending on real GPS / Play Services on the emulator.
        GpsBridgeService.fixProviderFactory = {
            object : FixProvider {
                override fun fixes(intervalMillis: Long): Flow<Fix> = flow {
                    while (true) {
                        emit(sampleFix)
                        delay(200)
                    }
                }
            }
        }
    }

    @After
    fun stopService() {
        GpsBridgeService.stop(compose.activity)
    }

    // Resolve UI copy from resources so the test code holds no localized literals.
    private fun str(resId: Int) = compose.activity.getString(resId)

    @Test
    fun showsTitleAndPairingInstructions() {
        compose.onNodeWithText(str(R.string.app_name)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.instructions_title)).assertIsDisplayed()
    }

    @Test
    fun disablingBothTransportsDisablesStart() {
        compose.onNodeWithTag("switch_tcp").performClick() // TCP off
        compose.onNodeWithTag("switch_udp").performClick() // UDP off
        compose.onNodeWithTag("action_button").assertIsNotEnabled()
    }

    @Test
    fun startingTheBridgeSwitchesButtonToStopAndShowsStatus() {
        // Toggle UDP off then back on to exercise the switch callbacks.
        compose.onNodeWithTag("switch_udp").performClick()
        compose.onNodeWithTag("switch_udp").performClick()

        compose.onNodeWithText(str(R.string.action_start)).assertIsDisplayed()
        compose.onNodeWithTag("action_button").performClick()

        // Permissions are pre-granted, so the service starts and BridgeState flips running=true,
        // which recomposes the button to the stop action and shows the status card.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasStopButton()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(str(R.string.action_stop)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.status_title)).assertIsDisplayed()
        // Status card fields (exercise StatusCard + KeyValue rows). These labels are
        // unique to the status card (the port label also labels the text field).
        compose.onNodeWithText(str(R.string.status_ip)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.status_protocols)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.status_sentences)).assertIsDisplayed()

        compose.onNodeWithTag("action_button").performClick() // stop
    }

    private fun hasStopButton() =
        androidx.compose.ui.test.hasText(str(R.string.action_stop))
}
