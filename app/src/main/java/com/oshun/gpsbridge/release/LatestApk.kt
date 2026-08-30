package com.oshun.gpsbridge.release

import com.oshun.gpsbridge.BuildConfig
import com.oshun.gpsbridge.net.Apk
import com.oshun.gpsbridge.net.ReleaseAssets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Asks GitHub where the newest APK is.
 *
 * The download used to be a page you then had to find the file on. Going straight at the file
 * means knowing its name, and the name carries the build number — so it is a question, not a
 * constant, and this is the one place the app asks it.
 *
 * Every failure answers null rather than throwing: the caller has a page to fall back to, and
 * a phone that cannot reach GitHub is not a phone that should crash over it.
 */
object LatestApk {

    private val client = OkHttpClient.Builder()
        // A tap that has to wait longer than this has already failed as far as anyone holding
        // the phone is concerned, and the Releases page is one tap away.
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Overridable so a test can point it at a server it runs itself, as the AIS feed does. */
    var apiUrl: String = BuildConfig.RELEASE_API_URL

    /**
     * The whole lookup, overridable so a screen test can stand in for GitHub's answer. What
     * the version screen does with that answer is the part worth testing, and going to the
     * real GitHub to find it out would make that a test of the network.
     */
    var lookup: suspend () -> Apk? = { fetch() }

    /** The newest published APK, or null when it cannot be worked out. */
    suspend fun latest(): Apk? = lookup()

    private suspend fun fetch(): Apk? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                ReleaseAssets.apk(response.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            null // no network, no DNS, GitHub rate-limiting us: all the same answer here
        }
    }
}
