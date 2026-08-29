package com.oshun.gpsbridge.net

import com.oshun.gpsbridge.core.AisTraffic
import com.oshun.gpsbridge.core.Geo
import com.oshun.gpsbridge.core.Position
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AisSubscriptionTest {

    private val here = Position(-34.95, -57.55)

    @Test
    fun theBoxCoversFurtherThanWeDraw() {
        // Vessels have to be known before they come within range, or they appear out of
        // nothing at twelve miles.
        val (southWest, northEast) = AisSubscription.boundingBox(here)
        val reach = Geo.distanceNauticalMiles(here, Position(northEast[0], here.longitude))
        assertTrue("reaches past the drawing range: $reach", reach > AisTraffic.RANGE_NAUTICAL_MILES)
        assertTrue("south is south of north", southWest[0] < northEast[0])
        assertTrue("west is west of east", southWest[1] < northEast[1])
        assertTrue("we are inside our own box", here.latitude in southWest[0]..northEast[0])
        assertTrue(here.longitude in southWest[1]..northEast[1])
    }

    @Test
    fun theMessageIsTheShapeTheFeedExpects() {
        val json = JSONObject(AisSubscription.message("test-key-not-a-real-one", here))
        assertEquals("test-key-not-a-real-one", json.getString("APIKey"))
        val corners = json.getJSONArray("BoundingBoxes").getJSONArray(0)
        assertEquals("a box is two corners", 2, corners.length())
        assertEquals(2, corners.getJSONArray(0).length())
        assertEquals(
            AisSubscription.boundingBox(here)[0][0],
            corners.getJSONArray(0).getDouble(0),
            1e-9,
        )
    }

    @Test
    fun theBoxFollowsTheBoat() {
        // Sailing out of your own subscription looks exactly like a feed that died.
        assertTrue("nothing subscribed yet", AisSubscription.shouldResubscribe(null, here))
        val nearby = Geo.destination(here, 90.0, AisSubscription.RESUBSCRIBE_AFTER_NAUTICAL_MILES - 1)
        assertFalse(AisSubscription.shouldResubscribe(here, nearby))
        val faraway = Geo.destination(here, 90.0, AisSubscription.RESUBSCRIBE_AFTER_NAUTICAL_MILES + 1)
        assertTrue(AisSubscription.shouldResubscribe(here, faraway))
    }
}
