package com.oshun.gpsbridge.net

import org.json.JSONObject

/** The APK a release publishes: where to download it, and which build produced it. */
data class Apk(val url: String, val build: Int?)

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

    // oshun-74.apk. The build number travels in the file name and nowhere else in the payload,
    // and it is the only thing the phone can hold its own versionCode up against.
    private val NAMED_AFTER_ITS_BUILD = Regex("""oshun-(\d+)\.apk""", RegexOption.IGNORE_CASE)

    /** The APK of the newest build in a release payload, or null when there is no APK in it. */
    fun apk(json: String): Apk? = try {
        val assets = JSONObject(json).optJSONArray("assets")
        val apks = (0 until (assets?.length() ?: 0))
            .mapNotNull { assets?.optJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
        // Newest wins. There should only ever be one — the release is pruned on publish — but
        // a race between two green builds must not hand back the older APK.
        val newest = apks.maxByOrNull { it.optString("updated_at") }
        newest?.optString("browser_download_url")
            ?.takeIf { it.isNotBlank() }
            ?.let { Apk(it, buildOf(newest.optString("name"))) }
    } catch (e: Exception) {
        null
    }

    private fun buildOf(name: String): Int? =
        NAMED_AFTER_ITS_BUILD.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()
}
