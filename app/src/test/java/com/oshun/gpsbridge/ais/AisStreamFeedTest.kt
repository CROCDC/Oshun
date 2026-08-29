package com.oshun.gpsbridge.ais

import com.oshun.gpsbridge.core.AisTraffic
import com.oshun.gpsbridge.core.Position
import com.oshun.gpsbridge.net.AisSubscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The feed, exercised end to end against a WebSocket server this test runs itself.
 *
 * That is the point. Testing this against aisstream would need a real API key in CI, and a key
 * in CI is a key published — so instead the server is ours: it accepts the connection, reads
 * the subscription we sent, replies with a canned message and closes when the test says so.
 * No secret, no internet, and deterministic.
 *
 * Robolectric, because the subscription is built with org.json, which a plain unit test in this
 * module gets as a stub that returns null from everything.
 */
@RunWith(RobolectricTestRunner::class)
class AisStreamFeedTest {

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val here = Position(-34.95, -57.55)

    /** What the server received from us. */
    private val subscriptions = LinkedBlockingQueue<String>()

    private var socket: WebSocket? = null

    @Before
    fun setUp() {
        server.start()
        AisSubscription.url = server.url("/stream").toString().replace("http://", "ws://")
    }

    @After
    fun tearDown() {
        AisSubscription.url = AisSubscription.DEFAULT_URL
        scope.cancel()
        runCatching { server.shutdown() }
    }

    /** Accepts the socket, remembers what we sent, and hands the server side to the test. */
    private fun acceptConnection() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                        socket = webSocket
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        subscriptions.put(text)
                    }
                },
            ),
        )
    }

    private fun feed(
        onUpdate: (AisTraffic.Update) -> Unit = {},
        onConnected: (Boolean, String) -> Unit = { _, _ -> },
        onRaw: (String) -> Unit = {},
    ) = AisStreamFeed("clave-de-prueba", scope, onUpdate, onConnected, onRaw)

    private fun <T> LinkedBlockingQueue<T>.await(): T? = poll(10, TimeUnit.SECONDS)

    @Test
    fun subscribesWithTheKeyAndABoxAroundUs() {
        acceptConnection()
        val bridge = feed()
        bridge.start()
        bridge.onOwnPosition(here)

        val sent = subscriptions.await()
        assertNotNull("the feed never subscribed", sent)
        val json = JSONObject(sent!!)
        assertEquals("clave-de-prueba", json.getString("APIKey"))

        val corners = json.getJSONArray("BoundingBoxes").getJSONArray(0)
        val southWest = corners.getJSONArray(0)
        val northEast = corners.getJSONArray(1)
        assertTrue("we are inside the box we asked for", here.latitude > southWest.getDouble(0))
        assertTrue(here.latitude < northEast.getDouble(0))
        assertTrue(here.longitude > southWest.getDouble(1))
        assertTrue(here.longitude < northEast.getDouble(1))

        bridge.stop()
    }

    @Test
    fun aVesselFromTheServerBecomesATarget() {
        acceptConnection()
        val targets = LinkedBlockingQueue<AisTraffic.Update>()
        val raw = LinkedBlockingQueue<String>()
        val bridge = feed(onUpdate = { targets.put(it) }, onRaw = { raw.put(it) })
        bridge.start()
        bridge.onOwnPosition(here)
        assertNotNull(subscriptions.await())

        socket!!.send(
            """
            {"MessageType":"PositionReport",
             "MetaData":{"MMSI":701000123,"ShipName":"RIO PARANA"},
             "Message":{"PositionReport":{"UserID":701000123,"Latitude":-34.96,"Longitude":-57.56,
                        "Sog":11.0,"Cog":120.0,"TrueHeading":121,"NavigationalStatus":0}}}
            """.trimIndent(),
        )

        assertNotNull("the message never reached us raw", raw.await())
        val update = targets.await() as AisTraffic.Update.Position
        assertEquals(701000123, update.target.mmsi)
        assertEquals("RIO PARANA", update.target.name)

        bridge.stop()
    }

    @Test
    fun aServerThatHangsUpSaysWhy() {
        // The failure that sent us here: the socket opens, the server refuses and closes, and
        // without its reason a rejected key is indistinguishable from empty water.
        acceptConnection()
        val drops = LinkedBlockingQueue<String>()
        val bridge = feed(onConnected = { connected, detail -> if (!connected) drops.put(detail) })
        bridge.start()
        bridge.onOwnPosition(here)
        assertNotNull(subscriptions.await())

        socket!!.close(1008, "invalid api key")

        val reason = drops.await()
        assertNotNull("the drop was not reported", reason)
        assertTrue(reason!!, reason.contains("1008"))
        assertTrue(reason, reason.contains("invalid api key"))

        bridge.stop()
    }
}
