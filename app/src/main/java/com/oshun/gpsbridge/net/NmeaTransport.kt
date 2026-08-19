package com.oshun.gpsbridge.net

/**
 * A way of pushing NMEA sentences onto the local network so the Navionics app
 * can consume them. Two implementations exist (TCP server, UDP broadcaster) and
 * both can run at the same time — Navionics is paired to whichever one you pick.
 *
 * Pure JVM (java.net/java.nio), no Android dependency. Networking must be driven off
 * the main thread by the caller, and no implementation may block the caller: a tablet
 * that stops reading must never stall the emitter.
 */
interface NmeaTransport {
    /** Human-readable name for the UI/logs, e.g. "TCP" or "UDP". */
    val label: String

    /** True once [start] has succeeded and the transport is accepting/sending. */
    val isRunning: Boolean

    /** Number of connected consumers. TCP tracks real clients; UDP is connectionless and returns 0. */
    val clientCount: Int

    /** Bind sockets / open resources. Safe to call twice. */
    fun start()

    /**
     * Push one fix worth of sentences, reporting what became of them. [nowMillis] is the
     * caller's clock, so stall timing stays testable. No-op (and [SendResult.down]) when
     * the transport is not running.
     */
    fun broadcast(lines: List<String>, nowMillis: Long): SendResult

    /** Release all resources. Safe to call twice. */
    fun stop()
}
