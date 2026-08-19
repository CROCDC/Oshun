package com.oshun.gpsbridge.core

/**
 * Why the bridge stopped transmitting. Persisted by the Android layer so the UI can
 * explain the silence afterwards: an idle shutdown used to be invisible — the user
 * only saw Navionics frozen on an old position with no hint that the phone had quit.
 */
enum class StopReason {
    /** The user pressed Stop. */
    USER,

    /** The idle watchdog shut the bridge down: no TCP client connected for the timeout window. */
    IDLE_TIMEOUT;

    companion object {
        /** Parses a token previously produced by [name]; null for absent or unknown values. */
        fun fromToken(token: String?): StopReason? = entries.firstOrNull { it.name == token }
    }
}
