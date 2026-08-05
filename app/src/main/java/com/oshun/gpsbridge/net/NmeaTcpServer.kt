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
            onClientsChanged?.invoke(clientCount)
        }
    }

    override fun broadcast(lines: List<String>) {
        if (!isRunning || lines.isEmpty()) return
        val payload = lines.joinToString("").toByteArray(Charsets.US_ASCII)
        var removedAny = false
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
                    try {
                        s.close()
                    } catch (_: Exception) {
                    }
                    it.remove()
                    removedAny = true
                }
            }
        }
        if (removedAny) onClientsChanged?.invoke(clientCount)
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
                    it.close()
                } catch (_: Exception) {
                }
            }
            clients.clear()
        }
        serverSocket = null
        onClientsChanged?.invoke(0)
    }
}
