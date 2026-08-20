package com.oshun.gpsbridge.net

/**
 * What one transport did with one batch of sentences, so the caller can log why a
 * position did or did not reach the tablet instead of only counting sends.
 */
data class SendResult(
    val label: String,
    /** Consumers attached. UDP is connectionless and always reports 0. */
    val clients: Int = 0,
    /** Clients that accepted the whole batch right away. */
    val accepted: Int = 0,
    /** Clients whose socket buffer is backing up — they are not draining what we write. */
    val stalled: Int = 0,
    /** Clients dropped during this send (broken pipe, peer gone). */
    val dropped: Int = 0,
    /** True for UDP: the datagram left, and nothing can tell us whether it arrived. */
    val blind: Boolean = false,
    /** True when the transport is not running at all. */
    val down: Boolean = false,
)
