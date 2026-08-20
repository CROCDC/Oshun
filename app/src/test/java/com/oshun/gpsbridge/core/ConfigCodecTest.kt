package com.oshun.gpsbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigCodecTest {

    @Test
    fun roundTripsEveryField() {
        val config = BridgeConfig(
            port = 10110,
            tcpEnabled = false,
            udpEnabled = true,
            intervalMillis = 500L,
            autoOffEnabled = false,
            rawLogEnabled = false,
            simulated = true,
        )
        assertEquals(config, ConfigCodec.decode(ConfigCodec.encode(config)))
    }

    @Test
    fun roundTripsTheDefaults() {
        val config = BridgeConfig()
        assertEquals(config, ConfigCodec.decode(ConfigCodec.encode(config)))
    }

    @Test
    fun missingValueFallsBackToDefaults() {
        assertEquals(BridgeConfig(), ConfigCodec.decode(null))
        assertEquals(BridgeConfig(), ConfigCodec.decode(""))
        assertEquals(BridgeConfig(), ConfigCodec.decode("   "))
    }

    @Test
    fun garbageFallsBackToDefaults() {
        assertEquals(BridgeConfig(), ConfigCodec.decode("no-fields-here"))
        assertEquals(BridgeConfig(), ConfigCodec.decode(";;;=x;=;"))
    }

    @Test
    fun unknownKeysAreIgnoredAndKnownOnesApplied() {
        val decoded = ConfigCodec.decode("v1;port=2001;wat=7;udp=1")
        assertEquals(2001, decoded.port)
        assertTrue(decoded.udpEnabled)
        // Untouched fields keep their defaults.
        assertEquals(BridgeConfig().intervalMillis, decoded.intervalMillis)
        assertTrue(decoded.tcpEnabled)
    }

    @Test
    fun outOfRangePortIsRejected() {
        assertEquals(BridgeConfig().port, ConfigCodec.decode("port=0").port)
        assertEquals(BridgeConfig().port, ConfigCodec.decode("port=65536").port)
        assertEquals(BridgeConfig().port, ConfigCodec.decode("port=-1").port)
        assertEquals(BridgeConfig().port, ConfigCodec.decode("port=abc").port)
        assertEquals(ConfigCodec.MAX_PORT, ConfigCodec.decode("port=${ConfigCodec.MAX_PORT}").port)
        assertEquals(ConfigCodec.MIN_PORT, ConfigCodec.decode("port=${ConfigCodec.MIN_PORT}").port)
    }

    @Test
    fun nonPositiveIntervalIsRejected() {
        assertEquals(BridgeConfig().intervalMillis, ConfigCodec.decode("interval=0").intervalMillis)
        assertEquals(BridgeConfig().intervalMillis, ConfigCodec.decode("interval=-5").intervalMillis)
        assertEquals(BridgeConfig().intervalMillis, ConfigCodec.decode("interval=soon").intervalMillis)
        assertEquals(2000L, ConfigCodec.decode("interval=2000").intervalMillis)
    }

    @Test
    fun booleanTokensParseAndFallBack() {
        assertFalse(ConfigCodec.decode("tcp=0;udp=1").tcpEnabled)
        assertTrue(ConfigCodec.decode("tcp=1").tcpEnabled)
        assertFalse(ConfigCodec.decode("autooff=0").autoOffEnabled)
        assertTrue(ConfigCodec.decode("autooff=1").autoOffEnabled)
        assertFalse(ConfigCodec.decode("rawlog=0").rawLogEnabled)
        assertTrue(ConfigCodec.decode("rawlog=1").rawLogEnabled)
        assertTrue(ConfigCodec.decode("rawlog=maybe").rawLogEnabled)
        assertTrue(ConfigCodec.decode("sim=1").simulated)
        assertFalse(ConfigCodec.decode("sim=0").simulated)
        assertFalse("a garbled flag must not start a simulation", ConfigCodec.decode("sim=maybe").simulated)
        // Unparseable booleans keep the default rather than flipping silently.
        assertTrue(ConfigCodec.decode("tcp=maybe").tcpEnabled)
        assertTrue(ConfigCodec.decode("autooff=maybe").autoOffEnabled)
        assertFalse(ConfigCodec.decode("udp=maybe").udpEnabled)
    }

    @Test
    fun configWithNoTransportFallsBackToTcp() {
        // A restart must never come back "running" but mute.
        val decoded = ConfigCodec.decode("tcp=0;udp=0")
        assertTrue(decoded.tcpEnabled)
        assertFalse(decoded.udpEnabled)
    }

    @Test
    fun encodesAsSemicolonSeparatedFields() {
        val encoded = ConfigCodec.encode(BridgeConfig(port = 2000))
        assertTrue(encoded.startsWith("v1;"))
        assertTrue(encoded.contains("port=2000"))
        assertTrue(encoded.contains("tcp=1"))
        assertTrue(encoded.contains("udp=0"))
    }
}
