package com.oshun.gpsbridge.model

/**
 * A single GPS fix, expressed in units independent of any platform API so the
 * NMEA formatter can be unit-tested on a plain JVM (no Android dependency).
 */
data class Fix(
    val latitude: Double,          // decimal degrees, +N / -S
    val longitude: Double,         // decimal degrees, +E / -W
    val speedMetersPerSecond: Double,
    val bearingDegrees: Double,    // course over ground, 0..360
    val altitudeMeters: Double,
    val satellites: Int,           // -1 when unknown
    val timeUtcMillis: Long,       // epoch millis, UTC
)
