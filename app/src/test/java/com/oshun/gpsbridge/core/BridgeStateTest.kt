package com.oshun.gpsbridge.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeStateTest {

    @After
    fun tearDown() {
        BridgeState.reset()
    }

    @Test
    fun configDefaults() {
        val c = BridgeConfig()
        assertEquals(2000, c.port)
        assertTrue(c.tcpEnabled)
        assertTrue(c.udpEnabled)
        assertEquals(1000L, c.intervalMillis)
        assertEquals(9000, c.copy(port = 9000).port)
    }

    @Test
    fun statusDefaults() {
        val s = BridgeStatus()
        assertFalse(s.running)
        assertNull(s.ipAddress)
        assertEquals(0, s.tcpClients)
        assertEquals(0L, s.sentencesSent)
        assertNull(s.lastFix)
    }

    @Test
    fun updateThenReset() {
        BridgeState.reset()
        assertEquals(BridgeStatus(), BridgeState.status.value)

        BridgeState.update { it.copy(running = true, port = 1234, tcpClients = 3) }
        val updated = BridgeState.status.value
        assertTrue(updated.running)
        assertEquals(1234, updated.port)
        assertEquals(3, updated.tcpClients)

        BridgeState.reset()
        assertEquals(BridgeStatus(), BridgeState.status.value)
    }
}
