package com.rhecyee.firelinemap.car

import kotlin.math.abs

/** A pin asking to be named, in surface pixels. */
data class MarkerLabelRequest(
    val id: String,
    val x: Float,
    val y: Float,
    val title: String,
    val type: String,
    /** Higher wins a crowded patch of screen. */
    val priority: Int = 0,
)

/** A pin that will be named, and what its caption says. */
data class MarkerLabel(
    val id: String,
    val x: Float,
    val y: Float,
    val lines: List<String>,
)

/**
 * Captions for the pins on the car display.
 *
 * A coloured dot says a resource is there and nothing else. On the phone that
 * is survivable, because a pin can be tapped. In a vehicle it is not: there is
 * no tapping to find out which engine that is, and a map of anonymous dots is
 * a map that has to be checked against something else before it can be used.
 *
 * So the pins carry their names. The awkward part is that names collide --
 * three resources at a drop point put three captions in the same place and the
 * result is less legible than the dots were. Which captions to draw is
 * therefore a decision, and it is made here, away from the canvas, where it can
 * be checked without a head unit.
 */
object CarMarkerLabels {

    /** Long enough for a resource designator, short enough to read at speed. */
    const val MAX_TITLE = 20

    /** Captions never to exceed, however many pins are on screen. */
    const val MAX_LABELS = 12

    /**
     * The caption for one pin: what it is called, and what it is.
     *
     * The type is dropped when the name already carries it. "Engine / Engine"
     * is two lines saying one thing, and the second one costs the space a
     * different pin's name needed.
     */
    fun lines(title: String, type: String): List<String> {
        val name = title.trim()
        val kind = type.trim()
        val shownName = shorten(name)
        if (kind.isEmpty()) return listOfNotNull(shownName.ifEmpty { null })
        if (shownName.isEmpty()) return listOf(shorten(kind))
        if (name.equals(kind, ignoreCase = true)) return listOf(shownName)
        if (name.contains(kind, ignoreCase = true)) return listOf(shownName)
        return listOf(shownName, shorten(kind))
    }

    private fun shorten(value: String): String =
        if (value.length <= MAX_TITLE) value
        // An ellipsis rather than a hard cut, so a clipped name reads as
        // clipped instead of as a different resource.
        else value.take(MAX_TITLE - 1).trimEnd() + "…"

    /**
     * Which pins get captions.
     *
     * Greedy, highest priority first: a caption is drawn unless something more
     * important has already claimed that patch of screen. Ties break on the id
     * so the same pins keep their captions frame after frame -- a set chosen by
     * whatever order the database happened to return would flicker between
     * names at four frames a second, which is worse than no captions at all.
     */
    fun place(
        requests: List<MarkerLabelRequest>,
        horizontalSpacing: Float,
        verticalSpacing: Float,
        limit: Int = MAX_LABELS,
    ): List<MarkerLabel> {
        if (requests.isEmpty() || limit <= 0) return emptyList()

        val ordered = requests.sortedWith(
            compareByDescending<MarkerLabelRequest> { it.priority }.thenBy { it.id }
        )
        val placed = ArrayList<MarkerLabel>(minOf(limit, ordered.size))

        for (request in ordered) {
            if (placed.size >= limit) break
            if (!request.x.isFinite() || !request.y.isFinite()) continue
            val lines = lines(request.title, request.type)
            if (lines.isEmpty()) continue

            val clear = placed.none { existing ->
                abs(existing.x - request.x) < horizontalSpacing &&
                    abs(existing.y - request.y) < verticalSpacing
            }
            if (!clear) continue

            placed.add(MarkerLabel(request.id, request.x, request.y, lines))
        }
        return placed
    }
}
