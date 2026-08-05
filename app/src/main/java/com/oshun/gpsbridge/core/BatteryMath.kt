package com.oshun.gpsbridge.core

/** Pure battery-drain math, kept Android-free so it is unit-tested on a plain JVM. */
object BatteryMath {

    /** Ignore windows shorter than this — the rate is too noisy to be meaningful. */
    const val MIN_WINDOW_MILLIS = 30_000L

    /**
     * Battery drain in percent-per-hour over the elapsed window, or null when the
     * window is too short. Charging (level went up) reports 0.0, not a negative rate.
     */
    fun drainPercentPerHour(startPercent: Int, nowPercent: Int, elapsedMillis: Long): Double? {
        if (elapsedMillis < MIN_WINDOW_MILLIS) return null
        val hours = elapsedMillis / 3_600_000.0
        if (hours <= 0.0) return null
        val drop = (startPercent - nowPercent).coerceAtLeast(0)
        return drop / hours
    }
}
