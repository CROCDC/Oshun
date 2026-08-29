package com.oshun.gpsbridge.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkUtilsTest {

    private val wifi = LocalInterface("wlan0", "192.168.1.37")
    private val hotspot = LocalInterface("ap0", "192.168.43.1")
    private val mobile = LocalInterface("rmnet_data0", "10.84.120.9")
    private val cable = LocalInterface("rndis0", "192.168.42.129")

    @Test
    fun recognisesTheAccessPointInterfaces() {
        listOf("ap0", "swlan0", "softap0", "wlan1", "bt-pan").forEach {
            assertTrue("$it is an AP interface", NetworkUtils.isHotspotName(it))
        }
        assertFalse("wlan0 is the client radio", NetworkUtils.isHotspotName("wlan0"))
        assertFalse(NetworkUtils.isHotspotName("eth0"))
        assertFalse("the cable is a link of its own", NetworkUtils.isHotspotName("rndis0"))
    }

    @Test
    fun recognisesTheUsbTetheringInterfaces() {
        listOf("rndis0", "usb0", "ncm0").forEach {
            assertTrue("$it is a USB link", NetworkUtils.isCableName(it))
        }
        assertFalse(NetworkUtils.isCableName("wlan0"))
        assertFalse(NetworkUtils.isCableName("ap0"))
    }

    @Test
    fun theCableWinsOverEverythingElse() {
        // A cable cannot lose range, drop signal or be joined by anyone else, so when one is
        // plugged in it is the address the tablet gets pointed at.
        assertEquals("192.168.42.129", NetworkUtils.localIpAddress(listOf(wifi, hotspot, cable)))
        assertEquals(Link.CABLE, NetworkUtils.linkOf(listOf(wifi, hotspot, cable)))
        assertEquals("192.168.42.129", NetworkUtils.cableAddress(listOf(wifi, cable)))
    }

    @Test
    fun theCableIsNotMistakenForAHotspot() {
        // They are both tethering, but only one of them makes the Wi-Fi rule unnecessary.
        assertNull(NetworkUtils.hotspotAddress(listOf(cable)))
        assertNull(NetworkUtils.cableAddress(listOf(wifi, hotspot)))
    }

    @Test
    fun namesTheLinkTheAddressCameFrom() {
        assertEquals(Link.HOTSPOT, NetworkUtils.linkOf(listOf(wifi, hotspot)))
        assertEquals(Link.OTHER, NetworkUtils.linkOf(listOf(wifi)))
        assertNull(NetworkUtils.linkOf(emptyList()))
        assertNull("a carrier address is no link at all", NetworkUtils.linkOf(listOf(mobile)))
    }

    @Test
    fun recognisesCellularInterfaces() {
        listOf("rmnet_data0", "ccmni0", "pdp0", "clat4").forEach {
            assertTrue("$it is cellular", NetworkUtils.isMobileName(it))
        }
        assertFalse(NetworkUtils.isMobileName("wlan0"))
    }

    @Test
    fun theHotspotAddressWinsOverTheWifiOne() {
        // The bug this fixes: at a mooring the app showed the club's Wi-Fi address, which
        // stops meaning anything the moment the boat pulls away.
        assertEquals("192.168.43.1", NetworkUtils.hotspotAddress(listOf(wifi, hotspot)))
        assertEquals("192.168.43.1", NetworkUtils.localIpAddress(listOf(wifi, hotspot)))
    }

    @Test
    fun noHotspotMeansNoHotspotAddress() {
        assertNull(NetworkUtils.hotspotAddress(listOf(wifi)))
        assertNull(NetworkUtils.hotspotAddress(emptyList()))
        assertNull("a carrier address is not a hotspot", NetworkUtils.hotspotAddress(listOf(mobile)))
    }

    @Test
    fun aVendorApUnderAnUnfamiliarNameIsStillFound() {
        // Some vendors name the AP something else; the phone is always its gateway.
        val vendor = LocalInterface("mlan0", "192.168.137.1")
        assertEquals("192.168.137.1", NetworkUtils.hotspotAddress(listOf(wifi, vendor)))
    }

    @Test
    fun aCellularAddressIsNeverAdvertised() {
        assertNull(NetworkUtils.localIpAddress(listOf(mobile)))
        assertEquals("192.168.1.37", NetworkUtils.localIpAddress(listOf(mobile, wifi)))
    }

    @Test
    fun withoutAHotspotItStillReportsTheLanAddress() {
        // Showing it is fine; refusing to transmit over it is the app's job, not this one's.
        assertEquals("192.168.1.37", NetworkUtils.localIpAddress(listOf(wifi)))
        assertNull(NetworkUtils.localIpAddress(emptyList()))
    }

    @Test
    fun enumeratingTheRealInterfacesNeverThrows() {
        NetworkUtils.localInterfaces().forEach { local ->
            val parts = local.ipv4.split(".")
            assertEquals("looks like IPv4: ${local.ipv4}", 4, parts.size)
            assertTrue("octets in range: ${local.ipv4}", parts.all { it.toIntOrNull() in 0..255 })
        }
        NetworkUtils.cableAddress()
        NetworkUtils.hotspotAddress()
        NetworkUtils.localIpAddress()
    }

    @Test
    fun reportsEveryAddressTheTabletCouldBeOn() {
        // The case this exists for: tethering left on while the tablet sits on the hotspot.
        // Only one address can be advertised, so the other has to be visible rather than
        // guessed at — pairing against the wrong one looks exactly like a dead bridge.
        val both = NetworkUtils.addresses(listOf(wifi, hotspot, cable))
        assertEquals(listOf(Link.CABLE, Link.HOTSPOT), both.map { it.link })
        assertEquals("192.168.42.129", both.first().ipv4)
        assertEquals("192.168.43.1", both.last().ipv4)
    }

    @Test
    fun aLoneHotspotIsTheOnlyAddressReported() {
        val only = NetworkUtils.addresses(listOf(wifi, hotspot))
        assertEquals(1, only.size)
        assertEquals(Link.HOTSPOT, only.single().link)
    }

    @Test
    fun withNoLinkOfOurOwnItStillNamesTheNetworkWeAreOn() {
        // Not good enough to sail with, but it is what a simulated run pairs against.
        assertEquals(listOf(Link.OTHER), NetworkUtils.addresses(listOf(wifi)).map { it.link })
        assertTrue(NetworkUtils.addresses(listOf(mobile)).isEmpty())
        assertTrue(NetworkUtils.addresses(emptyList()).isEmpty())
    }
}
