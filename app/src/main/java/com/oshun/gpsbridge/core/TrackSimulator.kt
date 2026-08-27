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

    /** The boat itself: one leg, sailed back and forth for ever. */
    val track = LegTrack(WAYPOINT_A, WAYPOINT_B, SPEED_KNOTS)

    /** Length of one leg, in nautical miles. Derived, never hardcoded twice. */
    val legNauticalMiles: Double get() = track.lengthNauticalMiles

    /** How long one leg takes at [SPEED_KNOTS]. */
    val legMillis: Long get() = track.legMillis

    /** Speed in the units [Fix] carries. */
    val speedMetersPerSecond: Double = SPEED_KNOTS / NmeaFormatter.MPS_TO_KNOTS

    /** The boat's state after [elapsedMillis] of sailing, looping A → B → A forever. */
    fun stateAt(elapsedMillis: Long): TrackState = track.stateAt(elapsedMillis)

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
