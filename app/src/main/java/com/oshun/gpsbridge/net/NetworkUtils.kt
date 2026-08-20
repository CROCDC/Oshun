package com.oshun.gpsbridge.net

import java.net.Inet4Address
import java.net.NetworkInterface

/** One IPv4-carrying network interface, as the phone reports it. */
data class LocalInterface(val name: String, val ipv4: String)

/**
 * Works out which address the tablet can actually reach the phone on.
 *
 * This matters more than it looks: at a mooring the club's Wi-Fi covers both devices, so
 * pairing over it appears to work — and then dies a few metres out, when the phone leaves
 * the access point's range and the address it advertised stops meaning anything. Only the
 * phone's own hotspot travels with the boat, so that is the address the app shows.
 *
 * Android exposes no public API for the hotspot state, so this is interface-based: the AP
 * shows up as a tethering interface, and the phone is its gateway (an address ending in .1).
 */
object NetworkUtils {

    /** Interface names Android and its vendors use for the built-in AP and tethering. */
    private val HOTSPOT_PREFIXES = listOf("ap", "swlan", "softap", "wlan1", "rndis", "usb", "bt-pan", "tether")

    /** Cellular interfaces: a carrier address is never something the tablet can reach. */
    private val MOBILE_PREFIXES = listOf("rmnet", "ccmni", "pdp", "clat", "seth")

    fun isHotspotName(name: String): Boolean =
        HOTSPOT_PREFIXES.any { name.startsWith(it, ignoreCase = true) }

    fun isMobileName(name: String): Boolean =
        MOBILE_PREFIXES.any { name.startsWith(it, ignoreCase = true) }

    /**
     * The phone's address on its own hotspot, or null when the hotspot is not up. Falls back
     * to a non-Wi-Fi interface holding a gateway-style address, which is what a vendor AP
     * under an unfamiliar name looks like.
     */
    fun hotspotAddress(interfaces: List<LocalInterface>): String? {
        val candidates = interfaces.filterNot { isMobileName(it.name) }
        return candidates.firstOrNull { isHotspotName(it.name) }?.ipv4
            ?: candidates.firstOrNull { !it.name.startsWith("wlan0", true) && it.ipv4.endsWith(".1") }?.ipv4
    }

    /** The address to advertise: the hotspot's if there is one, otherwise any reachable LAN address. */
    fun localIpAddress(interfaces: List<LocalInterface>): String? =
        hotspotAddress(interfaces) ?: interfaces.firstOrNull { !isMobileName(it.name) }?.ipv4

    /** Every up, non-loopback interface with a site-local IPv4. */
    fun localInterfaces(): List<LocalInterface> = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { networkInterface ->
                networkInterface.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
                    .mapNotNull { address -> address.hostAddress?.let { LocalInterface(networkInterface.name, it) } }
            }
            .toList()
    } catch (e: Exception) {
        emptyList()
    }

    /** The phone's hotspot address right now, or null when the hotspot is off. */
    fun hotspotAddress(): String? = hotspotAddress(localInterfaces())

    /** Best address to type into Navionics, or null when there is no local network at all. */
    fun localIpAddress(): String? = localIpAddress(localInterfaces())
}
