package com.oshun.gpsbridge.net

import com.oshun.gpsbridge.core.AisTraffic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The feed is somebody else's JSON arriving over a socket during a navigation. Every test
 * here is about the same thing: a message we cannot read must be skipped, never thrown, and
 * never turned into a vessel that is not there.
 */
class AisStreamMessagesTest {

    private val now = 1787229296000L

    private val positionReport = """
        {
          "MessageType": "PositionReport",
          "MetaData": {
            "MMSI": 701000123,
            "ShipName": "RIO PARANA  ",
            "latitude": -34.95,
            "longitude": -57.55,
            "time_utc": "2026-08-27 12:34:56.000000000 +0000 UTC"
          },
          "Message": {
            "PositionReport": {
              "UserID": 701000123,
              "Latitude": -34.9512,
              "Longitude": -57.5534,
              "Sog": 12.4,
              "Cog": 118.5,
              "TrueHeading": 119,
              "NavigationalStatus": 0
            }
          }
        }
    """.trimIndent()

    @Test
    fun readsAVesselOutOfAPositionReport() {
        val update = AisStreamMessages.parse(positionReport, now) as AisTraffic.Update.Position
        val target = update.target
        assertEquals(701000123, target.mmsi)
        assertEquals("RIO PARANA", target.name)
        assertEquals(-34.9512, target.latitude, 1e-9)
        assertEquals(-57.5534, target.longitude, 1e-9)
        assertEquals(12.4, target.speedKnots, 1e-9)
        assertEquals(118.5, target.courseDegrees, 1e-9)
        assertEquals(119.0, target.headingDegrees!!, 1e-9)
        assertEquals(0, target.navigationStatus)
        // Stamped with our clock, not the feed's: what matters is when we learned it.
        assertEquals(now, target.reportedAtMillis)
    }

    @Test
    fun honoursTheSentinelsInsteadOfDrawingThem() {
        // 102.3 knots, course 360 and heading 511 are how AIS says "not available". Passed
        // through as numbers they would put a vessel doing a hundred knots due north.
        val json = positionReport
            .replace("\"Sog\": 12.4", "\"Sog\": 102.3")
            .replace("\"Cog\": 118.5", "\"Cog\": 360.0")
            .replace("\"TrueHeading\": 119", "\"TrueHeading\": 511")
        val target = (AisStreamMessages.parse(json, now) as AisTraffic.Update.Position).target
        assertTrue("unknown speed, not a number to draw", target.speedKnots < 0)
        assertEquals(0.0, target.courseDegrees, 1e-9)
        assertNull(target.headingDegrees)
    }

    @Test
    fun readsAClassBReportToo() {
        // Class B is the small craft — the ones actually around you on a Sunday.
        val json = positionReport
            .replace("\"MessageType\": \"PositionReport\"", "\"MessageType\": \"StandardClassBPositionReport\"")
            .replace("\"PositionReport\": {", "\"StandardClassBPositionReport\": {")
        val update = AisStreamMessages.parse(json, now)
        assertTrue(update is AisTraffic.Update.Position)
    }

    @Test
    fun acceptsNumbersThatArriveAsStrings() {
        val json = positionReport
            .replace("\"Sog\": 12.4", "\"Sog\": \"12.4\"")
            .replace("\"UserID\": 701000123", "\"UserID\": \"701000123\"")
        val target = (AisStreamMessages.parse(json, now) as AisTraffic.Update.Position).target
        assertEquals(701000123, target.mmsi)
        assertEquals(12.4, target.speedKnots, 1e-9)
    }

    @Test
    fun fallsBackToTheMetadataPosition() {
        val json = positionReport
            .replace("\"Latitude\": -34.9512,", "")
            .replace("\"Longitude\": -57.5534,", "")
        val target = (AisStreamMessages.parse(json, now) as AisTraffic.Update.Position).target
        assertEquals(-34.95, target.latitude, 1e-9)
        assertEquals(-57.55, target.longitude, 1e-9)
    }

    @Test
    fun aStaticMessageOnlyRenames() {
        val json = """
            {
              "MessageType": "ShipStaticData",
              "MetaData": { "MMSI": 701000123 },
              "Message": { "ShipStaticData": { "UserID": 701000123, "Name": "RIO PARANA" } }
            }
        """.trimIndent()
        val update = AisStreamMessages.parse(json, now) as AisTraffic.Update.Name
        assertEquals(701000123, update.mmsi)
        assertEquals("RIO PARANA", update.name)
    }

    @Test
    fun refusesNullIslandAndImpossibleCoordinates() {
        // 0,0 is what the feed sends when it does not know; drawn literally it is a vessel
        // in the Gulf of Guinea, which is exactly the kind of ghost this must never make.
        val nullIsland = positionReport
            .replace("\"Latitude\": -34.9512", "\"Latitude\": 0.0")
            .replace("\"Longitude\": -57.5534", "\"Longitude\": 0.0")
            .replace("\"latitude\": -34.95", "\"latitude\": 0.0")
            .replace("\"longitude\": -57.55", "\"longitude\": 0.0")
        assertNull(AisStreamMessages.parse(nullIsland, now))
        assertNull(AisStreamMessages.parse(positionReport.replace("-34.9512", "-934.9512"), now))
    }

    @Test
    fun anythingUnreadableIsSkippedRatherThanThrown() {
        assertNull(AisStreamMessages.parse("", now))
        assertNull(AisStreamMessages.parse("not json at all", now))
        assertNull(AisStreamMessages.parse("{}", now))
        assertNull(AisStreamMessages.parse("""{"MessageType":"UnknownThing"}""", now))
        assertNull(AisStreamMessages.parse("""{"Message":{"PositionReport":{"UserID":0}}}""", now))
        // A message with an identity but nothing else to say is not a vessel.
        assertNull(AisStreamMessages.parse("""{"MetaData":{"MMSI":701000123}}""", now))
    }

    @Test
    fun keepsOneMessageReadableForTheLog() {
        // The case this serves: the feed connects, messages arrive, nothing reaches the chart.
        // Then one real message is the only thing that says whether the parser is wrong.
        val sample = AisStreamMessages.sample(positionReport, max = 60)
        assertTrue(sample, sample.startsWith("{ \"MessageType\": \"PositionReport\""))
        assertEquals(60, sample.length)
        assertFalse("no newlines: a log line is one line", sample.contains("\n"))
    }

    @Test
    fun aShortMessageSurvivesWhole() {
        assertEquals("{}", AisStreamMessages.sample("  {}\n ", max = 60))
    }
}
