package com.oshun.gpsbridge.net

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    /**
     * Best-effort local IPv4 address on the current Wi-Fi / LAN, to display in the
     * UI so the user can type it into Navionics. Returns null when not connected.
     */
    fun localIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
