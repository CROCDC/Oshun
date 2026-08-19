package com.oshun.gpsbridge.core

import com.oshun.gpsbridge.model.Fix
import com.oshun.gpsbridge.nmea.NmeaFormatter
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders the on-disk track log: one CSV row per emitted fix, plus the comment line that
 * opens each session. Pure, so the exact bytes we will later hand to someone debugging a
 * trip are pinned by unit tests.
 */
object TrackLogFormatter {

    const val CSV_HEADER = "utc,lat,lon,sog_kn,cog_deg,fix,sats,transports,clients,outcome"

    private val UTC_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** Opens a session in the file, so a shared log says what it was recording. */
    fun sessionHeader(nowMillis: Long, config: BridgeConfig): String {
        val transports = buildList {
            if (config.tcpEnabled) add("TCP")
            if (config.udpEnabled) add("UDP")
        }.joinToString("+").ifEmpty { "none" }
        return "# session ${utc(nowMillis)} port=${config.port} transports=$transports " +
            "interval=${config.intervalMillis}ms autooff=${if (config.autoOffEnabled) "on" else "off"}"
    }

    /** Closes a session, naming why it ended. */
    fun sessionFooter(nowMillis: Long, reason: StopReason): String =
        "# session end ${utc(nowMillis)} reason=${reason.name}"

    /** One emission: where we were, what we sent, and what became of it. */
    fun csvLine(
        nowMillis: Long,
        fix: Fix,
        valid: Boolean,
        outcome: DeliveryOutcome,
        transports: String,
        clients: Int,
    ): String = listOf(
        utc(nowMillis),
        String.format(Locale.US, "%.6f", fix.latitude),
        String.format(Locale.US, "%.6f", fix.longitude),
        String.format(Locale.US, "%.1f", fix.speedMetersPerSecond * NmeaFormatter.MPS_TO_KNOTS),
        String.format(Locale.US, "%.1f", fix.bearingDegrees),
        if (valid) "A" else "V",
        if (fix.satellites >= 0) fix.satellites.toString() else "",
        transports,
        clients.toString(),
        outcome.name,
    ).joinToString(",")

    /** UTC instant, seconds precision — the format every chart plotter and spreadsheet reads. */
    fun utc(millis: Long): String = UTC_FORMAT.format(Instant.ofEpochMilli(millis))

    /** Local wall-clock time for the on-screen log, where UTC would just make you do maths. */
    fun timeOfDay(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        TIME_FORMAT.withZone(zone).format(Instant.ofEpochMilli(millis))
}
