package com.oshun.gpsbridge.core

import com.oshun.gpsbridge.model.AisTarget
import com.oshun.gpsbridge.model.Fix
import com.oshun.gpsbridge.net.NmeaTcpServer
import com.oshun.gpsbridge.net.NmeaUdpBroadcaster
import com.oshun.gpsbridge.net.SendResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeLogicTest {

    private val tcp = SendResult(label = "TCP")
    private val udp = SendResult(label = "UDP", blind = true)

    @After
    fun tearDown() = Unit

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
    fun outcomeIsOkWhenSomeoneTookTheBytes() {
        assertEquals(
            DeliveryOutcome.OK,
            BridgeLogic.outcomeFor(listOf(tcp.copy(clients = 1, accepted = 1))),
        )
        // One healthy client is enough, even next to a stalled one.
        assertEquals(
            DeliveryOutcome.OK,
            BridgeLogic.outcomeFor(listOf(tcp.copy(clients = 2, accepted = 1, stalled = 1))),
        )
    }

    @Test
    fun outcomeNamesTheMostActionableProblem() {
        assertEquals(
            "backed up beats a blind UDP send",
            DeliveryOutcome.STALLED,
            BridgeLogic.outcomeFor(listOf(tcp.copy(clients = 1, stalled = 1), udp)),
        )
        assertEquals(
            DeliveryOutcome.DROPPED,
            BridgeLogic.outcomeFor(listOf(tcp.copy(clients = 1, dropped = 1), udp)),
        )
        assertEquals(DeliveryOutcome.BLIND, BridgeLogic.outcomeFor(listOf(tcp, udp)))
        assertEquals(DeliveryOutcome.NO_CLIENT, BridgeLogic.outcomeFor(listOf(tcp)))
    }

    @Test
    fun outcomeIsNotSentWithoutAWorkingTransport() {
        assertEquals(DeliveryOutcome.NOT_SENT, BridgeLogic.outcomeFor(emptyList()))
        assertEquals(
            DeliveryOutcome.NOT_SENT,
            BridgeLogic.outcomeFor(listOf(tcp.copy(down = true), udp.copy(blind = false, down = true))),
        )
    }

    @Test
    fun onlyDeliveredAndBlindEverLeaveThePhone() {
        assertTrue(BridgeLogic.leftThePhone(DeliveryOutcome.OK))
        assertTrue(BridgeLogic.leftThePhone(DeliveryOutcome.BLIND))
        assertFalse(BridgeLogic.leftThePhone(DeliveryOutcome.NO_CLIENT))
        assertFalse(BridgeLogic.leftThePhone(DeliveryOutcome.STALLED))
        assertFalse(BridgeLogic.leftThePhone(DeliveryOutcome.DROPPED))
        assertFalse(BridgeLogic.leftThePhone(DeliveryOutcome.NOT_SENT))
    }

    @Test
    fun logColumnsSummariseTheTransports() {
        assertEquals("TCP+UDP", BridgeLogic.transportsToken(listOf(tcp, udp)))
        assertEquals("none", BridgeLogic.transportsToken(emptyList()))
        assertEquals(3, BridgeLogic.clientTotal(listOf(tcp.copy(clients = 3), udp)))
        assertEquals(0, BridgeLogic.clientTotal(emptyList()))
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

    @Test
    fun aisPositionsGoOutOnTheirOwnSlowerClock() {
        // Repeating every target with every fix would triple the traffic to say nothing new.
        val now = 100_000L
        assertTrue("nothing sent yet", BridgeLogic.shouldEmitAgain(now, 0L, BridgeLogic.AIS_POSITION_INTERVAL_MILLIS))
        assertFalse(BridgeLogic.shouldEmitAgain(now, now - 1_000L, BridgeLogic.AIS_POSITION_INTERVAL_MILLIS))
        assertTrue(
            BridgeLogic.shouldEmitAgain(now, now - BridgeLogic.AIS_POSITION_INTERVAL_MILLIS, BridgeLogic.AIS_POSITION_INTERVAL_MILLIS),
        )
        assertTrue(
            "names go out far more rarely than positions",
            BridgeLogic.AIS_STATIC_INTERVAL_MILLIS > BridgeLogic.AIS_POSITION_INTERVAL_MILLIS,
        )
    }

    @Test
    fun aBatchCarriesTheNamesOnlyWhenTheyAreDue() {
        val target = AisTarget(
            mmsi = 701999001,
            name = "TEST CARGO",
            latitude = -34.9,
            longitude = -57.4,
            speedKnots = 10.0,
            courseDegrees = 90.0,
        )
        val withNames = BridgeLogic.aisSentencesFor(listOf(target), NOW, withNames = true)
        val positionsOnly = BridgeLogic.aisSentencesFor(listOf(target), NOW, withNames = false)
        assertEquals(2, withNames.size)
        assertEquals(1, positionsOnly.size)
        assertTrue(positionsOnly.single().startsWith("!AIVDM"))
        assertTrue(BridgeLogic.aisSentencesFor(emptyList(), NOW, withNames = true).isEmpty())
    }

    private companion object {
        const val NOW = 1787229296000L
    }
}
