package com.oshun.gpsbridge.core

import com.oshun.gpsbridge.model.Fix
import com.oshun.gpsbridge.net.NmeaTcpServer
import com.oshun.gpsbridge.net.NmeaTransport
import com.oshun.gpsbridge.net.NmeaUdpBroadcaster
import com.oshun.gpsbridge.nmea.NmeaFormatter
import java.util.Locale

/**
 * Pure, Android-free decision logic used by the foreground service. Kept separate
 * from [com.oshun.gpsbridge.service.GpsBridgeService] so it can be unit-tested on
 * a plain JVM — the service itself is then only lifecycle glue.
 */
object BridgeLogic {

    /** Floor for the staleness window: below this, normal GPS jitter would flag valid fixes. */
    const val MIN_STALE_MILLIS = 5_000L

    /** Builds the enabled transports for a config (no side effects; not started). */
    fun buildTransports(config: BridgeConfig): List<NmeaTransport> = buildList {
        if (config.tcpEnabled) add(NmeaTcpServer(config.port))
        if (config.udpEnabled) add(NmeaUdpBroadcaster(config.port))
    }

    /** The NMEA sentences to send for one fix; [valid] false marks it as no longer trustworthy. */
    fun sentencesFor(fix: Fix, valid: Boolean = true): List<String> = NmeaFormatter.sentences(fix, valid)

    /** How old a fix may get before we transmit it flagged invalid: three update periods, min 5 s. */
    fun staleAfterMillis(intervalMillis: Long): Long = maxOf(MIN_STALE_MILLIS, intervalMillis * 3)

    /** True when a fix that old must be sent as invalid instead of pretending it is live. */
    fun isStale(fixAgeMillis: Long, intervalMillis: Long): Boolean =
        fixAgeMillis >= staleAfterMillis(intervalMillis)

    /**
     * True when the heartbeat must re-send the last known fix: nothing has gone out for a
     * whole update period. A real NMEA source transmits continuously — going silent leaves
     * the consumer showing the last position with no way to know the source died.
     */
    fun shouldResend(nowMillis: Long, lastSentAtMillis: Long, intervalMillis: Long): Boolean =
        lastSentAtMillis <= 0L || nowMillis - lastSentAtMillis >= intervalMillis

    /**
     * Whether the idle watchdog applies. Reception is only observable over TCP (UDP is
     * connectionless), and the user can turn the whole thing off.
     */
    fun shouldArmIdleOff(config: BridgeConfig): Boolean =
        config.autoOffEnabled && config.tcpEnabled && !config.udpEnabled

    /** True when at least one transport can actually deliver right now (TCP client attached, or UDP up). */
    fun hasLiveConsumer(transports: List<NmeaTransport>): Boolean =
        transports.any { it.isRunning && (it is NmeaUdpBroadcaster || it.clientCount > 0) }

    /**
     * Active protocols as neutral tokens, e.g. ["TCP", "UDP"]. Callers localize the
     * surrounding text; this stays free of user-facing (translatable) strings.
     */
    fun enabledProtocols(status: BridgeStatus): List<String> = buildList {
        if (status.tcpEnabled) add("TCP")
        if (status.udpEnabled) add("UDP")
    }

    /**
     * Elapsed time since [thenMillis] as a compact, locale-neutral token ("12 s",
     * "3 min 07 s", "1 h 04 min"), or null when the instant is unknown. The UI wraps it
     * in translated text; keeping the arithmetic here makes it unit-testable.
     */
    fun ageToken(nowMillis: Long, thenMillis: Long?): String? {
        if (thenMillis == null || thenMillis <= 0L) return null
        val seconds = maxOf(0L, (nowMillis - thenMillis) / 1000L)
        return when {
            seconds < 60 -> "$seconds s"
            seconds < 3600 -> String.format(Locale.US, "%d min %02d s", seconds / 60, seconds % 60)
            else -> String.format(Locale.US, "%d h %02d min", seconds / 3600, (seconds % 3600) / 60)
        }
    }
}
