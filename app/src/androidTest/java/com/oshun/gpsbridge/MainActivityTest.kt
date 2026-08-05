package com.oshun.gpsbridge

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

    @Test
    fun showsTitleAndPairingInstructions() {
        compose.onNodeWithText("Oshun GPS Bridge").assertIsDisplayed()
        compose.onNodeWithText("En la tablet (Navionics)").assertIsDisplayed()
    }

    @Test
    fun startingTheBridgeSwitchesButtonToStop() {
        compose.onNodeWithText("Iniciar transmisión").assertIsDisplayed()
        compose.onNodeWithText("Iniciar transmisión").performClick()

        // Permissions are pre-granted, so the service starts and BridgeState flips running=true,
        // which recomposes the button to "Detener".
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasStopButton()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Detener").assertIsDisplayed()
        compose.onNodeWithText("Transmitiendo").assertIsDisplayed()

        compose.onNodeWithText("Detener").performClick()
    }

    private fun hasStopButton() =
        androidx.compose.ui.test.hasText("Detener")
}
