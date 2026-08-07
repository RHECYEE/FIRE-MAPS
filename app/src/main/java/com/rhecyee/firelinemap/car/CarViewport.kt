package com.rhecyee.firelinemap.car

import com.rhecyee.firelinemap.map.TileMath
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/** A pixel on the car display. */
data class CarPoint(val x: Float, val y: Float)

/** A latitude and longitude. */
data class CarPosition(val latitude: Double, val longitude: Double)

/**
 * What the car display is looking at.
 *
 * Deliberately free of Android: everything that decides where something lands
 * on the head unit is arithmetic, and arithmetic can be tested on a desk. The
 * alternative is finding out that the track is drawn a mile off the road while
 * driving, which is the worst possible place to discover it.
 *
 * Web Mercator, the same projection the phone and the browser use for terrain,
 * so a track recorded on the phone and shown in the car sits on the same
 * ground in both.
 */
data class CarViewport(
    val latitude: Double,
    val longitude: Double,
    /** Fractional, so a pinch or an animation can land between tile levels. */
    val zoom: Double,
    val widthPixels: Int,
    val heightPixels: Int
) {
    companion object {
        const val TILE = 256.0
        const val MIN_ZOOM = 5.0
        const val MAX_ZOOM = 17.0

        /** Clamped rather than rejected: a stuck control must not wedge the map. */
        fun clampZoom(value: Double): Double = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    private val scale: Double get() = TILE * pow2(zoom)

    private fun projectX(longitudeDegrees: Double): Double =
        (longitudeDegrees + 180.0) / 360.0 * scale

    private fun projectY(latitudeDegrees: Double): Double {
        val clamped = latitudeDegrees.coerceIn(-85.05112878, 85.05112878)
        val sine = sin(clamped * PI / 180.0)
        return (0.5 - ln((1 + sine) / (1 - sine)) / (4 * PI)) * scale
    }

    /** Where a position falls on the display. */
    fun toScreen(atLatitude: Double, atLongitude: Double): CarPoint {
        val centreX = projectX(longitude)
        val centreY = projectY(latitude)
        return CarPoint(
            x = (projectX(atLongitude) - centreX + widthPixels / 2.0).toFloat(),
            y = (projectY(atLatitude) - centreY + heightPixels / 2.0).toFloat()
        )
    }

    /** The ground under a pixel. */
    fun toGround(x: Float, y: Float): CarPosition {
        val worldX = projectX(longitude) + x - widthPixels / 2.0
        val worldY = projectY(latitude) + y - heightPixels / 2.0
        val n = PI - 2.0 * PI * worldY / scale
        return CarPosition(
            latitude = 180.0 / PI * atan(0.5 * (exp(n) - exp(-n))),
            longitude = worldX / scale * 360.0 - 180.0
        )
    }

    /** Whether a position is on screen, with a margin for a symbol's size. */
    fun isVisible(atLatitude: Double, atLongitude: Double, marginPixels: Float = 64f): Boolean {
        val at = toScreen(atLatitude, atLongitude)
        return at.x >= -marginPixels && at.x <= widthPixels + marginPixels &&
            at.y >= -marginPixels && at.y <= heightPixels + marginPixels
    }

    /** The integer tile level to fetch for this view. */
    val tileZoom: Int get() = zoom.toInt().coerceIn(0, 16)

    /**
     * The tiles covering the display.
     *
     * Bounded, so a bad viewport cannot ask for ten thousand tiles and take the
     * head unit down with it. On a car display the map is the only thing on
     * screen; if it stops, there is nothing else to look at.
     */
    fun tiles(maxTiles: Int = 60): List<CarTile> {
        if (widthPixels <= 0 || heightPixels <= 0) return emptyList()
        val level = tileZoom
        val northWest = toGround(0f, 0f)
        val southEast = toGround(widthPixels.toFloat(), heightPixels.toFloat())

        val minX = TileMath.tileX(northWest.longitude, level)
        val maxX = TileMath.tileX(southEast.longitude, level)
        val minY = TileMath.tileY(northWest.latitude, level)
        val maxY = TileMath.tileY(southEast.latitude, level)
        if (maxX < minX || maxY < minY) return emptyList()
        if ((maxX - minX + 1).toLong() * (maxY - minY + 1).toLong() > maxTiles) {
            return emptyList()
        }

        val span = 1 shl level
        val out = ArrayList<CarTile>()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                if (y < 0 || y >= span) continue
                val wrapped = ((x % span) + span) % span
                // Placed from the tile's own north-west corner, at the size one
                // tile occupies at this zoom, so a fractional zoom scales the
                // whole grid rather than leaving seams.
                val at = toScreen(tileNorth(y, level), tileWest(x, level))
                val size = (TILE * pow2(zoom - level)).toFloat()
                out += CarTile(level, wrapped, y, at.x, at.y, size)
            }
        }
        return out
    }

    /** Metres per pixel here, for a scale bar and for choosing a zoom. */
    fun metresPerPixel(): Double =
        156543.03392 * cos(latitude * PI / 180.0) / pow2(zoom)

    /** The north edge of a tile row, in degrees. */
    private fun tileNorth(y: Int, level: Int): Double {
        val n = PI - 2.0 * PI * y / (1 shl level).toDouble()
        return 180.0 / PI * atan(0.5 * (exp(n) - exp(-n)))
    }

    /** The west edge of a tile column, in degrees. */
    private fun tileWest(x: Int, level: Int): Double =
        x.toDouble() / (1 shl level).toDouble() * 360.0 - 180.0

    private fun pow2(exponent: Double): Double {
        var result = 1.0
        var whole = exponent.toInt()
        while (whole > 0) { result *= 2.0; whole-- }
        while (whole < 0) { result /= 2.0; whole++ }
        val fraction = exponent - exponent.toInt()
        return if (fraction == 0.0) result else result * exp(fraction * ln(2.0))
    }
}

/** One tile and where it goes on the display. */
data class CarTile(
    val zoom: Int,
    val x: Int,
    val y: Int,
    val left: Float,
    val top: Float,
    val size: Float
)
