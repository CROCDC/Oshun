package com.oshun.gpsbridge.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one decision that takes the download button off the screen — so the case that matters
 * most here is the one where it must not: anything short of a published build we can see is
 * no newer than ours leaves the download offered.
 */
class UpdateStatusTest {

    private fun apk(build: Int?) = Apk("https://example.test/oshun.apk", build)

    @Test
    fun aHigherPublishedBuildIsAnUpdate() {
        assertEquals(
            UpdateStatus.Available(74, "https://example.test/oshun.apk"),
            UpdateStatus.of(apk(74), installed = 73),
        )
    }

    @Test
    fun theBuildYouAreRunningIsNotAnUpdate() {
        assertEquals(UpdateStatus.UpToDate, UpdateStatus.of(apk(74), installed = 74))
    }

    @Test
    fun neitherIsAnOlderOne() {
        // A local build carries versionCode 1 and CI numbers climb, so this is really the
        // seam after a rollback: still nothing to download.
        assertEquals(UpdateStatus.UpToDate, UpdateStatus.of(apk(73), installed = 74))
    }

    @Test
    fun anApkWeCannotDateIsStillOffered() {
        assertEquals(
            UpdateStatus.Available(null, "https://example.test/oshun.apk"),
            UpdateStatus.of(apk(null), installed = 74),
        )
    }

    @Test
    fun noAnswerIsNotAnUpToDatePhone() {
        assertEquals(UpdateStatus.Unknown, UpdateStatus.of(null, installed = 74))
    }
}
