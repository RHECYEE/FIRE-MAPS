package com.rhecyee.firelinemap.car

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

/** A point on the car surface, in pixels from its top-left corner. */
data class ScreenPoint(val x: Float, val y: Float)

/** A position on the ground. */
data class GroundPoint(val latitude: Double, val longitude: Double)

/**
 * The Web Mercator view drawn on the car surface.
 *
 * Deliberately free of Android types. Everything that decides *where a thing
 * lands on the driver's screen* lives here so it can be tested on the JVM;
 * a registration error is not something to find out about on a head unit
 * halfway up a fire road.
 *
 * Two coordinate frames come out of this class and the difference matters:
 *
 * - [toUnrotated] is valid inside a canvas that has already been rotated by
 *   the map bearing. Tiles and the map sheet are drawn there, because in that
 *   frame they are axis-aligned rectangles and can go down as plain bitmap
 *   blits.
 * - [toScreen] is the real pixel position on the surface, rotation included.
 *   Used for anything drawn upright (labels, the vehicle marker) and for
 *   turning a touch back into a position.
 */
class CarMapProjection(
    val centerLatitude: Double,
    val centerLongitude: Double,
    val zoom: Double,
    /** Map rotation in degrees. Zero is north-up; otherwise the heading held upright. */
    val bearingDegrees: Double,
    val widthPixels: Int,
    val heightPixels: Int,
    /** Where [centerLatitude]/[centerLongitude] sits on the surface. */
    val anchorX: Float,
    val anchorY: Float
) {
    /** Side of the whole world in pixels at this zoom. */
    val worldSize: Double = TILE_SIZE * 2.0.pow(zoom)

    val centerWorldX: Double = worldX(centerLongitude)
    val centerWorldY: Double = worldY(centerLatitude)

    private val rotationRadians = Math.toRadians(-bearingDegrees)
    private val rotationCos = cos(rotationRadians)
    private val rotationSin = sin(rotationRadians)

    fun worldX(longitude: Double): Double = (longitude + 180.0) / 360.0 * worldSize

    fun worldY(latitude: Double): Double {
        val clamped = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val radians = Math.toRadians(clamped)
        return (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0 * worldSize
    }

    fun longitudeAt(worldX: Double): Double = worldX / worldSize * 360.0 - 180.0

    fun latitudeAt(worldY: Double): Double {
        val n = PI - 2.0 * PI * worldY / worldSize
        return Math.toDegrees(atan(sinh(n)))
    }

    /**
     * Offset east of the view centre, in pixels, taking the shorter way round.
     *
     * Without the wrap a view straddling the antimeridian would place ground a
     * few hundred metres away most of a world-width off screen.
     */
    private fun eastOffset(longitude: Double): Double {
        var dx = worldX(longitude) - centerWorldX
        if (dx > worldSize / 2) dx -= worldSize
        if (dx < -worldSize / 2) dx += worldSize
        return dx
    }

    /** Position ignoring map rotation. Valid inside a canvas already rotated by the bearing. */
    fun toUnrotated(latitude: Double, longitude: Double): ScreenPoint = ScreenPoint(
        (anchorX + eastOffset(longitude)).toFloat(),
        (anchorY + (worldY(latitude) - centerWorldY)).toFloat()
    )

    /** Position on the surface, map rotation included. */
    fun toScreen(latitude: Double, longitude: Double): ScreenPoint {
        val dx = eastOffset(longitude)
        val dy = worldY(latitude) - centerWorldY
        return ScreenPoint(
            (anchorX + dx * rotationCos - dy * rotationSin).toFloat(),
            (anchorY + dx * rotationSin + dy * rotationCos).toFloat()
        )
    }

    /** The ground under a point on the surface. Inverse of [toScreen]. */
    fun toGround(x: Float, y: Float): GroundPoint {
        val sx = x - anchorX
        val sy = y - anchorY
        // Rotating back is the transpose, the rotation being orthonormal.
        val dx = sx * rotationCos + sy * rotationSin
        val dy = -sx * rotationSin + sy * rotationCos
        var wx = (centerWorldX + dx) % worldSize
        if (wx < 0) wx += worldSize
        val wy = (centerWorldY + dy).coerceIn(0.0, worldSize)
        return GroundPoint(latitudeAt(wy), longitudeAt(wx))
    }

    /** Ground covered by one surface pixel at the view centre. */
    fun metersPerPixel(): Double =
        EQUATOR_METERS * cos(Math.toRadians(centerLatitude)) / worldSize

    /**
     * Geographic box covering the whole surface.
     *
     * Taken from the four surface corners rather than from the centre and a
     * radius. Under rotation the view is a tilted rectangle, and its corners
     * are exactly the extremes of the box that has to be filled with tiles.
     */
    fun visibleBounds(): DoubleArray {
        val corners = listOf(
            toGround(0f, 0f),
            toGround(widthPixels.toFloat(), 0f),
            toGround(0f, heightPixels.toFloat()),
            toGround(widthPixels.toFloat(), heightPixels.toFloat())
        )
        return doubleArrayOf(
            corners.minOf { it.latitude },
            corners.minOf { it.longitude },
            corners.maxOf { it.latitude },
            corners.maxOf { it.longitude }
        )
    }

    /** A copy centred somewhere else, everything else held. */
    fun centeredOn(latitude: Double, longitude: Double): CarMapProjection =
        CarMapProjection(
            latitude, longitude, zoom, bearingDegrees,
            widthPixels, heightPixels, anchorX, anchorY
        )

    companion object {
        const val TILE_SIZE = 256.0

        /** Web Mercator cannot represent the poles. */
        const val MAX_LATITUDE = 85.05112878

        private const val EQUATOR_METERS = 40_075_016.686

        /** Zoom levels that are worth fetching terrain for. */
        const val MIN_ZOOM = 6.0
        const val MAX_ZOOM = 17.0

        /**
         * Ground offset of a position from another, in metres east and north.
         *
         * Used for the readouts rather than for drawing, so a local flat-earth
         * approximation is the right amount of arithmetic here.
         */
        fun offsetMeters(
            fromLatitude: Double,
            fromLongitude: Double,
            toLatitude: Double,
            toLongitude: Double
        ): Pair<Double, Double> {
            val metersPerDegree = EQUATOR_METERS / 360.0
            val east = (toLongitude - fromLongitude) * metersPerDegree *
                cos(Math.toRadians((fromLatitude + toLatitude) / 2.0))
            val north = (toLatitude - fromLatitude) * metersPerDegree
            return east to north
        }
    }
}
