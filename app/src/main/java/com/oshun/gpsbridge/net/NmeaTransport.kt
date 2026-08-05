package com.oshun.gpsbridge.net

/**
 * A way of pushing NMEA sentences onto the local network so the Navionics app
 * can consume them. Two implementations exist (TCP server, UDP broadcaster) and
 * both can run at the same time — Navionics is paired to whichever one you pick.
 *
 * Pure JVM (java.net), no Android dependency. Networking must be driven off the
 * main thread by the caller.
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

    /** Push one fix worth of sentences. No-op when not running. */
    fun broadcast(lines: List<String>)

    /** Release all resources. Safe to call twice. */
    fun stop()
}
