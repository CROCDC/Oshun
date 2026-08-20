package com.oshun.gpsbridge.location

import com.oshun.gpsbridge.core.TrackSimulator
import com.oshun.gpsbridge.model.Fix
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Feeds the bridge with the simulated Río de la Plata track instead of the phone's GPS, so
 * the full path to Navionics can be tested without going out on the water.
 *
 * The clock is injectable so tests do not have to wait three hours to reach a waypoint.
 */
class SimulatedFixProvider(
    private val clock: () -> Long = System::currentTimeMillis,
) : FixProvider {

    override fun fixes(intervalMillis: Long): Flow<Fix> = flow {
        val startedAt = clock()
        while (true) {
            val now = clock()
            emit(TrackSimulator.fixAt(now - startedAt, now))
            delay(intervalMillis)
        }
    }
}
