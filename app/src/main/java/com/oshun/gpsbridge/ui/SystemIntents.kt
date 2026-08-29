package com.oshun.gpsbridge.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.oshun.gpsbridge.BuildConfig

/**
 * The handful of places the app sends the user out to the system, each with the fallback it
 * needs: the settings screens the bridge depends on are not guaranteed to exist under the
 * same name on every ROM, and none of them is worth a crash.
 */

/** Opens the Wi-Fi settings so the client radio can be turned off. */
internal fun openWifiSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: Exception) {
        openHotspotSettings(context) // the wireless screen is the next best thing
    }
}

/** Opens the tethering/hotspot settings, falling back to the wireless settings screen. */
internal fun openHotspotSettings(context: Context) {
    val candidates = listOf(
        Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
    )
    for (intent in candidates) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {
            // Try the next candidate.
        }
    }
}

/** True when the system won't throttle us in the background (or the API isn't available). */
internal fun isIgnoringBatteryOptimizations(context: Context): Boolean = try {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    pm.isIgnoringBatteryOptimizations(context.packageName)
} catch (e: Exception) {
    true // never nag when we can't tell
}

/** Asks for the exemption, falling back to the system list when the direct request is blocked. */
@Suppress("BatteryLife") // a sideloaded navigation bridge is exactly the exempt-worthy case
internal fun openBatteryOptimizationSettings(context: Context) {
    val candidates = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:" + context.packageName)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )
    for (intent in candidates) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {
            // Try the next candidate.
        }
    }
}

/** Opens the Releases page, where every green build publishes a fresh APK. */
internal fun openReleases(context: Context) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.RELEASES_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: Exception) {
        // No browser to handle it: nothing useful to do, and never worth crashing over.
    }
}
