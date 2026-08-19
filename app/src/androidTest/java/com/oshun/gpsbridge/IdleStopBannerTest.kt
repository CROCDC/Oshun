package com.oshun.gpsbridge

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oshun.gpsbridge.core.StopReason
import com.oshun.gpsbridge.store.ConfigStore
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bridge shutting itself down for lack of clients used to be invisible: the user only
 * found out because Navionics sat on an old position. On next open the app must say so,
 * and the notice must be dismissible.
 */
@RunWith(AndroidJUnit4::class)
class IdleStopBannerTest {

    companion object {
        // Stored before the activity rule launches the activity, which reads it on compose.
        @BeforeClass
        @JvmStatic
        fun recordAnIdleShutdown() {
            ConfigStore.saveStopReason(
                InstrumentationRegistry.getInstrumentation().targetContext,
                StopReason.IDLE_TIMEOUT,
            )
        }
    }

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @After
    fun clearStopReason() {
        ConfigStore.saveStopReason(
            InstrumentationRegistry.getInstrumentation().targetContext,
            null,
        )
    }

    @Test
    fun explainsTheIdleShutdownAndForgetsItOnDismiss() {
        val context = compose.activity
        val title = context.getString(R.string.idle_banner_title)
        // Wait rather than assert straight away: a previous test class may still be
        // tearing its service down, and the banner only shows while stopped.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("idle_dismiss").performClick()

        compose.onNodeWithText(title).assertDoesNotExist()
        assertNull(ConfigStore.readStopReason(context))
    }
}
