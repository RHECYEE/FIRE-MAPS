package com.rhecyee.firelinemap.terrain

import com.rhecyee.firelinemap.geopdf.MapFrame
import kotlin.math.atan2

/**
 * A contour already turned into page fractions.
 *
 * Projecting geographic points is expensive -- a Transverse Mercator forward
 * for every point -- and the answer does not change when the map is panned or
 * zoomed, only when the lines themselves do. Held as flat float arrays because
 * a screenful of contours is tens of thousands of points and boxed pairs of
 * them are more garbage than a frame budget can carry.
 */
class ProjectedContour(
    val elevationFeet: Int,
    val isIndex: Boolean,
    val xs: FloatArray,
    val ys: FloatArray,
    /** Where the label goes, in the same fractions, and which way it lies. */
    val labelX: Float,
    val labelY: Float,
    val labelDegrees: Float,
    val hasLabel: Boolean
)

/**
 * Contours ready to draw, and what the key should say about them.
 *
 * Deliberately a plain class rather than a data class. A data class would give
 * this structural equality, and Compose compares state on every recomposition
 * -- which during a pinch is every frame. Deep-comparing tens of thousands of
 * points sixty times a second is enough on its own to stop the interface
 * answering. Identity is the correct comparison here anyway: a new set of
 * lines is a new object.
 */
class ContourRender(
    val interval: ContourInterval,
    val lowestFeet: Int,
    val highestFeet: Int,
    val lines: List<ProjectedContour>
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    companion object {
        val NONE = ContourRender(ContourInterval(40), 0, 0, emptyList())
    }
}

/**
 * Turns a contour set into page fractions.
 *
 * Runs off the main thread, once per cut. Doing it during composition or
 * during a draw is what made zooming unusable: the same twenty thousand
 * projections were being repeated for every frame of a gesture, and the work
 * does not depend on the gesture at all.
 *
 * A ceiling on total points is applied because a pathological elevation grid
 * -- a corrupt tile, a nodata band read as terrain -- can ask for far more
 * line than any screen can show, and drawing it would take the app down rather
 * than merely look wrong.
 */
object ContourProjector {

    /** More line than any screen can show; past this something is wrong upstream. */
    const val MAX_POINTS = 120_000

    fun project(
        set: ContourSet,
        frame: MapFrame,
        pageWidthPoints: Int,
        pageHeightPoints: Int,
        maxPoints: Int = MAX_POINTS,
        isActive: () -> Boolean = { true }
    ): ContourRender {
        if (pageWidthPoints <= 0 || pageHeightPoints <= 0) return ContourRender.NONE
        val width = pageWidthPoints.toDouble()
        val height = pageHeightPoints.toDouble()
        val out = ArrayList<ProjectedContour>(set.lines.size)
        var budget = maxPoints

        // Index lines first, so a set too large to draw whole keeps the lines
        // that carry the numbers rather than whichever happened to be traced
        // first.
        for (line in set.lines.sortedByDescending { it.isIndex }) {
            if (budget <= 0 || !isActive()) break
            val count = minOf(line.points.size, budget)
            budget -= count
            if (count < 2) continue

            val xs = FloatArray(count)
            val ys = FloatArray(count)
            var kept = 0
            for (index in 0 until count) {
                val (latitude, longitude) = line.points[index]
                val page = frame.geoToPage(latitude, longitude) ?: continue
                val x = (page.first / width).toFloat()
                val y = 1f - (page.second / height).toFloat()
                if (!x.isFinite() || !y.isFinite()) continue
                xs[kept] = x
                ys[kept] = y
                kept++
            }
            if (kept < 2) continue

            var labelX = 0f
            var labelY = 0f
            var labelDegrees = 0f
            var hasLabel = false
            if (line.isIndex && kept >= 3) {
                val middle = kept / 2
                labelX = xs[middle]
                labelY = ys[middle]
                val before = middle - 1
                val after = (middle + 1).coerceAtMost(kept - 1)
                // Screen space, so the angle is the one the reader sees rather
                // than the one the ground makes.
                var degrees = Math.toDegrees(
                    atan2(
                        (ys[after] - ys[before]).toDouble(),
                        (xs[after] - xs[before]).toDouble()
                    )
                ).toFloat()
                // Never upside down: a number read the wrong way up is misread.
                if (degrees > 90f) degrees -= 180f
                if (degrees < -90f) degrees += 180f
                labelDegrees = degrees
                hasLabel = true
            }

            out += ProjectedContour(
                elevationFeet = line.elevationFeet,
                isIndex = line.isIndex,
                xs = if (kept == xs.size) xs else xs.copyOf(kept),
                ys = if (kept == ys.size) ys else ys.copyOf(kept),
                labelX = labelX,
                labelY = labelY,
                labelDegrees = labelDegrees,
                hasLabel = hasLabel
            )
        }

        return ContourRender(set.interval, set.lowestFeet, set.highestFeet, out)
    }
}
