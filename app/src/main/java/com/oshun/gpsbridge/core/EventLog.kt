package com.oshun.gpsbridge.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bounded, process-wide history of session events, newest last. Kept in memory and
 * observed by the log screen; the full per-fix record lives in the CSV on disk.
 */
object EventLog {

    /** Enough for a long day of transitions without ever growing unbounded. */
    const val CAPACITY = 300

    private val _events = MutableStateFlow<List<LogEvent>>(emptyList())
    val events: StateFlow<List<LogEvent>> = _events.asStateFlow()

    @Synchronized
    fun record(event: LogEvent) {
        val next = _events.value + event
        _events.value = if (next.size > CAPACITY) next.takeLast(CAPACITY) else next
    }

    @Synchronized
    fun recordAll(events: List<LogEvent>) {
        events.forEach { record(it) }
    }

    @Synchronized
    fun clear() {
        _events.value = emptyList()
    }
}
