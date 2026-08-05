package com.rhecyee.firelinemap.share

import com.rhecyee.firelinemap.map.MapCoverage
import kotlin.math.abs

/**
 * Dropping points a track does not need.
 *
 * A shift recorded every five seconds is thousands of fixes, and almost all of
 * them say the same thing: the road went straight here. Texting is where that
 * matters -- a picture message is capped somewhere around three hundred
 * kilobytes on most carriers and simply refused above it, so a track that is
 * not thinned is a track that does not send.
 *
 * Douglas-Peucker: keep the point furthest from the straight line between the
 * ends, recurse either side, discard anything that never moves the line by
 * more than the tolerance. Bends, switchbacks and turnarounds survive by
 * construction, because they are exactly the points that are far from the
 * line. What goes is the straight running in between.
 *
 * The first and last points are always kept, so a track never gets shorter at
 * its ends -- where it started and where it finished are the two things nobody
 * can afford to lose.
 */
object TrackSimplify {

    /**
     * Thins a track until no point is further than [toleranceMeters] from the
     * line that replaces it.
     */
    fun simplify(points: List<SharePoint>, toleranceMeters: Double): List<SharePoint> {
        if (points.size <= 2 || toleranceMeters <= 0) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        thin(points, 0, points.lastIndex, toleranceMeters, keep)
        return points.filterIndexed { index, _ -> keep[index] }
    }

    /**
     * Thins only as far as it has to, to fit a budget.
     *
     * Tolerance is stepped up until the rendered file fits. Deliberately in the
     * direction of keeping detail: it starts fine and coarsens, so a track that
     * already fits is sent untouched and one that does not loses the least it
     * can rather than a fixed amount.
     *
     * [render] measures the whole file, not the points, because the metadata
     * and the pins count against the same budget.
     */
    fun toFit(
        pkg: SharePackage,
        budgetBytes: Int,
        render: (SharePackage) -> Int
    ): Simplified {
        if (render(pkg) <= budgetBytes) return Simplified(pkg, 0.0, false)

        var tolerance = 5.0
        var last = pkg
        while (tolerance <= MAX_TOLERANCE_METERS) {
            val thinned = pkg.copy(
                tracks = pkg.tracks.map { track ->
                    track.copy(points = simplify(track.points, tolerance))
                }
            )
            last = thinned
            if (render(thinned) <= budgetBytes) return Simplified(thinned, tolerance, true)
            tolerance *= 2
        }
        // Past this the shape stops being the shape. Better to hand back the
        // coarsest honest version and let the caller say it will not fit than
        // to keep thinning until the track is a straight line.
        return Simplified(last, MAX_TOLERANCE_METERS, true, fits = false)
    }

    /** The result of fitting a package to a budget. */
    data class Simplified(
        val pkg: SharePackage,
        val toleranceMeters: Double,
        val wasSimplified: Boolean,
        /** False when even the coarsest version is still over budget. */
        val fits: Boolean = true
    ) {
        fun describe(original: SharePackage): String {
            if (!wasSimplified) return "Full detail"
            val before = original.tracks.sumOf { it.points.size }
            val after = pkg.tracks.sumOf { it.points.size }
            val metres = toleranceMeters.toInt()
            return "Thinned to fit: $before points to $after, within $metres m of the real line"
        }
    }

    /**
     * Past this the shape stops being the shape.
     *
     * A hundred metres will still show which road was driven and which drainage
     * was walked. It will not show a turnaround, and it must never be applied
     * silently.
     */
    const val MAX_TOLERANCE_METERS = 100.0

    private fun thin(
        points: List<SharePoint>,
        from: Int,
        to: Int,
        tolerance: Double,
        keep: BooleanArray
    ) {
        if (to <= from + 1) return
        var worst = 0.0
        var worstIndex = -1
        for (index in from + 1 until to) {
            val distance = distanceToSegment(points[index], points[from], points[to])
            if (distance > worst) {
                worst = distance
                worstIndex = index
            }
        }
        if (worstIndex < 0 || worst <= tolerance) return
        keep[worstIndex] = true
        thin(points, from, worstIndex, tolerance, keep)
        thin(points, worstIndex, to, tolerance, keep)
    }

    /**
     * How far a point lies off the line between two others, in metres.
     *
     * Worked in metres rather than in degrees. A degree of longitude is a
     * different distance at every latitude, so a tolerance in degrees thins
     * far harder in the north than in the south -- and the difference across
     * the western states is enough to matter.
     */
    private fun distanceToSegment(
        point: SharePoint,
        start: SharePoint,
        end: SharePoint
    ): Double {
        val alongX = MapCoverage.distanceMeters(
            start.latitude, start.longitude, start.latitude, end.longitude
        ) * signOf(end.longitude - start.longitude)
        val alongY = MapCoverage.distanceMeters(
            start.latitude, start.longitude, end.latitude, start.longitude
        ) * signOf(end.latitude - start.latitude)

        val pointX = MapCoverage.distanceMeters(
            start.latitude, start.longitude, start.latitude, point.longitude
        ) * signOf(point.longitude - start.longitude)
        val pointY = MapCoverage.distanceMeters(
            start.latitude, start.longitude, point.latitude, start.longitude
        ) * signOf(point.latitude - start.latitude)

        val lengthSquared = alongX * alongX + alongY * alongY
        if (lengthSquared <= 0.0) {
            // Start and end are the same place, so the segment is a point.
            return kotlin.math.sqrt(pointX * pointX + pointY * pointY)
        }
        val cross = abs(alongX * pointY - alongY * pointX)
        return cross / kotlin.math.sqrt(lengthSquared)
    }

    private fun signOf(value: Double): Double = if (value < 0) -1.0 else 1.0
}
