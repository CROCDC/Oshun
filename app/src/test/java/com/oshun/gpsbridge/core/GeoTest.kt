package com.oshun.gpsbridge.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {

    @Test
    fun oneDegreeOfLatitudeIsSixtyNauticalMiles() {
        // The definition of the nautical mile: a minute of latitude.
        val distance = Geo.distanceNauticalMiles(Position(0.0, 0.0), Position(1.0, 0.0))
        assertEquals(60.0, distance, 0.2)
    }

    @Test
    fun oneMinuteOfLatitudeIsOneNauticalMile() {
        val distance = Geo.distanceNauticalMiles(Position(-34.0, -57.0), Position(-34.0 - 1 / 60.0, -57.0))
        assertEquals(1.0, distance, 0.005)
    }

    @Test
    fun distanceIsZeroForTheSamePointAndSymmetric() {
        val a = Position(-34.95, -57.55)
        val b = Position(-35.05, -57.34)
        assertEquals(0.0, Geo.distanceNauticalMiles(a, a), 1e-9)
        assertEquals(Geo.distanceNauticalMiles(a, b), Geo.distanceNauticalMiles(b, a), 1e-9)
    }

    @Test
    fun bearingPointsTheRightWay() {
        val origin = Position(-34.95, -57.55)
        assertEquals(0.0, Geo.initialBearingDegrees(origin, Position(-34.85, -57.55)), 0.01)
        assertEquals(90.0, Geo.initialBearingDegrees(origin, Position(-34.95, -57.45)), 0.1)
        assertEquals(180.0, Geo.initialBearingDegrees(origin, Position(-35.05, -57.55)), 0.01)
        assertEquals(270.0, Geo.initialBearingDegrees(origin, Position(-34.95, -57.65)), 0.1)
    }

    @Test
    fun interpolateHitsBothEndsAndTheMiddle() {
        val a = Position(-34.0, -57.0)
        val b = Position(-35.0, -58.0)
        assertEquals(a, Geo.interpolate(a, b, 0.0))
        assertEquals(b, Geo.interpolate(a, b, 1.0))
        val mid = Geo.interpolate(a, b, 0.5)
        assertEquals(-34.5, mid.latitude, 1e-9)
        assertEquals(-57.5, mid.longitude, 1e-9)
    }

    @Test
    fun interpolateClampsOutOfRangeFractions() {
        val a = Position(-34.0, -57.0)
        val b = Position(-35.0, -58.0)
        assertEquals(a, Geo.interpolate(a, b, -1.0))
        assertEquals(b, Geo.interpolate(a, b, 7.5))
    }
}
