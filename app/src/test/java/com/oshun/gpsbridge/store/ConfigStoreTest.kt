package com.oshun.gpsbridge.store

import android.content.Context
import com.oshun.gpsbridge.core.BridgeConfig
import com.oshun.gpsbridge.core.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        ConfigStore.saveStopReason(context, null)
    }

    @Test
    fun loadsTheDefaultsWhenNothingWasStored() {
        assertEquals(BridgeConfig(), ConfigStore.load(context))
    }

    @Test
    fun savedConfigSurvivesForTheNextStart() {
        val config = BridgeConfig(
            port = 10110,
            tcpEnabled = true,
            udpEnabled = true,
            intervalMillis = 2000L,
            autoOffEnabled = false,
        )
        ConfigStore.save(context, config)
        assertEquals(config, ConfigStore.load(context))
    }

    @Test
    fun lastSavedConfigWins() {
        ConfigStore.save(context, BridgeConfig(port = 2001))
        ConfigStore.save(context, BridgeConfig(port = 2002))
        assertEquals(2002, ConfigStore.load(context).port)
    }

    @Test
    fun stopReasonIsStoredAndCleared() {
        assertNull(ConfigStore.readStopReason(context))

        ConfigStore.saveStopReason(context, StopReason.IDLE_TIMEOUT)
        assertEquals(StopReason.IDLE_TIMEOUT, ConfigStore.readStopReason(context))

        ConfigStore.saveStopReason(context, StopReason.USER)
        assertEquals(StopReason.USER, ConfigStore.readStopReason(context))

        ConfigStore.saveStopReason(context, null)
        assertNull(ConfigStore.readStopReason(context))
    }
}
