package com.rhecyee.firelinemap.terrain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** A point on a contour, in grid coordinates: fractional column and row. */
internal data class GridPoint(val column: Double, val row: Double)

/**
 * One traced contour.
 *
 * [points] are latitude to longitude, in order along the line. A closed line
 * repeats its first point at the end, which is what lets a drawing routine
 * treat rings and open lines the same way.
 */
data class ContourLine(
    val elevationFeet: Int,
    val isIndex: Boolean,
    val points: List<Pair<Double, Double>>
) {
    val isClosed: Boolean
        get() = points.size > 2 && points.first() == points.last()

    /**
     * Where to write the elevation, and which way the line runs there.
     *
     * The middle of the line rather than an end: an end is usually at the edge
     * of the screen, where a label is half off it. The bearing lets the label
     * be laid along the contour the way it is on a printed quad, instead of
     * lying across it and hiding the line it belongs to.
     */
    fun labelAnchor(): Triple<Double, Double, Double>? {
        if (points.size < 2) return null
        val middle = points.size / 2
        val before = points[(middle - 1).coerceAtLeast(0)]
        val after = points[(middle + 1).coerceAtMost(points.lastIndex)]
        val (latitude, longitude) = points[middle]
        val bearing = Math.toDegrees(
            kotlin.math.atan2(after.second - before.second, after.first - before.first)
        )
        return Triple(latitude, longitude, bearing)
    }
}

/** Every contour in one view, with the interval they were cut at. */
data class ContourSet(
    val interval: ContourInterval,
    val lines: List<ContourLine>,
    val lowestFeet: Int,
    val highestFeet: Int,
    /** Fraction of the grid that had elevation behind it. */
    val coverage: Double
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    companion object {
        val NONE = ContourSet(ContourInterval(40), emptyList(), 0, 0, 0.0)
    }
}

/**
 * Traces contours through a grid of elevations.
 *
 * Marching squares, which is what every contouring tool from GDAL down uses:
 * each cell of four samples is classified by which corners are above the
 * level, and the sixteen possible classifications each say where the line
 * crosses the cell's edges. Crossings are placed by linear interpolation
 * between the two corner elevations, so a line sits at the height it claims
 * rather than snapping to the sample spacing.
 *
 * Segments are then stitched end to end into runs, because a contour is only
 * useful if it can be followed and labelled -- a bag of disconnected cell
 * crossings can be drawn but not read.
 */
object ContourBuilder {

    /**
     * A hard ceiling on levels, so a grid holding a bad value cannot ask for
     * tens of thousands of passes and take the interface with it.
     */
    const val MAX_LEVELS = 200

    /** Runs shorter than this are dropped as sampling noise. */
    const val MIN_POINTS = 3

    /**
     * @param isActive checked between levels. A view that has moved on has no
     *   use for the lines being cut for the old one, and without this the
     *   abandoned work runs to completion while the next request queues behind
     *   it -- several full traces at once, which is memory the phone does not
     *   have to spare.
     */
    fun build(
        grid: ElevationGrid,
        interval: ContourInterval,
        isActive: () -> Boolean = { true }
    ): ContourSet {
        val relief = grid.relief() ?: return ContourSet.NONE
        val (lowMeters, highMeters) = relief
        val step = interval.meters
        if (step <= 0.0) return ContourSet.NONE

        val lowFeet = lowMeters * ContourInterval.FEET_PER_METER
        val highFeet = highMeters * ContourInterval.FEET_PER_METER
        val firstLevel = ceil(lowFeet / interval.feet).toInt()
        val lastLevel = floor(highFeet / interval.feet).toInt()
        if (lastLevel < firstLevel) {
            // Ground flat enough that no whole contour crosses it. Reporting
            // the interval anyway keeps the key honest: it is telling the
            // operator the ground is flat, not that the layer is broken.
            return ContourSet(
                interval = interval,
                lines = emptyList(),
                lowestFeet = lowFeet.roundToInt(),
                highestFeet = highFeet.roundToInt(),
                coverage = grid.coverage()
            )
        }

        val lines = mutableListOf<ContourLine>()
        val levels = (lastLevel - firstLevel + 1).coerceAtMost(MAX_LEVELS)
        for (n in 0 until levels) {
            if (!isActive()) break
            val feet = (firstLevel + n) * interval.feet
            val meters = feet / ContourInterval.FEET_PER_METER
            val segments = trace(grid, meters)
            if (segments.isEmpty()) continue
            val isIndex = interval.isIndex(feet)
            for (run in stitch(segments)) {
                if (run.size < MIN_POINTS) continue
                lines += ContourLine(
                    elevationFeet = feet,
                    isIndex = isIndex,
                    points = run.map { grid.latitudeAt(it.row) to grid.longitudeAt(it.column) }
                )
            }
        }

        return ContourSet(
            interval = interval,
            lines = lines,
            lowestFeet = lowFeet.roundToInt(),
            highestFeet = highFeet.roundToInt(),
            coverage = grid.coverage()
        )
    }

    /** Every cell crossing at one level, as unordered segments. */
    internal fun trace(grid: ElevationGrid, level: Double): List<Pair<GridPoint, GridPoint>> {
        val segments = mutableListOf<Pair<GridPoint, GridPoint>>()
        for (row in 0 until grid.rows - 1) {
            for (column in 0 until grid.columns - 1) {
                val topLeft = grid.at(row, column)
                val topRight = grid.at(row, column + 1)
                val bottomRight = grid.at(row + 1, column + 1)
                val bottomLeft = grid.at(row + 1, column)
                // A cell missing any corner is skipped whole. Interpolating
                // across a hole would draw a contour through ground nobody
                // has any data for.
                if (topLeft.isNaN() || topRight.isNaN() ||
                    bottomRight.isNaN() || bottomLeft.isNaN()
                ) {
                    continue
                }

                var code = 0
                if (topLeft >= level) code = code or 8
                if (topRight >= level) code = code or 4
                if (bottomRight >= level) code = code or 2
                if (bottomLeft >= level) code = code or 1
                if (code == 0 || code == 15) continue

                val r = row.toDouble()
                val c = column.toDouble()
                // Crossings on each edge, named for the edge they sit on.
                fun top() = GridPoint(c + fraction(topLeft, topRight, level), r)
                fun bottom() = GridPoint(c + fraction(bottomLeft, bottomRight, level), r + 1)
                fun left() = GridPoint(c, r + fraction(topLeft, bottomLeft, level))
                fun right() = GridPoint(c + 1, r + fraction(topRight, bottomRight, level))

                when (code) {
                    1, 14 -> segments += left() to bottom()
                    2, 13 -> segments += bottom() to right()
                    3, 12 -> segments += left() to right()
                    4, 11 -> segments += top() to right()
                    6, 9 -> segments += top() to bottom()
                    7, 8 -> segments += left() to top()
                    // The two ambiguous cells. Whether the high ground is
                    // joined through the middle or the low ground is decides
                    // which pair of crossings belong together; the average of
                    // the four corners stands in for the centre sample that
                    // was never taken.
                    5 -> {
                        val centre = (topLeft + topRight + bottomRight + bottomLeft) / 4.0
                        if (centre >= level) {
                            segments += left() to top()
                            segments += bottom() to right()
                        } else {
                            segments += top() to right()
                            segments += left() to bottom()
                        }
                    }
                    10 -> {
                        val centre = (topLeft + topRight + bottomRight + bottomLeft) / 4.0
                        if (centre >= level) {
                            segments += top() to right()
                            segments += left() to bottom()
                        } else {
                            segments += left() to top()
                            segments += bottom() to right()
                        }
                    }
                }
            }
        }
        return segments
    }

    /** Where between two corner elevations the level falls. */
    private fun fraction(from: Double, to: Double, level: Double): Double {
        val span = to - from
        if (abs(span) < 1e-12) return 0.5
        return ((level - from) / span).coerceIn(0.0, 1.0)
    }

    /**
     * Joins segments into runs.
     *
     * Endpoints are matched on a rounded key rather than on equality: the same
     * crossing computed from either side of a shared edge differs in the last
     * bits, and matching exactly would leave every contour as a heap of
     * two-point fragments.
     */
    internal fun stitch(
        segments: List<Pair<GridPoint, GridPoint>>
    ): List<List<GridPoint>> {
        // Every segment end, indexed by where it is.
        val heads = HashMap<Long, MutableList<Int>>()
        fun keyOf(point: GridPoint): Long {
            val x = (point.column * QUANTUM).roundToInt().toLong()
            val y = (point.row * QUANTUM).roundToInt().toLong()
            return (x shl 32) xor (y and 0xFFFFFFFFL)
        }
        segments.forEachIndexed { index, segment ->
            heads.getOrPut(keyOf(segment.first)) { mutableListOf() } += index
            heads.getOrPut(keyOf(segment.second)) { mutableListOf() } += index
        }

        val used = BooleanArray(segments.size)
        val runs = mutableListOf<List<GridPoint>>()

        /** Walks from one end of a segment until nothing joins on. */
        fun extend(from: GridPoint, run: ArrayDeque<GridPoint>, append: Boolean) {
            var tip = from
            while (true) {
                val candidates = heads[keyOf(tip)] ?: return
                val next = candidates.firstOrNull { !used[it] } ?: return
                used[next] = true
                val (a, b) = segments[next]
                val far = if (keyOf(a) == keyOf(tip)) b else a
                if (append) run.addLast(far) else run.addFirst(far)
                tip = far
            }
        }

        for (index in segments.indices) {
            if (used[index]) continue
            used[index] = true
            val (a, b) = segments[index]
            val run = ArrayDeque<GridPoint>()
            run.addLast(a)
            run.addLast(b)
            extend(b, run, append = true)
            extend(a, run, append = false)
            runs += run.toList()
        }
        return runs
    }

    /** Grid units are cells; a hundredth of a cell is well inside the noise. */
    private const val QUANTUM = 100.0
}
