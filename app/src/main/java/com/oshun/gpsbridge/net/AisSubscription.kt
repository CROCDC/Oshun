package com.oshun.gpsbridge.net

import com.oshun.gpsbridge.core.AisTraffic
import com.oshun.gpsbridge.core.Geo
import com.oshun.gpsbridge.core.Position
import org.json.JSONArray
import org.json.JSONObject

/**
 * What we ask the AIS feed for: the box of water around the boat.
 *
 * The box travels with us. Asking for a fixed rectangle would mean sailing out of your own
 * subscription — the targets would simply stop arriving, with nothing on screen to say why —
 * so the subscription is renewed once the boat has moved far enough from the centre of the
 * one it asked for.
 *
 * Pure, so the geometry is a unit test rather than something you find out at sea.
 */
object AisSubscription {

    const val DEFAULT_URL = "wss://stream.aisstream.io/v0/stream"

    /**
     * Overridable so a test can point the feed at a server it runs itself. That is the only
     * honest way to exercise this end to end: a live third-party service would need a real
     * key in CI, and a key in CI is a key published.
     */
    var url: String = DEFAULT_URL

    /**
     * How far past the range we actually draw the box reaches. Vessels are then already
     * known by the time they come within range, instead of appearing out of nothing.
     */
    const val MARGIN_NAUTICAL_MILES = 6.0

    /** How far the boat may move before the box is asked for again. */
    const val RESUBSCRIBE_AFTER_NAUTICAL_MILES = 5.0

    private val radiusNauticalMiles = AisTraffic.RANGE_NAUTICAL_MILES + MARGIN_NAUTICAL_MILES

    /** The box as the feed wants it: south-west corner first, then north-east. */
    fun boundingBox(centre: Position): List<List<Double>> {
        val south = Geo.destination(centre, 180.0, radiusNauticalMiles).latitude
        val north = Geo.destination(centre, 0.0, radiusNauticalMiles).latitude
        val west = Geo.destination(centre, 270.0, radiusNauticalMiles).longitude
        val east = Geo.destination(centre, 90.0, radiusNauticalMiles).longitude
        return listOf(listOf(south, west), listOf(north, east))
    }

    /** The subscription message. The key goes on the wire and nowhere else. */
    fun message(apiKey: String, centre: Position): String {
        val box = boundingBox(centre)
        val corners = JSONArray().put(JSONArray(box[0])).put(JSONArray(box[1]))
        return JSONObject()
            .put("APIKey", apiKey)
            .put("BoundingBoxes", JSONArray().put(corners))
            .toString()
    }

    /** True once the boat has left the middle of the box it subscribed to. */
    fun shouldResubscribe(subscribedCentre: Position?, now: Position): Boolean =
        subscribedCentre == null ||
            Geo.distanceNauticalMiles(subscribedCentre, now) >= RESUBSCRIBE_AFTER_NAUTICAL_MILES
}
