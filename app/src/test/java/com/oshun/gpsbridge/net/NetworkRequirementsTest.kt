package com.oshun.gpsbridge.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRequirementsTest {

    @Test
    fun realNavigationDemandsTheHotspotAndNothingElse() {
        assertTrue(NetworkRequirements(hotspotUp = true, wifiOff = true).metFor(simulated = false))
        assertFalse("no hotspot", NetworkRequirements(hotspotUp = false, wifiOff = true).metFor(simulated = false))
        assertFalse("wifi still on", NetworkRequirements(hotspotUp = true, wifiOff = false).metFor(simulated = false))
        assertFalse(NetworkRequirements(hotspotUp = false, wifiOff = false).metFor(simulated = false))
    }

    @Test
    fun aWifiWithAnAddressIsNotEnoughForTheRealThing() {
        // The failure this prevents: the club's Wi-Fi covers both devices at the mooring and
        // stops covering them a few metres out.
        val onClubWifi = NetworkRequirements(hotspotUp = false, wifiOff = false, address = "192.168.1.37")
        assertFalse(onClubWifi.metFor(simulated = false))
    }

    @Test
    fun aSimulatedRunTakesWhateverNetworkIsAtHand() {
        // It never leaves the desk, and refusing the house Wi-Fi would break the feature
        // that exists to test Navionics without going out on the water.
        val houseWifi = NetworkRequirements(hotspotUp = false, wifiOff = false, address = "192.168.1.37")
        assertTrue(houseWifi.metFor(simulated = true))
        assertTrue(
            NetworkRequirements(hotspotUp = true, wifiOff = true, address = "192.168.43.1")
                .metFor(simulated = true),
        )
    }

    @Test
    fun noNetworkAtAllStopsEvenASimulatedRun() {
        val nothing = NetworkRequirements(hotspotUp = false, wifiOff = true)
        assertFalse(nothing.anyLocalNetwork)
        assertFalse(nothing.metFor(simulated = true))
        assertFalse(nothing.metFor(simulated = false))
    }

    @Test
    fun carriesTheAddressToPairAgainst() {
        val state = NetworkRequirements(hotspotUp = true, wifiOff = true, address = "192.168.43.1")
        assertTrue(state.metFor(simulated = false))
        assertTrue(state.anyLocalNetwork)
        assertTrue(state.address == "192.168.43.1")
    }
}
