package com.oshun.gpsbridge.ais

import com.oshun.gpsbridge.core.AisTraffic
import com.oshun.gpsbridge.core.Geo
import com.oshun.gpsbridge.core.Position
import com.oshun.gpsbridge.model.AisTarget
import com.oshun.gpsbridge.net.AisStreamMessages
import com.oshun.gpsbridge.net.AisSubscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The feed against the real aisstream.io, with a real key — the one question the mocked
 * tests cannot answer: is what actually comes down that socket what we believe we are
 * parsing?
 *
 * Kept out of every ordinary run, because it needs the network and a credential:
 *
 *     ./gradlew :app:testDebugUnitTest -PliveAis --tests '*AisStreamLiveTest*'
 *
 * The key comes from `local.properties` (`aisstream.apiKey=...`), which is gitignored; with
 * no key the class skips rather than fails. `-PaisLat` / `-PaisLon` point the capture at other
 * water — the Canal Mitre, say (`-PaisLat=-34.30 -PaisLon=-58.37`) — where the question stops
 * being "does the parser work" and becomes "is there coverage where we sail".
 *
 * One capture for the whole class: the assertions are all about the same handful of seconds
 * of traffic, and a socket per test would be six more connections for the feed to carry.
 * It hangs off the first test rather than `@BeforeClass` because Robolectric runs class
 * setup outside its sandbox, where org.json is the stub that answers null to everything.
 */
@RunWith(RobolectricTestRunner::class)
class AisStreamLiveTest {

    @Before
    fun captureOnce() {
        val key = LocalAisKey.value
        assumeTrue("no aisstream key in local.properties, so the live feed is not tested", key != null)
        if (capturedAtMillis == 0L) capture(key!!)
    }

    @Test
    fun theFeedAcceptsOurKey() {
        assertTrue("the socket never opened; drops: $drops", connected)
        // A rejected key does not close the socket: the feed answers in a text frame and goes.
        val refusals = raw.mapNotNull { AisStreamMessages.errorOf(it) }.distinct()
        assertTrue("the feed refused us: $refusals", refusals.isEmpty())
    }

    @Test
    fun messagesActuallyArrive() {
        assertTrue(
            "nothing came down the socket in ${CAPTURE_MILLIS / 1000}s at $centre; drops: $drops",
            raw.isNotEmpty(),
        )
    }

    @Test
    fun everyPositionReportBecomesATarget() {
        val reports = raw.filter { messageTypeOf(it) in POSITION_TYPES }
        assertTrue("no position report arrived at $centre", reports.isNotEmpty())
        // A report the feed itself cannot place is dropped on purpose, so only the ones
        // carrying a real position say anything about the parser.
        val unread = reports
            .filter { hasUsablePosition(it) }
            .filter { AisStreamMessages.parse(it, capturedAtMillis) !is AisTraffic.Update.Position }
        assertEquals(
            "position reports the parser could not read (${unread.size} of ${reports.size})",
            emptyList<String>(),
            unread.take(3).map { AisStreamMessages.sample(it, 300) },
        )
    }

    @Test
    fun targetsLandInsideTheBoxWeAskedFor() {
        val box = AisSubscription.boundingBox(centre)
        val (south, west) = box[0]
        val (north, east) = box[1]
        val strays = targets.filter {
            it.latitude < south - BOX_TOLERANCE || it.latitude > north + BOX_TOLERANCE ||
                it.longitude < west - BOX_TOLERANCE || it.longitude > east + BOX_TOLERANCE
        }
        assertEquals(
            "vessels outside the box we subscribed to",
            emptyList<String>(),
            strays.take(3).map { "${it.mmsi} at ${it.latitude},${it.longitude}" },
        )
    }

    @Test
    fun theValuesAreWithinAisRanges() {
        assertTrue("no target to check", targets.isNotEmpty())
        targets.forEach { target ->
            val who = "$target"
            assertTrue("impossible mmsi: $who", target.mmsi in 1..999_999_999)
            assertTrue("off the earth: $who", target.latitude >= -90.0 && target.latitude <= 90.0)
            assertTrue("off the earth: $who", target.longitude >= -180.0 && target.longitude <= 180.0)
            // -1 is our own "the vessel did not say"; 102.3 and up is the AIS sentinel, which
            // must never have survived as a number to draw.
            assertTrue("sentinel speed forwarded: $who", target.speedKnots == -1.0 || target.speedKnots < 102.3)
            assertTrue("negative speed: $who", target.speedKnots >= -1.0)
            assertTrue(
                "course off the compass: $who",
                target.courseDegrees >= 0.0 && target.courseDegrees < 360.0,
            )
            target.headingDegrees?.let {
                assertTrue("heading off the compass: $who", it >= 0.0 && it < 360.0)
            }
            assertTrue("nav status outside 0..15: $who", target.navigationStatus in 0..15)
        }
    }

    @Test
    fun theVesselsCarryTheirNames() {
        // Names travel apart from positions, and losing them leaves a chart of anonymous
        // triangles — so "the data arrived" includes knowing who is out there.
        val named = targets.count { it.name.isNotBlank() }
        assertTrue("not one of ${targets.size} reports came with a name", named > 0)
    }

    @Test
    fun theChartGetsTargetsOutOfIt() {
        val visible = AisTraffic.visible(known(), centre, capturedAtMillis)
        assertTrue("nothing to draw out of ${updates.size} updates", visible.isNotEmpty())
        val distances = visible.map { Geo.distanceNauticalMiles(centre, Position(it.latitude, it.longitude)) }
        assertEquals("the chart is not sorted nearest first", distances.sorted(), distances)
        assertTrue(
            "something beyond our range got through",
            distances.all { it <= AisTraffic.RANGE_NAUTICAL_MILES },
        )
    }

    private companion object {

        /**
         * The Maas approaches off Rotterdam. These assertions are about the parser, not about
         * any one stretch of water being busy, so the default capture happens where traffic is
         * guaranteed and "nothing arrived" means a broken feed rather than a quiet Sunday.
         */
        val BUSY_WATER = Position(51.95, 4.05)

        const val CAPTURE_MILLIS = 45_000L
        const val ENOUGH_POSITIONS = 60

        /** The feed filters by the box server-side; this only absorbs rounding at the edges. */
        const val BOX_TOLERANCE = 0.05

        val POSITION_TYPES = setOf(
            "PositionReport",
            "StandardClassBPositionReport",
            "ExtendedClassBPositionReport",
        )

        val centre = Position(
            System.getProperty("aisLive.lat")?.toDoubleOrNull() ?: BUSY_WATER.latitude,
            System.getProperty("aisLive.lon")?.toDoubleOrNull() ?: BUSY_WATER.longitude,
        )

        val raw = CopyOnWriteArrayList<String>()
        val updates = CopyOnWriteArrayList<AisTraffic.Update>()
        val drops = CopyOnWriteArrayList<String>()
        val positionCount = AtomicInteger()

        @Volatile var connected = false

        /** Also the "already captured" flag: nothing is asserted before it is set. */
        @Volatile var capturedAtMillis = 0L

        val targets: List<AisTarget>
            get() = updates.filterIsInstance<AisTraffic.Update.Position>().map { it.target }

        fun capture(key: String) {
            // Another test in this module points the feed at a server of its own; make sure
            // we are talking to aisstream and not to whatever ran before us.
            AisSubscription.url = AisSubscription.DEFAULT_URL
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val feed = AisStreamFeed(
                apiKey = key,
                scope = scope,
                onUpdate = {
                    updates += it
                    if (it is AisTraffic.Update.Position) positionCount.incrementAndGet()
                },
                onConnected = { open, detail -> if (open) connected = true else drops += detail },
                onRaw = { raw += it },
            )

            feed.start()
            feed.onOwnPosition(centre)
            val deadline = System.currentTimeMillis() + CAPTURE_MILLIS
            while (System.currentTimeMillis() < deadline && positionCount.get() < ENOUGH_POSITIONS) {
                Thread.sleep(250)
            }
            capturedAtMillis = System.currentTimeMillis()
            feed.stop()
            scope.cancel()
            report()
        }

        fun known(): Map<Int, AisTarget> {
            var known = emptyMap<Int, AisTarget>()
            updates.forEach { known = AisTraffic.merge(known, it, capturedAtMillis) }
            return known
        }

        /**
         * What actually arrived, printed rather than asserted. The assertions can only fail on
         * what we already thought to ask; this is where a message type we drop in silence, or
         * a field the feed stopped sending, becomes visible.
         */
        fun report() {
            val byType = raw.groupingBy { messageTypeOf(it) ?: "(no MessageType)" }.eachCount()
            val chart = AisTraffic.visible(known(), centre, capturedAtMillis)
            val lines = mutableListOf(
                "",
                "── live aisstream capture ────────────────────────────────────",
                "  centre             ${centre.latitude}, ${centre.longitude}",
                "  window             ${CAPTURE_MILLIS / 1000}s, or $ENOUGH_POSITIONS positions",
                "  raw messages       ${raw.size}",
                "  parsed             ${updates.size} updates: ${positionCount.get()} positions, " +
                    "${updates.size - positionCount.get()} names",
                "  distinct vessels   ${known().size}",
                "  named              ${targets.count { it.name.isNotBlank() }} of ${targets.size} reports",
                "  within 12 NM       ${chart.size}",
                "  drops              ${drops.take(3)}",
                "  by message type    $byType",
                "  not modelled       ${byType.filterKeys { it !in POSITION_TYPES && it != "ShipStaticData" }}",
                "  nearest targets",
            )
            chart.take(6).forEach { lines += "    ${line(it)}" }
            lines += "  a raw message      ${raw.firstOrNull()?.let { AisStreamMessages.sample(it, 220) }}"
            lines += "──────────────────────────────────────────────────────────────"
            println(lines.joinToString("\n"))
        }

        fun line(target: AisTarget): String {
            val distance = Geo.distanceNauticalMiles(centre, Position(target.latitude, target.longitude))
            return "%-22s %9d  %6.2f NM  %5.1f kn  %5.1f deg".format(
                target.name.ifBlank { "(sin nombre)" },
                target.mmsi,
                distance,
                target.speedKnots,
                target.courseDegrees,
            )
        }

        fun messageTypeOf(json: String): String? = try {
            JSONObject(json).optString("MessageType").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }

        /** True when the report carries a position the feed itself considers known. */
        fun hasUsablePosition(json: String): Boolean = try {
            val message = JSONObject(json).getJSONObject("Message")
            val payload = POSITION_TYPES.firstNotNullOfOrNull { message.optJSONObject(it) }
            val latitude = payload?.optDouble("Latitude") ?: Double.NaN
            val longitude = payload?.optDouble("Longitude") ?: Double.NaN
            !latitude.isNaN() && !longitude.isNaN() &&
                latitude >= -90.0 && latitude <= 90.0 &&
                longitude >= -180.0 && longitude <= 180.0 &&
                !(latitude == 0.0 && longitude == 0.0)
        } catch (e: Exception) {
            false
        }
    }
}
