package com.rhecyee.firelinemap.ui

import com.rhecyee.firelinemap.geopdf.MapFrame
import com.rhecyee.firelinemap.map.BasemapTileCache
import com.rhecyee.firelinemap.map.GeoBounds
import com.rhecyee.firelinemap.map.TileMath
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

/**
 * The ground actually on screen, as a latitude and longitude box.
 *
 * All four corners of the view, not two. Page space and true north are not
 * aligned: a sheet drawn in UTM is turned by its own grid convergence, and a
 * cartographer will turn it further to fit a fire's long axis onto the paper.
 * The view is then a rotated rectangle on the ground, and the box through two
 * opposite corners of it is not the box that contains it -- it is short by the
 * view's other dimension times the sine of that angle, along two opposite
 * edges. That shortfall is what terrain gets asked for, so that is what gets
 * drawn: bare strips down two sides of the map. A degree of convergence leaves
 * about forty pixels of them; a sheet turned thirty degrees on the page leaves
 * something closer to a quarter of the screen at the top and the bottom.
 *
 * Null when the frame cannot place a corner, or when the result is degenerate.
 */
internal fun visibleGeoBounds(
    frame: MapFrame,
    pageWidthPoints: Int,
    pageHeightPoints: Int,
    originX: Float,
    originY: Float,
    drawWidth: Float,
    drawHeight: Float,
    viewWidth: Float,
    viewHeight: Float
): GeoBounds? {
    if (pageWidthPoints <= 0 || pageHeightPoints <= 0) return null
    if (drawWidth <= 0f || drawHeight <= 0f) return null
    if (viewWidth <= 0f || viewHeight <= 0f) return null

    var north = -Double.MAX_VALUE
    var south = Double.MAX_VALUE
    var west = Double.MAX_VALUE
    var east = -Double.MAX_VALUE

    for (corner in CORNERS) {
        val screenX = corner.first * viewWidth
        val screenY = corner.second * viewHeight
        val fx = (screenX - originX) / drawWidth
        val fy = (screenY - originY) / drawHeight
        val geo = frame.pageToGeo(
            fx * pageWidthPoints.toDouble(),
            (1f - fy) * pageHeightPoints.toDouble()
        ) ?: return null
        if (!geo.latitude.isFinite() || !geo.longitude.isFinite()) return null
        north = maxOf(north, geo.latitude)
        south = minOf(south, geo.latitude)
        west = minOf(west, geo.longitude)
        east = maxOf(east, geo.longitude)
    }

    if (north <= south || east <= west) return null
    return GeoBounds(south = south, west = west, north = north, east = east)
}

private val CORNERS = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)

/**
 * The deepest zoom whose tiles will cover [bounds] within a frame's budget.
 *
 * Covering a wide view at full detail can run to thousands of tiles, which is
 * more fetching and more drawing than a frame can carry. The answer to that is
 * coarser terrain, not no terrain: stepping the zoom down keeps the map filled
 * while an early return leaves the operator looking at the background colour
 * and no way to tell that apart from the app being broken.
 *
 * Null only when even the coarsest zoom is too much, which a real view cannot
 * reach.
 */
internal fun basemapZoom(
    bounds: GeoBounds,
    targetMetersPerPixel: Double,
    budget: Long = MAX_TILES_PER_FRAME,
    minZoom: Int = MIN_BASEMAP_ZOOM,
    maxZoom: Int = MAX_BASEMAP_ZOOM
): Int? {
    val centre = (bounds.north + bounds.south) / 2.0
    var zoom = BasemapTileCache.zoomFor(centre, targetMetersPerPixel, maxZoom)
        .coerceIn(minZoom, maxZoom)
    while (zoom > minZoom && TileMath.tileCount(bounds, zoom) > budget) zoom--
    if (TileMath.tileCount(bounds, zoom) > budget) return null
    return zoom
}

internal const val MIN_BASEMAP_ZOOM = 4

/** Tiles one frame may draw. Beyond this the zoom steps down instead. */
internal const val MAX_TILES_PER_FRAME = 200L
