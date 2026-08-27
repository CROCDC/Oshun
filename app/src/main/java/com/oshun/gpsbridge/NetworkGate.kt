package com.oshun.gpsbridge

import android.content.Context
import android.net.wifi.WifiManager
import com.oshun.gpsbridge.net.NetworkRequirements
import com.oshun.gpsbridge.net.NetworkUtils

/**
 * Reads the live network conditions the bridge insists on before transmitting.
 *
 * [stateProvider] is overridable for the same reason the fix provider is: an emulator has no
 * hotspot and no cable, and its Wi-Fi is always on, so the instrumented tests could never
 * exercise the start path otherwise.
 */
object NetworkGate {

    /** The real reader, kept reachable so a test that stands in for it can put it back. */
    val liveState: (Context) -> NetworkRequirements = { read(it) }

    var stateProvider: (Context) -> NetworkRequirements = liveState

    fun state(context: Context): NetworkRequirements = stateProvider(context)

    private fun read(context: Context): NetworkRequirements {
        val interfaces = NetworkUtils.localInterfaces()
        val cable = NetworkUtils.cableAddress(interfaces)
        val hotspot = NetworkUtils.hotspotAddress(interfaces)
        // Already cable-first, then hotspot: the same order the bridge advertises.
        val address = NetworkUtils.localIpAddress(interfaces)
        return NetworkRequirements(
            hotspotUp = hotspot != null,
            wifiOff = !isWifiEnabled(context),
            cableUp = cable != null,
            address = address,
            addresses = NetworkUtils.addresses(interfaces),
            anyLocalNetwork = address != null,
        )
    }

    /** The client radio, not the hotspot: on most phones enabling the AP turns this off by itself. */
    private fun isWifiEnabled(context: Context): Boolean = try {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.isWifiEnabled
    } catch (e: Exception) {
        false // can't tell: don't stand in the way
    }
}
