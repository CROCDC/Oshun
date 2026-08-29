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
        // reset() deliberately keeps the AIS report; nothing may carry it into the next test.
        BridgeState.update { it.copy(aisSnapshot = null) }
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
    fun stoppingKeepsTheAisReportForWhoeverGoesLookingForIt() {
        // The report is evidence about the session that just ended and it is read after the
        // stop — that is the entire point of it. Clearing it here left the log screen with
        // nothing to hand over exactly when somebody had gone there to hand it over.
        val snapshot = AisReport.Snapshot(
            atMillis = 1_787_229_296_000L,
            own = null,
            fixValid = false,
            known = emptyList(),
            transmitted = emptySet(),
            feedConnected = false,
            feedMessages = 12,
            simulated = false,
            link = "TCP:2000 · 1 cliente",
            sentences = emptyList(),
        )
        BridgeState.update { it.copy(running = true, sentencesSent = 42, aisSnapshot = snapshot) }

        BridgeState.reset()

        assertFalse("everything else goes", BridgeState.status.value.running)
        assertEquals(0L, BridgeState.status.value.sentencesSent)
        assertEquals("the report stays", snapshot, BridgeState.status.value.aisSnapshot)
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
