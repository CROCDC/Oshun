package com.oshun.gpsbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryTrackerTest {

    private val tracker = DeliveryTracker()

    @Test
    fun firstEmissionRecordsBothTheOutcomeAndTheFixState() {
        val events = tracker.onEmission(1_000L, DeliveryOutcome.NO_CLIENT, fixValid = true)
        assertEquals(2, events.size)
        assertEquals(EventKind.DELIVERY, events[0].kind)
        assertEquals(DeliveryOutcome.NO_CLIENT, events[0].outcome)
        assertEquals(EventKind.FIX, events[1].kind)
        assertEquals(true, events[1].fixValid)
        assertEquals(1_000L, events[0].atMillis)
    }

    @Test
    fun steadyStateRecordsNothing() {
        tracker.onEmission(1_000L, DeliveryOutcome.OK, fixValid = true)
        repeat(10) { i ->
            assertTrue(
                "nothing changed",
                tracker.onEmission(2_000L + i, DeliveryOutcome.OK, fixValid = true).isEmpty(),
            )
        }
    }

    @Test
    fun onlyTheChangedDimensionIsRecorded() {
        tracker.onEmission(1_000L, DeliveryOutcome.OK, fixValid = true)

        val delivery = tracker.onEmission(2_000L, DeliveryOutcome.STALLED, fixValid = true)
        assertEquals(1, delivery.size)
        assertEquals(DeliveryOutcome.STALLED, delivery.single().outcome)

        val fix = tracker.onEmission(3_000L, DeliveryOutcome.STALLED, fixValid = false)
        assertEquals(1, fix.size)
        assertEquals(EventKind.FIX, fix.single().kind)
        assertEquals(false, fix.single().fixValid)
    }

    @Test
    fun aRecoveryIsAChangeToo() {
        tracker.onEmission(1_000L, DeliveryOutcome.STALLED, fixValid = false)
        val recovered = tracker.onEmission(2_000L, DeliveryOutcome.OK, fixValid = true)
        assertEquals(2, recovered.size)
        assertEquals(DeliveryOutcome.OK, recovered[0].outcome)
        assertEquals(true, recovered[1].fixValid)
    }

    @Test
    fun resetMakesTheNextEmissionReportAfresh() {
        tracker.onEmission(1_000L, DeliveryOutcome.OK, fixValid = true)
        assertTrue(tracker.onEmission(1_500L, DeliveryOutcome.OK, fixValid = true).isEmpty())

        tracker.reset()
        assertEquals(2, tracker.onEmission(2_000L, DeliveryOutcome.OK, fixValid = true).size)
    }
}
