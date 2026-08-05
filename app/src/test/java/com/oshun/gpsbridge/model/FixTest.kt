package com.oshun.gpsbridge.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FixTest {

    private val fix = Fix(
        latitude = 1.0,
        longitude = 2.0,
        speedMetersPerSecond = 3.0,
        bearingDegrees = 4.0,
        altitudeMeters = 5.0,
        satellites = 6,
        timeUtcMillis = 7L,
    )

    @Test
    fun exposesAllFields() {
        assertEquals(1.0, fix.latitude, 0.0)
        assertEquals(2.0, fix.longitude, 0.0)
        assertEquals(3.0, fix.speedMetersPerSecond, 0.0)
        assertEquals(4.0, fix.bearingDegrees, 0.0)
        assertEquals(5.0, fix.altitudeMeters, 0.0)
        assertEquals(6, fix.satellites)
        assertEquals(7L, fix.timeUtcMillis)
    }

    @Test
    fun copyChangesOnlyRequestedField() {
        val moved = fix.copy(latitude = 9.0)
        assertEquals(9.0, moved.latitude, 0.0)
        assertEquals(fix.longitude, moved.longitude, 0.0)
        assertNotEquals(fix, moved)
    }

    @Test
    fun equalityAndHashAndToString() {
        val same = fix.copy()
        assertEquals(fix, same)
        assertEquals(fix.hashCode(), same.hashCode())
        assert(fix.toString().contains("latitude"))
    }
}
