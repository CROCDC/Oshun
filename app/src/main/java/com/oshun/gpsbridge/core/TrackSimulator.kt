package com.oshun.gpsbridge.core

import com.oshun.gpsbridge.model.Fix
import com.oshun.gpsbridge.nmea.NmeaFormatter

/** Where the simulated boat is, and how it is moving, at one instant. */
data class TrackState(
    val position: Position,
    val bearingDegrees: Double,
    val speedKnots: Double,
    /** True while running from A to B; false on the way back. Useful in tests and logs. */
    val outbound: Boolean,
)

/**
 * A boat that sails back and forth between two fixed waypoints in the middle of the Río de
 * la Plata, so the whole chain — NMEA, sockets, Navionics — can be exercised from dry land.
 *
 * The waypoints sit in open water: roughly 9 and 11 NM off the Argentine shore and over 23 NM
 * from the Uruguayan one, in the main body of the estuary. They are 12.0 NM apart on a
 * 120°/300° axis, which at 4 knots is a three-hour leg each way.
 *
 * Pure: given the elapsed time it returns the position, so nothing depends on a clock, a
 * sensor or a permission.
 */
object TrackSimulator {

    /** Mid-estuary, Río de la Plata. */
    val WAYPOINT_A = Position(-34.950000, -57.550000)

    /** 12.0 NM from [WAYPOINT_A] on a 120° course, still well offshore. */
    val WAYPOINT_B = Position(-35.049749, -57.338570)

    const val SPEED_KNOTS = 4.0

    /** Length of one leg, in nautical miles. Derived, never hardcoded twice. */
    val legNauticalMiles: Double = Geo.distanceNauticalMiles(WAYPOINT_A, WAYPOINT_B)

    /** How long one leg takes at [SPEED_KNOTS]. */
    val legMillis: Long = (legNauticalMiles / SPEED_KNOTS * 3_600_000.0).toLong()

    /** Speed in the units [Fix] carries. */
    val speedMetersPerSecond: Double = SPEED_KNOTS / NmeaFormatter.MPS_TO_KNOTS

    private val outboundBearing = Geo.initialBearingDegrees(WAYPOINT_A, WAYPOINT_B)
    private val inboundBearing = Geo.initialBearingDegrees(WAYPOINT_B, WAYPOINT_A)

    /** The boat's state after [elapsedMillis] of sailing, looping A → B → A forever. */
    fun stateAt(elapsedMillis: Long): TrackState {
        val elapsed = elapsedMillis.coerceAtLeast(0L)
        val cycle = legMillis * 2
        val phase = elapsed % cycle
        val outbound = phase < legMillis
        val legProgress = if (outbound) phase else phase - legMillis
        val fraction = legProgress.toDouble() / legMillis.toDouble()
        val from = if (outbound) WAYPOINT_A else WAYPOINT_B
        val to = if (outbound) WAYPOINT_B else WAYPOINT_A
        return TrackState(
            position = Geo.interpolate(from, to, fraction),
            bearingDegrees = if (outbound) outboundBearing else inboundBearing,
            speedKnots = SPEED_KNOTS,
            outbound = outbound,
        )
    }

    /**
     * The same state as a [Fix], indistinguishable on the wire from a real one — that is the
     * point: what Navionics receives in test mode must be exactly what it receives at sea.
     * The app, its log and its notification are where the simulation is called out.
     */
    fun fixAt(elapsedMillis: Long, nowMillis: Long): Fix {
        val state = stateAt(elapsedMillis)
        return Fix(
            latitude = state.position.latitude,
            longitude = state.position.longitude,
            speedMetersPerSecond = speedMetersPerSecond,
            bearingDegrees = state.bearingDegrees,
            altitudeMeters = 0.0,
            satellites = SIMULATED_SATELLITES,
            timeUtcMillis = nowMillis,
        )
    }

    /** A plausible count for a boat with a clear sky overhead. */
    private const val SIMULATED_SATELLITES = 10
}
