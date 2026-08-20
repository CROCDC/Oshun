package com.oshun.gpsbridge.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRequirementsTest {

    @Test
    fun bothConditionsAreNeeded() {
        assertTrue(NetworkRequirements(hotspotUp = true, wifiOff = true).met)
        assertFalse("no hotspot", NetworkRequirements(hotspotUp = false, wifiOff = true).met)
        assertFalse("wifi still on", NetworkRequirements(hotspotUp = true, wifiOff = false).met)
        assertFalse(NetworkRequirements(hotspotUp = false, wifiOff = false).met)
    }

    @Test
    fun carriesTheAddressToPairAgainst() {
        val state = NetworkRequirements(hotspotUp = true, wifiOff = true, address = "192.168.43.1")
        assertTrue(state.met)
        assertTrue(state.address == "192.168.43.1")
    }
}
