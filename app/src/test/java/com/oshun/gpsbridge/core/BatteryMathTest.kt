package com.oshun.gpsbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryMathTest {

    @Test
    fun windowTooShortReturnsNull() {
        assertNull(BatteryMath.drainPercentPerHour(80, 79, 10_000))
    }

    @Test
    fun computesPercentPerHour() {
        // 2% drop over exactly one hour -> 2.0 %/h.
        assertEquals(2.0, BatteryMath.drainPercentPerHour(80, 78, 3_600_000)!!, 1e-9)
    }

    @Test
    fun chargingReportsZeroNotNegative() {
        assertEquals(0.0, BatteryMath.drainPercentPerHour(50, 55, 3_600_000)!!, 1e-9)
    }

    @Test
    fun halfHourWindowScales() {
        // 1% over half an hour -> 2.0 %/h.
        assertEquals(2.0, BatteryMath.drainPercentPerHour(90, 89, 1_800_000)!!, 1e-9)
    }
}
