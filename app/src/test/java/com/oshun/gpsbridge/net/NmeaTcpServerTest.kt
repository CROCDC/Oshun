package com.oshun.gpsbridge.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.BufferedReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Test

class NmeaTcpServerTest {

    private var server: NmeaTcpServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun connect(port: Int): Socket {
        // Retry briefly: the accept loop starts on its own thread.
        var last: Exception? = null
        repeat(50) {
            try {
                return Socket(InetAddress.getByName("127.0.0.1"), port)
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
        s.onClientsChanged = { callbackCount = it }
        s.start()
        assertTrue(s.isRunning)
        assertEquals("TCP", s.label)

        val client = connect(port)
        awaitClients(s, 1)
        assertEquals(1, callbackCount)

        s.broadcast(listOf("\$GPRMC,test*00\r\n"))
        val reader = BufferedReader(client.getInputStream().reader(Charsets.US_ASCII))
        val line = reader.readLine()
        assertEquals("\$GPRMC,test*00", line)

        client.close()
    }

    @Test
    fun droppedClientIsRemovedOnNextBroadcast() {
        val port = freePort()
        val s = NmeaTcpServer(port).also { server = it }
        s.start()
        val client = connect(port)
        awaitClients(s, 1)

        client.close()
        // First broadcast after close may still "succeed" into the OS buffer;
        // a second one detects the broken pipe and prunes the client.
        repeat(5) { s.broadcast(listOf("x\r\n")); Thread.sleep(20) }
        awaitClients(s, 0)
    }

    @Test
    fun broadcastBeforeStartIsNoop() {
        val s = NmeaTcpServer(freePort()).also { server = it }
        assertFalse(s.isRunning)
        s.broadcast(listOf("ignored\r\n")) // must not throw
        s.broadcast(emptyList())
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
}
