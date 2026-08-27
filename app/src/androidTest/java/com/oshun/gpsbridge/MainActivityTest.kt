package com.oshun.gpsbridge

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.oshun.gpsbridge.net.NetworkRequirements
import com.oshun.gpsbridge.core.BridgeConfig
import com.oshun.gpsbridge.location.FixProvider
import com.oshun.gpsbridge.model.Fix
import com.oshun.gpsbridge.service.GpsBridgeService
import com.oshun.gpsbridge.store.ConfigStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Before
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    companion object {
        /**
         * The screen now pre-fills from the stored config, so start each class run from the
         * defaults instead of from whatever a previous run left behind. Runs before the
         * activity rule launches the activity.
         */
        @BeforeClass
        @JvmStatic
        fun resetStoredConfig() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            ConfigStore.save(context, BridgeConfig())
            ConfigStore.saveStopReason(context, null)
            // An emulator has no hotspot and its Wi-Fi never turns off, so the start path
            // would be unreachable without standing in for the network conditions.
            NetworkGate.stateProvider = { PAIRED_OVER_HOTSPOT }
        }

        /** What a phone serving its own hotspot looks like. */
        val PAIRED_OVER_HOTSPOT = NetworkRequirements(
            hotspotUp = true,
            wifiOff = true,
            address = "192.168.43.1",
        )

        @AfterClass
        @JvmStatic
        fun restoreNetworkGate() {
            NetworkGate.stateProvider = NetworkGate.liveState
        }
    }

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
        // The instructions card is at the bottom of a scrolling column; assert it
        // exists in the tree rather than requiring it to be on-screen.
        compose.onNodeWithText(str(R.string.instructions_title)).assertExists()
    }

    @Test
    fun disablingBothTransportsDisablesStart() {
        // UDP starts off; turning TCP off leaves no transport enabled.
        compose.onNodeWithTag("switch_tcp").performClick() // TCP off
        compose.onNodeWithTag("action_button").assertIsNotEnabled()
    }

    @Test
    fun startingTheBridgeSwitchesButtonToStopAndShowsStatus() {
        // Toggle UDP on then back off to exercise the switch callbacks.
        compose.onNodeWithTag("switch_udp").performClick()
        compose.onNodeWithTag("switch_udp").performClick()

        compose.onNodeWithText(str(R.string.action_start)).assertIsDisplayed()
        compose.onNodeWithTag("action_button").performClick()

        // Permissions are pre-granted, so the service starts and BridgeState flips running=true,
        // which recomposes the button to the stop action and shows the status card.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasStopButton()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(str(R.string.action_stop)).assertExists()
        // Status card fields (exercise StatusCard + KeyValue rows). The taller layout
        // may push these below the fold, so assert existence, not on-screen display.
        // These labels are unique to the status card (the port label also labels the field).
        compose.onNodeWithText(str(R.string.status_title)).assertExists()
        compose.onNodeWithText(str(R.string.status_ip)).assertExists()
        // Which link is carrying the position: a cable and a hotspot fail in different ways.
        compose.onNodeWithText(str(R.string.status_link)).assertExists()
        compose.onNodeWithText(str(R.string.link_hotspot)).assertExists()
        compose.onNodeWithText(str(R.string.status_protocols)).assertExists()
        compose.onNodeWithText(str(R.string.status_sentences)).assertExists()
        // Diagnostics rows: they are what tells a stalled bridge from a healthy one.
        compose.onNodeWithText(str(R.string.status_last_fix)).assertExists()
        compose.onNodeWithText(str(R.string.status_last_send)).assertExists()

        compose.onNodeWithTag("action_button").performClick() // stop
    }

    @Test
    fun autoOffSwitchTogglesWithoutBlockingStart() {
        compose.onNodeWithTag("switch_autooff").performClick() // off
        compose.onNodeWithTag("switch_autooff").performClick() // back on
        compose.onNodeWithTag("action_button").assertIsEnabled()
    }

    @Test
    fun testModeCanBeToggledAndIsOffByDefault() {
        // It lives below the action button on purpose: opt-in, and never in the way of Start.
        compose.onNodeWithText(str(R.string.sim_title)).assertExists()
        compose.onNodeWithTag("switch_sim").performScrollTo().performClick()
        compose.onNodeWithTag("switch_sim").performScrollTo().performClick()
        compose.onNodeWithTag("action_button").assertIsEnabled()
    }

    @Test
    fun showsTheInstalledBuildAndAWayToUpdate() {
        // Sideloaded: nothing else tells you whether the phone has the newest build.
        compose.onNodeWithText(str(R.string.version_title)).assertExists()
        compose.onNodeWithTag("download_update").assertExists()
    }

    @Test
    fun withoutAHotspotTheBridgeRefusesToStart() {
        // The failure this prevents: pairing over the marina's Wi-Fi works at the mooring
        // and dies a few metres out.
        NetworkGate.stateProvider = { NetworkRequirements(hotspotUp = false, wifiOff = true) }
        try {
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithText(str(R.string.net_req_title)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("action_button").assertIsNotEnabled()
        } finally {
            NetworkGate.stateProvider = { PAIRED_OVER_HOTSPOT }
        }
    }

    @Test
    fun aCableIsEnoughEvenWithTheWifiOn() {
        // The address a USB link advertises exists only on that cable, so a Wi-Fi left on
        // cannot point the tablet at the wrong place — the hotspot rule has nothing to guard.
        NetworkGate.stateProvider = {
            NetworkRequirements(
                hotspotUp = false,
                wifiOff = false,
                cableUp = true,
                address = "192.168.42.129",
            )
        }
        try {
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithText(str(R.string.net_req_title)).fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("action_button").assertIsEnabled()
        } finally {
            NetworkGate.stateProvider = { PAIRED_OVER_HOTSPOT }
        }
    }

    @Test
    fun testModeRunsOverWhateverNetworkIsAtHand() {
        // On land the house Wi-Fi is fine: the simulated boat never leaves the desk, and
        // refusing it would break the feature that exists to test Navionics from dry land.
        NetworkGate.stateProvider = {
            NetworkRequirements(hotspotUp = false, wifiOff = false, address = "192.168.1.37")
        }
        try {
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithText(str(R.string.net_req_title)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("action_button").assertIsNotEnabled()

            compose.onNodeWithTag("switch_sim").performScrollTo().performClick()

            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithText(str(R.string.net_req_title)).fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("action_button").assertIsEnabled()
        } finally {
            compose.onNodeWithTag("switch_sim").performScrollTo().performClick()
            NetworkGate.stateProvider = { PAIRED_OVER_HOTSPOT }
        }
    }

    private fun hasStopButton() =
        androidx.compose.ui.test.hasText(str(R.string.action_stop))
}
