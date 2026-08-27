package com.oshun.gpsbridge.nmea

import com.oshun.gpsbridge.model.AisTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The encoder packs binary fields a chart plotter parses without ever telling us it failed:
 * a wrong bit offset shows up as a vessel in the Sahara, or as nothing at all. So the tests
 * take the sentence apart again, field by field, with a decoder written independently of the
 * encoder — and pin the two things about AIVDM that are externally checkable: the armouring
 * table, and the message type the payload's first character encodes.
 */
class AisEncoderTest {

    /** Río de la Plata, southern and western: the hemispheres that need two's complement. */
    private val target = AisTarget(
        mmsi = 701999001,
        name = "TEST CARGO",
        latitude = -34.987654,
        longitude = -57.443210,
        speedKnots = 12.3,
        courseDegrees = 210.5,
        headingDegrees = 211.0,
        navigationStatus = 0,
    )

    /** 2026-08-20T12:34:56Z — the seconds are what lands in the timestamp field. */
    private val nowMillis = 1787229296000L

    @Test
    fun looksLikeAnAivdmSentence() {
        val sentence = AisEncoder.positionReport(target, nowMillis)
        assertTrue(sentence, sentence.startsWith("!AIVDM,1,1,,A,"))
        assertTrue("ends with CRLF", sentence.endsWith("\r\n"))
        val body = sentence.removePrefix("!").substringBefore('*')
        val checksum = sentence.substringAfter('*').trim()
        assertEquals("checksum", NmeaFormatter.checksum(body), checksum)
        // 168 bits is one slot, so the payload is exactly 28 six-bit characters and no fill.
        assertEquals(28, payloadOf(sentence).length)
        assertEquals("0", body.substringAfterLast(','))
    }

    @Test
    fun thePayloadStartsWithTheMessageType() {
        // Externally checkable: every type 1 report in the wild starts with '1' (6 bits = 1),
        // and every type 24 with 'H' (24 → '0' + 24). Get the armouring wrong and both move.
        assertTrue(payloadOf(AisEncoder.positionReport(target, nowMillis)).startsWith("1"))
        assertTrue(payloadOf(AisEncoder.staticReport(target)).startsWith("H"))
    }

    @Test
    fun carriesThePositionBackIntact() {
        val bits = bitsOf(AisEncoder.positionReport(target, nowMillis))
        assertEquals(1, unsigned(bits, 0, 6))
        assertEquals(target.mmsi.toLong(), unsigned(bits, 8, 30))
        // A tenth of a minute of arc is about 0.18 m; the field's own resolution is the tolerance.
        assertEquals(target.longitude, signed(bits, 61, 28) / 600_000.0, 1e-5)
        assertEquals(target.latitude, signed(bits, 89, 27) / 600_000.0, 1e-5)
        assertEquals(123L, unsigned(bits, 50, 10))   // 12.3 kn in tenths
        assertEquals(2105L, unsigned(bits, 116, 12)) // 210.5° in tenths
        assertEquals(211L, unsigned(bits, 128, 9))   // heading, whole degrees
        assertEquals(56L, unsigned(bits, 137, 6))    // the second of the UTC minute
    }

    @Test
    fun aVesselThatDoesNotReportItsHeadingSaysSo() {
        // 511 is the standard's "not available"; a zero here would draw every target north.
        val bits = bitsOf(AisEncoder.positionReport(target.copy(headingDegrees = null), nowMillis))
        assertEquals(AisEncoder.HEADING_NOT_AVAILABLE.toLong(), unsigned(bits, 128, 9))
    }

    @Test
    fun wrapsCourseAndClampsSpeed() {
        val bits = bitsOf(
            AisEncoder.positionReport(target.copy(courseDegrees = 370.0, speedKnots = 140.0), nowMillis),
        )
        assertEquals(100L, unsigned(bits, 116, 12))  // 370° is 10°
        assertEquals(1022L, unsigned(bits, 50, 10))  // the field's ceiling, not an overflow
    }

    @Test
    fun aNegativeSpeedIsReportedAsUnknownRatherThanInvented() {
        val bits = bitsOf(AisEncoder.positionReport(target.copy(speedKnots = -1.0), nowMillis))
        assertEquals(AisEncoder.SPEED_NOT_AVAILABLE.toLong(), unsigned(bits, 50, 10))
    }

    @Test
    fun theStaticMessageCarriesTheName() {
        val bits = bitsOf(AisEncoder.staticReport(target))
        assertEquals(24, unsigned(bits, 0, 6))
        assertEquals(target.mmsi.toLong(), unsigned(bits, 8, 30))
        assertEquals(0, unsigned(bits, 38, 2)) // part A
        assertEquals("TEST CARGO", text(bits, 40, characters = 20))
    }

    @Test
    fun lowercaseAndUnknownCharactersSurviveAsTheAlphabetAllows() {
        // The AIS alphabet is uppercase; anything outside it becomes '?' instead of shifting
        // every following character by six bits.
        val bits = bitsOf(AisEncoder.staticReport(target.copy(name = "Oshun ñ")))
        assertEquals("OSHUN ?", text(bits, 40, characters = 20))
    }

    @Test
    fun anAnonymousTargetStillGetsAPosition() {
        val sentences = AisEncoder.sentences(target.copy(name = ""), nowMillis)
        assertEquals(1, sentences.size)
        assertTrue(payloadOf(sentences.first()).startsWith("1"))
        assertEquals(2, AisEncoder.sentences(target, nowMillis).size)
    }

    // --- Decoding, written from the format rather than from the encoder ------------------

    private fun payloadOf(sentence: String): String = sentence.split(',')[5]

    /** Undoes the six-bit armouring: '0'..'W' are 0..39, '`'..'w' are 40..63. */
    private fun bitsOf(sentence: String): String = buildString {
        for (ch in payloadOf(sentence)) {
            val value = ch.code - 48
            val sixBit = if (value >= 48) value - 8 else value
            append(sixBit.toString(2).padStart(6, '0'))
        }
    }

    private fun unsigned(bits: String, start: Int, width: Int): Long =
        bits.substring(start, start + width).toLong(radix = 2)

    private fun signed(bits: String, start: Int, width: Int): Long {
        val raw = unsigned(bits, start, width)
        val span = 1L shl width
        return if (raw >= span / 2) raw - span else raw
    }

    private fun text(bits: String, start: Int, characters: Int): String = buildString {
        for (index in 0 until characters) {
            val value = unsigned(bits, start + index * 6, 6).toInt()
            if (value == 0) break // '@' pads the field out
            append(if (value < 32) (value + 64).toChar() else value.toChar())
        }
    }.trimEnd()
}
