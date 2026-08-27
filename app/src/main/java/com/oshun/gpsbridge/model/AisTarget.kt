package com.oshun.gpsbridge.model

/**
 * Another vessel, as AIS reports it. The fields are the ones a chart plotter draws: who it
 * is, where it is, and where it is heading.
 *
 * [reportedAtMillis] is when the report was *made*, not when we forwarded it: a target from
 * an internet feed can be minutes old, and the difference is the whole reason a stale target
 * must be dropped rather than drawn.
 */
data class AisTarget(
    /** Maritime Mobile Service Identity — the vessel's identity on the air, 9 digits. */
    val mmsi: Int,
    /** Vessel name, up to 20 characters of the AIS six-bit alphabet. Blank when unknown. */
    val name: String = "",
    val latitude: Double,
    val longitude: Double,
    val speedKnots: Double,
    val courseDegrees: Double,
    /** Where the bow points, when the vessel reports it. Null means "not available" (511). */
    val headingDegrees: Double? = null,
    /** ITU-R M.1371 navigation status; 0 = under way using engine, 15 = undefined. */
    val navigationStatus: Int = 0,
    val reportedAtMillis: Long = 0L,
)
