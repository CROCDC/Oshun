package com.oshun.gpsbridge.core

import com.oshun.gpsbridge.model.AisTarget

/**
 * The vessels around us, as an internet feed reports them — and, more importantly, when to
 * stop believing them.
 *
 * A chart with a stale target on it is worse than a chart with none: it draws a ship where
 * no ship is, and nothing about the triangle says how old it is. So a report has a shelf
 * life here, and one that runs out is dropped rather than redrawn. Everything is pure and
 * time is a parameter, so "what does the chart show six minutes after the feed died" is a
 * unit test instead of an afternoon on the water.
 */
object AisTraffic {

    /**
     * How long a report may be repeated before it is forgotten. A class A transponder reports
     * every few seconds and a slow class B every three minutes; six minutes is past the point
     * where the slowest of them should have said something again.
     */
    const val MAX_AGE_MILLIS = 6 * 60_000L

    /** Beyond this there is nothing to avoid, and every extra target is noise on the chart. */
    const val RANGE_NAUTICAL_MILES = 12.0

    /** A busy estuary can carry hundreds of vessels; the nearest few are the ones that matter. */
    const val MAX_TARGETS = 40

    /** What a feed can tell us about a vessel: where it is, or what it is called. */
    sealed interface Update {
        data class Position(val target: AisTarget) : Update
        data class Name(val mmsi: Int, val name: String) : Update
    }

    /** Folds one update into what we know, forgetting whatever has aged out meanwhile. */
    fun merge(known: Map<Int, AisTarget>, update: Update, nowMillis: Long): Map<Int, AisTarget> =
        when (update) {
            is Update.Position -> fresh(known, nowMillis) + (update.target.mmsi to named(update.target, known))
            is Update.Name -> {
                val target = known[update.mmsi]
                if (target == null || update.name.isBlank()) fresh(known, nowMillis)
                else fresh(known, nowMillis) + (update.mmsi to target.copy(name = update.name))
            }
        }

    /** Drops every report old enough that we can no longer stand behind it. */
    fun fresh(known: Map<Int, AisTarget>, nowMillis: Long): Map<Int, AisTarget> =
        known.filterValues { nowMillis - it.reportedAtMillis < MAX_AGE_MILLIS }

    /**
     * The targets worth putting on the chart: still fresh, close enough to matter, nearest
     * first. [from] is our own position; without one there is no near or far, so the feed's
     * own bounding box is all the filtering there is.
     */
    fun visible(known: Map<Int, AisTarget>, from: Position?, nowMillis: Long): List<AisTarget> {
        val live = fresh(known, nowMillis).values
        if (from == null) return live.take(MAX_TARGETS)
        return live
            .map { it to Geo.distanceNauticalMiles(from, Position(it.latitude, it.longitude)) }
            .filter { (_, distance) -> distance <= RANGE_NAUTICAL_MILES }
            .sortedBy { (_, distance) -> distance }
            .take(MAX_TARGETS)
            .map { (target, _) -> target }
    }

    /**
     * A position report carries no name. Losing the label every few seconds would leave the
     * chart full of anonymous triangles, so a name we already learned survives the update
     * that does not mention it.
     */
    private fun named(target: AisTarget, known: Map<Int, AisTarget>): AisTarget {
        if (target.name.isNotBlank()) return target
        val previous = known[target.mmsi]?.name.orEmpty()
        return if (previous.isBlank()) target else target.copy(name = previous)
    }
}
