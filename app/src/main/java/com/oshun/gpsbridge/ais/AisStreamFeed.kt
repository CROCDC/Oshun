package com.oshun.gpsbridge.ais

import com.oshun.gpsbridge.core.AisTraffic
import com.oshun.gpsbridge.core.Position
import com.oshun.gpsbridge.net.AisStreamMessages
import com.oshun.gpsbridge.net.AisSubscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * The live connection to the AIS feed: one WebSocket, a subscription that follows the boat,
 * and a reconnect that keeps trying.
 *
 * Nothing here decides what a target means — that is [AisTraffic]'s job and it is pure. This
 * class only owns the socket, which is the part that cannot be unit-tested and therefore
 * should hold as little thinking as possible.
 *
 * It stays silent until the first fix: the feed is asked for a box of water, and without our
 * own position there is no box to ask for.
 */
class AisStreamFeed(
    private val apiKey: String,
    private val scope: CoroutineScope,
    private val onUpdate: (AisTraffic.Update) -> Unit,
    private val onConnected: (Boolean) -> Unit,
) {

    private val client = OkHttpClient.Builder()
        // The feed can be quiet for a while in empty water, so no read timeout — but a ping
        // every half minute, which is what tells us the link died instead of the estuary
        // being empty.
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var subscribedCentre: Position? = null
    @Volatile private var lastKnown: Position? = null
    @Volatile private var running = false
    @Volatile private var attempt = 0
    private var retry: Job? = null

    /** Arms the feed. Nothing connects until [onOwnPosition] says where we are. */
    fun start() {
        running = true
        lastKnown?.let { connect(it) }
    }

    /** Our own position moved: open the connection, or renew the box we asked for. */
    fun onOwnPosition(position: Position) {
        lastKnown = position
        if (!running) return
        val open = socket
        if (open == null) connect(position)
        else if (AisSubscription.shouldResubscribe(subscribedCentre, position)) subscribe(open, position)
    }

    fun stop() {
        running = false
        retry?.cancel()
        retry = null
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
        subscribedCentre = null
    }

    private fun connect(centre: Position) {
        if (!running || socket != null) return
        val request = Request.Builder().url(AisSubscription.URL).build()
        socket = client.newWebSocket(request, Listener(centre))
    }

    private fun subscribe(open: WebSocket, centre: Position) {
        if (open.send(AisSubscription.message(apiKey, centre))) subscribedCentre = centre
    }

    /** Drops the socket and comes back for it, backing off so a bad key is not hammered. */
    private fun scheduleReconnect() {
        socket = null
        subscribedCentre = null
        if (!running) return
        val wait = RECONNECT_DELAYS_MILLIS[attempt.coerceAtMost(RECONNECT_DELAYS_MILLIS.lastIndex)]
        attempt += 1
        retry?.cancel()
        retry = scope.launch {
            delay(wait)
            lastKnown?.let { connect(it) }
        }
    }

    private inner class Listener(private val centre: Position) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            subscribe(webSocket, lastKnown ?: centre)
            onConnected(true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            AisStreamMessages.parse(text, System.currentTimeMillis())?.let(onUpdate)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onConnected(false)
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onConnected(false)
            scheduleReconnect()
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000

        /**
         * Backoff, in the units that matter on a boat: try again at once, then give it a
         * little, then settle at a minute. A wrong key fails identically every time and
         * there is no point burning the battery on it.
         */
        val RECONNECT_DELAYS_MILLIS = listOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L)
    }
}
