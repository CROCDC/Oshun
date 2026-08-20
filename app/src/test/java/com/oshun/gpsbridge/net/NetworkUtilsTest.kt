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

    @Test
    fun recognisesTheTetheringInterfaces() {
        listOf("ap0", "swlan0", "softap0", "wlan1", "rndis0", "usb0", "bt-pan").forEach {
            assertTrue("$it is an AP interface", NetworkUtils.isHotspotName(it))
        }
        assertFalse("wlan0 is the client radio", NetworkUtils.isHotspotName("wlan0"))
        assertFalse(NetworkUtils.isHotspotName("eth0"))
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
        NetworkUtils.hotspotAddress()
        NetworkUtils.localIpAddress()
    }
}
