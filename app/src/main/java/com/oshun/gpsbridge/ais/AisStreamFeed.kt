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
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    private val onConnected: (connected: Boolean, detail: String) -> Unit,
    private val onRaw: (String) -> Unit = {},
) {

    private val client = OkHttpClient.Builder()
        // The feed can be quiet for a while in empty water, so no read timeout — but a ping
        // every half minute, which is what tells us the link died instead of the estuary
        // being empty.
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null

    /**
     * The socket once the handshake finished, which is not the same thing as [socket].
     *
     * Telling them apart is the whole point: `newWebSocket` returns before the connection is
     * open, and a subscription sent into that gap is a second subscription — the feed closes
     * a connection that updates its subscription more than once a second.
     */
    @Volatile private var openSocket: WebSocket? = null
    @Volatile private var subscribedCentre: Position? = null
    @Volatile private var lastSubscriptionAtMillis = 0L
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
        if (socket == null) {
            connect(position)
            return
        }
        // Only over a connection that actually finished opening, and never faster than the
        // feed tolerates. Fixes arrive once a second; subscriptions must not.
        val open = openSocket ?: return
        val now = System.currentTimeMillis()
        if (now - lastSubscriptionAtMillis < MIN_SUBSCRIPTION_INTERVAL_MILLIS) return
        if (AisSubscription.shouldResubscribe(subscribedCentre, position)) subscribe(open, position)
    }

    fun stop() {
        running = false
        retry?.cancel()
        retry = null
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
        openSocket = null
        subscribedCentre = null
    }

    private fun connect(centre: Position) {
        if (!running || socket != null) return
        val request = Request.Builder().url(AisSubscription.url).build()
        socket = client.newWebSocket(request, Listener(centre))
    }

    private fun subscribe(open: WebSocket, centre: Position) {
        if (!open.send(AisSubscription.message(apiKey, centre))) return
        subscribedCentre = centre
        lastSubscriptionAtMillis = System.currentTimeMillis()
    }

    /** Drops the socket and comes back for it, backing off so a bad key is not hammered. */
    private fun scheduleReconnect() {
        socket = null
        openSocket = null
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

        /** One drop per connection: a clean close arrives twice, and it is one event. */
        private val dropped = AtomicBoolean(false)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            openSocket = webSocket
            subscribe(webSocket, lastKnown ?: centre)
            onConnected(true, "")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // Raw first, parsed second: a message we cannot read still proves the feed is alive,
            // which is the difference between "no coverage" and "our parser is wrong".
            onRaw(text)
            AisStreamMessages.parse(text, System.currentTimeMillis())?.let(onUpdate)
        }

        /**
         * The feed sends its JSON in binary frames, not text ones.
         *
         * Overriding only the text overload is a deafness with no symptom to follow: the socket
         * opens, the subscription is accepted, nothing ever closes, and not one vessel arrives
         * — which reads exactly like empty water.
         */
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onMessage(webSocket, bytes.utf8())
        }

        /**
         * The server is closing. We have to answer with a close of our own — until we do, the
         * socket sits half-open, `onClosed` never comes, and the feed stays dead without ever
         * saying so or reconnecting.
         */
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
            drop(closeDetail(code, reason))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            drop(closeDetail(code, reason))
        }

        /**
         * Why it dropped is the whole diagnosis, and it used to be thrown away.
         *
         * A rejected key does not look like bad coverage: the socket opens, the server says no
         * and closes, and the app reconnects for ever. The HTTP code and the close reason are
         * what tell those apart, so they go into the log verbatim.
         */
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val http = response?.let { "HTTP ${it.code} " }.orEmpty()
            drop(http + (t.message ?: t::class.java.simpleName))
        }

        private fun closeDetail(code: Int, reason: String): String =
            if (reason.isBlank()) "close $code" else "close $code: $reason"

        private fun drop(detail: String) {
            if (!dropped.compareAndSet(false, true)) return
            if (!running) return // we are the ones who closed it
            onConnected(false, detail)
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

        /**
         * Floor between subscription updates. The feed documents that it closes a connection
         * whose subscription changes more than once a second; ten seconds is far from that
         * line and still follows the boat, since the box is renewed every five miles — nearly
         * an hour at six knots.
         */
        const val MIN_SUBSCRIPTION_INTERVAL_MILLIS = 10_000L
    }
}
