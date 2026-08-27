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
        // TCP-only by default, matching the UI: a system-restarted service must not come
        // back with a transport the user never picked.
        assertFalse(c.udpEnabled)
        assertTrue(c.autoOffEnabled)
        // The CSV is on by default: the point of the log is having it when it fails.
        assertTrue(c.rawLogEnabled)
        // Test mode is opt-in: nobody must transmit a simulated position by accident.
        assertFalse(c.simulated)
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
        assertEquals(0L, s.heartbeatsSent)
        assertNull(s.lastFix)
        assertNull(s.lastFixAtMillis)
        assertNull(s.lastSendOkAtMillis)
        assertFalse(s.fixValid)
        assertFalse(s.autoOffEnabled)
        assertFalse(s.simulated)
        assertNull(s.outcome)
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

    @Test
    fun anUpdateInFlightCannotResurrectAStoppedBridge() {
        // The interleaving that turned CI red: a sentence goes out, its update reads the
        // status, the user stops the bridge, and the update writes its copy back over the
        // reset — leaving the UI reporting a session that no longer exists. Doing the reset
        // inside the transform makes that exact ordering deterministic instead of a race.
        BridgeState.update { it.copy(running = true, sentencesSent = 10) }

        BridgeState.update { status ->
            BridgeState.reset()
            status.copy(sentencesSent = status.sentencesSent + 1)
        }

        assertFalse("the stop must win", BridgeState.status.value.running)
    }

    @Test
    fun concurrentUpdatesDoNotLoseEachOther() {
        // Different threads own different fields; a read-modify-write drops one of them.
        val threads = (0 until 4).map { worker ->
            Thread {
                repeat(500) {
                    if (worker % 2 == 0) BridgeState.update { s -> s.copy(sentencesSent = s.sentencesSent + 1) }
                    else BridgeState.update { s -> s.copy(heartbeatsSent = s.heartbeatsSent + 1) }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(1000L, BridgeState.status.value.sentencesSent)
        assertEquals(1000L, BridgeState.status.value.heartbeatsSent)
    }
}
