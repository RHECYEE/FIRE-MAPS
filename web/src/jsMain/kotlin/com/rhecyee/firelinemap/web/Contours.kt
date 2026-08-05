package com.rhecyee.firelinemap.web

import com.rhecyee.firelinemap.terrain.ContourBuilder
import com.rhecyee.firelinemap.terrain.ContourDetail
import com.rhecyee.firelinemap.terrain.ContourIntervals
import com.rhecyee.firelinemap.terrain.DemSource
import com.rhecyee.firelinemap.terrain.ElevationGrid
import com.rhecyee.firelinemap.terrain.TerrainMath
import kotlin.js.json

/**
 * Contours, from the same elevation data the phone draws.
 *
 * The browser fetches the tiles and decodes their pixels -- that is what a
 * browser is good at -- and everything after that is the phone's own code:
 * decoding a terrarium pixel into metres, choosing an interval off the USGS
 * quadrangle ladder, and tracing the lines. A contour is a claim about the
 * shape of the ground, and two apps tracing it differently would put the same
 * ridge in two places.
 *
 * The lines come back in latitude and longitude rather than screen pixels. The
 * page already owns tile placement and redraws on every pan; handing it
 * geography means a pan costs a redraw rather than a retrace.
 */
@JsExport
@JsName("FirelineContours")
object Contours {

    /** Where the tiles come from, and what the source asks to be credited. */
    fun tileUrl(zoom: Int, x: Int, y: Int): String = DemSource.url(zoom, x, y)

    val attribution: String get() = DemSource.ATTRIBUTION
    val tileSize: Int get() = DemSource.TILE_SIZE
    val maxZoom: Int get() = DemSource.MAX_ZOOM

    /** One terrarium pixel as metres above sea level. */
    fun elevationOf(red: Int, green: Int, blue: Int): Double =
        TerrainMath.decodeTerrarium(red, green, blue)

    /**
     * Traces contours across a decoded grid.
     *
     * [heights] is row-major metres, [columns] wide, running from [north] down
     * to [south] and [west] across to [east]. The interval is chosen from the
     * zoom and from how much relief is actually in view, so a flat bench and a
     * canyon wall at the same zoom do not get the same spacing -- one would be
     * blank and the other a smear.
     *
     * [detail] is FINE, NORMAL or COARSE.
     */
    @Suppress("LongParameterList")
    fun trace(
        heights: Array<Double>,
        columns: Int,
        rows: Int,
        north: Double,
        south: Double,
        west: Double,
        east: Double,
        zoom: Int,
        detail: String
    ): String {
        if (columns < 2 || rows < 2 || heights.size < columns * rows) {
            return JSON.stringify(json("ready" to false, "lines" to emptyArray<String>()))
        }

        val grid = ElevationGrid(
            columns = columns,
            rows = rows,
            values = DoubleArray(columns * rows) { heights[it] },
            north = north, south = south, west = west, east = east
        )

        val relief = grid.relief()
        val reliefFeet = relief?.let { (low, high) -> (high - low) * FEET_PER_METRE }
        val interval = ContourIntervals.forView(
            zoom = zoom,
            reliefFeet = reliefFeet,
            detail = ContourDetail.fromName(detail)
        )

        val set = ContourBuilder.build(grid, interval)

        return JSON.stringify(
            json(
                "ready" to set.lines.isNotEmpty(),
                "intervalFeet" to interval.feet,
                "coverage" to grid.coverage(),
                "lines" to set.lines.map { line ->
                    // Flattened to a plain number array: an array of objects
                    // per point costs more to cross the boundary than the
                    // whole trace costs to compute.
                    val flat = ArrayList<Double>(line.points.size * 2)
                    line.points.forEach { (latitude, longitude) ->
                        flat.add(latitude)
                        flat.add(longitude)
                    }
                    json(
                        "feet" to line.elevationFeet,
                        // Index lines carry the numbers and are drawn heavier.
                        "index" to line.isIndex,
                        "points" to flat.toTypedArray()
                    )
                }.toTypedArray()
            )
        )
    }

    private const val FEET_PER_METRE = 3.280839895013123
}
