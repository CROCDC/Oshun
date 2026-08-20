package com.oshun.gpsbridge.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A point on the earth, in the decimal degrees every chart app speaks. */
data class Position(val latitude: Double, val longitude: Double)

/** Spherical-earth navigation maths, in the units a navigator actually uses. */
object Geo {

    /** Mean earth radius expressed in nautical miles (6371.0088 km / 1.852). */
    const val EARTH_RADIUS_NM = 6371.0088 / 1.852

    /** Great-circle distance between two positions, in nautical miles (haversine). */
    fun distanceNauticalMiles(from: Position, to: Position): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_NM * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial great-circle bearing from one position to another, 0..360 degrees true. */
    fun initialBearingDegrees(from: Position, to: Position): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * A point [fraction] of the way from one position to another. Linear in lat/lon, which
     * over a leg of a few miles differs from the great circle by centimetres — and keeps the
     * simulated boat's speed exactly constant, which is what a test track needs.
     */
    fun interpolate(from: Position, to: Position, fraction: Double): Position {
        val f = fraction.coerceIn(0.0, 1.0)
        return Position(
            latitude = from.latitude + (to.latitude - from.latitude) * f,
            longitude = from.longitude + (to.longitude - from.longitude) * f,
        )
    }
}
