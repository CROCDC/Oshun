package com.oshun.gpsbridge.core

import com.oshun.gpsbridge.model.AisTarget

/** A made-up vessel: a track it sails for ever, and the identity it broadcasts. */
data class SimulatedVessel(
    val mmsi: Int,
    val name: String,
    val track: LegTrack,
    val navigationStatus: Int = 0,
)

/**
 * Traffic around the simulated boat, so the AIS side of the integration can be exercised
 * from dry land — the same reason [TrackSimulator] exists for our own position.
 *
 * The two vessels are laid out relative to the boat's own leg rather than at invented
 * coordinates: one crosses it square amidships, which is what makes a target worth looking
 * at on the chart, and one loiters near the first waypoint at fishing speed. Their MMSIs are
 * in a range no administration assigns and their names say TEST, because these sentences are
 * byte-identical to real ones on the wire and nothing downstream can tell them apart.
 */
object AisSimulator {

    /** Where the boat's leg is crossed, halfway along it. */
    private val crossingCentre = Geo.interpolate(TrackSimulator.WAYPOINT_A, TrackSimulator.WAYPOINT_B, 0.5)

    /** Square across the boat's course, so the two tracks actually intersect. */
    private val crossingBearing =
        (Geo.initialBearingDegrees(TrackSimulator.WAYPOINT_A, TrackSimulator.WAYPOINT_B) + 90.0) % 360.0

    private const val CROSSING_HALF_LEG_NM = 3.0
    private const val LOITER_LEG_NM = 1.5

    val VESSELS = listOf(
        SimulatedVessel(
            mmsi = 701999001,
            name = "TEST CARGO",
            track = LegTrack(
                from = Geo.destination(crossingCentre, (crossingBearing + 180.0) % 360.0, CROSSING_HALF_LEG_NM),
                to = Geo.destination(crossingCentre, crossingBearing, CROSSING_HALF_LEG_NM),
                speedKnots = 12.0,
            ),
        ),
        SimulatedVessel(
            mmsi = 701999002,
            name = "TEST LANCHA",
            track = LegTrack(
                from = TrackSimulator.WAYPOINT_A,
                to = Geo.destination(TrackSimulator.WAYPOINT_A, 45.0, LOITER_LEG_NM),
                speedKnots = 3.0,
            ),
            navigationStatus = 7, // engaged in fishing
        ),
    )

    /** Where the traffic is after [elapsedMillis] of the session. */
    fun targetsAt(elapsedMillis: Long, nowMillis: Long): List<AisTarget> = VESSELS.map { vessel ->
        val state = vessel.track.stateAt(elapsedMillis)
        AisTarget(
            mmsi = vessel.mmsi,
            name = vessel.name,
            latitude = state.position.latitude,
            longitude = state.position.longitude,
            speedKnots = state.speedKnots,
            courseDegrees = state.bearingDegrees,
            headingDegrees = state.bearingDegrees,
            navigationStatus = vessel.navigationStatus,
            reportedAtMillis = nowMillis,
        )
    }
}
