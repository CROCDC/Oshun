package com.oshun.gpsbridge.net

import com.oshun.gpsbridge.core.AisTraffic
import com.oshun.gpsbridge.model.AisTarget
import org.json.JSONObject

/**
 * Reads what aisstream.io sends down its WebSocket and turns it into something the bridge
 * can put on the wire.
 *
 * Written defensively on purpose. The feed is somebody else's JSON: it carries several
 * message types, the same value sometimes appears in two places, and a field can be missing
 * or a string where a number was expected. None of that may throw here — a message we cannot
 * read is a message we skip, not a bridge that dies mid-navigation.
 *
 * The AIS sentinels are honoured rather than passed through: a speed of 102.3 knots, a course
 * of 360° and a heading of 511 all mean "the vessel did not say", and forwarding them as
 * numbers would draw a ship doing a hundred knots due north.
 */
object AisStreamMessages {

    /** The payload keys that carry a position, across the class A and class B report types. */
    private val POSITION_PAYLOADS = listOf(
        "PositionReport",
        "StandardClassBPositionReport",
        "ExtendedClassBPositionReport",
    )

    private const val SPEED_UNAVAILABLE = 102.3
    private const val COURSE_UNAVAILABLE = 360.0
    private const val HEADING_UNAVAILABLE = 511.0

    /**
     * The feed's own complaint, when it sends one.
     *
     * It refuses in a text frame before closing — `{"error": "Api Key Is Not Valid"}` — and
     * that sentence is the entire diagnosis. Read as a vessel report it is simply unreadable
     * and would be dropped in silence.
     */
    fun errorOf(json: String): String? = try {
        JSONObject(json).optString("error").takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    /**
     * A message cut down to something a log line can hold.
     *
     * This exists for the case that cannot be debugged any other way: the feed connects,
     * messages arrive, and none of them becomes a vessel. Then the only useful thing in the
     * world is one real message to read — the shape of somebody else's JSON is not something
     * you can guess twice.
     */
    fun sample(json: String, max: Int = 180): String =
        json.replace(Regex("\\s+"), " ").trim().take(max)

    /** One message from the feed, or null when it says nothing we can use. */
    fun parse(json: String, nowMillis: Long): AisTraffic.Update? = try {
        read(JSONObject(json), nowMillis)
    } catch (e: Exception) {
        null // malformed, truncated, or a message type we do not model
    }

    private fun read(root: JSONObject, nowMillis: Long): AisTraffic.Update? {
        val metadata = root.optJSONObject("MetaData")
        val message = root.optJSONObject("Message")
        val mmsi = mmsiOf(root, metadata, message) ?: return null

        val position = POSITION_PAYLOADS.firstNotNullOfOrNull { message?.optJSONObject(it) }
        if (position != null) {
            val latitude = number(position, "Latitude") ?: number(metadata, "latitude") ?: return null
            val longitude = number(position, "Longitude") ?: number(metadata, "longitude") ?: return null
            if (!isPlausible(latitude, longitude)) return null
            return AisTraffic.Update.Position(
                AisTarget(
                    mmsi = mmsi,
                    name = nameOf(metadata),
                    latitude = latitude,
                    longitude = longitude,
                    speedKnots = speed(number(position, "Sog")),
                    courseDegrees = course(number(position, "Cog")),
                    headingDegrees = heading(number(position, "TrueHeading")),
                    navigationStatus = number(position, "NavigationalStatus")?.toInt() ?: 15,
                    reportedAtMillis = nowMillis,
                ),
            )
        }

        // Static data: no position, but this is where a vessel says what it is called.
        val static = message?.optJSONObject("ShipStaticData")
        val name = text(static, "Name") ?: nameOf(metadata)
        return if (name.isBlank()) null else AisTraffic.Update.Name(mmsi, name)
    }

    /** The identity travels in the payload, in the metadata, and sometimes as a string. */
    private fun mmsiOf(root: JSONObject, metadata: JSONObject?, message: JSONObject?): Int? {
        val payload = POSITION_PAYLOADS.firstNotNullOfOrNull { message?.optJSONObject(it) }
            ?: message?.optJSONObject("ShipStaticData")
        val candidate = number(payload, "UserID")
            ?: number(metadata, "MMSI")
            ?: text(metadata, "MMSI_String")?.toIntOrNull()?.toDouble()
            ?: number(root, "MMSI")
        val mmsi = candidate?.toInt() ?: return null
        return mmsi.takeIf { it > 0 }
    }

    private fun nameOf(metadata: JSONObject?): String = text(metadata, "ShipName").orEmpty()

    /** Accepts a number written as a number or as a string, which this feed does both of. */
    private fun number(json: JSONObject?, key: String): Double? {
        if (json == null || !json.has(key) || json.isNull(key)) return null
        val value = json.opt(key)
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }
    }

    private fun text(json: JSONObject?, key: String): String? {
        if (json == null || !json.has(key) || json.isNull(key)) return null
        return json.opt(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun isPlausible(latitude: Double, longitude: Double): Boolean =
        latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            !(latitude == 0.0 && longitude == 0.0) // null island: the feed's own "unknown"

    private fun speed(sog: Double?): Double =
        if (sog == null || sog < 0 || sog >= SPEED_UNAVAILABLE) -1.0 else sog

    private fun course(cog: Double?): Double =
        if (cog == null || cog < 0 || cog >= COURSE_UNAVAILABLE) 0.0 else cog

    private fun heading(trueHeading: Double?): Double? =
        if (trueHeading == null || trueHeading < 0 || trueHeading >= HEADING_UNAVAILABLE) null
        else trueHeading
}
