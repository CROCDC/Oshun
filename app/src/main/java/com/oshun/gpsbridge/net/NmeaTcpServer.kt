package com.oshun.gpsbridge.net

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * TCP transport: opens a server socket and streams NMEA sentences to every
 * connected client. In Navionics this is "Paired Devices → protocol TCP", host =
 * the phone's IP, port = [port]. Only one client connects in typical use, but
 * multiple are supported.
 *
 * Each client also gets a reader thread. We never expect input from Navionics — the
 * point is detecting the *end* of the stream: when the tablet goes away, writes keep
 * succeeding into the kernel buffer for a while, so without this the app reports a
 * connected client and rising sentence counts while nothing reaches the tablet.
 */
class NmeaTcpServer(private val port: Int) : NmeaTransport {

    override val label = "TCP"

    @Volatile
    private var serverSocket: ServerSocket? = null
    private val clients = Collections.synchronizedList(mutableListOf<Socket>())
    private var acceptThread: Thread? = null

    @Volatile
    override var isRunning = false
        private set

    override val clientCount: Int
        get() = clients.size

    /** Invoked (off the main thread) whenever the connected-client count changes. */
    @Volatile
    var onClientsChanged: ((Int) -> Unit)? = null

    override fun start() {
        if (isRunning) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        serverSocket = ss
        isRunning = true
        acceptThread = Thread({ acceptLoop(ss) }, "nmea-tcp-accept").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (isRunning) {
            val socket = try {
                ss.accept()
            } catch (e: Exception) {
                if (isRunning) continue else break
            }
            try {
                socket.keepAlive = true
                socket.tcpNoDelay = true
            } catch (_: Exception) {
            }
            clients.add(socket)
            watchClient(socket)
            onClientsChanged?.invoke(clientCount)
        }
    }

    /**
     * Blocks on the client's input until it reports end-of-stream (the tablet closed the
     * connection or the socket broke), then drops the client. This is what makes a dead
     * peer visible right away instead of minutes later, on the write that finally fails.
     */
    private fun watchClient(socket: Socket) {
        Thread({
            try {
                val input = socket.getInputStream()
                while (isRunning && !socket.isClosed) {
                    if (input.read() == -1) break // peer closed; discard anything it sends
                }
            } catch (_: Exception) {
                // Broken connection — same outcome as EOF.
            }
            removeClient(socket)
        }, "nmea-tcp-watch").apply {
            isDaemon = true
            start()
        }
    }

    /** Closes and forgets one client, notifying only when it was still in the list. */
    private fun removeClient(socket: Socket) {
        val wasConnected = synchronized(clients) { clients.remove(socket) }
        try {
            socket.close()
        } catch (_: Exception) {
        }
        if (wasConnected) onClientsChanged?.invoke(clientCount)
    }

    override fun broadcast(lines: List<String>) {
        if (!isRunning || lines.isEmpty()) return
        val payload = lines.joinToString("").toByteArray(Charsets.US_ASCII)
        val broken = mutableListOf<Socket>()
        synchronized(clients) {
            val it = clients.iterator()
            while (it.hasNext()) {
                val s = it.next()
                try {
                    s.getOutputStream().apply {
                        write(payload)
                        flush()
                    }
                } catch (e: Exception) {
                    it.remove()
                    broken += s
                }
            }
        }
        broken.forEach {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        if (broken.isNotEmpty()) onClientsChanged?.invoke(clientCount)
    }

    override fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        synchronized(clients) {
            clients.forEach {
                try {
                    it.close() // also unblocks its reader thread
                } catch (_: Exception) {
                }
            }
            clients.clear()
        }
        serverSocket = null
        onClientsChanged?.invoke(0)
    }
}
