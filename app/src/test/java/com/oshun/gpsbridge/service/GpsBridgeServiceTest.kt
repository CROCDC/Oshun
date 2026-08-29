package com.oshun.gpsbridge.service

import android.content.Intent
import android.os.Looper
import com.oshun.gpsbridge.core.BridgeConfig
import com.oshun.gpsbridge.core.BridgeState
import com.oshun.gpsbridge.core.DeliveryOutcome
import com.oshun.gpsbridge.core.EventKind
import com.oshun.gpsbridge.core.EventLog
import com.oshun.gpsbridge.core.StopReason
import com.oshun.gpsbridge.location.FixProvider
import com.oshun.gpsbridge.location.LocationSource
import com.oshun.gpsbridge.model.Fix
import com.oshun.gpsbridge.net.AisSubscription
import com.oshun.gpsbridge.store.AisKeyStore
import com.oshun.gpsbridge.store.ConfigStore
import com.oshun.gpsbridge.store.TrackLogWriter
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GpsBridgeServiceTest {

    // Track controllers so each test's service (and its coroutine scope) is destroyed,
    // keeping tests hermetic — no leaked coroutines mutating the shared BridgeState.
    private val controllers = mutableListOf<ServiceController<GpsBridgeService>>()
    private val defaultAutoOffMillis = GpsBridgeService.autoOffMillis

    /** The fake aisstream, for the tests that need the real feed path without the network. */
    private var aisServer: MockWebServer? = null
    private var feedSocket: WebSocket? = null

    @Before
    fun setUp() {
        feedSocket = null
        BridgeState.reset()
        EventLog.clear()
        TrackLogWriter.clear(RuntimeEnvironment.getApplication())
        ConfigStore.saveStopReason(RuntimeEnvironment.getApplication(), null)
    }

    @After
    fun tearDown() {
        controllers.forEach { runCatching { it.destroy() } }
        controllers.clear()
        // The idle watchdog posts its shutdown to the main looper. Robolectric runs those
        // only when idled, so drain them here — otherwise a stale one lands mid-test and
        // resets the process-wide BridgeState under the next test's feet.
        shadowOf(Looper.getMainLooper()).idle()
        GpsBridgeService.fixProviderFactory = { LocationSource(it) }
        GpsBridgeService.autoOffMillis = defaultAutoOffMillis
        ConfigStore.saveStopReason(RuntimeEnvironment.getApplication(), null)
        TrackLogWriter.close(null)
        TrackLogWriter.clear(RuntimeEnvironment.getApplication())
        EventLog.clear()
        BridgeState.reset()
        AisSubscription.url = AisSubscription.DEFAULT_URL
        aisServer?.let { runCatching { it.shutdown() } }
        aisServer = null
        feedSocket = null
        AisKeyStore.save(RuntimeEnvironment.getApplication(), "")
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private val sampleFix = Fix(
        latitude = 48.1173,
        longitude = 11.5167,
        speedMetersPerSecond = 5.0,
        bearingDegrees = 90.0,
        altitudeMeters = 100.0,
        satellites = 8,
        timeUtcMillis = 0L,
    )

    private fun fakeProvider(fix: Fix) = object : FixProvider {
        override fun fixes(intervalMillis: Long): Flow<Fix> = flow {
            while (true) {
                emit(fix)
                delay(100)
            }
        }
    }

    /** One fix and then silence — the GPS going quiet, which is what the heartbeat is for. */
    private fun singleFixProvider(fix: Fix) = object : FixProvider {
        override fun fixes(intervalMillis: Long): Flow<Fix> = flow {
            emit(fix)
            awaitCancellation()
        }
    }

    private fun startIntent(
        port: Int,
        tcp: Boolean,
        udp: Boolean,
        autoOff: Boolean = true,
        rawLog: Boolean = true,
        simulated: Boolean = false,
        ais: Boolean = false,
    ): Intent = Intent(RuntimeEnvironment.getApplication(), GpsBridgeService::class.java).apply {
        putExtra(GpsBridgeService.EXTRA_PORT, port)
        putExtra(GpsBridgeService.EXTRA_TCP, tcp)
        putExtra(GpsBridgeService.EXTRA_UDP, udp)
        putExtra(GpsBridgeService.EXTRA_INTERVAL, 100L)
        putExtra(GpsBridgeService.EXTRA_AUTO_OFF, autoOff)
        putExtra(GpsBridgeService.EXTRA_RAW_LOG, rawLog)
        putExtra(GpsBridgeService.EXTRA_SIMULATED, simulated)
        putExtra(GpsBridgeService.EXTRA_AIS, ais)
    }

    private fun startService(
        port: Int,
        tcp: Boolean,
        udp: Boolean,
        autoOff: Boolean = true,
        rawLog: Boolean = true,
        simulated: Boolean = false,
        ais: Boolean = false,
    ): GpsBridgeService = startWith(startIntent(port, tcp, udp, autoOff, rawLog, simulated, ais))

    /** In the river, so the fake feed's vessel is inside the range we actually transmit. */
    private val riverFix = Fix(
        latitude = -34.95,
        longitude = -57.55,
        speedMetersPerSecond = 3.0,
        bearingDegrees = 45.0,
        altitudeMeters = 2.0,
        satellites = 9,
        timeUtcMillis = 0L,
    )

    private fun awaitFeedSocket() {
        repeat(200) {
            if (feedSocket != null) return
            Thread.sleep(20)
        }
        assertTrue("the feed never opened its socket", feedSocket != null)
    }

    private fun awaitAisTargets(expected: Int) {
        repeat(200) {
            if (BridgeState.status.value.aisTargets == expected) return
            Thread.sleep(20)
        }
        assertEquals(expected, BridgeState.status.value.aisTargets)
    }

    private fun trackFile(): File = File(File(RuntimeEnvironment.getApplication().filesDir, "logs"), "track.csv")

    /**
     * The client count is published by the accept thread just after the socket joins the
     * list, so a sentence can reach the client a hair before the state catches up. Wait for
     * it rather than racing it.
     */
    private fun awaitTcpClients(expected: Int) {
        repeat(100) {
            if (BridgeState.status.value.tcpClients == expected) return
            Thread.sleep(20)
        }
        assertEquals(expected, BridgeState.status.value.tcpClients)
    }

    /** Polls the process-wide log, which several threads write to. */
    private fun awaitEvent(kind: EventKind, outcome: DeliveryOutcome? = null) {
        repeat(200) {
            val hit = EventLog.events.value.any {
                it.kind == kind && (outcome == null || it.outcome == outcome)
            }
            if (hit) return
            Thread.sleep(20)
        }
        assertTrue(
            "expected a $kind${outcome?.let { " ($it)" } ?: ""} event, got " +
                EventLog.events.value.map { "${it.kind}/${it.outcome}" },
            false,
        )
    }

    private fun startWith(intent: Intent): GpsBridgeService {
        val controller = Robolectric.buildService(GpsBridgeService::class.java, intent)
            .create()
            .startCommand(0, 1)
        controllers += controller
        return controller.get()
    }

    private fun stop(service: GpsBridgeService) {
        service.onStartCommand(
            Intent(RuntimeEnvironment.getApplication(), GpsBridgeService::class.java)
                .setAction(GpsBridgeService.ACTION_STOP),
            0, 2,
        )
    }

    /**
     * Polls for a running state to avoid depending on background-coroutine timing, draining
     * the main looper so work the service posts there (the idle shutdown) actually runs.
     */
    private fun awaitRunning(expected: Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (BridgeState.status.value.running == expected) return
            Thread.sleep(20)
        }
        assertTrue("expected running=$expected", BridgeState.status.value.running == expected)
    }

    @Test
    fun startBroadcastsNmeaOverTcpThenStops() {
        val port = freePort()
        GpsBridgeService.fixProviderFactory = { fakeProvider(sampleFix) }

        val service = startService(port, tcp = true, udp = true)

        awaitRunning(true)
        assertTrue("tcp enabled", BridgeState.status.value.tcpEnabled)

        val client = connect(port)
        val line = BufferedReader(client.getInputStream().reader(Charsets.US_ASCII)).readLine()
        assertTrue("got an NMEA sentence: $line", line.startsWith("\$GP"))
        client.close()

        stop(service)
        awaitRunning(false)
    }

    @Test
    fun udpOnlyConfigStartsWithoutTcp() {
        val port = freePort()
        GpsBridgeService.fixProviderFactory = { fakeProvider(sampleFix) }

        val service = startService(port, tcp = false, udp = true)

        awaitRunning(true)
        assertFalse("tcp disabled", BridgeState.status.value.tcpEnabled)
        assertTrue("udp enabled", BridgeState.status.value.udpEnabled)

        stop(service)
        awaitRunning(false)
    }

    @Test
    fun heartbeatKeepsSendingAfterTheGpsGoesQuiet() {
        // The bug this covers: with no new fixes the stream went silent and Navionics kept
        // showing the last position with no way to tell the source had died.
        val port = freePort()
        GpsBridgeService.fixProviderFactory = { singleFixProvider(sampleFix) }

        val service = startService(port, tcp = true, udp = false)
        val client = connect(port)
        val reader = BufferedReader(client.getInputStream().reader(Charsets.US_ASCII))

        // Only one fix was ever produced, so everything past the first pair is a resend.
        repeat(6) {
            val line = reader.readLine()
            assertTrue("NMEA sentence: $line", line != null && line.startsWith("\$GP"))
        }
        assertTrue("heartbeat counted", BridgeState.status.value.heartbeatsSent > 0)
        assertTrue("delivery timestamp recorded", (BridgeState.status.value.lastSendOkAtMillis ?: 0L) > 0L)
        assertTrue("fix timestamp recorded", (BridgeState.status.value.lastFixAtMillis ?: 0L) > 0L)

        client.close()
        stop(service)
        awaitRunning(false)
    }

    @Test
    fun startingAgainWithADifferentConfigSwitchesToIt() {
        // Used to be ignored outright: the UI showed the new port while the bridge kept
        // serving the old one.
        val portA = freePort()
        val portB = freePort()
        GpsBridgeService.fixProviderFactory = { fakeProvider(sampleFix) }

        val service = startService(portA, tcp = true, udp = false)
        assertEquals(portA, BridgeState.status.value.port)

        service.onStartCommand(startIntent(portB, tcp = true, udp = false), 0, 2)
        assertEquals(portB, BridgeState.status.value.port)

        val client = connect(portB)
        val line = BufferedReader(client.getInputStream().reader(Charsets.US_ASCII)).readLine()
        assertTrue("serving the new port: $line", line.startsWith("\$GP"))
        client.close()

        stop(service)
        awaitRunning(false)
    }

    @Test
    fun startingAgainWithTheSameConfigIsANoOp() {
        val port = freePort()
        GpsBridgeService.fixProviderFactory = { fakeProvider(sampleFix) }

        val service = startService(port, tcp = true, udp = false)
        val client = connect(port)

        // Same config: the session must not be torn down, so the client stays connected.
        service.onStartCommand(startIntent(port, tcp = true, udp = false), 0, 2)

        val line = BufferedReader(client.getInputStream().reader(Charsets.US_ASCII)).readLine()
        assertTrue(line.startsWith("\$GP"))
        client.close()

        stop(service)
        awaitRunning(false)
    }

    @Test
    fun restartWithoutExtrasUsesTheStoredConfig() {
        // What START_STICKY does after the system kills the service: no extras, so the
        // service used to silently fall back to the hardcoded defaults.
        val port = freePort()
        val context = RuntimeEnvironment.getApplication()
        ConfigStore.save(
            context,
            BridgeConfig(port = port, tcpEnabled = true, udpEnabled = false, intervalMillis = 100L),
        )
        GpsBridgeService.fixProviderFactory = { fakeProvider(sampleFix) }

        val service = startWith(Intent(context, GpsBridgeService::class.java))

        assertEquals(port, BridgeState.status.value.port)
        assertTrue(BridgeState.status.value.tcpEnabled)
        assertFalse(BridgeState.status.value.udpEnabled)

        val client = connect(port)
        val line = BufferedReader(client.getInputStream().reader(Charsets.US_ASCII)).readLine()
        assertTrue(line.startsWith("\$GP"))
        client.close()

        stop(service)
        awaitRunning(false)
    }

    @Test
    fun startingStoresTheConfigAndClearsTheStopReason() {
        val port = freePort()
        val context = RuntimeEnvironment.getApplication()
        ConfigStore.saveStopReason(context, StopReason.IDLE_TIMEOUT)
        GpsBridgeService.fixProviderFactory = { fakeProvider(sampleFix) }

        val service = startService(port, tcp = true, udp = false, autoOff = false)

        assertEquals(port, ConfigStore.load(context).port)
        assertFalse(ConfigStore.load(context).autoOffEnabled)
        assertNull(ConfigStore.readStopReason(context))

        stop(service)
        awaitRunning(false)
        // A user stop is not something to explain afterwards.
        assertNull(ConfigStore.readStopReason(context))
    }

    @Test
    fun idleWatchdogStopsTheBridgeAndRecordsWhy() {
        GpsBridgeService.autoOffMillis = 300L
        val port = freePort()
        GpsBridgeService.fixProviderFactory = { fakeProvider(sampleFix) }

        startService(port, tcp = true, udp = false) // TCP-only, nobody ever connects

        awaitRunning(false)
        assertEquals(
            StopReason.IDLE_TIMEOUT,
            ConfigStore.readStopReason(RuntimeEnvironment.getApplication()),
        )
    }

    @Test
    fun idleWatchdogIsNotArmedWhenTheUserTurnedItOff() {
        GpsBridgeService.autoOffMillis = 200L
        val port = freePort()
        GpsBridgeService.fixProviderFactory = { fakeProvider(sampleFix) }

        val service = startService(port, tcp = true, udp = false, autoOff = false)

        repeat(30) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue("still transmitting", BridgeState.status.value.running)
        assertFalse(BridgeState.status.value.autoOffEnabled)

        stop(service)
        awaitRunning(false)
    }

    @Test
    fun idleWatchdogDoesNotApplyWhenUdpIsOn() {
        // UDP reception is not observable, so "no TCP clients" says nothing about delivery.
        GpsBridgeService.autoOffMillis = 200L
        val port = freePort()
        GpsBridgeService.fixProviderFactory = { fakeProvider(sampleFix) }

        val service = startService(port, tcp = true, udp = true)

        repeat(30) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue("still transmitting", BridgeState.status.value.running)

        stop(service)
        awaitRunning(false)
    }

    @Test
    fun aConnectingClientGetsThePositionWithoutWaitingForTheNextFix() {
        val port = freePort()
        GpsBridgeService.fixProviderFactory = { singleFixProvider(sampleFix) }

        val service = startService(port, tcp = true, udp = false)
        // The only fix was emitted before this client existed.
        val client = connect(port)
        val line = BufferedReader(client.getInputStream().reader(Charsets.US_ASCII)).readLine()
        assertTrue("got the last known position: $line", line.startsWith("\$GP"))
        awaitTcpClients(1)

        client.close()
        stop(service)
        awaitRunning(false)
    }

    @Test
    fun aSessionIsRecordedAsEventsAndAsCsvRows() {
        val port = freePort()
        GpsBridgeService.fixProviderFactory = { fakeProvider(sampleFix) }

        val service = startService(port, tcp = true, udp = false)
        awaitEvent(EventKind.SESSION_START)
        // Nobody is attached yet, so the honest answer is "it never left the phone".
        awaitEvent(EventKind.DELIVERY, DeliveryOutcome.NO_CLIENT)

        val client = connect(port)
        BufferedReader(client.getInputStream().reader(Charsets.US_ASCII)).readLine()
        awaitEvent(EventKind.CLIENT_CONNECTED)
        awaitEvent(EventKind.DELIVERY, DeliveryOutcome.OK)

        client.close()
        stop(service)
        awaitRunning(false)
        awaitEvent(EventKind.SESSION_STOP)

        val lines = trackFile().readLines()
        assertTrue("csv has a header", lines.first().startsWith("utc,"))
        assertTrue("csv has a session line", lines.any { it.startsWith("# session ") })
        assertTrue("csv recorded a delivery", lines.any { it.endsWith(",OK") })
        assertTrue("csv recorded the silence too", lines.any { it.endsWith(",NO_CLIENT") })
        assertTrue("csv is closed with a reason", lines.any { it.contains("reason=USER") })
    }

    @Test
    fun theCsvCanBeTurnedOffWithoutLosingTheEventLog() {
        val port = freePort()
        GpsBridgeService.fixProviderFactory = { fakeProvider(sampleFix) }

        val service = startService(port, tcp = true, udp = false, rawLog = false)
        awaitEvent(EventKind.SESSION_START)
        awaitEvent(EventKind.DELIVERY, DeliveryOutcome.NO_CLIENT)

        assertFalse("no CSV was written", trackFile().exists() && trackFile().length() > 0)

        stop(service)
        awaitRunning(false)
    }

    @Test
    fun testModeAlsoTransmitsTheSimulatedAisTraffic() {
        // A plotter takes other vessels on the same stream as your own position, so the proof
        // that has to exist is the one over a real socket: AIVDM lines interleaved with the
        // GPRMC, positions and names both.
        val port = freePort()
        GpsBridgeService.fixProviderFactory = {
            throw IllegalStateException("test mode must not touch the phone's GPS")
        }

        val service = startService(port, tcp = true, udp = false, simulated = true)
        awaitRunning(true)

        val client = connect(port)
        val reader = BufferedReader(client.getInputStream().reader(Charsets.US_ASCII))
        val ais = buildList {
            repeat(MAX_LINES_READ) {
                val line = reader.readLine() ?: return@repeat
                if (line.startsWith("!AIVDM")) add(line)
                if (size >= 4) return@buildList
            }
        }

        val payloads = ais.map { it.split(",")[5] }
        // '1' is a class A position report and 'H' a static message: the first six bits of the
        // payload are the message type, so the type is legible without decoding the rest.
        assertEquals("two vessels reporting their position", 2, payloads.count { it.startsWith("1") })
        assertEquals("two vessels naming themselves", 2, payloads.count { it.startsWith("H") })
        assertEquals("the same two targets the status card counts", 2, BridgeState.status.value.aisTargets)

        client.close()
        stop(service)
        awaitRunning(false)
    }

    @Test
    fun everySendCarriesTheTrafficAlongWithTheFix() {
        // A plotter that hears the boat move without the vessels around it draws them a step
        // behind: the traffic goes out in the same send as the position, not on its own clock.
        val port = freePort()
        GpsBridgeService.fixProviderFactory = {
            throw IllegalStateException("test mode must not touch the phone's GPS")
        }

        val service = startService(port, tcp = true, udp = false, simulated = true)
        awaitRunning(true)

        val client = connect(port)
        val reader = BufferedReader(client.getInputStream().reader(Charsets.US_ASCII))
        val lines = buildList {
            repeat(MAX_LINES_READ) { add(reader.readLine() ?: return@repeat) }
        }

        // Every batch opens with the GPRMC, so what follows one belongs to that same send.
        val sends = mutableListOf<MutableList<String>>()
        lines.forEach { line ->
            if (line.startsWith("\$GPRMC")) sends.add(mutableListOf())
            sends.lastOrNull()?.add(line)
        }

        // The last one was cut off mid-send by the read limit; the rest are whole.
        val whole = sends.dropLast(1)
        assertTrue("too few sends to tell a cadence from a coincidence: $lines", whole.size >= 3)
        whole.forEach { send ->
            assertEquals(
                "a send went out without the traffic: $send",
                2,
                send.count { it.startsWith("!AIVDM") && it.split(",")[5].startsWith("1") },
            )
        }

        client.close()
        stop(service)
        awaitRunning(false)
    }

    @Test
    fun aConnectingClientDoesNotSilenceTheAisFeed() {
        // The bug this guards: tearing the feed down lived in cancelIdleOff(), which runs when
        // a client CONNECTS. So the moment Navionics attached, the feed was closed and the
        // traffic wiped, and every batch after that carried our own position and not one
        // vessel — for the rest of the session. The plotter was right to say it had no AIS.
        val port = freePort()
        GpsBridgeService.fixProviderFactory = { fakeProvider(riverFix) }
        AisKeyStore.save(RuntimeEnvironment.getApplication(), "clave-de-prueba")
        aisServer = MockWebServer().apply { start() }
        AisSubscription.url = aisServer!!.url("/stream").toString().replace("http://", "ws://")
        aisServer!!.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                        feedSocket = webSocket
                    }
                },
            ),
        )

        // autoOff on and TCP only: exactly the configuration that armed the watchdog, which
        // is what made a connecting client run the teardown.
        val service = startService(port, tcp = true, udp = false, autoOff = true, ais = true)
        awaitRunning(true)

        // The feed opens on the first fix, so wait for the socket before feeding it a vessel.
        awaitFeedSocket()
        feedSocket!!.send(RIVER_VESSEL)
        awaitAisTargets(1)

        // Only now the plotter shows up — the moment that used to kill it.
        val client = connect(port)
        awaitTcpClients(1)

        val reader = BufferedReader(client.getInputStream().reader(Charsets.US_ASCII))
        var vessels = 0
        repeat(MAX_LINES_READ) {
            val line = reader.readLine() ?: return@repeat
            if (line.startsWith("!AIVDM")) vessels++
        }
        assertTrue("the client connected and the traffic stopped", vessels > 0)
        assertEquals("and the count must still be telling the truth", 1, BridgeState.status.value.aisTargets)

        client.close()
        stop(service)
        awaitRunning(false)
    }

    @Test
    fun aDeadFeedStopsClaimingItHasVessels() {
        // The count froze at its last value when the feed went away, so a status card reading
        // "12 targets" sat over a stream carrying none. A number that cannot go back down is
        // worse than no number: it is the one thing that looked healthy while nothing was.
        val port = freePort()
        GpsBridgeService.fixProviderFactory = { fakeProvider(riverFix) }
        // No key, so the feed never opens: that is the state whose batches used to return
        // early and leave the count exactly as they found it.
        AisKeyStore.save(RuntimeEnvironment.getApplication(), "")
        val service = startService(port, tcp = true, udp = false, ais = true)
        awaitRunning(true)

        // A count a live feed had published, moments before it went away. The next batch has
        // to correct it; the old code walked past it for the rest of the session.
        BridgeState.update { it.copy(aisTargets = 12) }
        awaitAisTargets(0)

        stop(service)
        awaitRunning(false)
    }

    @Test
    fun aRealNavigationCarriesNoSimulatedTraffic() {
        // Inventing vessels on a live chart is the one failure this feature could cause, so
        // the guard gets its own test rather than being a line nobody exercises.
        val port = freePort()
        GpsBridgeService.fixProviderFactory = { fakeProvider(sampleFix) }

        val service = startService(port, tcp = true, udp = false)
        awaitRunning(true)

        val client = connect(port)
        val reader = BufferedReader(client.getInputStream().reader(Charsets.US_ASCII))
        repeat(MAX_LINES_READ) {
            val line = reader.readLine() ?: return@repeat
            assertFalse("a simulated vessel reached a real session: $line", line.startsWith("!AIVDM"))
        }
        assertEquals(0, BridgeState.status.value.aisTargets)

        client.close()
        stop(service)
        awaitRunning(false)
    }

    @Test
    fun testModeTransmitsTheSimulatedRiverTrack() {
        // The whole point of test mode: exercise the real path to Navionics from dry land.
        val port = freePort()
        GpsBridgeService.fixProviderFactory = {
            throw IllegalStateException("test mode must not touch the phone's GPS")
        }

        val service = startService(port, tcp = true, udp = false, simulated = true)
        awaitRunning(true)
        assertTrue("the session is marked as simulated", BridgeState.status.value.simulated)
        awaitEvent(EventKind.SIMULATION)

        val client = connect(port)
        val line = BufferedReader(client.getInputStream().reader(Charsets.US_ASCII)).readLine()
        val fields = line.split(",")

        assertEquals("\$GPRMC", fields[0])
        assertEquals("A", fields[2])
        // 34°57'S 057°33'W — the first waypoint, mid Río de la Plata.
        assertTrue("latitude ${fields[3]}", fields[3].startsWith("3457."))
        assertEquals("S", fields[4])
        assertTrue("longitude ${fields[5]}", fields[5].startsWith("05733."))
        assertEquals("W", fields[6])
        assertEquals("speed in knots", 4.0, fields[7].toDouble(), 0.1)
        assertEquals("course", 120.0, fields[8].toDouble(), 1.0)

        client.close()
        stop(service)
        awaitRunning(false)

        assertTrue(
            "the CSV says the track was simulated",
            trackFile().readLines().any { it.contains("source=simulated") },
        )
    }

    /** Enough sentences to cover several emissions without hanging if the stream goes quiet. */
    private val MAX_LINES_READ = 40

    /** One vessel, a mile or so from [riverFix], in the shape aisstream actually sends. */
    private val RIVER_VESSEL = """
        {"MessageType":"PositionReport",
         "MetaData":{"MMSI":701000123,"ShipName":"RIO PARANA"},
         "Message":{"PositionReport":{"UserID":701000123,"Latitude":-34.96,"Longitude":-57.56,
                    "Sog":11.0,"Cog":120.0,"TrueHeading":121,"NavigationalStatus":0}}}
    """.trimIndent()

    private fun connect(port: Int): Socket {
        var last: Exception? = null
        repeat(100) {
            try {
                return Socket(InetAddress.getByName("127.0.0.1"), port).apply { soTimeout = 5000 }
            } catch (e: Exception) {
                last = e
                Thread.sleep(20)
            }
        }
        throw last!!
    }
}
