package com.oshun.gpsbridge.core

import com.oshun.gpsbridge.nmea.NmeaFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackSimulatorTest {

    private val hour = 3_600_000L

    @Test
    fun theTwoWaypointsAreTwelveMilesApart() {
        assertEquals(12.0, TrackSimulator.legNauticalMiles, 0.01)
    }

    @Test
    fun bothWaypointsSitInTheOpenEstuary() {
        // Rough bounding box of the Río de la Plata's main body — a coordinate typo that put
        // the boat on land or in the Atlantic would fail here.
        listOf(TrackSimulator.WAYPOINT_A, TrackSimulator.WAYPOINT_B).forEach { p ->
            assertTrue("latitude $p", p.latitude in -35.35..-34.60)
            assertTrue("longitude $p", p.longitude in -58.00..-56.60)
        }
        // And they are clear of the Argentine shore, which runs roughly Punta Lara → Punta Piedras.
        val shore = listOf(Position(-34.83, -57.98), Position(-35.44, -57.13))
        listOf(TrackSimulator.WAYPOINT_A, TrackSimulator.WAYPOINT_B).forEach { p ->
            val nearest = (0..100).minOf { step ->
                val f = step / 100.0
                Geo.distanceNauticalMiles(p, Geo.interpolate(shore[0], shore[1], f))
            }
            assertTrue("$p is only $nearest NM offshore", nearest > 5.0)
        }
    }

    @Test
    fun aLegTakesThreeHoursAtFourKnots() {
        assertEquals(4.0, TrackSimulator.SPEED_KNOTS, 0.0)
        assertEquals((3 * hour).toDouble(), TrackSimulator.legMillis.toDouble(), 30_000.0)
    }

    @Test
    fun theBoatStartsAtTheFirstWaypointAndReachesTheSecond() {
        val start = TrackSimulator.stateAt(0L)
        assertEquals(TrackSimulator.WAYPOINT_A, start.position)
        assertTrue(start.outbound)

        val arrival = TrackSimulator.stateAt(TrackSimulator.legMillis)
        assertEquals(
            0.0,
            Geo.distanceNauticalMiles(arrival.position, TrackSimulator.WAYPOINT_B),
            0.01,
        )
    }

    @Test
    fun itSailsBackAndForthForever() {
        val backAtStart = TrackSimulator.stateAt(TrackSimulator.legMillis * 2)
        assertEquals(
            0.0,
            Geo.distanceNauticalMiles(backAtStart.position, TrackSimulator.WAYPOINT_A),
            0.01,
        )
        // Three and a half legs in: halfway home on the return leg.
        val returning = TrackSimulator.stateAt((TrackSimulator.legMillis * 3.5).toLong())
        assertFalse(returning.outbound)
        assertEquals(6.0, Geo.distanceNauticalMiles(returning.position, TrackSimulator.WAYPOINT_A), 0.05)
    }

    @Test
    fun courseIsOutboundThenReciprocal() {
        val out = TrackSimulator.stateAt(hour).bearingDegrees
        val back = TrackSimulator.stateAt(TrackSimulator.legMillis + hour).bearingDegrees
        assertEquals(120.0, out, 0.5)
        assertEquals(300.0, back, 0.5)
    }

    @Test
    fun itCoversFourMilesEveryHour() {
        (1..3).forEach { hours ->
            val state = TrackSimulator.stateAt(hour * hours)
            assertEquals(
                4.0 * hours,
                Geo.distanceNauticalMiles(TrackSimulator.WAYPOINT_A, state.position),
                0.02,
            )
            assertEquals(4.0, state.speedKnots, 0.0)
        }
    }

    @Test
    fun negativeElapsedTimeStaysAtTheStart() {
        assertEquals(TrackSimulator.WAYPOINT_A, TrackSimulator.stateAt(-5_000L).position)
    }

    @Test
    fun theFixLooksLikeAnyOtherFix() {
        val fix = TrackSimulator.fixAt(elapsedMillis = hour, nowMillis = 1_787_173_200_000L)
        assertEquals(4.0, fix.speedMetersPerSecond * NmeaFormatter.MPS_TO_KNOTS, 0.001)
        assertEquals(1_787_173_200_000L, fix.timeUtcMillis)
        assertEquals(120.0, fix.bearingDegrees, 0.5)
        assertEquals(10, fix.satellites)
        assertEquals(
            4.0,
            Geo.distanceNauticalMiles(TrackSimulator.WAYPOINT_A, Position(fix.latitude, fix.longitude)),
            0.02,
        )
    }
}
