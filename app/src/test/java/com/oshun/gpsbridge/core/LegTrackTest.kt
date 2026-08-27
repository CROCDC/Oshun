package com.oshun.gpsbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegTrackTest {

    private val track = LegTrack(
        from = Position(-34.95, -57.55),
        to = Geo.destination(Position(-34.95, -57.55), bearingDegrees = 90.0, distanceNauticalMiles = 6.0),
        speedKnots = 6.0,
    )

    @Test
    fun theLegLastsTheTimeItTakesToSailIt() {
        assertEquals(6.0, track.lengthNauticalMiles, 1e-6)
        assertEquals(3_600_000L, track.legMillis) // 6 NM at 6 kn is an hour
    }

    @Test
    fun itTurnsRoundAtTheEndAndComesBack() {
        val out = track.stateAt(track.legMillis / 2)
        val back = track.stateAt(track.legMillis + track.legMillis / 2)
        assertTrue(out.outbound)
        assertFalse(back.outbound)
        assertEquals("the same water, sailed the other way", out.position.latitude, back.position.latitude, 1e-9)
        assertEquals(180.0, ((out.bearingDegrees - back.bearingDegrees) + 360.0) % 360.0, 0.5)
    }

    @Test
    fun itLoopsForEverAndNeverRunsBackwardsInTime() {
        val cycle = track.legMillis * 2
        assertEquals(track.stateAt(1_000L).position, track.stateAt(cycle + 1_000L).position)
        assertEquals(track.stateAt(0L).position, track.stateAt(-5_000L).position)
    }
}
