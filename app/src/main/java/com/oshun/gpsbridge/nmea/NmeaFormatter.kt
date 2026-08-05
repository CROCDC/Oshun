package com.oshun.gpsbridge.nmea

import com.oshun.gpsbridge.model.Fix
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Builds NMEA 0183 sentences that the Navionics / Garmin Boating app consumes
 * as an external GPS source ("Paired Devices" over TCP/UDP).
 *
 * Pure Kotlin, no Android dependencies, so it is unit-tested on the JVM.
 */
object NmeaFormatter {

    private const val MPS_TO_KNOTS = 1.943844

    /** The sentences to emit for one fix. RMC is the minimum Navionics needs; GGA adds fix quality/altitude. */
    fun sentences(fix: Fix): List<String> = listOf(rmc(fix), gga(fix))

    /** $GPRMC — recommended minimum: time, position, speed (knots), course, date. */
    fun rmc(fix: Fix): String {
        val c = utc(fix.timeUtcMillis)
        val lat = latitude(fix.latitude)
        val lon = longitude(fix.longitude)
        val sog = String.format(Locale.US, "%.1f", fix.speedMetersPerSecond * MPS_TO_KNOTS)
        val cog = String.format(Locale.US, "%.1f", normalizeBearing(fix.bearingDegrees))
        val body = "GPRMC,${hms(c)},A,${lat.value},${lat.hemi},${lon.value},${lon.hemi}," +
            "$sog,$cog,${dmy(c)},,"
        return frame(body)
    }

    /** $GPGGA — fix quality, satellites, altitude. */
    fun gga(fix: Fix): String {
        val c = utc(fix.timeUtcMillis)
        val lat = latitude(fix.latitude)
        val lon = longitude(fix.longitude)
        val sats = if (fix.satellites >= 0) String.format(Locale.US, "%02d", fix.satellites) else ""
        val alt = String.format(Locale.US, "%.1f", fix.altitudeMeters)
        val body = "GPGGA,${hms(c)},${lat.value},${lat.hemi},${lon.value},${lon.hemi}," +
            "1,$sats,1.0,$alt,M,0.0,M,,"
        return frame(body)
    }

    /** XOR of every character between '$' and '*', as two-digit uppercase hex. */
    fun checksum(body: String): String {
        var cs = 0
        for (ch in body) cs = cs xor ch.code
        return String.format(Locale.US, "%02X", cs)
    }

    private data class Coord(val value: String, val hemi: Char)

    private fun latitude(deg: Double): Coord =
        Coord(degreesMinutes(abs(deg), degreeWidth = 2), if (deg < 0) 'S' else 'N')

    private fun longitude(deg: Double): Coord =
        Coord(degreesMinutes(abs(deg), degreeWidth = 3), if (deg < 0) 'W' else 'E')

    /** Formats decimal degrees as NMEA "dddmm.mmmm" (degrees zero-padded, minutes as 2 int digits + 4 decimals). */
    private fun degreesMinutes(absDeg: Double, degreeWidth: Int): String {
        var d = absDeg.toInt()
        var minutes = (absDeg - d) * 60.0
        // Guard the floating-point edge where minutes rounds to 60.0000.
        if (minutes >= 59.99995) {
            minutes = 0.0
            d += 1
        }
        return String.format(Locale.US, "%0${degreeWidth}d%07.4f", d, minutes)
    }

    private fun normalizeBearing(b: Double): Double {
        var x = b % 360.0
        if (x < 0) x += 360.0
        return x
    }

    private fun hms(c: Calendar): String = String.format(
        Locale.US, "%02d%02d%02d",
        c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND),
    )

    private fun dmy(c: Calendar): String = String.format(
        Locale.US, "%02d%02d%02d",
        c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR) % 100,
    )

    private fun utc(millis: Long): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }

    private fun frame(body: String): String = "\$$body*${checksum(body)}\r\n"
}
