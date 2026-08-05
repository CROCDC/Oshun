package com.oshun.gpsbridge.core

import com.oshun.gpsbridge.model.Fix
import com.oshun.gpsbridge.net.NmeaTcpServer
import com.oshun.gpsbridge.net.NmeaUdpBroadcaster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeLogicTest {

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
}
