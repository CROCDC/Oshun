package com.oshun.gpsbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The traffic exists to be looked at on a chart, so what matters is that it is *near the
 * boat and moving*: targets parked on the other side of the estuary would prove nothing
 * about the integration.
 */
class AisSimulatorTest {

    @Test
    fun theTrafficSailsWhereTheBoatSails() {
        val elapsed = 20 * 60_000L
        val boat = TrackSimulator.stateAt(elapsed).position
        AisSimulator.targetsAt(elapsed, NOW).forEach { target ->
            val distance = Geo.distanceNauticalMiles(boat, Position(target.latitude, target.longitude))
            assertTrue("${target.name} is $distance NM away", distance < 8.0)
        }
    }

    @Test
    fun theCargoCrossesTheBoatsTrack() {
        // Square across the leg is what makes a target worth watching: it closes, passes and
        // opens again instead of pacing you for ever at a fixed bearing.
        val cargo = AisSimulator.VESSELS.first()
        val cargoCourse = Geo.initialBearingDegrees(cargo.track.from, cargo.track.to)
        val boatCourse = Geo.initialBearingDegrees(TrackSimulator.WAYPOINT_A, TrackSimulator.WAYPOINT_B)
        val difference = ((cargoCourse - boatCourse + 360.0) % 360.0)
        assertEquals(90.0, difference, 0.5)
    }

    @Test
    fun everyTargetIsIdentifiableAsAFake() {
        // What goes on the wire is byte-identical to a real report, so the only thing that can
        // stop someone mistaking these for traffic is the name and an MMSI nobody was assigned.
        AisSimulator.VESSELS.forEach {
            assertTrue(it.name, it.name.startsWith("TEST"))
            assertTrue("${it.mmsi} is in the unassigned block", it.mmsi in 701_999_000..701_999_999)
        }
        assertEquals(
            "MMSIs must be unique or a plotter merges the targets",
            AisSimulator.VESSELS.size,
            AisSimulator.VESSELS.map { it.mmsi }.toSet().size,
        )
    }

    @Test
    fun theTargetsActuallyMove() {
        val first = AisSimulator.targetsAt(0L, NOW).first()
        val later = AisSimulator.targetsAt(5 * 60_000L, NOW).first()
        assertNotEquals(first.latitude, later.latitude)
        val run = Geo.distanceNauticalMiles(
            Position(first.latitude, first.longitude),
            Position(later.latitude, later.longitude),
        )
        // Five minutes at 12 knots is one mile, whatever the geometry underneath.
        assertEquals(1.0, run, 0.02)
    }

    @Test
    fun aTargetReportsWhatTheEncoderNeeds() {
        val target = AisSimulator.targetsAt(0L, NOW).first()
        assertEquals(12.0, target.speedKnots, 1e-9)
        assertTrue(target.courseDegrees in 0.0..360.0)
        assertEquals(NOW, target.reportedAtMillis)
    }

    private companion object {
        const val NOW = 1787229296000L
    }
}
