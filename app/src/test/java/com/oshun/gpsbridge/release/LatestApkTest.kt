package com.oshun.gpsbridge.release

import com.oshun.gpsbridge.net.Apk
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The lookup against a server this test runs itself: no GitHub, no rate limit, no internet.
 *
 * Robolectric, because the answer is read with org.json, which a plain unit test in this module
 * gets as a stub that returns null from everything.
 */
@RunWith(RobolectricTestRunner::class)
class LatestApkTest {

    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
        LatestApk.apiUrl = server.url("/releases/tags/debug-latest").toString()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun findsTheApkOfTheNewestBuild() {
        server.enqueue(
            MockResponse().setBody(
                """{"assets":[{"name":"oshun-74.apk",
                   "browser_download_url":"https://example.test/oshun-74.apk",
                   "updated_at":"2026-08-29T12:10:00Z"}]}""",
            ),
        )
        assertEquals(Apk("https://example.test/oshun-74.apk", 74), runBlocking { LatestApk.latest() })
    }

    @Test
    fun asksForTheReleaseWeMean() {
        server.enqueue(MockResponse().setBody("""{"assets":[]}"""))
        runBlocking { LatestApk.latest() }
        val request = server.takeRequest()
        assertEquals("/releases/tags/debug-latest", request.path)
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
    }

    @Test
    fun aRefusalIsAnAnswer() {
        // Rate limiting is the one that will actually happen: GitHub allows sixty unauthenticated
        // calls an hour, and the caller needs a null to fall back on, not an exception.
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"API rate limit exceeded"}"""))
        assertNull(runBlocking { LatestApk.latest() })
    }

    @Test
    fun aServerThatIsNotThereIsAnAnswerToo() {
        server.shutdown()
        assertNull(runBlocking { LatestApk.latest() })
    }
}
