package com.oshun.gpsbridge.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRequirementsTest {

    @Test
    fun aCableIsEnoughOnItsOwn() {
        // The address it advertises exists only on that cable, so nothing the radios do can
        // point the tablet somewhere else — not even a Wi-Fi left on by mistake.
        val cabled = NetworkRequirements(hotspotUp = false, wifiOff = false, cableUp = true)
        assertTrue(cabled.metFor(simulated = false))
        assertTrue(cabled.metFor(simulated = true))
        assertEquals(Link.CABLE, cabled.link)
    }

    @Test
    fun theCablePreemptsTheHotspot() {
        val both = NetworkRequirements(
            hotspotUp = true,
            wifiOff = true,
            cableUp = true,
            address = "192.168.42.129",
        )
        assertEquals(Link.CABLE, both.link)
    }

    @Test
    fun namesTheLinkItIsAbout() {
        assertEquals(
            Link.HOTSPOT,
            NetworkRequirements(hotspotUp = true, wifiOff = true, address = "192.168.43.1").link,
        )
        assertEquals(
            Link.OTHER,
            NetworkRequirements(hotspotUp = false, wifiOff = false, address = "192.168.1.37").link,
        )
        assertNull(NetworkRequirements(hotspotUp = false, wifiOff = true).link)
    }

    @Test
    fun withoutACableRealNavigationStillDemandsTheHotspotAndNothingElse() {
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

    @Test
    fun eitherLinkOnItsOwnIsACompleteAnswer() {
        // Neither is a fallback: the position and the AIS traffic ride the same stream, so
        // whatever works on one link works on the other.
        val overCable = NetworkRequirements(hotspotUp = false, wifiOff = false, cableUp = true)
        val overHotspot = NetworkRequirements(hotspotUp = true, wifiOff = true)
        assertTrue(overCable.metFor(simulated = false))
        assertTrue(overHotspot.metFor(simulated = false))
    }

    @Test
    fun namesTheAddressesThatAreNotBeingAdvertised() {
        val both = NetworkRequirements(
            hotspotUp = true,
            wifiOff = true,
            cableUp = true,
            address = "192.168.42.129",
            addresses = listOf(
                LinkAddress(Link.CABLE, "192.168.42.129"),
                LinkAddress(Link.HOTSPOT, "192.168.43.1"),
            ),
        )
        assertEquals(listOf(LinkAddress(Link.HOTSPOT, "192.168.43.1")), both.otherAddresses())
    }

    @Test
    fun withOneAddressThereIsNothingElseToSuggest() {
        val single = NetworkRequirements(
            hotspotUp = true,
            wifiOff = true,
            address = "192.168.43.1",
            addresses = listOf(LinkAddress(Link.HOTSPOT, "192.168.43.1")),
        )
        assertTrue(single.otherAddresses().isEmpty())
    }
}
