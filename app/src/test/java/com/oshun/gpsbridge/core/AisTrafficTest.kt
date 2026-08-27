package com.oshun.gpsbridge.core

import com.oshun.gpsbridge.model.AisTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AisTrafficTest {

    private val here = Position(-34.95, -57.55)
    private val now = 1787229296000L

    private fun target(
        mmsi: Int,
        name: String = "",
        bearing: Double = 0.0,
        distanceNm: Double = 1.0,
        at: Long = now,
    ): AisTarget {
        val position = Geo.destination(here, bearing, distanceNm)
        return AisTarget(
            mmsi = mmsi,
            name = name,
            latitude = position.latitude,
            longitude = position.longitude,
            speedKnots = 8.0,
            courseDegrees = bearing,
            reportedAtMillis = at,
        )
    }

    @Test
    fun aReportThatAgedOutStopsBeingDrawn() {
        // The whole point: a target nobody has heard from is removed, not redrawn where it
        // was. A ghost on the chart is worse than an empty one.
        val known = mapOf(1 to target(1, at = now - AisTraffic.MAX_AGE_MILLIS - 1))
        assertTrue(AisTraffic.fresh(known, now).isEmpty())
        assertTrue(AisTraffic.visible(known, here, now).isEmpty())
    }

    @Test
    fun aFreshReportSurvives() {
        val known = mapOf(1 to target(1, at = now - AisTraffic.MAX_AGE_MILLIS + 1_000))
        assertEquals(1, AisTraffic.visible(known, here, now).size)
    }

    @Test
    fun aNewerReportReplacesTheOlderOne() {
        val old = target(1, distanceNm = 1.0, at = now - 60_000)
        val new = target(1, distanceNm = 2.0)
        val known = AisTraffic.merge(mapOf(1 to old), AisTraffic.Update.Position(new), now)
        assertEquals(1, known.size)
        assertEquals(new.latitude, known.getValue(1).latitude, 1e-12)
    }

    @Test
    fun theNameSurvivesAPositionReportThatDoesNotCarryOne() {
        // Position reports have no name; losing it every few seconds would leave the chart
        // full of anonymous triangles.
        val named = AisTraffic.merge(emptyMap(), AisTraffic.Update.Position(target(1, name = "RIO PARANA")), now)
        val moved = AisTraffic.merge(named, AisTraffic.Update.Position(target(1, distanceNm = 3.0)), now)
        assertEquals("RIO PARANA", moved.getValue(1).name)
    }

    @Test
    fun aStaticMessageNamesAVesselWeAlreadyKnow() {
        val known = AisTraffic.merge(emptyMap(), AisTraffic.Update.Position(target(1)), now)
        val renamed = AisTraffic.merge(known, AisTraffic.Update.Name(1, "SARANDI"), now)
        assertEquals("SARANDI", renamed.getValue(1).name)
    }

    @Test
    fun aNameForAVesselWeHaveNeverSeenIsNotAVessel() {
        // Without a position there is nothing to draw, and inventing one is the failure mode.
        val known = AisTraffic.merge(emptyMap(), AisTraffic.Update.Name(99, "FANTASMA"), now)
        assertTrue(known.isEmpty())
    }

    @Test
    fun everyUpdateAlsoForgetsWhatAgedOut() {
        val stale = mapOf(1 to target(1, at = now - AisTraffic.MAX_AGE_MILLIS - 1))
        val known = AisTraffic.merge(stale, AisTraffic.Update.Position(target(2)), now)
        assertFalse("the stale one is gone", known.containsKey(1))
        assertTrue(known.containsKey(2))
    }

    @Test
    fun whatIsFarAwayIsNotWorthDrawing() {
        val known = mapOf(
            1 to target(1, distanceNm = AisTraffic.RANGE_NAUTICAL_MILES + 0.5),
            2 to target(2, distanceNm = 2.0),
        )
        assertEquals(listOf(2), AisTraffic.visible(known, here, now).map { it.mmsi })
    }

    @Test
    fun theNearestOnesComeFirstAndTheListIsCapped() {
        val known = (1..AisTraffic.MAX_TARGETS + 10).associateWith { mmsi ->
            target(mmsi, bearing = (mmsi % 360).toDouble(), distanceNm = mmsi * 0.1)
        }
        val visible = AisTraffic.visible(known, here, now)
        assertEquals(AisTraffic.MAX_TARGETS, visible.size)
        assertEquals(1, visible.first().mmsi)
        assertTrue("sorted by distance", visible.last().mmsi <= AisTraffic.MAX_TARGETS)
    }

    @Test
    fun withoutOurOwnPositionThereIsNoNearOrFar() {
        // Before the first fix the feed's bounding box is all the filtering there is.
        val known = mapOf(1 to target(1, distanceNm = 50.0))
        assertEquals(1, AisTraffic.visible(known, from = null, nowMillis = now).size)
    }
}
