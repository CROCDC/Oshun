package com.oshun.gpsbridge.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EventLogTest {

    @Before
    @After
    fun reset() {
        EventLog.clear()
    }

    private fun event(at: Long) = LogEvent(atMillis = at, kind = EventKind.DELIVERY, outcome = DeliveryOutcome.OK)

    @Test
    fun startsEmpty() {
        assertTrue(EventLog.events.value.isEmpty())
    }

    @Test
    fun keepsEventsInOrder() {
        EventLog.record(event(1))
        EventLog.recordAll(listOf(event(2), event(3)))
        assertEquals(listOf(1L, 2L, 3L), EventLog.events.value.map { it.atMillis })
    }

    @Test
    fun dropsTheOldestPastCapacity() {
        repeat(EventLog.CAPACITY + 50) { EventLog.record(event(it.toLong())) }
        val events = EventLog.events.value
        assertEquals(EventLog.CAPACITY, events.size)
        assertEquals(50L, events.first().atMillis) // the first 50 fell off
        assertEquals((EventLog.CAPACITY + 49).toLong(), events.last().atMillis)
    }

    @Test
    fun clearEmptiesIt() {
        EventLog.record(event(1))
        EventLog.clear()
        assertTrue(EventLog.events.value.isEmpty())
    }
}
