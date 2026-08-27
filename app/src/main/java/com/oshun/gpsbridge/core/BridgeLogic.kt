package com.oshun.gpsbridge.core

import com.oshun.gpsbridge.model.AisTarget
import com.oshun.gpsbridge.model.Fix
import com.oshun.gpsbridge.net.NmeaTcpServer
import com.oshun.gpsbridge.net.NmeaTransport
import com.oshun.gpsbridge.net.NmeaUdpBroadcaster
import com.oshun.gpsbridge.net.SendResult
import com.oshun.gpsbridge.nmea.AisEncoder
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
     * How often a target's position goes out. A real class A transponder reports every 2 to
     * 10 seconds depending on its speed; one fixed rate in that band is enough to be drawn
     * and moved, and far less traffic than repeating it with every fix of our own.
     */
    const val AIS_POSITION_INTERVAL_MILLIS = 5_000L

    /**
     * How often the names go out. A real transponder repeats its static data every 6 minutes,
     * which is a long time to stare at an unlabelled triangle after connecting — a minute
     * costs one extra sentence a minute per target and labels the chart much sooner.
     */
    const val AIS_STATIC_INTERVAL_MILLIS = 60_000L

    /** True when [intervalMillis] has passed since the last one, or nothing has gone out yet. */
    fun shouldEmitAgain(nowMillis: Long, lastAtMillis: Long, intervalMillis: Long): Boolean =
        lastAtMillis <= 0L || nowMillis - lastAtMillis >= intervalMillis

    /**
     * The AIS sentences to append to this batch: the positions when they are due, and the
     * names only on the slower cycle.
     */
    fun aisSentencesFor(targets: List<AisTarget>, nowMillis: Long, withNames: Boolean): List<String> =
        targets.flatMap { target ->
            if (withNames) AisEncoder.sentences(target, nowMillis)
            else listOf(AisEncoder.positionReport(target, nowMillis))
        }

    /**
     * Whether the idle watchdog applies. Reception is only observable over TCP (UDP is
     * connectionless), and the user can turn the whole thing off.
     */
    fun shouldArmIdleOff(config: BridgeConfig): Boolean =
        config.autoOffEnabled && config.tcpEnabled && !config.udpEnabled

    /**
     * What became of one batch, across every transport. Ordered by how much it matters to
     * someone staring at a frozen chart: a client that took the bytes wins, then the states
     * that explain silence, most actionable first.
     */
    fun outcomeFor(results: List<SendResult>): DeliveryOutcome = when {
        results.isEmpty() -> DeliveryOutcome.NOT_SENT
        results.any { it.accepted > 0 } -> DeliveryOutcome.OK
        results.any { it.stalled > 0 } -> DeliveryOutcome.STALLED
        results.any { it.dropped > 0 } -> DeliveryOutcome.DROPPED
        results.any { it.blind } -> DeliveryOutcome.BLIND
        results.any { !it.down } -> DeliveryOutcome.NO_CLIENT
        else -> DeliveryOutcome.NOT_SENT
    }

    /** True when the batch reached the network at all, so the UI can age the last real delivery. */
    fun leftThePhone(outcome: DeliveryOutcome): Boolean =
        outcome == DeliveryOutcome.OK || outcome == DeliveryOutcome.BLIND

    /** Transports as a compact CSV token, e.g. "TCP+UDP". */
    fun transportsToken(results: List<SendResult>): String =
        results.joinToString("+") { it.label }.ifEmpty { "none" }

    /** Consumers we can actually count (TCP clients; UDP is connectionless). */
    fun clientTotal(results: List<SendResult>): Int = results.sumOf { it.clients }

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
