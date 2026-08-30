package com.oshun.gpsbridge.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The release payload is what stands between the app and being updatable at all, so every
 * shape that could come back has to end in either an address or a null — never a throw.
 */
class ReleaseAssetsTest {

    private fun release(vararg assets: String) = """
        {"tag_name":"debug-latest","assets":[${assets.joinToString(",")}]}
    """.trimIndent()

    private fun asset(name: String, url: String, updated: String) = """
        {"name":"$name","browser_download_url":"$url","updated_at":"$updated"}
    """.trimIndent()

    @Test
    fun findsTheApk() {
        val json = release(asset("oshun-74.apk", "https://example.test/oshun-74.apk", "2026-08-29T11:46:22Z"))
        assertEquals(Apk("https://example.test/oshun-74.apk", 74), ReleaseAssets.apk(json))
    }

    @Test
    fun takesTheNewestWhenTwoBuildsRaced() {
        // The release is pruned on publish, so this is the seam between two green builds
        // finishing at once — where handing back the older APK would be a silent downgrade.
        val json = release(
            asset("oshun-73.apk", "https://example.test/oshun-73.apk", "2026-08-29T11:46:22Z"),
            asset("oshun-74.apk", "https://example.test/oshun-74.apk", "2026-08-29T12:10:00Z"),
        )
        assertEquals(Apk("https://example.test/oshun-74.apk", 74), ReleaseAssets.apk(json))
    }

    @Test
    fun ignoresWhateverIsNotAnApk() {
        val json = release(
            asset("coverage.zip", "https://example.test/coverage.zip", "2026-08-29T12:10:00Z"),
            asset("oshun-74.apk", "https://example.test/oshun-74.apk", "2026-08-29T11:46:22Z"),
        )
        assertEquals(Apk("https://example.test/oshun-74.apk", 74), ReleaseAssets.apk(json))
    }

    @Test
    fun readsTheBuildOutOfTheNameAndSaysSoWhenItCannot() {
        // The build number only exists in the file name, and the screen compares it against
        // the installed one. A release published under some other name must not read as a
        // build number that isn't there.
        val named = release(asset("app-debug.apk", "https://example.test/app-debug.apk", "2026-08-29T11:46:22Z"))
        assertEquals(Apk("https://example.test/app-debug.apk", null), ReleaseAssets.apk(named))
    }

    @Test
    fun saysNothingRatherThanThrowing() {
        assertNull(ReleaseAssets.apk(""))
        assertNull(ReleaseAssets.apk("not json at all"))
        assertNull(ReleaseAssets.apk("""{"message":"Not Found"}"""))
        assertNull(ReleaseAssets.apk(release()))
        assertNull(ReleaseAssets.apk(release(asset("notes.txt", "https://example.test/notes.txt", "2026-08-29T11:46:22Z"))))
        // An asset with no address is not an address.
        assertNull(ReleaseAssets.apk(release("""{"name":"oshun-74.apk","updated_at":"2026-08-29T11:46:22Z"}""")))
    }
}
