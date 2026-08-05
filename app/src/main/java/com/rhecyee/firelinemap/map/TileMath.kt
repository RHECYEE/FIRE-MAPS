package com.rhecyee.firelinemap.map

import com.rhecyee.firelinemap.map.degreesToRadians
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.tan

/**
 * Web Mercator tile arithmetic.
 *
 * Used to size an offline download before it starts. A field app must be able
 * to say "this is 24 MB" while the operator still has the option to say no;
 * a progress bar that starts at an unknown total is how people end up
 * burning a hotspot allowance at ICP.
 */
object TileMath {

    /** Web Mercator cannot represent the poles; this is its usable latitude limit. */
    const val MAX_LATITUDE = 85.05112878

    /** Shared with [MapCoverage] so buffers and distances agree. See [Earth]. */
    const val METERS_PER_DEGREE_LATITUDE = Earth.METERS_PER_DEGREE

    fun tileX(longitude: Double, zoom: Int): Int {
        val scale = 1 shl zoom
        val normalized = (longitude + 180.0) / 360.0
        return floor(normalized * scale).toInt().coerceIn(0, scale - 1)
    }

    fun tileY(latitude: Double, zoom: Int): Int {
        val scale = 1 shl zoom
        val clamped = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val radians = degreesToRadians(clamped)
        val normalized = (1.0 - asinh(tan(radians)) / PI) / 2.0
        return floor(normalized * scale).toInt().coerceIn(0, scale - 1)
    }

    /** Number of tiles covering [bounds] at a single zoom level. */
    fun tileCount(bounds: GeoBounds, zoom: Int): Long {
        val minX = tileX(bounds.west, zoom)
        val maxX = tileX(bounds.east, zoom)
        // Tile Y increases southward, so the northern edge yields the smaller index.
        val minY = tileY(bounds.north, zoom)
        val maxY = tileY(bounds.south, zoom)
        val across = (maxX - minX + 1).toLong()
        val down = (maxY - minY + 1).toLong()
        return across * down
    }

    /** Number of tiles covering [bounds] across an inclusive zoom range. */
    fun tileCount(bounds: GeoBounds, minZoom: Int, maxZoom: Int): Long {
        require(minZoom in 0..24) { "minZoom out of range: $minZoom" }
        require(maxZoom in minZoom..24) { "maxZoom out of range: $maxZoom" }
        return (minZoom..maxZoom).sumOf { tileCount(bounds, it) }
    }

    /**
     * Expands [bounds] outward by [meters] on every side.
     *
     * The download deliberately covers ground beyond the incident map's edge.
     * Travel to and from the fire, ICP, and drop points routinely sit outside
     * the ops map's neatline, and those are exactly the positions where the
     * operator has nothing else to look at.
     */
    fun buffer(bounds: GeoBounds, meters: Double): GeoBounds {
        require(meters >= 0) { "buffer must not be negative: $meters" }
        val latitudeDelta = meters / METERS_PER_DEGREE_LATITUDE

        // Longitude degrees shrink with latitude; use the edge furthest from the
        // equator so the buffer is at least the requested distance everywhere.
        val widestLatitude = maxOf(kotlin.math.abs(bounds.south), kotlin.math.abs(bounds.north))
        val metersPerDegreeLongitude =
            METERS_PER_DEGREE_LATITUDE * cos(degreesToRadians(widestLatitude))
        val longitudeDelta = if (metersPerDegreeLongitude > 1.0) {
            meters / metersPerDegreeLongitude
        } else {
            180.0
        }

        return GeoBounds(
            south = (bounds.south - latitudeDelta).coerceAtLeast(-MAX_LATITUDE),
            west = (bounds.west - longitudeDelta).coerceAtLeast(-180.0),
            north = (bounds.north + latitudeDelta).coerceAtMost(MAX_LATITUDE),
            east = (bounds.east + longitudeDelta).coerceAtMost(180.0)
        )
    }

    /** A square-ish box centred on a position, used when no map has been imported yet. */
    fun around(latitude: Double, longitude: Double, radiusMeters: Double): GeoBounds =
        buffer(
            GeoBounds(
                south = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE),
                west = longitude,
                north = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE),
                east = longitude
            ),
            radiusMeters
        )
}
