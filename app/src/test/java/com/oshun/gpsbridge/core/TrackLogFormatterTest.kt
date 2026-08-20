package com.oshun.gpsbridge.core

import com.oshun.gpsbridge.model.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class TrackLogFormatterTest {

    private val fix = Fix(
        latitude = -34.601234,
        longitude = -58.381234,
        speedMetersPerSecond = 5.144, // ~10 kn
        bearingDegrees = 84.4,
        altitudeMeters = 12.0,
        satellites = 8,
        timeUtcMillis = 0L,
    )

    // 2026-08-19T21:00:00Z
    private val now = 1_787_173_200_000L

    @Test
    fun csvLineCarriesPositionAndOutcome() {
        val line = TrackLogFormatter.csvLine(
            nowMillis = now,
            fix = fix,
            valid = true,
            outcome = DeliveryOutcome.OK,
            transports = "TCP",
            clients = 1,
        )
        val fields = line.split(",")
        assertEquals(10, fields.size)
        assertEquals(TrackLogFormatter.utc(now), fields[0])
        assertEquals("-34.601234", fields[1])
        assertEquals("-58.381234", fields[2])
        assertEquals("10.0", fields[3]) // knots, from the same constant the NMEA uses
        assertEquals("84.4", fields[4])
        assertEquals("A", fields[5])
        assertEquals("8", fields[6])
        assertEquals("TCP", fields[7])
        assertEquals("1", fields[8])
        assertEquals("OK", fields[9])
    }

    @Test
    fun staleFixAndUnknownSatellitesAreRecorded() {
        val line = TrackLogFormatter.csvLine(
            nowMillis = now,
            fix = fix.copy(satellites = -1),
            valid = false,
            outcome = DeliveryOutcome.NO_CLIENT,
            transports = "TCP+UDP",
            clients = 0,
        )
        val fields = line.split(",")
        assertEquals("V", fields[5])
        assertEquals("", fields[6])
        assertEquals("TCP+UDP", fields[7])
        assertEquals("NO_CLIENT", fields[9])
    }

    @Test
    fun headerColumnsMatchTheRow() {
        val columns = TrackLogFormatter.CSV_HEADER.split(",")
        val fields = TrackLogFormatter.csvLine(now, fix, true, DeliveryOutcome.OK, "TCP", 1).split(",")
        assertEquals(columns.size, fields.size)
    }

    @Test
    fun sessionHeaderNamesWhatIsBeingRecorded() {
        val header = TrackLogFormatter.sessionHeader(
            now,
            BridgeConfig(port = 2000, tcpEnabled = true, udpEnabled = false, intervalMillis = 1000L),
        )
        assertTrue(header.startsWith("# session "))
        assertTrue(header.contains("port=2000"))
        assertTrue(header.contains("transports=TCP"))
        assertTrue(header.contains("interval=1000ms"))
        assertTrue(header.contains("autooff=on"))
    }

    @Test
    fun sessionHeaderCoversBothTransportsAndNone() {
        assertTrue(
            TrackLogFormatter.sessionHeader(now, BridgeConfig(tcpEnabled = true, udpEnabled = true))
                .contains("transports=TCP+UDP"),
        )
        val none = TrackLogFormatter.sessionHeader(
            now,
            BridgeConfig(tcpEnabled = false, udpEnabled = false, autoOffEnabled = false),
        )
        assertTrue(none.contains("transports=none"))
        assertTrue(none.contains("autooff=off"))
    }

    @Test
    fun sessionFooterNamesTheReason() {
        assertTrue(
            TrackLogFormatter.sessionFooter(now, StopReason.IDLE_TIMEOUT)
                .contains("reason=IDLE_TIMEOUT"),
        )
    }

    @Test
    fun utcIsSecondsPrecisionZulu() {
        assertEquals("2026-08-19T21:00:00Z", TrackLogFormatter.utc(now))
        assertEquals("2026-08-19T21:00:00Z", TrackLogFormatter.utc(now + 999L))
    }

    @Test
    fun timeOfDayIsLocalWallClock() {
        assertEquals("21:00:00", TrackLogFormatter.timeOfDay(now, ZoneOffset.UTC))
        assertEquals("18:00:00", TrackLogFormatter.timeOfDay(now, ZoneOffset.ofHours(-3)))
    }
}
