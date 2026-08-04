package com.rhecyee.firelinemap.map

import com.rhecyee.firelinemap.geopdf.MapFrame
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tan

/**
 * Ground to the canvas's unit square, and back.
 *
 * Everything drawn on the map -- the position, tracks, pins, measurements,
 * contours, terrain tiles -- goes through this. Until now the only thing that
 * could provide it was an imported sheet, which meant the app showed nothing
 * at all until somebody had a GeoPDF to hand. That is exactly backwards for a
 * tool whose case is that it works with no server and no onboarding: the first
 * five minutes on a new fire are the ones with no product yet.
 *
 * The unit square runs 0 to 1 left to right and 0 to 1 top to bottom, so y
 * increases the way screen coordinates do rather than the way a PDF page does.
 * Outside 0..1 is legal and normal -- ground off the sheet still has to be
 * pointed at.
 */
interface MapProjection {

    fun toUnit(latitude: Double, longitude: Double): Pair<Float, Float>?

    fun toGeo(x: Float, y: Float): Pair<Double, Double>?

    /** Nominal size of the content, which sets the fit-to-view scale. */
    val contentWidth: Float
    val contentHeight: Float

    /** How far in this projection is worth zooming. */
    val maxScale: Float

    /** Ground metres across the content, for choosing a tile level. */
    val contentSpanMeters: Double

    /** Whether an imported sheet is behind this. */
    val hasSheet: Boolean
}

/**
 * An imported GeoPDF's page space.
 *
 * The page is the content: unit coordinates are fractions of the sheet, so the
 * neatline sits at the edges and everything registers to the product a crew is
 * already reading off.
 */
class SheetProjection(
    private val frame: MapFrame,
    private val pageWidthPoints: Int,
    private val pageHeightPoints: Int,
    override val contentWidth: Float,
    override val contentHeight: Float
) : MapProjection {

    override val maxScale: Float get() = 12f

    override val hasSheet: Boolean get() = true

    override fun toUnit(latitude: Double, longitude: Double): Pair<Float, Float>? {
        if (pageWidthPoints <= 0 || pageHeightPoints <= 0) return null
        val page = frame.geoToPage(latitude, longitude) ?: return null
        val x = (page.first / pageWidthPoints).toFloat()
        val y = 1f - (page.second / pageHeightPoints).toFloat()
        if (!x.isFinite() || !y.isFinite()) return null
        return x to y
    }

    override fun toGeo(x: Float, y: Float): Pair<Double, Double>? {
        if (pageWidthPoints <= 0 || pageHeightPoints <= 0) return null
        val point = frame.pageToGeo(
            x * pageWidthPoints.toDouble(),
            (1f - y) * pageHeightPoints.toDouble()
        ) ?: return null
        if (!point.latitude.isFinite() || !point.longitude.isFinite()) return null
        return point.latitude to point.longitude
    }

    override val contentSpanMeters: Double by lazy {
        val west = toGeo(0f, 0.5f) ?: return@lazy 0.0
        val east = toGeo(1f, 0.5f) ?: return@lazy 0.0
        MapCoverage.distanceMeters(west.first, west.second, east.first, east.second)
    }
}

/**
 * Plain ground, with no sheet behind it.
 *
 * A square of Web Mercator anchored on a position. Mercator because that is
 * what the terrain tiles are cut in, so tiles land on exact rectangles here
 * with no projection work at all -- this is cheaper to draw through than the
 * sheet is, not more expensive.
 *
 * The square is finite, which is the one real limit: it covers a working area
 * rather than the world. That is the right trade for a fire. A whole incident
 * including the drive in fits inside [DEFAULT_SPAN_METERS], and travelling
 * clear of it re-anchors rather than running out of map.
 */
class GroundProjection(
    val centreLatitude: Double,
    val centreLongitude: Double,
    val spanMeters: Double = DEFAULT_SPAN_METERS
) : MapProjection {

    override val contentWidth: Float get() = NOMINAL_PIXELS
    override val contentHeight: Float get() = NOMINAL_PIXELS

    /**
     * Further in than the sheet, because the content is a whole working area
     * rather than one product. At the far end this is about a metre to the
     * pixel, which is finer than the elevation behind it and as far in as
     * looking closer can tell anyone anything.
     */
    override val maxScale: Float get() = 64f

    override val hasSheet: Boolean get() = false

    override val contentSpanMeters: Double get() = spanMeters

    private val centreX = (centreLongitude + 180.0) / 360.0
    private val centreY = 0.5 - mercatorY(centreLatitude) / (2.0 * PI)

    /**
     * Half the content's width in normalised Mercator.
     *
     * Mercator is conformal, so one unit of normalised x and one of normalised
     * y cover the same ground distance at a given latitude. That is what lets
     * a square here be a square on the ground.
     */
    private val half: Double = run {
        val scale = EQUATORIAL_CIRCUMFERENCE * cos(Math.toRadians(centreLatitude))
        if (scale <= 0.0) 0.0 else (spanMeters / 2.0) / scale
    }

    override fun toUnit(latitude: Double, longitude: Double): Pair<Float, Float>? {
        if (half <= 0.0) return null
        if (latitude.isNaN() || longitude.isNaN()) return null
        val nx = (longitude + 180.0) / 360.0
        val ny = 0.5 - mercatorY(latitude) / (2.0 * PI)
        val x = ((nx - centreX) / (2.0 * half) + 0.5).toFloat()
        val y = ((ny - centreY) / (2.0 * half) + 0.5).toFloat()
        if (!x.isFinite() || !y.isFinite()) return null
        return x to y
    }

    override fun toGeo(x: Float, y: Float): Pair<Double, Double>? {
        if (half <= 0.0) return null
        if (!x.isFinite() || !y.isFinite()) return null
        val nx = centreX + (x - 0.5) * 2.0 * half
        val ny = centreY + (y - 0.5) * 2.0 * half
        val longitude = nx * 360.0 - 180.0
        val latitude = inverseMercatorY((0.5 - ny) * 2.0 * PI)
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        return latitude to longitude
    }

    /** How far the anchor is from a position, for deciding to re-anchor. */
    fun metersFromCentre(latitude: Double, longitude: Double): Double =
        MapCoverage.distanceMeters(centreLatitude, centreLongitude, latitude, longitude)

    companion object {
        /**
         * Roughly forty miles across.
         *
         * Chosen to hold a whole incident and the drive in. Larger would mean
         * less detail at the fitted view for no gain, since nobody navigates
         * at that scale; smaller and a camp-to-division move leaves the map.
         */
        const val DEFAULT_SPAN_METERS = 64_000.0

        /** Content size in nominal pixels. Square, matching the ground. */
        const val NOMINAL_PIXELS = 2048f

        /** Beyond this from the anchor, the working area is re-cut around you. */
        const val REANCHOR_METERS = 20_000.0
    }
}

/** Metres round the equator, which is the scale normalised Mercator is in. */
const val EQUATORIAL_CIRCUMFERENCE = 40_075_016.686

internal fun mercatorY(latitude: Double): Double {
    val clamped = latitude.coerceIn(-85.05112878, 85.05112878)
    return ln(tan(PI / 4.0 + Math.toRadians(clamped) / 2.0))
}

internal fun inverseMercatorY(y: Double): Double =
    Math.toDegrees(2.0 * atan(exp(y)) - PI / 2.0)
