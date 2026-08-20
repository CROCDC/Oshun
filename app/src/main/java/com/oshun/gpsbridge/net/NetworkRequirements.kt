package com.oshun.gpsbridge.net

/**
 * What the network has to look like before the bridge may transmit.
 *
 * The bridge refuses to start over a Wi-Fi network it did not create: that network belongs
 * to the shore, and the moment the boat moves away from it the tablet loses the phone
 * without either device saying so. The hotspot is the only link that casts off with you.
 */
data class NetworkRequirements(
    /** The phone is running its own hotspot, so the tablet can reach it anywhere. */
    val hotspotUp: Boolean,
    /** The phone's Wi-Fi client radio is off, so there is no second address to confuse. */
    val wifiOff: Boolean,
    /** The address the tablet must be pointed at, when there is one. */
    val address: String? = null,
    /** Any local network at all, hotspot or not. Enough for a dry-land test, never at sea. */
    val anyLocalNetwork: Boolean = address != null,
) {
    /**
     * Real navigation demands the hotspot. A simulated run does not leave the desk, so there
     * the house Wi-Fi is fine — and refusing it would break the very feature that exists to
     * test Navionics without going out on the water.
     */
    fun metFor(simulated: Boolean): Boolean =
        if (simulated) anyLocalNetwork else hotspotUp && wifiOff
}
