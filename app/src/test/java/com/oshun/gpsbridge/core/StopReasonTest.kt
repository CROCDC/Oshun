package com.oshun.gpsbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StopReasonTest {

    @Test
    fun parsesKnownTokens() {
        assertEquals(StopReason.USER, StopReason.fromToken("USER"))
        assertEquals(StopReason.IDLE_TIMEOUT, StopReason.fromToken("IDLE_TIMEOUT"))
    }

    @Test
    fun unknownAndAbsentTokensAreNull() {
        assertNull(StopReason.fromToken(null))
        assertNull(StopReason.fromToken(""))
        assertNull(StopReason.fromToken("idle_timeout")) // tokens are the exact enum names
        assertNull(StopReason.fromToken("CRASH"))
    }
}
