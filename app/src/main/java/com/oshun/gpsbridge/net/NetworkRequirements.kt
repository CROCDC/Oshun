package com.oshun.gpsbridge.net

/**
 * What the network has to look like before the bridge may transmit.
 *
 * The bridge refuses to start over a Wi-Fi network it did not create: that network belongs
 * to the shore, and the moment the boat moves away from it the tablet loses the phone
 * without either device saying so.
 *
 * Two links go with the boat, and either one is a complete answer: the hotspot, or a USB
 * cable between the two devices. Everything the bridge sends — the position and the AIS
 * traffic alike — travels on the same stream, so nothing works on one link and not the other.
 *
 * A cable is enough on its own whatever the phone's Wi-Fi happens to be doing, because the
 * address it advertises exists only on that cable. Over the hotspot the client radio has to
 * be off, so there is no second address to confuse.
 */
data class NetworkRequirements(
    /** The phone is running its own hotspot, so the tablet can reach it anywhere. */
    val hotspotUp: Boolean,
    /** The phone's Wi-Fi client radio is off, so there is no second address to confuse. */
    val wifiOff: Boolean,
    /** USB tethering is up: there is a cable, and it is the link that cannot be interfered with. */
    val cableUp: Boolean = false,
    /** The address the tablet must be pointed at, when there is one. */
    val address: String? = null,
    /**
     * Every address the phone is reachable on. Both links can be up at once — a cable
     * plugged in while the tablet sits on the hotspot — and only one of them can be
     * advertised, so the others have to be visible rather than guessed at.
     */
    val addresses: List<LinkAddress> = emptyList(),
    /** Any local network at all, cable or hotspot or not. Enough for a dry-land test, never at sea. */
    val anyLocalNetwork: Boolean = address != null,
) {
    /** Which link the advertised address belongs to, or null when there is no network. */
    val link: Link?
        get() = when {
            cableUp -> Link.CABLE
            hotspotUp -> Link.HOTSPOT
            anyLocalNetwork -> Link.OTHER
            else -> null
        }

    /**
     * Real navigation demands a link that leaves the mooring: the cable, or failing that the
     * hotspot with the client radio off. A simulated run does not leave the desk, so there the
     * house Wi-Fi is fine — and refusing it would break the very feature that exists to test
     * Navionics without going out on the water.
     */
    /** The addresses that are not the one being advertised — what to try if pairing fails. */
    fun otherAddresses(): List<LinkAddress> = addresses.filter { it.ipv4 != address }

    fun metFor(simulated: Boolean): Boolean = when {
        cableUp -> true
        hotspotUp && wifiOff -> true
        else -> simulated && anyLocalNetwork
    }
}
