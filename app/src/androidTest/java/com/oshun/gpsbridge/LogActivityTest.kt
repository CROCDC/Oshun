package com.oshun.gpsbridge

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oshun.gpsbridge.core.DeliveryOutcome
import com.oshun.gpsbridge.core.EventKind
import com.oshun.gpsbridge.core.EventLog
import com.oshun.gpsbridge.core.LogEvent
import org.junit.After
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
        // Seeded before the rule launches the activity, which reads the log on compose.
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
    }

    @get:Rule
    val compose = createAndroidComposeRule<LogActivity>()

    @After
    fun clearEvents() {
        EventLog.clear()
    }

    private fun str(resId: Int) = compose.activity.getString(resId)

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
