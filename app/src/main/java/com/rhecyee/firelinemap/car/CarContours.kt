package com.rhecyee.firelinemap.car

import android.graphics.Path
import com.rhecyee.firelinemap.terrain.GeoContour

/**
 * Contours flattened for the car surface.
 *
 * Held as a fraction of the whole world rather than in pixels, because the
 * view moves under them every frame while the lines themselves only change
 * when a new trace lands. In these units a frame is a scale and a translate --
 * one matrix -- instead of tens of thousands of projections at four frames a
 * second on a head unit that has other things to do.
 */
class CarContourPaths(val regular: Path, val index: Path) {
    val isEmpty: Boolean get() = regular.isEmpty && index.isEmpty
}

/**
 * Projects traced contours into world fractions.
 *
 * The antimeridian is not handled, in step with the terrain tiles beside it: a
 * view straddling it is not ground this tool covers, and a line drawn the long
 * way round the planet is no worse than the tiles already are there.
 */
fun buildCarContourPaths(contours: List<GeoContour>): CarContourPaths? {
    if (contours.isEmpty()) return null
    val regular = Path()
    val index = Path()

    for (contour in contours) {
        val target = if (contour.index) index else regular
        var started = false
        var firstX = 0f
        var firstY = 0f
        for (vertex in contour.points) {
            val x = CarMapProjection.unitX(vertex.longitude).toFloat()
            val y = CarMapProjection.unitY(vertex.latitude).toFloat()
            if (!x.isFinite() || !y.isFinite()) {
                started = false
                continue
            }
            if (!started) {
                target.moveTo(x, y)
                firstX = x
                firstY = y
                started = true
            } else {
                target.lineTo(x, y)
            }
        }
        if (contour.closed && started) target.lineTo(firstX, firstY)
    }

    val paths = CarContourPaths(regular, index)
    return if (paths.isEmpty) null else paths
}
