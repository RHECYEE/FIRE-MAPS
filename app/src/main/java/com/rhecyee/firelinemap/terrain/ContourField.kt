package com.rhecyee.firelinemap.terrain

import com.rhecyee.firelinemap.map.ElevationGrid

/** A contour on the ground, ready to be projected onto whatever map is open. */
data class GeoContour(
    /** Feet, because that is what a topo sheet and a briefing both use. */
    val elevationFeet: Double,
    /** Index contours are drawn heavier and carry the label. */
    val index: Boolean,
    val points: List<GeoVertex>,
    val closed: Boolean,
)

data class GeoVertex(val latitude: Double, val longitude: Double)

/**
 * Contours over a window of ground.
 *
 * Kept apart from the drawing so it can be run off the main thread and tested
 * without a device. The output is in latitude and longitude, not in pixels,
 * which is what lets the same set of lines be laid over an incident sheet, the
 * plain terrain view, and the car screen without being traced three times.
 */
object ContourField {

    /**
     * @param grid elevations in metres.
     * @param intervalFeet vertical spacing between lines.
     */
    fun build(grid: ElevationGrid, intervalFeet: Int): List<GeoContour> {
        if (intervalFeet <= 0) return emptyList()
        if (grid.width < 2 || grid.height < 2) return emptyList()

        // Traced in feet so the levels land on round numbers an operator can
        // read off -- 7,400, not 2,255.5 metres converted after the fact.
        val feet = FloatArray(grid.values.size) { index ->
            val meters = grid.values[index]
            if (meters.isNaN()) Float.NaN
            else (meters * ContourGenerator.FEET_PER_METER).toFloat()
        }

        // Zooming out puts more relief in the window than the asked-for band
        // can draw, and the generator refuses rather than filling the screen
        // with ink. Refusing quietly reads as the feature being broken, so
        // step the band up until it fits instead: coarser lines when the view
        // is wide, and exactly what was asked for when it is not.
        val known = feet.filter { !it.isNaN() }
        if (known.isEmpty()) return emptyList()
        val interval = fittedInterval(
            minimumFeet = known.min().toDouble(),
            maximumFeet = known.max().toDouble(),
            requestedFeet = intervalFeet
        ).toDouble()

        return ContourGenerator.contours(feet, grid.width, grid.height, interval)
            .flatMap { line ->
                val index = line.isIndex(interval)
                ContourGenerator.join(line.segments).map { path ->
                    GeoContour(
                        elevationFeet = line.elevation,
                        index = index,
                        points = path.points.map { point ->
                            GeoVertex(
                                latitude = grid.latitudeAt(point.y),
                                longitude = grid.longitudeAt(point.x)
                            )
                        },
                        closed = path.closed
                    )
                }
            }
    }

    /**
     * An interval that suits the ground rather than the menu.
     *
     * Flat country at two hundred feet shows one line; a canyon wall at twenty
     * shows a solid band of ink. Used when the operator has not picked, and as
     * the starting point of the picker.
     */
    fun suggestedInterval(reliefFeet: Double): Int {
        if (reliefFeet <= 0) return DEFAULT_INTERVAL_FEET
        // Aim for roughly a dozen lines across what is on screen.
        val target = reliefFeet / TARGET_LINES
        return ContourGenerator.INTERVALS_FEET.minByOrNull { candidate ->
            kotlin.math.abs(kotlin.math.ln(candidate / target))
        } ?: DEFAULT_INTERVAL_FEET
    }

    /**
     * The finest interval at or above the one asked for that will actually
     * draw across this much relief.
     *
     * Never finer than requested: an operator who asked for two hundred foot
     * bands does not want twenty foot bands appearing because the view got
     * small.
     */
    fun fittedInterval(minimumFeet: Double, maximumFeet: Double, requestedFeet: Int): Int {
        if (requestedFeet <= 0) return requestedFeet
        val coarser = ContourGenerator.INTERVALS_FEET.filter { it >= requestedFeet }.sorted()
        for (candidate in coarser) {
            if (ContourGenerator.levels(minimumFeet, maximumFeet, candidate.toDouble())
                    .isNotEmpty()
            ) return candidate
        }
        // Past the coarsest on the menu, keep doubling. A view spanning a
        // whole range still gets lines rather than nothing at all.
        var candidate = coarser.lastOrNull() ?: requestedFeet
        repeat(MAX_DOUBLINGS) {
            candidate *= 2
            if (ContourGenerator.levels(minimumFeet, maximumFeet, candidate.toDouble())
                    .isNotEmpty()
            ) return candidate
        }
        return candidate
    }

    /** The interval on a USGS quad over most of the mountain west. */
    const val DEFAULT_INTERVAL_FEET = 40

    private const val TARGET_LINES = 12.0

    private const val MAX_DOUBLINGS = 8
}
