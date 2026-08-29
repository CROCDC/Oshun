package com.oshun.gpsbridge.net

import org.json.JSONObject

/**
 * Which file to download out of a GitHub release.
 *
 * Every build publishes its APK under its own name, so the address cannot be a constant: the
 * app would have to know the name of a build that did not exist when it was compiled. It asks
 * GitHub instead, and this is the part of that answer worth reading.
 *
 * Defensive like the AIS parser and for the same reason — somebody else's JSON, arriving over
 * a network, in the one place the app has to be able to update itself from.
 */
object ReleaseAssets {

    /** The APK's download address in a release payload, or null when there is no APK in it. */
    fun apkUrl(json: String): String? = try {
        val assets = JSONObject(json).optJSONArray("assets")
        val apks = (0 until (assets?.length() ?: 0))
            .mapNotNull { assets?.optJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
        // Newest wins. There should only ever be one — the release is pruned on publish — but
        // a race between two green builds must not hand back the older APK.
        apks.maxByOrNull { it.optString("updated_at") }
            ?.optString("browser_download_url")
            ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }
}
