package com.oshun.gpsbridge.store

import android.content.Context
import com.oshun.gpsbridge.core.TrackLogFormatter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackLogWriterTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val defaultMax = TrackLogWriter.maxBytes

    private fun logDir() = File(context.filesDir, "logs")
    private fun currentFile() = File(logDir(), "track.csv")
    private fun previousFile() = File(logDir(), "track.1.csv")

    @Before
    fun setUp() {
        TrackLogWriter.clear(context)
    }

    @After
    fun tearDown() {
        TrackLogWriter.close(null)
        TrackLogWriter.clear(context)
        TrackLogWriter.maxBytes = defaultMax
    }

    @Test
    fun aFreshFileStartsWithTheColumnsThenTheSession() {
        TrackLogWriter.open(context, "# session one")
        TrackLogWriter.append(context, "row-1")
        TrackLogWriter.close("# session end")

        val lines = currentFile().readLines()
        assertEquals(TrackLogFormatter.CSV_HEADER, lines[0])
        assertEquals("# session one", lines[1])
        assertEquals("row-1", lines[2])
        assertEquals("# session end", lines[3])
    }

    @Test
    fun aSecondSessionAppendsWithoutRepeatingTheColumns() {
        TrackLogWriter.open(context, "# session one")
        TrackLogWriter.append(context, "row-1")
        TrackLogWriter.close(null)

        TrackLogWriter.open(context, "# session two")
        TrackLogWriter.append(context, "row-2")
        TrackLogWriter.close(null)

        val lines = currentFile().readLines()
        assertEquals(1, lines.count { it == TrackLogFormatter.CSV_HEADER })
        assertEquals(listOf("# session one", "row-1", "# session two", "row-2"), lines.drop(1))
    }

    @Test
    fun eachLineIsFlushed() {
        // The record has to survive the process being killed mid-trip, which is exactly
        // when it matters — so nothing may sit in a buffer.
        TrackLogWriter.open(context, "# session")
        TrackLogWriter.append(context, "row-1")
        assertTrue(currentFile().readLines().contains("row-1"))
    }

    @Test
    fun appendingWithoutOpeningIsIgnored() {
        TrackLogWriter.append(context, "orphan")
        assertTrue(!currentFile().exists() || currentFile().readLines().isEmpty())
    }

    @Test
    fun rotationKeepsThePreviousFile() {
        TrackLogWriter.maxBytes = 200L
        TrackLogWriter.open(context, "# session")
        repeat(20) { TrackLogWriter.append(context, "row-$it-padding-padding-padding") }
        TrackLogWriter.close(null)

        assertTrue("previous kept", previousFile().length() > 0)
        assertTrue("current started over", currentFile().readLines().first() == TrackLogFormatter.CSV_HEADER)
    }

    @Test
    fun sizeCoversBothFilesAndClearWipesThem() {
        TrackLogWriter.maxBytes = 200L
        TrackLogWriter.open(context, "# session")
        repeat(20) { TrackLogWriter.append(context, "row-$it-padding-padding-padding") }
        TrackLogWriter.close(null)

        assertEquals(currentFile().length() + previousFile().length(), TrackLogWriter.sizeBytes(context))

        TrackLogWriter.clear(context)
        assertEquals(0L, TrackLogWriter.sizeBytes(context))
    }

    @Test
    fun shareBuildsOneFileWithTheWholeHistory() {
        TrackLogWriter.maxBytes = 200L
        TrackLogWriter.open(context, "# session")
        repeat(20) { TrackLogWriter.append(context, "row-$it-padding-padding-padding") }
        TrackLogWriter.close(null)

        val uri = TrackLogWriter.shareUri(context)
        assertNotNull(uri)
        val shared = File(File(context.cacheDir, "logs"), "oshun-track.csv")
        assertEquals(previousFile().length() + currentFile().length(), shared.length())
        assertTrue(shared.readLines().any { it.startsWith("row-0") })
    }

    @Test
    fun nothingToShareBeforeAnythingIsLogged() {
        assertNull(TrackLogWriter.shareUri(context))
    }
}
