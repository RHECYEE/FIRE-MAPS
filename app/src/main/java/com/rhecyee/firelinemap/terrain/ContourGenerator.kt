package com.rhecyee.firelinemap.terrain

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Contour lines from a grid of elevations.
 *
 * The topographic basemap has contours drawn into it, but they are part of a
 * photograph of a map: they cannot be turned off, they cannot be re-intervalled,
 * and they cannot be lifted off and laid over an incident sheet. An operator
 * reading a division map wants the contours *on* that map, at the interval the
 * ground calls for, and that means drawing them rather than downloading a
 * picture of them.
 *
 * Marching squares, which is the standard way and is exact at the resolution of
 * the grid it is given. Deliberately free of Android types: where a contour
 * falls is arithmetic, and arithmetic is testable without a device.
 */

/** One straight piece of a contour, in grid coordinates. */
data class ContourSegment(
    val x1: Double, val y1: Double,
    val x2: Double, val y2: Double,
)

/** A run of joined segments: one continuous stroke of a contour. */
data class ContourPath(val points: List<ContourPoint>, val closed: Boolean)

/** A point in grid coordinates. */
data class ContourPoint(val x: Double, val y: Double)

/** Every segment traced at one elevation. */
data class ContourLine(val elevation: Double, val segments: List<ContourSegment>) {
    /** Index contours are drawn heavier and are the ones that carry a label. */
    fun isIndex(interval: Double, every: Int = 5): Boolean {
        if (interval <= 0 || every <= 0) return false
        return Math.round(elevation / interval) % every == 0L
    }
}

object ContourGenerator {

    const val FEET_PER_METER = 3.280839895

    /** Intervals offered to the operator, in feet. */
    val INTERVALS_FEET = listOf(20, 40, 80, 100, 200, 500)

    /**
     * Elevations to trace between two bounds.
     *
     * Snapped to multiples of the interval so a contour sits where a map reader
     * expects it -- 7,400 rather than 7,383 -- and so the same line lands at the
     * same elevation whatever window happens to be on screen.
     */
    fun levels(minimum: Double, maximum: Double, interval: Double): List<Double> {
        if (interval <= 0 || maximum <= minimum) return emptyList()
        val first = ceil(minimum / interval) * interval
        val last = floor(maximum / interval) * interval
        if (last < first) return emptyList()
        val count = ((last - first) / interval).toInt() + 1
        // A window spanning the Grand Canyon at a twenty foot interval is
        // thousands of lines nobody can read. Beyond this the interval is the
        // wrong choice, and drawing them all would only cost the frame.
        if (count > MAX_LEVELS) return emptyList()
        return (0 until count).map { first + it * interval }
    }

    /**
     * Traces one elevation across a grid.
     *
     * [grid] is row-major, [width] samples across and [height] down, and the
     * coordinates returned are in grid cells: (0,0) is the first sample and
     * (width-1, height-1) the last. The caller knows what ground a cell covers.
     */
    fun trace(
        grid: FloatArray,
        width: Int,
        height: Int,
        level: Double,
    ): List<ContourSegment> {
        if (width < 2 || height < 2 || grid.size < width * height) return emptyList()
        val out = ArrayList<ContourSegment>()

        for (row in 0 until height - 1) {
            for (column in 0 until width - 1) {
                val topLeft = grid[row * width + column].toDouble()
                val topRight = grid[row * width + column + 1].toDouble()
                val bottomRight = grid[(row + 1) * width + column + 1].toDouble()
                val bottomLeft = grid[(row + 1) * width + column].toDouble()
                if (topLeft.isNaN() || topRight.isNaN() ||
                    bottomRight.isNaN() || bottomLeft.isNaN()
                ) continue

                // Corners at exactly the level are treated as above, so a flat
                // plateau at a contour elevation produces one boundary rather
                // than a shimmer of segments along every cell edge.
                var code = 0
                if (topLeft >= level) code = code or 8
                if (topRight >= level) code = code or 4
                if (bottomRight >= level) code = code or 2
                if (bottomLeft >= level) code = code or 1
                if (code == 0 || code == 15) continue

                val x = column.toDouble()
                val y = row.toDouble()
                fun top() = x + cut(topLeft, topRight, level) to y
                fun right() = x + 1 to y + cut(topRight, bottomRight, level)
                fun bottom() = x + cut(bottomLeft, bottomRight, level) to y + 1
                fun left() = x to y + cut(topLeft, bottomLeft, level)

                fun add(a: Pair<Double, Double>, b: Pair<Double, Double>) {
                    out.add(ContourSegment(a.first, a.second, b.first, b.second))
                }

                when (code) {
                    1, 14 -> add(left(), bottom())
                    2, 13 -> add(bottom(), right())
                    3, 12 -> add(left(), right())
                    4, 11 -> add(top(), right())
                    6, 9 -> add(top(), bottom())
                    7, 8 -> add(left(), top())
                    // Saddles. Resolved against the cell's average so the two
                    // strands connect the way the surface actually runs; the
                    // arbitrary choice is what produces crossed contours.
                    5 -> {
                        val middle = (topLeft + topRight + bottomRight + bottomLeft) / 4
                        if (middle >= level) {
                            add(left(), top()); add(bottom(), right())
                        } else {
                            add(left(), bottom()); add(top(), right())
                        }
                    }
                    10 -> {
                        val middle = (topLeft + topRight + bottomRight + bottomLeft) / 4
                        if (middle >= level) {
                            add(top(), right()); add(left(), bottom())
                        } else {
                            add(left(), top()); add(bottom(), right())
                        }
                    }
                }
            }
        }
        return out
    }

    /** Every contour across a grid, at one interval. */
    fun contours(
        grid: FloatArray,
        width: Int,
        height: Int,
        interval: Double,
    ): List<ContourLine> {
        val usable = grid.filter { !it.isNaN() }
        if (usable.isEmpty()) return emptyList()
        return levels(usable.min().toDouble(), usable.max().toDouble(), interval)
            .mapNotNull { level ->
                val segments = trace(grid, width, height, level)
                if (segments.isEmpty()) null else ContourLine(level, segments)
            }
    }

    /**
     * Chains loose segments into strokes.
     *
     * Marching squares emits each cell's crossing on its own and in no
     * particular order. Drawn that way a contour is thousands of one-cell
     * dashes: every one costs a draw call, a dashed or tapered stroke has
     * nothing to run along, and there is nowhere to hang an elevation label.
     * Joined, a contour is the one thing a map reader thinks it is -- a line.
     *
     * Endpoints from neighbouring cells are computed from the same pair of
     * samples, so they are bit-identical and match on the nose; no tolerance
     * is needed or wanted. Where a contour runs exactly through a grid corner
     * more than two ends can meet, and there the walk stops rather than
     * guessing which way the line went.
     */
    fun join(segments: List<ContourSegment>): List<ContourPath> {
        if (segments.isEmpty()) return emptyList()

        val ends = HashMap<ContourPoint, MutableList<Int>>(segments.size * 2)
        for ((index, segment) in segments.withIndex()) {
            ends.getOrPut(ContourPoint(segment.x1, segment.y1)) { ArrayList(2) }.add(index)
            ends.getOrPut(ContourPoint(segment.x2, segment.y2)) { ArrayList(2) }.add(index)
        }

        fun other(index: Int, from: ContourPoint): ContourPoint {
            val segment = segments[index]
            val first = ContourPoint(segment.x1, segment.y1)
            return if (first == from) ContourPoint(segment.x2, segment.y2) else first
        }

        /** The one unused segment carrying on from here, if there is exactly one. */
        fun next(from: ContourPoint, used: BooleanArray): Int? {
            val touching = ends[from] ?: return null
            // A junction: more than two strands meet, so which one continues
            // this stroke is not determined. Break the line instead.
            if (touching.size > 2) return null
            return touching.firstOrNull { !used[it] }
        }

        val used = BooleanArray(segments.size)
        val paths = ArrayList<ContourPath>()

        for (seed in segments.indices) {
            if (used[seed]) continue
            used[seed] = true
            val head = ContourPoint(segments[seed].x1, segments[seed].y1)
            val tail = ContourPoint(segments[seed].x2, segments[seed].y2)

            val points = ArrayDeque<ContourPoint>()
            points.addFirst(head)
            points.addLast(tail)

            // Forward from the tail.
            var end = tail
            while (true) {
                val index = next(end, used) ?: break
                used[index] = true
                end = other(index, end)
                if (end == head) break
                points.addLast(end)
            }
            val closed = end == head

            // Then backward from the head, unless the stroke already met itself.
            if (!closed) {
                var start = head
                while (true) {
                    val index = next(start, used) ?: break
                    used[index] = true
                    start = other(index, start)
                    points.addFirst(start)
                }
            }

            paths.add(ContourPath(points.toList(), closed))
        }
        return paths
    }

    /** Where between two samples the level falls. */
    private fun cut(from: Double, to: Double, level: Double): Double {
        val span = to - from
        if (span == 0.0) return 0.5
        return ((level - from) / span).coerceIn(0.0, 1.0)
    }

    private const val MAX_LEVELS = 60
}
