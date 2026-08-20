package com.oshun.gpsbridge.net

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/**
 * TCP transport: opens a server socket and streams NMEA sentences to every connected
 * client. In Navionics this is "Paired Devices → protocol TCP", host = the phone's IP,
 * port = [port]. Only one client connects in typical use, but multiple are supported.
 *
 * Sockets are **non-blocking** on purpose. Blocking writes hid the failure that matters:
 * when the tablet stops reading, the kernel buffer fills and a blocking write simply
 * waits, so the app reported a healthy client and rising counters while nothing arrived.
 * A non-blocking write reports how many bytes the peer's window accepted, which is what
 * lets the log say "sent, and nobody consumed it". It also means one stuck client can
 * never stall the emitter.
 */
class NmeaTcpServer(
    private val port: Int,
    /** A client backed up for longer than this is hopeless: cut it so it can reconnect. */
    private val dropAfterStalledMillis: Long = 60_000L,
    /**
     * Deliberately small: a large send buffer would let megabytes of stale positions queue
     * up for a tablet that is not reading, so the chart would replay old fixes instead of
     * jumping to the current one — and the backlog would hide the stall from the log.
     */
    private val sendBufferBytes: Int = 32 * 1024,
) : NmeaTransport {

    override val label = "TCP"

    private class Client(val channel: SocketChannel, val remote: String) {
        /** Tail of the last batch the peer's window could not take. */
        var pending: ByteBuffer? = null
        var backedUpSinceMillis = 0L
    }

    @Volatile
    private var serverChannel: ServerSocketChannel? = null
    private val clients = mutableListOf<Client>()
    private var acceptThread: Thread? = null
    private val scratch = ByteBuffer.allocate(256)

    @Volatile
    override var isRunning = false
        private set

    override val clientCount: Int
        get() = synchronized(clients) { clients.size }

    /** Invoked (off the main thread) whenever the connected-client count changes. */
    @Volatile
    var onClientsChanged: ((Int) -> Unit)? = null

    /** Invoked when a client attaches or leaves, with its address, for the session log. */
    @Volatile
    var onClientEvent: ((connected: Boolean, remote: String) -> Unit)? = null

    override fun start() {
        if (isRunning) return
        val server = ServerSocketChannel.open()
        server.socket().reuseAddress = true
        server.socket().bind(InetSocketAddress(port))
        serverChannel = server
        isRunning = true
        acceptThread = Thread({ acceptLoop(server) }, "nmea-tcp-accept").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop(server: ServerSocketChannel) {
        while (isRunning) {
            val channel = try {
                server.accept() ?: continue
            } catch (e: Exception) {
                if (isRunning) continue else break
            }
            val remote = try {
                channel.configureBlocking(false)
                channel.socket().tcpNoDelay = true
                channel.socket().keepAlive = true
                channel.socket().sendBufferSize = sendBufferBytes
                (channel.remoteAddress as? InetSocketAddress)?.address?.hostAddress ?: "?"
            } catch (e: Exception) {
                "?"
            }
            val count = synchronized(clients) {
                clients += Client(channel, remote)
                clients.size
            }
            onClientEvent?.invoke(true, remote)
            onClientsChanged?.invoke(count)
        }
    }

    override fun broadcast(lines: List<String>, nowMillis: Long): SendResult {
        if (!isRunning) return SendResult(label, down = true)
        if (lines.isEmpty()) return SendResult(label, clients = clientCount)

        val payload = lines.joinToString("").toByteArray(Charsets.US_ASCII)
        var accepted = 0
        var stalled = 0
        val gone = mutableListOf<Client>()

        val remaining = synchronized(clients) {
            val iterator = clients.iterator()
            while (iterator.hasNext()) {
                val client = iterator.next()
                when (send(client, payload, nowMillis)) {
                    Status.ACCEPTED -> accepted++
                    Status.STALLED -> stalled++
                    Status.GONE -> {
                        iterator.remove()
                        gone += client
                    }
                }
            }
            clients.size
        }

        gone.forEach { close(it) }
        if (gone.isNotEmpty()) {
            gone.forEach { onClientEvent?.invoke(false, it.remote) }
            onClientsChanged?.invoke(remaining)
        }

        return SendResult(
            label = label,
            clients = remaining + gone.size,
            accepted = accepted,
            stalled = stalled,
            dropped = gone.size,
        )
    }

    private enum class Status { ACCEPTED, STALLED, GONE }

    /**
     * Writes one batch to one client. While a client is backed up we deliberately drop new
     * batches instead of queueing them: a position that arrives late is worse than useless,
     * and an unbounded queue would just turn a stalled tablet into an OOM.
     */
    private fun send(client: Client, payload: ByteArray, nowMillis: Long): Status {
        try {
            if (peerClosed(client)) return Status.GONE

            val queued = client.pending
            if (queued != null) {
                client.channel.write(queued)
                if (queued.hasRemaining()) {
                    return if (nowMillis - client.backedUpSinceMillis >= dropAfterStalledMillis) {
                        Status.GONE
                    } else {
                        Status.STALLED
                    }
                }
                client.pending = null
                client.backedUpSinceMillis = 0L
            }

            val buffer = ByteBuffer.wrap(payload)
            client.channel.write(buffer)
            if (buffer.hasRemaining()) {
                // The peer's receive window is full: it is not draining what we send.
                client.pending = buffer
                client.backedUpSinceMillis = nowMillis
                return Status.STALLED
            }
            return Status.ACCEPTED
        } catch (e: Exception) {
            return Status.GONE
        }
    }

    /** True once the peer closed its side. Anything it sends us is a consumer's business, not ours. */
    private fun peerClosed(client: Client): Boolean {
        while (true) {
            scratch.clear()
            val read = client.channel.read(scratch)
            if (read == -1) return true
            if (read == 0) return false
        }
    }

    private fun close(client: Client) {
        try {
            client.channel.close()
        } catch (_: Exception) {
        }
    }

    override fun stop() {
        isRunning = false
        try {
            serverChannel?.close()
        } catch (_: Exception) {
        }
        val leaving = synchronized(clients) {
            val snapshot = clients.toList()
            clients.clear()
            snapshot
        }
        leaving.forEach { close(it) }
        serverChannel = null
        leaving.forEach { onClientEvent?.invoke(false, it.remote) }
        onClientsChanged?.invoke(0)
    }
}
