package com.oshun.gpsbridge.core

import com.oshun.gpsbridge.model.Fix
import com.oshun.gpsbridge.net.NmeaTcpServer
import com.oshun.gpsbridge.net.NmeaTransport
import com.oshun.gpsbridge.net.NmeaUdpBroadcaster
import com.oshun.gpsbridge.nmea.NmeaFormatter

/**
 * Pure, Android-free decision logic used by the foreground service. Kept separate
 * from [com.oshun.gpsbridge.service.GpsBridgeService] so it can be unit-tested on
 * a plain JVM — the service itself is then only lifecycle glue.
 */
object BridgeLogic {

    /** Builds the enabled transports for a config (no side effects; not started). */
    fun buildTransports(config: BridgeConfig): List<NmeaTransport> = buildList {
        if (config.tcpEnabled) add(NmeaTcpServer(config.port))
        if (config.udpEnabled) add(NmeaUdpBroadcaster(config.port))
    }

    /** The NMEA sentences to send for one fix. */
    fun sentencesFor(fix: Fix): List<String> = NmeaFormatter.sentences(fix)

    /** Human-readable list of active protocols, e.g. ["TCP", "UDP"]. */
    fun enabledProtocols(status: BridgeStatus): List<String> = buildList {
        if (status.tcpEnabled) add("TCP")
        if (status.udpEnabled) add("UDP")
    }

    /** The text shown in the ongoing notification, e.g. "192.168.1.5:2000 · TCP/UDP · 1 cliente(s)". */
    fun notificationText(status: BridgeStatus): String {
        val protocols = enabledProtocols(status).joinToString("/").ifEmpty { "—" }
        val base = "${status.ipAddress ?: "?"}:${status.port} · $protocols"
        return if (status.tcpEnabled) "$base · ${status.tcpClients} cliente(s)" else base
    }
}
