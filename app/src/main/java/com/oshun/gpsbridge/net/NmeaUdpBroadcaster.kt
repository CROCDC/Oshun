package com.oshun.gpsbridge.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * UDP transport: sends each NMEA sentence as a broadcast datagram on [port]. In
 * Navionics this is "Paired Devices → protocol UDP", port = [port]. UDP is
 * connectionless, so [clientCount] is always 0; any device on the subnet that
 * listens on the port receives the data (Navionics uses UDP port 2000 by default).
 */
class NmeaUdpBroadcaster(
    private val port: Int,
    private val broadcastAddress: String = "255.255.255.255",
) : NmeaTransport {

    override val label = "UDP"

    @Volatile
    private var socket: DatagramSocket? = null
    private var target: InetAddress? = null

    @Volatile
    override var isRunning = false
        private set

    override val clientCount: Int
        get() = 0

    override fun start() {
        if (isRunning) return
        socket = DatagramSocket().apply { broadcast = true }
        target = InetAddress.getByName(broadcastAddress)
        isRunning = true
    }

    override fun broadcast(lines: List<String>, nowMillis: Long): SendResult {
        val s = socket ?: return SendResult(label, down = true)
        val addr = target ?: return SendResult(label, down = true)
        if (lines.isEmpty()) return SendResult(label, blind = true)
        val bytes = lines.joinToString("").toByteArray(Charsets.US_ASCII)
        return try {
            s.send(DatagramPacket(bytes, bytes.size, addr, port))
            // Blind by definition: UDP has no acknowledgement, so "it left the phone" is
            // the strongest thing this transport will ever be able to tell the log.
            SendResult(label, blind = true)
        } catch (e: Exception) {
            SendResult(label, dropped = 1)
        }
    }

    override fun stop() {
        isRunning = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }
}
