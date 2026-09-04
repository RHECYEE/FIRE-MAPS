package com.rhecyee.firelinemap.geopdf

import android.graphics.Bitmap
import com.rhecyee.firelinemap.map.Earth
import com.rhecyee.firelinemap.map.MapCoverage
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max

/**
 * A georeferenced sheet with nothing printed on it, so terrain can be the map.
 *
 * Until now the whole map view hung off an imported GeoPDF: every position on
 * screen was worked out through that sheet's frame, so with no sheet there was
 * no coordinate system, and the canvas showed "NO MAP IMPORTED" rather than
 * drawing anything -- terrain included. That made the topo a margin fill for a
 * product map instead of a base map, which is not what a base map is for. An
 * operator standing on a road with no incident product yet still needs to see
 * where they are.
 *
 * Rather than build a second rendering path for that case, this supplies the
 * missing piece: a frame covering ground around the operator, with a blank page
 * behind it. Everything downstream -- terrain tiles, position, tracks, markers,
 * measurement, tap-to-place -- goes through a frame already and carries on
 * working, and the incident sheet drops straight back in on top when there is
 * one.
 */
object TerrainSheet {

    /** Page size of the blank sheet, in PDF points. Square, so nothing stretches. */
    const val PAGE_POINTS = 1000

    /**
     * Half the width of the ground covered.
     *
     * Twenty kilometres each way is about a division's worth of ground: wide
     * enough that the drive in, ICP and the line are all on the same sheet, and
     * tight enough that the canvas's own zoom range still reaches the few
     * metres per pixel where a road is a road rather than a smudge.
     */
    const val HALF_SPAN_METERS = 20_000.0

    /** How far off the anchor the operator gets before the ground is rebuilt. */
    const val REANCHOR_METERS = 12_000.0

    private const val ID_PREFIX = "terrain@"
    private const val MAX_LATITUDE = 85.0

    /** Keeps the longitude span finite where the cosine collapses. */
    private const val MIN_COSINE = 0.02

    /**
     * The ground around a position, as a frame.
     *
     * Deliberately carries no WKT. [MapFrame] then interpolates between the
     * corners, and for an axis-aligned box that interpolation is exactly the
     * plate carree the corners describe -- latitude falling out of the vertical
     * alone and longitude out of the horizontal alone. Naming a projection
     * would add a least-squares fit with nothing to fit. The east-west span is
     * widened by the cosine of the latitude so the box is square on the ground
     * and not merely square in degrees, which is what stops the terrain being
     * stretched sideways on the square page it is drawn onto.
     */
    fun frame(
        latitude: Double,
        longitude: Double,
        halfSpanMeters: Double = HALF_SPAN_METERS
    ): MapFrame {
        val centre = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val latitudeSpan = halfSpanMeters / Earth.METERS_PER_DEGREE
        val shrink = max(cos(Math.toRadians(centre)), MIN_COSINE)
        val longitudeSpan = halfSpanMeters / (Earth.METERS_PER_DEGREE * shrink)

        val south = (centre - latitudeSpan).coerceAtLeast(-MAX_LATITUDE)
        val north = (centre + latitudeSpan).coerceAtMost(MAX_LATITUDE)
        val west = longitude - longitudeSpan
        val east = longitude + longitudeSpan

        return MapFrame(
            name = "Terrain",
            box = PageBox(0.0, 0.0, PAGE_POINTS.toDouble(), PAGE_POINTS.toDouble()),
            // Ordered to match localCorners below: lower-left, upper-left,
            // upper-right, lower-right.
            geoCorners = listOf(
                GeoPoint(south, west),
                GeoPoint(north, west),
                GeoPoint(north, east),
                GeoPoint(south, east)
            ),
            localCorners = listOf(
                0.0 to 0.0,
                0.0 to 1.0,
                1.0 to 1.0,
                1.0 to 0.0
            ),
            wkt = null
        )
    }

    /**
     * The sheet itself.
     *
     * The id carries the anchor. The canvas resets its pan and zoom when the
     * sheet identity changes, which is right when the ground underneath has
     * genuinely been rebuilt somewhere else and wrong on every GPS fix in
     * between, so the anchor only moves when [needsReanchor] says so.
     */
    fun map(latitude: Double, longitude: Double): ImportedMap = ImportedMap(
        id = idFor(latitude, longitude),
        displayName = "Terrain",
        // Never opened: the canvas is handed the page bitmap directly, and
        // nothing re-reads a terrain sheet off disk because there is none.
        file = File(""),
        document = GeoPdfDocument(listOf(frame(latitude, longitude)), PdfKind.GEOREFERENCED)
    )

    fun idFor(latitude: Double, longitude: Double): String =
        ID_PREFIX + String.format(Locale.US, "%.4f,%.4f", latitude, longitude)

    fun isTerrain(id: String?): Boolean = id?.startsWith(ID_PREFIX) == true

    /** Whether the operator has travelled far enough off the anchor to rebuild. */
    fun needsReanchor(
        anchorLatitude: Double,
        anchorLongitude: Double,
        latitude: Double,
        longitude: Double
    ): Boolean = MapCoverage.distanceMeters(
        anchorLatitude, anchorLongitude, latitude, longitude
    ) > REANCHOR_METERS

    /**
     * The blank page the frame is pinned to.
     *
     * Fully transparent, and square so it scales the same way in both axes. The
     * pixel size cancels out of the canvas's fit calculation, so this is kept
     * small: it exists to be a rectangle with an aspect ratio, not to be looked
     * at. Terrain is drawn across the whole canvas rather than inside this
     * rectangle, so nothing here bounds what the operator can see.
     */
    fun blankPage(): Bitmap =
        Bitmap.createBitmap(BLANK_PIXELS, BLANK_PIXELS, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.TRANSPARENT)
        }

    private const val BLANK_PIXELS = 256
}
