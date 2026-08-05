package com.oshun.gpsbridge.net

import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkUtilsTest {

    @Test
    fun returnsNullOrValidIpv4() {
        val ip = NetworkUtils.localIpAddress()
        if (ip != null) {
            val parts = ip.split(".")
            assertTrue("looks like IPv4: $ip", parts.size == 4)
            assertTrue("octets in range: $ip", parts.all { it.toIntOrNull() in 0..255 })
        }
        // Either way, the call must not throw — reaching here is the assertion.
    }
}
