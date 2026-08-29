package com.oshun.gpsbridge.core

import com.oshun.gpsbridge.model.AisTarget
import java.util.Locale

/**
 * Everything the bridge knows about the traffic around it, as one block of text to paste
 * into a message.
 *
 * This exists for the failure that cannot be debugged from the phone: the app counts targets
 * and the plotter draws none. Between those two facts sit a distance filter, an age limit and
 * a wire format, and from the boat there is no way to tell which of them ate the vessel. So
 * the report carries all three at once — every vessel the feed reported, whether it went out
 * or was filtered and why, and the exact sentences the last batch put on the wire.
 *
 * Pure, so what the report says is a unit test rather than something read off a screenshot.
 */
object AisReport {

    /** What the service hands over; assembled where the batch is built, formatted on demand. */
    data class Snapshot(
        val atMillis: Long,
        val own: Position?,
        val fixValid: Boolean,
        /** Every vessel the feed still stands behind, filtered or not. */
        val known: List<AisTarget>,
        /** The MMSIs that actually reached the wire in the last batch. */
        val transmitted: Set<Int>,
        val feedConnected: Boolean,
        val feedMessages: Long,
        val simulated: Boolean,
        /** e.g. "TCP+UDP:2000 · 1 cliente". */
        val link: String,
        /** The last batch, verbatim and in order — our own position included. */
        val sentences: List<String>,
    )

    fun format(snapshot: Snapshot): String {
        val lines = mutableListOf<String>()
        lines += "Oshun · barcos AIS"
        lines += TrackLogFormatter.utc(snapshot.atMillis)
        lines += ""
        lines += row("modo", if (snapshot.simulated) "PRUEBA (barcos inventados)" else "real")
        lines += row("mi posición", ownPosition(snapshot))
        lines += row("feed", feed(snapshot))
        lines += row("recibidos", snapshot.known.size.toString())
        lines += row(
            "transmitidos",
            "${snapshot.transmitted.size}  (≤ ${format(AisTraffic.RANGE_NAUTICAL_MILES)} NM, " +
                "< ${AisTraffic.MAX_AGE_MILLIS / 60_000} min, los ${AisTraffic.MAX_TARGETS} más cercanos)",
        )
        lines += row("enlace", snapshot.link)
        lines += ""

        if (snapshot.known.isEmpty()) {
            lines += "Ningún barco recibido."
        } else {
            lines += HEADER
            // Nearest first, so the ones that should be on the chart are the ones you read
            // first; without our own position there is no near or far and the feed's order
            // is as good as any.
            val sorted = snapshot.own?.let { own ->
                snapshot.known.sortedBy { distance(own, it) }
            } ?: snapshot.known
            sorted.forEach { lines += vesselRow(it, snapshot) }
        }

        lines += ""
        lines += "última tanda enviada (${snapshot.sentences.size} sentencias)"
        // Verbatim: the whole point is to read the bytes that left the phone, so the
        // line endings the sentences carry are stripped rather than shown as blanks.
        snapshot.sentences.forEach { lines += it.trimEnd('\r', '\n') }
        return lines.joinToString("\n")
    }

    private const val HEADER = "MMSI       NOMBRE                DIST NM    COG    SOG  EDAD  TX"

    private fun vesselRow(target: AisTarget, snapshot: Snapshot): String {
        val distance = snapshot.own
            ?.let { "%8.2f" .format(Locale.US, distance(it, target)) }
            ?: "       ?"
        val age = ((snapshot.atMillis - target.reportedAtMillis) / 1000).coerceAtLeast(0)
        return "%-9d  %-20s %s  %5.1f  %5.1f  %3ds  %s".format(
            Locale.US,
            target.mmsi,
            target.name.ifBlank { "(sin nombre)" }.take(20),
            distance,
            target.courseDegrees,
            target.speedKnots,
            age,
            if (target.mmsi in snapshot.transmitted) "sí" else "no",
        )
    }

    private fun ownPosition(snapshot: Snapshot): String {
        val own = snapshot.own ?: return "sin fix — nada puede salir"
        return "%.5f, %.5f  (%s)".format(
            Locale.US,
            own.latitude,
            own.longitude,
            if (snapshot.fixValid) "fix válido" else "fix viejo, se transmite inválido",
        )
    }

    private fun feed(snapshot: Snapshot): String = when {
        snapshot.simulated -> "apagado en modo prueba"
        snapshot.feedConnected -> "conectado · ${snapshot.feedMessages} mensajes"
        else -> "caído · ${snapshot.feedMessages} mensajes"
    }

    private fun distance(own: Position, target: AisTarget): Double =
        Geo.distanceNauticalMiles(own, Position(target.latitude, target.longitude))

    private fun row(label: String, value: String): String = "%-14s %s".format(label, value)

    private fun format(value: Double): String = "%.0f".format(Locale.US, value)
}
