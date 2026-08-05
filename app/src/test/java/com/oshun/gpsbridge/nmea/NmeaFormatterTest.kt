package com.oshun.gpsbridge.nmea

import com.oshun.gpsbridge.model.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.Calendar
import java.util.TimeZone
import org.junit.Test

class NmeaFormatterTest {

    private fun epoch(y: Int, mon: Int, d: Int, h: Int, min: Int, s: Int): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(y, mon - 1, d, h, min, s)
        return c.timeInMillis
    }

    // 48°07.038'N, 011°31.000'E — the classic NMEA reference position.
    private val fix = Fix(
        latitude = 48.0 + 7.038 / 60.0,
        longitude = 11.0 + 31.000 / 60.0,
        speedMetersPerSecond = 22.4 / 1.943844, // -> 22.4 knots
        bearingDegrees = 84.4,
        altitudeMeters = 545.4,
        satellites = 8,
        timeUtcMillis = epoch(1994, 3, 23, 12, 35, 19),
    )

    /** Recomputes the checksum from the sentence body and compares to the transmitted one. */
    private fun checksumIsValid(sentence: String): Boolean {
        val star = sentence.indexOf('*')
        assertTrue("no checksum delimiter", star > 0)
        val body = sentence.substring(1, star) // between '$' and '*'
        val transmitted = sentence.substring(star + 1).trim()
        return NmeaFormatter.checksum(body) == transmitted
    }

    @Test
    fun rmc_hasCorrectFieldsAndChecksum() {
        val s = NmeaFormatter.rmc(fix)
        assertTrue("starts with GPRMC: $s", s.startsWith("\$GPRMC,"))
        assertTrue("time: $s", s.contains(",123519,"))
        assertTrue("status active: $s", s.contains(",123519,A,"))
        assertTrue("latitude: $s", s.contains("4807.0380,N,"))
        assertTrue("longitude: $s", s.contains("01131.0000,E,"))
        assertTrue("speed knots: $s", s.contains(",22.4,"))
        assertTrue("course: $s", s.contains(",84.4,"))
        assertTrue("date: $s", s.contains(",230394,"))
        assertTrue("ends CRLF", s.endsWith("\r\n"))
        assertTrue("valid checksum: $s", checksumIsValid(s))
    }

    @Test
    fun gga_hasCorrectFieldsAndChecksum() {
        val s = NmeaFormatter.gga(fix)
        assertTrue("starts with GPGGA: $s", s.startsWith("\$GPGGA,"))
        assertTrue("latitude: $s", s.contains("4807.0380,N,"))
        assertTrue("longitude: $s", s.contains("01131.0000,E,"))
        assertTrue("satellites: $s", s.contains(",08,"))
        assertTrue("altitude: $s", s.contains(",545.4,M,"))
        assertTrue("valid checksum: $s", checksumIsValid(s))
    }

    @Test
    fun southernAndWesternHemispheres() {
        val s = NmeaFormatter.rmc(
            fix.copy(latitude = -33.8688, longitude = -151.2093),
        )
        assertTrue("S hemisphere: $s", s.contains(",S,"))
        assertTrue("W hemisphere: $s", s.contains(",W,"))
        assertTrue("valid checksum: $s", checksumIsValid(s))
    }

    @Test
    fun unknownSatellitesLeavesGgaFieldEmpty() {
        val s = NmeaFormatter.gga(fix.copy(satellites = -1))
        assertTrue("empty sat field: $s", s.contains(",M,") && s.contains(",1,,1.0,"))
        assertTrue("valid checksum: $s", checksumIsValid(s))
    }

    @Test
    fun bearingIsNormalizedIntoRange() {
        val s = NmeaFormatter.rmc(fix.copy(bearingDegrees = -10.0))
        assertTrue("normalized to 350: $s", s.contains(",350.0,"))
    }

    @Test
    fun minutesRoundingUpCarriesIntoDegrees() {
        // 12.99999999° -> minutes computes to ~59.9999994, which must carry to 13°00.0000'.
        val s = NmeaFormatter.rmc(fix.copy(latitude = 12.99999999))
        assertTrue("carry to 13 degrees: $s", s.contains("1300.0000,N,"))
    }

    @Test
    fun sentencesReturnsBothRmcAndGga() {
        val list = NmeaFormatter.sentences(fix)
        assertEquals(2, list.size)
        assertTrue(list[0].startsWith("\$GPRMC,"))
        assertTrue(list[1].startsWith("\$GPGGA,"))
    }

    @Test
    fun checksumMatchesKnownValue() {
        // Independent hand-verifiable case: XOR of "GPRMC" characters only.
        // G=0x47 P=0x50 R=0x52 M=0x4D C=0x43  -> 0x47^0x50=0x17 ^0x52=0x45 ^0x4D=0x08 ^0x43=0x4B
        assertEquals("4B", NmeaFormatter.checksum("GPRMC"))
    }
}
