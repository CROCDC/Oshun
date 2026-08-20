package com.oshun.gpsbridge.core

/** The kinds of things worth remembering about a session, in the order they matter. */
enum class EventKind {
    SESSION_START,
    SESSION_STOP,
    CLIENT_CONNECTED,
    CLIENT_DISCONNECTED,

    /** The delivery situation changed (see [LogEvent.outcome]). */
    DELIVERY,

    /** The fix went stale (transmitted as invalid) or came back. */
    FIX,

    /** The session is transmitting the simulator's track, not the phone's GPS. */
    SIMULATION,
}

/**
 * One line of the session history. Only transitions are recorded — a log that repeats
 * "OK" once a second is unreadable, and the question after a bad trip is always *when*
 * something changed.
 *
 * [detail] carries neutral tokens (an IP, a stop reason); the UI localizes around them.
 */
data class LogEvent(
    val atMillis: Long,
    val kind: EventKind,
    val outcome: DeliveryOutcome? = null,
    val fixValid: Boolean? = null,
    val detail: String = "",
)
