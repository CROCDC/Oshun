package com.oshun.gpsbridge.core

/**
 * Turns a per-emission stream of outcomes into the handful of events worth logging:
 * the moments the delivery situation or the fix validity actually changed.
 *
 * Pure and stateful-by-instance, so the service keeps one per session and the whole
 * transition logic stays unit-testable without sockets or a clock.
 */
class DeliveryTracker {

    private var lastOutcome: DeliveryOutcome? = null
    private var lastFixValid: Boolean? = null

    /** The events to record for this emission; empty while nothing changes. */
    fun onEmission(nowMillis: Long, outcome: DeliveryOutcome, fixValid: Boolean): List<LogEvent> {
        val events = mutableListOf<LogEvent>()
        if (outcome != lastOutcome) {
            events += LogEvent(atMillis = nowMillis, kind = EventKind.DELIVERY, outcome = outcome)
            lastOutcome = outcome
        }
        if (fixValid != lastFixValid) {
            events += LogEvent(atMillis = nowMillis, kind = EventKind.FIX, fixValid = fixValid)
            lastFixValid = fixValid
        }
        return events
    }

    /** Forgets the current state so the next emission logs its situation afresh. */
    fun reset() {
        lastOutcome = null
        lastFixValid = null
    }
}
