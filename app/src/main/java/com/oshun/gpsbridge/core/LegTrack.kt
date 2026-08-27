package com.oshun.gpsbridge.core

import kotlin.math.roundToLong

/**
 * A vessel sailing back and forth between two fixed points at a constant speed, for ever.
 *
 * Pure: given the elapsed time it returns where the vessel is, so nothing depends on a clock,
 * a sensor or a permission — which is what makes a simulated track testable in milliseconds
 * instead of hours.
 */
class LegTrack(
    val from: Position,
    val to: Position,
    val speedKnots: Double,
) {
    /** Length of one leg, in nautical miles. Derived, never hardcoded twice. */
    val lengthNauticalMiles: Double = Geo.distanceNauticalMiles(from, to)

    /** How long one leg takes at [speedKnots]. Rounded: truncating loses up to a millisecond. */
    val legMillis: Long = (lengthNauticalMiles / speedKnots * 3_600_000.0).roundToLong()

    private val outboundBearing = Geo.initialBearingDegrees(from, to)
    private val inboundBearing = Geo.initialBearingDegrees(to, from)

    /** Where the vessel is after [elapsedMillis] of sailing, looping from → to → from. */
    fun stateAt(elapsedMillis: Long): TrackState {
        val elapsed = elapsedMillis.coerceAtLeast(0L)
        val cycle = legMillis * 2
        val phase = elapsed % cycle
        val outbound = phase < legMillis
        val legProgress = if (outbound) phase else phase - legMillis
        val fraction = legProgress.toDouble() / legMillis.toDouble()
        val origin = if (outbound) from else to
        val destination = if (outbound) to else from
        return TrackState(
            position = Geo.interpolate(origin, destination, fraction),
            bearingDegrees = if (outbound) outboundBearing else inboundBearing,
            speedKnots = speedKnots,
            outbound = outbound,
        )
    }
}
