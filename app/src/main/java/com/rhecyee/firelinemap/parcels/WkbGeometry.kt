package com.rhecyee.firelinemap.parcels

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A ring of longitude/latitude pairs. */
typealias Ring = List<Pair<Double, Double>>

/**
 * A parcel boundary: one or more polygons, each with an outer ring and any
 * holes.
 */
data class ParcelGeometry(
    val polygons: List<List<Ring>>,
    val minLongitude: Double,
    val minLatitude: Double,
    val maxLongitude: Double,
    val maxLatitude: Double
) {
    val isEmpty: Boolean get() = polygons.isEmpty()

    fun intersects(
        south: Double,
        west: Double,
        north: Double,
        east: Double
    ): Boolean = maxLatitude >= south && minLatitude <= north &&
        maxLongitude >= west && minLongitude <= east

    /**
     * Whether a position falls inside the parcel.
     *
     * Ray casting on the outer rings, with holes subtracted. Used to answer
     * "whose ground am I standing on", so a point on a shared boundary
     * resolving to either neighbour is acceptable.
     */
    fun contains(latitude: Double, longitude: Double): Boolean {
        if (longitude < minLongitude || longitude > maxLongitude) return false
        if (latitude < minLatitude || latitude > maxLatitude) return false
        for (polygon in polygons) {
            if (polygon.isEmpty()) continue
            if (!inRing(polygon[0], latitude, longitude)) continue
            val inHole = polygon.drop(1).any { inRing(it, latitude, longitude) }
            if (!inHole) return true
        }
        return false
    }

    private fun inRing(ring: Ring, latitude: Double, longitude: Double): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val (xi, yi) = ring[i]
            val (xj, yj) = ring[j]
            if ((yi > latitude) != (yj > latitude) &&
                longitude < (xj - xi) * (latitude - yi) / (yj - yi) + xi
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}

/**
 * Reads parcel boundaries out of GeoPackage blobs.
 *
 * A GeoPackage stores each geometry as a small header followed by standard
 * well-known binary. Both are parsed here rather than through a native
 * library, which keeps county packages readable with nothing installed beyond
 * the app itself -- the same reasoning as the GeoPDF reader.
 *
 * Only polygons are kept. A parcel is an area; anything else in the file is
 * not a boundary and is skipped rather than guessed at.
 */
object WkbGeometry {

    private const val TYPE_POLYGON = 3
    private const val TYPE_MULTIPOLYGON = 6
    private const val TYPE_GEOMETRY_COLLECTION = 7

    /**
     * Strips the GeoPackage binary header and reads the geometry inside.
     *
     * The header is "GP", a version, flags, an SRS id, and an optional
     * envelope whose size the flags describe.
     */
    fun fromGeoPackageBlob(blob: ByteArray): ParcelGeometry? {
        if (blob.size < 8) return null
        if (blob[0] != 'G'.code.toByte() || blob[1] != 'P'.code.toByte()) {
            // Not a GeoPackage blob; it may be bare well-known binary.
            return fromWkb(blob)
        }
        val flags = blob[3].toInt()
        val envelopeCode = (flags shr 1) and 0x07
        val envelopeBytes = when (envelopeCode) {
            0 -> 0
            1 -> 32
            2, 3 -> 48
            4 -> 64
            else -> return null
        }
        val offset = 8 + envelopeBytes
        if (offset >= blob.size) return null
        return fromWkb(blob.copyOfRange(offset, blob.size))
    }

    fun fromWkb(bytes: ByteArray): ParcelGeometry? = runCatching {
        val buffer = ByteBuffer.wrap(bytes)
        val polygons = mutableListOf<List<Ring>>()
        readGeometry(buffer, polygons, depth = 0)
        if (polygons.isEmpty()) return null

        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (polygon in polygons) {
            for (ring in polygon) {
                for ((x, y) in ring) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        ParcelGeometry(polygons, minX, minY, maxX, maxY)
    }.getOrNull()

    private fun readGeometry(
        buffer: ByteBuffer,
        into: MutableList<List<Ring>>,
        depth: Int
    ) {
        // A collection nested this deep is malformed rather than interesting.
        if (depth > 8) return
        val order = buffer.get()
        buffer.order(if (order.toInt() == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)

        val rawType = buffer.int
        // The high bytes carry Z, M and SRID flags; the base type is the rest.
        val hasZ = (rawType and 0x80000000.toInt()) != 0 || (rawType / 1000) % 10 == 1
        val hasM = (rawType and 0x40000000.toInt()) != 0 || (rawType / 1000) % 10 == 2
        val hasSrid = (rawType and 0x20000000.toInt()) != 0
        val type = rawType and 0xFF
        if (hasSrid) buffer.int

        val extra = (if (hasZ) 1 else 0) + (if (hasM) 1 else 0)

        when (type) {
            TYPE_POLYGON -> into += readPolygon(buffer, extra)
            TYPE_MULTIPOLYGON -> {
                val count = buffer.int
                repeat(count) { readGeometry(buffer, into, depth + 1) }
            }
            TYPE_GEOMETRY_COLLECTION -> {
                val count = buffer.int
                repeat(count) { readGeometry(buffer, into, depth + 1) }
            }
            // Points and lines are not parcel boundaries.
            else -> return
        }
    }

    private fun readPolygon(buffer: ByteBuffer, extraOrdinates: Int): List<Ring> {
        val ringCount = buffer.int
        val rings = mutableListOf<Ring>()
        repeat(ringCount) {
            val pointCount = buffer.int
            val ring = ArrayList<Pair<Double, Double>>(pointCount)
            repeat(pointCount) {
                val x = buffer.double
                val y = buffer.double
                repeat(extraOrdinates) { buffer.double }
                ring += x to y
            }
            rings += ring
        }
        return rings
    }
}
