package com.oshun.gpsbridge.core

/**
 * What happened to one batch of sentences. TCP cannot prove that Navionics *parsed*
 * anything — no acknowledgement exists at the application level — but it does tell us
 * whether anyone was attached and whether the peer is still draining what we write.
 * That is the difference between "the chart is frozen because nothing left the phone"
 * and "it left and nobody consumed it".
 */
enum class DeliveryOutcome {
    /** A client took the whole batch immediately: as close to delivered as TCP gets. */
    OK,

    /** Nobody is connected. The sentences never left the phone. */
    NO_CLIENT,

    /** The socket buffer is backing up: we write and the other side is not reading. */
    STALLED,

    /** The connection broke while sending. */
    DROPPED,

    /** Sent over UDP only: datagrams left, delivery is unknowable by design. */
    BLIND,

    /** No transport is running (bind failed, or the bridge is stopped). */
    NOT_SENT,
}
