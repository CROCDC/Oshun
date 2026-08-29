package com.oshun.gpsbridge.net

import java.net.Inet4Address
import java.net.NetworkInterface

/** One IPv4-carrying network interface, as the phone reports it. */
data class LocalInterface(val name: String, val ipv4: String)

/** How the tablet reaches the phone, best first. */
enum class Link {
    /** USB tethering: a cable. Nothing to pair, nothing to lose range of, nothing to drain. */
    CABLE,

    /** The phone's own access point: wireless, but it casts off with the boat. */
    HOTSPOT,

    /** Somebody else's network — the club's Wi-Fi, the house router. Fine on land, never at sea. */
    OTHER,
}

/** One address the tablet can reach the phone on, and the kind of link it belongs to. */
data class LinkAddress(val link: Link, val ipv4: String)

/**
 * Works out how, and on which address, the tablet can actually reach the phone.
 *
 * This matters more than it looks: at a mooring the club's Wi-Fi covers both devices, so
 * pairing over it appears to work — and then dies a few metres out, when the phone leaves
 * the access point's range and the address it advertised stops meaning anything.
 *
 * Two links survive leaving the mooring, and either one does the whole job — position and
 * AIS travel on the same stream, so neither depends on which link carries it. When both are
 * up only one address can be advertised, so the cable wins the tie; but every reachable
 * address is reported, because the tablet is on one of them and guessing wrong is silent.
 *
 * Android exposes no public API for either state, so this is interface-based: USB tethering
 * appears as a virtual ethernet (rndis/ncm), and the AP as a tethering interface on which the
 * phone is the gateway (an address ending in .1).
 */
object NetworkUtils {

    /** USB tethering, under the names the gadget drivers use. */
    private val CABLE_PREFIXES = listOf("rndis", "usb", "ncm")

    /** Interface names Android and its vendors use for the built-in AP. */
    private val HOTSPOT_PREFIXES = listOf("ap", "swlan", "softap", "wlan1", "bt-pan", "tether")

    /** Cellular interfaces: a carrier address is never something the tablet can reach. */
    private val MOBILE_PREFIXES = listOf("rmnet", "ccmni", "pdp", "clat", "seth")

    fun isCableName(name: String): Boolean =
        CABLE_PREFIXES.any { name.startsWith(it, ignoreCase = true) }

    fun isHotspotName(name: String): Boolean =
        HOTSPOT_PREFIXES.any { name.startsWith(it, ignoreCase = true) }

    fun isMobileName(name: String): Boolean =
        MOBILE_PREFIXES.any { name.startsWith(it, ignoreCase = true) }

    /** The phone's address on the USB cable, or null when no cable link is up. */
    fun cableAddress(interfaces: List<LocalInterface>): String? =
        interfaces.firstOrNull { isCableName(it.name) }?.ipv4

    /**
     * The phone's address on its own hotspot, or null when the hotspot is not up. Falls back
     * to a non-Wi-Fi interface holding a gateway-style address, which is what a vendor AP
     * under an unfamiliar name looks like.
     */
    fun hotspotAddress(interfaces: List<LocalInterface>): String? {
        val candidates = interfaces.filterNot { isMobileName(it.name) || isCableName(it.name) }
        return candidates.firstOrNull { isHotspotName(it.name) }?.ipv4
            ?: candidates.firstOrNull { !it.name.startsWith("wlan0", true) && it.ipv4.endsWith(".1") }?.ipv4
    }

    /** The address to advertise: the cable's, else the hotspot's, else any reachable LAN address. */
    fun localIpAddress(interfaces: List<LocalInterface>): String? =
        cableAddress(interfaces)
            ?: hotspotAddress(interfaces)
            ?: interfaces.firstOrNull { !isMobileName(it.name) }?.ipv4

    /**
     * Every address the tablet could be reached on, best first. More than one means the
     * choice matters: pairing against the wrong one looks exactly like a broken bridge.
     */
    fun addresses(interfaces: List<LocalInterface>): List<LinkAddress> = buildList {
        cableAddress(interfaces)?.let { add(LinkAddress(Link.CABLE, it)) }
        hotspotAddress(interfaces)?.let { add(LinkAddress(Link.HOTSPOT, it)) }
        val other = interfaces.firstOrNull { !isMobileName(it.name) && !isCableName(it.name) && !isHotspotName(it.name) }
        if (isEmpty() && other != null) add(LinkAddress(Link.OTHER, other.ipv4))
    }

    /** Which kind of link the advertised address belongs to, or null when there is no network. */
    fun linkOf(interfaces: List<LocalInterface>): Link? = when {
        cableAddress(interfaces) != null -> Link.CABLE
        hotspotAddress(interfaces) != null -> Link.HOTSPOT
        localIpAddress(interfaces) != null -> Link.OTHER
        else -> null
    }

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

    /** The phone's USB tethering address right now, or null when there is no cable link. */
    fun cableAddress(): String? = cableAddress(localInterfaces())

    /** The phone's hotspot address right now, or null when the hotspot is off. */
    fun hotspotAddress(): String? = hotspotAddress(localInterfaces())

    /** Best address to type into Navionics, or null when there is no local network at all. */
    fun localIpAddress(): String? = localIpAddress(localInterfaces())
}
