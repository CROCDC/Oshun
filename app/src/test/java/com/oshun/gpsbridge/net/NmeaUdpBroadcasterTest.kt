package com.oshun.gpsbridge.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import org.junit.Test

class NmeaUdpBroadcasterTest {

    private var receiver: DatagramSocket? = null
    private var udp: NmeaUdpBroadcaster? = null

    @After
    fun tearDown() {
        udp?.stop()
        receiver?.close()
    }

    @Test
    fun sendsDatagramToTarget() {
        // Bind a receiver on loopback and aim the broadcaster at it.
        val rx = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).also { receiver = it }
        rx.soTimeout = 2000
        val port = rx.localPort

        val b = NmeaUdpBroadcaster(port, broadcastAddress = "127.0.0.1").also { udp = it }
        b.start()
        assertTrue(b.isRunning)
        assertEquals("UDP", b.label)
        assertEquals(0, b.clientCount)

        val result = b.broadcast(listOf("\$GPGGA,udp*00\r\n"), nowMillis = 1_000L)
        // UDP can never say more than "it left": there is no acknowledgement to wait for.
        assertTrue(result.blind)
        assertEquals(0, result.clients)
        assertFalse(result.down)

        val buf = ByteArray(256)
        val packet = DatagramPacket(buf, buf.size)
        rx.receive(packet)
        val received = String(packet.data, 0, packet.length, Charsets.US_ASCII)
        assertEquals("\$GPGGA,udp*00\r\n", received)
    }

    @Test
    fun broadcastBeforeStartIsNoop() {
        val b = NmeaUdpBroadcaster(9999).also { udp = it }
        assertFalse(b.isRunning)
        val result = b.broadcast(listOf("ignored\r\n"), nowMillis = 0L) // socket is null
        assertTrue(result.down)
    }

    @Test
    fun emptyLinesAreNotSent() {
        val b = NmeaUdpBroadcaster(9999, broadcastAddress = "127.0.0.1").also { udp = it }
        b.start()
        b.broadcast(emptyList(), nowMillis = 0L) // no-op
    }

    @Test
    fun doubleStartAndDoubleStopAreSafe() {
        val b = NmeaUdpBroadcaster(9999, broadcastAddress = "127.0.0.1").also { udp = it }
        b.start()
        b.start()
        assertTrue(b.isRunning)
        b.stop()
        assertFalse(b.isRunning)
        b.stop()
    }
}
