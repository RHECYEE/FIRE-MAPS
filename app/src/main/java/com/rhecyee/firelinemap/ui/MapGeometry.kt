package com.rhecyee.firelinemap.ui

import com.rhecyee.firelinemap.geopdf.MapFrame
import kotlin.math.hypot

/**
 * The arithmetic behind the map canvas, kept apart from the drawing.
 *
 * Both of these went wrong in a way that only showed up a long way off the
 * sheet, which is exactly where nobody is in a position to debug them, so they
 * live here where they can be tested against a real frame on the JVM.
 */

/**
 * Where a tile's four corners land, in draw order NW, NE, SE, SW.
 *
 * Returned as a quad rather than a rectangle because a tile is not a rectangle
 * on the page. A sheet drawn in UTM is rotated against true north by the grid
 * convergence, so a square of latitude and longitude lands on the page as a
 * rotated, slightly sheared quadrilateral. Near the sheet's own meridian that
 * rotation is a fraction of a pixel and a rectangle through two opposite
 * corners looks right; a long way off it, it is degrees, and drawing each tile
 * as a rectangle leaves wedges of background between neighbours -- the bars
 * that show up as soon as the operator is working off the end of the sheet.
 *
 * Corners are shared exactly between neighbouring tiles, so quads drawn from
 * this tile the plane with no gaps to leave, whatever the frame is doing.
 */
internal fun tileQuad(
    frame: MapFrame,
    north: Double,
    south: Double,
    west: Double,
    east: Double,
    pageWidthPoints: Int,
    pageHeightPoints: Int,
    originX: Float,
    originY: Float,
    drawWidth: Float,
    drawHeight: Float
): FloatArray? {
    if (pageWidthPoints <= 0 || pageHeightPoints <= 0) return null
    val quad = FloatArray(8)
    val corners = arrayOf(
        north to west,
        north to east,
        south to east,
        south to west
    )
    corners.forEachIndexed { index, (latitude, longitude) ->
        val page = frame.geoToPage(latitude, longitude) ?: return null
        val x = originX + (page.first / pageWidthPoints).toFloat() * drawWidth
        val y = originY + (1f - (page.second / pageHeightPoints).toFloat()) * drawHeight
        if (!x.isFinite() || !y.isFinite()) return null
        quad[index * 2] = x
        quad[index * 2 + 1] = y
    }
    return quad
}

/**
 * Grows a quad about its own centre so neighbours overlap by about a pixel.
 *
 * Adjacent tiles already share their corners exactly, but each is clipped to
 * its own edge and a shared edge rounded independently either side can still
 * show a hairline. Overlapping is the cheaper of the two failures: a sliver of
 * one tile drawn over its neighbour is invisible, a sliver of background is a
 * seam across the map.
 */
internal fun growQuad(quad: FloatArray, byPixels: Float = 0.75f): FloatArray {
    val centreX = (quad[0] + quad[2] + quad[4] + quad[6]) / 4f
    val centreY = (quad[1] + quad[3] + quad[5] + quad[7]) / 4f
    val grown = FloatArray(8)
    for (corner in 0 until 4) {
        val dx = quad[corner * 2] - centreX
        val dy = quad[corner * 2 + 1] - centreY
        val reach = hypot(dx, dy)
        val scale = if (reach > 0.001f) (reach + byPixels) / reach else 1f
        grown[corner * 2] = centreX + dx * scale
        grown[corner * 2 + 1] = centreY + dy * scale
    }
    return grown
}

/** The longest side of a quad, used to reject a transform that has blown up. */
internal fun quadExtent(quad: FloatArray): Float {
    var widest = 0f
    for (corner in 0 until 4) {
        val next = (corner + 1) % 4
        widest = maxOf(
            widest,
            hypot(
                quad[next * 2] - quad[corner * 2],
                quad[next * 2 + 1] - quad[corner * 2 + 1]
            )
        )
    }
    return widest
}

/**
 * The pan offset that holds the ground under a pinch still while it zooms.
 *
 * The canvas draws content at `centre + offset + u * scale`, so changing scale
 * without touching the offset moves everything by its own distance from the
 * view centre times the change. On the sheet that distance is under a page
 * width and the shift is small enough to read as zooming. Off the sheet the
 * same content sits tens of page widths out, and a two-percent pinch throws it
 * hundreds of pixels sideways -- the map teleporting out from under whoever is
 * pinching it. Solving for the offset that leaves the focal point where it is
 * removes the distance from the arithmetic entirely.
 */
internal fun zoomedOffset(
    offsetX: Float,
    offsetY: Float,
    focusX: Float,
    focusY: Float,
    ratio: Float
): Pair<Float, Float> {
    if (!ratio.isFinite() || ratio <= 0f) return offsetX to offsetY
    return (offsetX * ratio + focusX * (1f - ratio)) to
        (offsetY * ratio + focusY * (1f - ratio))
}
