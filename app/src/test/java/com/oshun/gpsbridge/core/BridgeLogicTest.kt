package com.oshun.gpsbridge.core

import com.oshun.gpsbridge.model.Fix
import com.oshun.gpsbridge.net.NmeaTcpServer
import com.oshun.gpsbridge.net.NmeaTransport
import com.oshun.gpsbridge.net.NmeaUdpBroadcaster
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeLogicTest {

    private val started = mutableListOf<NmeaTransport>()

    @After
    fun tearDown() {
        started.forEach { runCatching { it.stop() } }
        started.clear()
    }

    /** Stand-in for a transport with attached consumers, without opening a socket. */
    private fun fakeTransport(running: Boolean, clients: Int) = object : NmeaTransport {
        override val label = "FAKE"
        override val isRunning = running
        override val clientCount = clients
        override fun start() = Unit
        override fun broadcast(lines: List<String>) = Unit
        override fun stop() = Unit
    }

    private val fix = Fix(
        latitude = 10.0,
        longitude = 20.0,
        speedMetersPerSecond = 1.0,
        bearingDegrees = 90.0,
        altitudeMeters = 0.0,
        satellites = 5,
        timeUtcMillis = 0L,
    )

    @Test
    fun buildTransportsForBoth() {
        val t = BridgeLogic.buildTransports(BridgeConfig(port = 2000, tcpEnabled = true, udpEnabled = true))
        assertEquals(2, t.size)
        assertTrue(t.any { it is NmeaTcpServer })
        assertTrue(t.any { it is NmeaUdpBroadcaster })
    }

    @Test
    fun buildTransportsTcpOnly() {
        val t = BridgeLogic.buildTransports(BridgeConfig(tcpEnabled = true, udpEnabled = false))
        assertEquals(1, t.size)
        assertTrue(t.single() is NmeaTcpServer)
    }

    @Test
    fun buildTransportsUdpOnly() {
        val t = BridgeLogic.buildTransports(BridgeConfig(tcpEnabled = false, udpEnabled = true))
        assertEquals(1, t.size)
        assertTrue(t.single() is NmeaUdpBroadcaster)
    }

    @Test
    fun buildTransportsNone() {
        val t = BridgeLogic.buildTransports(BridgeConfig(tcpEnabled = false, udpEnabled = false))
        assertTrue(t.isEmpty())
    }

    @Test
    fun sentencesForProducesRmcAndGga() {
        val lines = BridgeLogic.sentencesFor(fix)
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("\$GPRMC,"))
        assertTrue(lines[1].startsWith("\$GPGGA,"))
    }

    @Test
    fun enabledProtocolsVariants() {
        assertEquals(listOf("TCP", "UDP"), BridgeLogic.enabledProtocols(BridgeStatus(tcpEnabled = true, udpEnabled = true)))
        assertEquals(listOf("TCP"), BridgeLogic.enabledProtocols(BridgeStatus(tcpEnabled = true)))
        assertEquals(listOf("UDP"), BridgeLogic.enabledProtocols(BridgeStatus(udpEnabled = true)))
        assertEquals(emptyList<String>(), BridgeLogic.enabledProtocols(BridgeStatus()))
    }

    @Test
    fun sentencesForCanMarkTheFixInvalid() {
        val valid = BridgeLogic.sentencesFor(fix, valid = true)
        val invalid = BridgeLogic.sentencesFor(fix, valid = false)
        assertTrue(valid[0].contains(",A,"))
        assertTrue(invalid[0].contains(",V,"))
        // GGA quality: 1 = fix, 0 = no fix.
        assertTrue(invalid[1].contains(",0,"))
    }

    @Test
    fun staleWindowIsThreeIntervalsWithAFloor() {
        assertEquals(BridgeLogic.MIN_STALE_MILLIS, BridgeLogic.staleAfterMillis(500L))
        assertEquals(BridgeLogic.MIN_STALE_MILLIS, BridgeLogic.staleAfterMillis(1000L))
        assertEquals(6000L, BridgeLogic.staleAfterMillis(2000L))
        assertEquals(15000L, BridgeLogic.staleAfterMillis(5000L))
    }

    @Test
    fun isStaleAtAndBeyondTheWindow() {
        assertFalse(BridgeLogic.isStale(0L, 1000L))
        assertFalse(BridgeLogic.isStale(4999L, 1000L))
        assertTrue(BridgeLogic.isStale(5000L, 1000L))
        assertTrue(BridgeLogic.isStale(60_000L, 1000L))
        assertFalse(BridgeLogic.isStale(5000L, 5000L)) // longer interval, wider window
    }

    @Test
    fun shouldResendWhenNothingWentOutForAWholeInterval() {
        assertTrue("nothing sent yet", BridgeLogic.shouldResend(10_000L, 0L, 1000L))
        assertTrue(BridgeLogic.shouldResend(11_000L, 10_000L, 1000L))
        assertTrue(BridgeLogic.shouldResend(12_500L, 10_000L, 1000L))
        assertFalse(BridgeLogic.shouldResend(10_500L, 10_000L, 1000L))
    }

    @Test
    fun idleWatchdogOnlyAppliesToTcpOnlyAndWhenEnabled() {
        assertTrue(BridgeLogic.shouldArmIdleOff(BridgeConfig(tcpEnabled = true, udpEnabled = false)))
        // UDP reception is not observable, so "no clients" would mean nothing.
        assertFalse(BridgeLogic.shouldArmIdleOff(BridgeConfig(tcpEnabled = true, udpEnabled = true)))
        assertFalse(BridgeLogic.shouldArmIdleOff(BridgeConfig(tcpEnabled = false, udpEnabled = true)))
        assertFalse(
            BridgeLogic.shouldArmIdleOff(
                BridgeConfig(tcpEnabled = true, udpEnabled = false, autoOffEnabled = false),
            ),
        )
    }

    @Test
    fun hasLiveConsumerNeedsAnAttachedClientOrUdp() {
        assertFalse(BridgeLogic.hasLiveConsumer(emptyList()))
        assertFalse("stopped transports deliver nothing", BridgeLogic.hasLiveConsumer(listOf(fakeTransport(running = false, clients = 3))))
        assertFalse("a TCP server with no client delivers nothing", BridgeLogic.hasLiveConsumer(listOf(fakeTransport(running = true, clients = 0))))
        assertTrue(BridgeLogic.hasLiveConsumer(listOf(fakeTransport(running = true, clients = 1))))

        // UDP is connectionless: once the socket is up, datagrams do leave the phone.
        val udp = NmeaUdpBroadcaster(port = 0).also { started += it }
        assertFalse("not started yet", BridgeLogic.hasLiveConsumer(listOf(udp)))
        udp.start()
        assertTrue(BridgeLogic.hasLiveConsumer(listOf(udp)))
    }

    @Test
    fun ageTokenFormatsSecondsMinutesAndHours() {
        val now = 1_000_000_000L
        assertEquals("0 s", BridgeLogic.ageToken(now, now))
        assertEquals("12 s", BridgeLogic.ageToken(now, now - 12_000L))
        assertEquals("59 s", BridgeLogic.ageToken(now, now - 59_999L))
        assertEquals("1 min 00 s", BridgeLogic.ageToken(now, now - 60_000L))
        assertEquals("3 min 07 s", BridgeLogic.ageToken(now, now - 187_000L))
        assertEquals("1 h 04 min", BridgeLogic.ageToken(now, now - 3_840_000L))
    }

    @Test
    fun ageTokenIsNullWhenTheInstantIsUnknown() {
        assertNull(BridgeLogic.ageToken(1_000L, null))
        assertNull(BridgeLogic.ageToken(1_000L, 0L))
        assertNull(BridgeLogic.ageToken(1_000L, -5L))
    }

    @Test
    fun ageTokenClampsClockSkewToZero() {
        // System.currentTimeMillis() can step backwards; never render a negative age.
        assertEquals("0 s", BridgeLogic.ageToken(1_000L, 5_000L))
    }
}
