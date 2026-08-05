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

    /**
     * Active protocols as neutral tokens, e.g. ["TCP", "UDP"]. Callers localize the
     * surrounding text; this stays free of user-facing (translatable) strings.
     */
    fun enabledProtocols(status: BridgeStatus): List<String> = buildList {
        if (status.tcpEnabled) add("TCP")
        if (status.udpEnabled) add("UDP")
    }
}
