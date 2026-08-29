package com.oshun.gpsbridge

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oshun.gpsbridge.core.AisReport
import com.oshun.gpsbridge.core.BridgeState
import com.oshun.gpsbridge.core.DeliveryOutcome
import com.oshun.gpsbridge.core.EventKind
import com.oshun.gpsbridge.core.EventLog
import com.oshun.gpsbridge.core.LogEvent
import com.oshun.gpsbridge.core.Position
import com.oshun.gpsbridge.model.AisTarget
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The log screen is the point of the whole feature: after a trip where the chart froze,
 * the answer has to be readable on the phone, on the boat.
 */
@RunWith(AndroidJUnit4::class)
class LogActivityTest {

    companion object {
        // Seeded before the rule launches the activity, which reads the log on compose. Once
        // for the class, so no test may clear it out from under the next one.
        @BeforeClass
        @JvmStatic
        fun seedEvents() {
            EventLog.clear()
            EventLog.record(
                LogEvent(atMillis = 1_787_173_200_000L, kind = EventKind.SESSION_START, detail = "TCP:2000"),
            )
            EventLog.record(
                LogEvent(
                    atMillis = 1_787_173_260_000L,
                    kind = EventKind.DELIVERY,
                    outcome = DeliveryOutcome.STALLED,
                ),
            )
        }

        @AfterClass
        @JvmStatic
        fun forgetEvents() {
            EventLog.clear()
        }
    }

    @get:Rule
    val compose = createAndroidComposeRule<LogActivity>()

    @After
    fun forgetTheSnapshot() {
        BridgeState.reset()
    }

    private fun str(resId: Int) = compose.activity.getString(resId)

    private val snapshot = AisReport.Snapshot(
        atMillis = 1_787_173_260_000L,
        own = Position(-34.4123, -58.4890),
        fixValid = true,
        known = listOf(
            AisTarget(
                mmsi = 538006335,
                name = "MYRA",
                latitude = -34.42925,
                longitude = -58.42386,
                speedKnots = 10.7,
                courseDegrees = 150.4,
                reportedAtMillis = 1_787_173_250_000L,
            ),
        ),
        transmitted = setOf(538006335),
        feedConnected = true,
        feedMessages = 1843,
        simulated = false,
        link = "TCP:2000 · 1 cliente",
        sentences = listOf("!AIVDM,1,1,,A,1815C?hP1cKlS`QdC:LUp4hD0000,0*14\r\n"),
    )

    @Test
    fun theCopyButtonSurvivesBothAnEmptyBridgeAndALiveOne() {
        // The clipboard itself is deliberately not asserted. From API 29 on, reading or
        // writing it needs a focused window, and the emulators this runs on are headless: a
        // probe that writes and reads it straight from the test comes back null before the
        // app is involved at all. So what is covered here is the wiring, which is where a bug
        // would actually live — the button finds the snapshot the service published, and
        // neither path takes the screen down. What the text says is AisReportTest's business.
        compose.onNodeWithTag("log_copy_ais").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.log_title)).assertExists()

        BridgeState.update { it.copy(aisSnapshot = snapshot) }
        compose.waitForIdle()

        compose.onNodeWithTag("log_copy_ais").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.log_title)).assertExists()
    }

    @Test
    fun showsWhatHappenedAndForgetsItOnDemand() {
        compose.onNodeWithText(str(R.string.log_title)).assertExists()
        // The event that explains a frozen chart: we sent, nobody consumed it.
        compose.onNodeWithText(str(R.string.outcome_stalled)).assertExists()

        compose.onNodeWithTag("log_clear").performClick()

        compose.onNodeWithText(str(R.string.log_empty)).assertExists()
        assertTrue(EventLog.events.value.isEmpty())
    }
}
