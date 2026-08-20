package com.oshun.gpsbridge.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class NmeaTcpServerTest {

    private var server: NmeaTcpServer? = null
    private val sockets = mutableListOf<Socket>()

    @After
    fun tearDown() {
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        server?.stop()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun connect(port: Int, receiveBufferBytes: Int? = null): Socket {
        // Retry briefly: the accept loop starts on its own thread.
        var last: Exception? = null
        repeat(50) {
            try {
                val socket = Socket()
                receiveBufferBytes?.let { size -> socket.receiveBufferSize = size }
                socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                sockets += socket
                return socket
            } catch (e: Exception) {
                last = e
                Thread.sleep(20)
            }
        }
        throw last!!
    }

    private fun awaitClients(s: NmeaTcpServer, expected: Int) {
        repeat(100) {
            if (s.clientCount == expected) return
            Thread.sleep(20)
        }
        assertEquals(expected, s.clientCount)
    }

    @Test
    fun deliversSentencesToConnectedClient() {
        val port = freePort()
        val s = NmeaTcpServer(port).also { server = it }
        var callbackCount = -1
        var connectedRemote: String? = null
        s.onClientsChanged = { callbackCount = it }
        s.onClientEvent = { connected, remote -> if (connected) connectedRemote = remote }
        s.start()
        assertTrue(s.isRunning)
        assertEquals("TCP", s.label)

        val client = connect(port)
        awaitClients(s, 1)
        assertEquals(1, callbackCount)
        assertEquals("127.0.0.1", connectedRemote)

        val result = s.broadcast(listOf("\$GPRMC,test*00\r\n"), nowMillis = 1_000L)
        assertEquals(1, result.accepted)
        assertEquals(1, result.clients)
        assertEquals(0, result.stalled)
        assertFalse(result.blind)

        val reader = BufferedReader(client.getInputStream().reader(Charsets.US_ASCII))
        assertEquals("\$GPRMC,test*00", reader.readLine())
    }

    @Test
    fun clientThatWentAwayIsReportedAsDroppedOnTheNextSend() {
        val port = freePort()
        val s = NmeaTcpServer(port).also { server = it }
        val leaving = mutableListOf<String>()
        s.onClientEvent = { connected, remote -> if (!connected) leaving += remote }
        s.start()

        val client = connect(port)
        awaitClients(s, 1)
        client.close()

        // The peer's FIN is seen when we next look at the socket, not minutes later on a
        // write that finally fails.
        var result = s.broadcast(listOf("x\r\n"), nowMillis = 1_000L)
        repeat(10) {
            if (result.dropped == 0) {
                Thread.sleep(20)
                result = s.broadcast(listOf("x\r\n"), nowMillis = 1_000L)
            }
        }
        assertEquals(1, result.dropped)
        assertEquals(0, s.clientCount)
        assertEquals(listOf("127.0.0.1"), leaving)
    }

    @Test
    fun clientThatNeverReadsIsReportedAsStalled() {
        // The failure that started all of this: bytes leave the app, the socket stays open,
        // and nothing is consuming them. A blocking write hid it; a non-blocking one names it.
        val port = freePort()
        val s = NmeaTcpServer(port, sendBufferBytes = 4096).also { server = it }
        s.start()

        connect(port, receiveBufferBytes = 4096) // connected, and deliberately never reads
        awaitClients(s, 1)

        val big = listOf("x".repeat(32 * 1024) + "\r\n")
        var result = s.broadcast(big, nowMillis = 1_000L)
        repeat(20) {
            if (result.stalled == 0) result = s.broadcast(big, nowMillis = 1_000L)
        }

        assertEquals("client is backed up", 1, result.stalled)
        assertEquals(0, result.accepted)
        assertEquals(1, s.clientCount) // still connected: stalled is not the same as gone
    }

    @Test
    fun hopelesslyStalledClientIsCutSoItCanReconnect() {
        val port = freePort()
        val s = NmeaTcpServer(port, dropAfterStalledMillis = 100L, sendBufferBytes = 4096)
            .also { server = it }
        s.start()

        connect(port, receiveBufferBytes = 4096)
        awaitClients(s, 1)

        val big = listOf("x".repeat(32 * 1024) + "\r\n")
        var result = s.broadcast(big, nowMillis = 1_000L)
        repeat(20) {
            if (result.stalled == 0) result = s.broadcast(big, nowMillis = 1_000L)
        }
        assertEquals(1, result.stalled)

        // Same backlog, well past the patience window.
        val afterTimeout = s.broadcast(big, nowMillis = 1_000L + 500L)
        assertEquals(1, afterTimeout.dropped)
        assertEquals(0, s.clientCount)
    }

    @Test
    fun broadcastBeforeStartIsDown() {
        val s = NmeaTcpServer(freePort()).also { server = it }
        assertFalse(s.isRunning)
        val result = s.broadcast(listOf("ignored\r\n"), nowMillis = 0L)
        assertTrue(result.down)
        assertEquals(0, result.accepted)
    }

    @Test
    fun emptyBatchIsANoOp() {
        val port = freePort()
        val s = NmeaTcpServer(port).also { server = it }
        s.start()
        val result = s.broadcast(emptyList(), nowMillis = 0L)
        assertEquals(0, result.accepted)
        assertFalse(result.down)
    }

    @Test
    fun doubleStartAndDoubleStopAreSafe() {
        val port = freePort()
        val s = NmeaTcpServer(port).also { server = it }
        s.start()
        s.start() // no-op second start
        assertTrue(s.isRunning)
        s.stop()
        assertFalse(s.isRunning)
        s.stop() // no-op second stop
        assertEquals(0, s.clientCount)
    }

    @Test
    fun stopDropsConnectedClients() {
        val port = freePort()
        val s = NmeaTcpServer(port).also { server = it }
        val leaving = mutableListOf<String>()
        s.onClientEvent = { connected, remote -> if (!connected) leaving += remote }
        s.start()
        val client = connect(port)
        awaitClients(s, 1)

        s.stop()
        assertEquals(0, s.clientCount)
        assertEquals(listOf("127.0.0.1"), leaving)
        // The client sees the connection close rather than hanging on a dead socket.
        assertEquals(-1, client.getInputStream().read())
    }
}
