package com.oshun.gpsbridge.nmea

import com.oshun.gpsbridge.model.AisTarget
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToLong

/**
 * Turns [AisTarget]s into the `!AIVDM` sentences a chart plotter draws as other vessels.
 *
 * A plotter will not take a target in any other shape: the position has to arrive as a real
 * AIS message — ITU-R M.1371 binary fields, packed into six-bit ASCII — on the same NMEA
 * stream as our own position. So this builds the two messages that matter:
 *
 * - **Type 1**, the class A position report: where the vessel is, its speed and its course.
 *   This is what puts the triangle on the chart.
 * - **Type 24 part A**, static data: the vessel's name, so the triangle has a label. Sent
 *   rarely, exactly as a real transponder does.
 *
 * Pure Kotlin, no Android dependencies, so the bit packing is unit-tested on the JVM.
 */
object AisEncoder {

    /** Bits in the two messages this encoder builds; both are one-slot, so one sentence each. */
    private const val MESSAGE_BITS = 168

    /** Sentinel values the standard reserves for "the vessel did not report this". */
    const val SPEED_NOT_AVAILABLE = 1023
    const val COURSE_NOT_AVAILABLE = 3600
    const val HEADING_NOT_AVAILABLE = 511
    const val SECOND_NOT_AVAILABLE = 60

    /** Rate of turn "not available", as the 8-bit signed field carries it. */
    private const val ROT_NOT_AVAILABLE = -128

    /** The position report and, when the name is known, the static message that labels it. */
    fun sentences(target: AisTarget, nowMillis: Long): List<String> =
        if (target.name.isBlank()) listOf(positionReport(target, nowMillis))
        else listOf(positionReport(target, nowMillis), staticReport(target))

    /** Message 1 — class A position report. */
    fun positionReport(target: AisTarget, nowMillis: Long): String {
        val bits = BitWriter()
        bits.unsigned(1, 6)                                   // message type
        bits.unsigned(0, 2)                                   // repeat indicator
        bits.unsigned(target.mmsi.toLong(), 30)
        bits.unsigned(target.navigationStatus.coerceIn(0, 15).toLong(), 4)
        bits.signed(ROT_NOT_AVAILABLE.toLong(), 8)            // rate of turn: not available
        bits.unsigned(speedField(target.speedKnots), 10)
        bits.unsigned(0, 1)                                   // position accuracy: unaugmented GNSS
        bits.signed(coordinateField(target.longitude), 28)
        bits.signed(coordinateField(target.latitude), 27)
        bits.unsigned(courseField(target.courseDegrees), 12)
        bits.unsigned(headingField(target.headingDegrees), 9)
        bits.unsigned(secondOfMinute(nowMillis), 6)
        bits.unsigned(0, 2)                                   // special manoeuvre: not available
        bits.unsigned(0, 3)                                   // spare
        bits.unsigned(0, 1)                                   // RAIM not in use
        bits.unsigned(0, 19)                                  // radio status
        return frame(bits)
    }

    /** Message 24 part A — the vessel's name, so the target on the chart has a label. */
    fun staticReport(target: AisTarget): String {
        val bits = BitWriter()
        bits.unsigned(24, 6)                                  // message type
        bits.unsigned(0, 2)                                   // repeat indicator
        bits.unsigned(target.mmsi.toLong(), 30)
        bits.unsigned(0, 2)                                   // part number A
        bits.text(target.name, characters = 20)
        bits.unsigned(0, 8)                                   // spare
        return frame(bits)
    }

    /** Speed over ground in tenths of a knot; the standard's sentinel when unknown. */
    private fun speedField(knots: Double): Long =
        if (knots < 0 || knots.isNaN()) SPEED_NOT_AVAILABLE.toLong()
        else (knots * 10).roundToLong().coerceIn(0, 1022)

    /** Course over ground in tenths of a degree, wrapped into 0..359.9. */
    private fun courseField(degrees: Double): Long {
        if (degrees.isNaN()) return COURSE_NOT_AVAILABLE.toLong()
        val tenths = (degrees * 10).roundToLong()
        return ((tenths % 3600) + 3600) % 3600
    }

    private fun headingField(degrees: Double?): Long {
        if (degrees == null || degrees.isNaN()) return HEADING_NOT_AVAILABLE.toLong()
        val whole = degrees.roundToLong()
        return ((whole % 360) + 360) % 360
    }

    /** Latitude and longitude travel as signed 1/10000 of a minute. */
    private fun coordinateField(degrees: Double): Long = (degrees * 600_000.0).roundToLong()

    /** The UTC second the report was made — how a plotter ages a target out. */
    private fun secondOfMinute(millis: Long): Long {
        if (millis <= 0L) return SECOND_NOT_AVAILABLE.toLong()
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
        return c.get(Calendar.SECOND).toLong()
    }

    /** Wraps a payload as a single-part AIVDM sentence on channel A, with its checksum. */
    private fun frame(bits: BitWriter): String {
        val body = "AIVDM,1,1,,A,${bits.armored()},${bits.fillBits()}"
        return "!$body*${NmeaFormatter.checksum(body)}\r\n"
    }

    /**
     * Packs fields into the bit stream AIS defines, then armours it into the printable
     * six-bit ASCII an NMEA sentence can carry.
     */
    private class BitWriter {
        private val bits = StringBuilder(MESSAGE_BITS)

        fun unsigned(value: Long, width: Int) {
            for (bit in width - 1 downTo 0) bits.append((value shr bit) and 1L)
        }

        /** Two's complement, which is how AIS carries southern latitudes and western longitudes. */
        fun signed(value: Long, width: Int) {
            val span = 1L shl width
            unsigned(((value % span) + span) % span, width)
        }

        /**
         * AIS six-bit text: '@' is 0 and doubles as the pad, letters and digits keep their
         * ASCII order, and anything outside the alphabet becomes '?' rather than corrupting
         * the field. Names are uppercase on the air.
         */
        fun text(value: String, characters: Int) {
            val upper = value.uppercase(Locale.US)
            for (index in 0 until characters) {
                val ch = upper.getOrNull(index) ?: '@'
                unsigned(sixBit(ch).toLong(), 6)
            }
        }

        fun fillBits(): Int = (6 - bits.length % 6) % 6

        fun armored(): String {
            val padded = bits.toString() + "0".repeat(fillBits())
            val out = StringBuilder(padded.length / 6)
            for (start in padded.indices step 6) {
                val value = padded.substring(start, start + 6).toInt(radix = 2)
                // The armouring skips the control characters: 0..39 map from '0', 40..63 from '`'.
                out.append((if (value < 40) value + 48 else value + 56).toChar())
            }
            return out.toString()
        }

        private fun sixBit(ch: Char): Int = when (ch.code) {
            in 64..95 -> ch.code - 64   // '@'..'_' : the letters live here
            in 32..63 -> ch.code        // ' '..'?' : digits, space, punctuation
            else -> 63                  // '?', for anything the alphabet cannot carry
        }
    }
}
