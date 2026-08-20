package com.oshun.gpsbridge.location

import com.oshun.gpsbridge.core.Geo
import com.oshun.gpsbridge.core.Position
import com.oshun.gpsbridge.core.TrackSimulator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedFixProviderTest {

    private val hour = 3_600_000L

    /** Advances an hour per call, so three emissions cover a whole leg without waiting for one. */
    private fun steppingClock(base: Long, step: Long): () -> Long {
        var calls = 0
        return { base + step * calls++ }
    }

    @Test
    fun emitsTheSimulatedBoatMovingAlongTheLeg() = runTest {
        val base = 1_787_173_200_000L
        val provider = SimulatedFixProvider(clock = steppingClock(base, hour))

        val fixes = provider.fixes(intervalMillis = 1000L).take(3).toList()

        assertEquals(3, fixes.size)
        // One hour of sailing per emission, at four knots.
        fixes.forEachIndexed { index, fix ->
            val travelled = Geo.distanceNauticalMiles(
                TrackSimulator.WAYPOINT_A,
                Position(fix.latitude, fix.longitude),
            )
            assertEquals(4.0 * (index + 1), travelled, 0.02)
        }
        // The third one lands on the far waypoint: the full 12 NM leg.
        val last = Position(fixes.last().latitude, fixes.last().longitude)
        assertEquals(0.0, Geo.distanceNauticalMiles(last, TrackSimulator.WAYPOINT_B), 0.02)
    }

    @Test
    fun everyFixCarriesTheSimulatedSpeedAndItsOwnTimestamp() = runTest {
        val base = 1_787_173_200_000L
        val provider = SimulatedFixProvider(clock = steppingClock(base, hour))

        val fixes = provider.fixes(intervalMillis = 500L).take(2).toList()

        assertEquals(TrackSimulator.speedMetersPerSecond, fixes[0].speedMetersPerSecond, 1e-9)
        assertEquals(base + hour, fixes[0].timeUtcMillis)
        assertEquals(base + 2 * hour, fixes[1].timeUtcMillis)
    }
}
