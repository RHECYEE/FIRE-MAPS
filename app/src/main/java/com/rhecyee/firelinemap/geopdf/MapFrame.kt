package com.rhecyee.firelinemap.geopdf

import kotlin.math.abs

/** A page-space rectangle in PDF points, origin at the lower-left of the page. */
data class PageBox(val left: Double, val bottom: Double, val right: Double, val top: Double) {
    val width get() = right - left
    val height get() = top - bottom
    val area get() = width * height
}

/** A geographic position in the frame's datum. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * One georeferenced map frame from a PDF page's /VP array.
 *
 * A single incident product routinely carries several. The Burnt Creek
 * transportation map has three: the main sheet plus two detail insets at
 * completely different extents. Treating any of them as "the map" would put
 * the GPS dot in the wrong drainage, so frames are kept as a list and the
 * caller chooses deliberately.
 */
data class MapFrame(
    val name: String?,
    val box: PageBox,
    /** Corner positions, ordered to match [localCorners]. */
    val geoCorners: List<GeoPoint>,
    /** Corner positions in the box's unit square, from the /LPTS entry. */
    val localCorners: List<Pair<Double, Double>>,
    val wkt: String?
) {
    val projection: UtmProjection? by lazy { UtmProjection.fromWkt(wkt) }

    /** Whether the frame projects properly or falls back to corner interpolation. */
    val usesProjection: Boolean get() = affine != null

    /** Page-space positions of the frame's corners. */
    val pageCorners: List<Pair<Double, Double>>
        get() = localCorners.map { (u, v) ->
            box.left + u * box.width to box.bottom + v * box.height
        }

    /**
     * Affine mapping page points to projected metres.
     *
     * A map drawn in a projected CRS is an affine function of page space, so
     * fitting this once gives an exact transform rather than an approximation.
     * Null when the WKT was unusable.
     */
    private val affine: Affine? by lazy {
        val projection = projection ?: return@lazy null
        if (geoCorners.size < 3 || localCorners.size != geoCorners.size) return@lazy null
        val page = pageCorners
        val projected = geoCorners.map { projection.forward(it.latitude, it.longitude) }
        Affine.fit(page, projected)
    }

    /** Geographic position of a page point. */
    fun pageToGeo(x: Double, y: Double): GeoPoint? {
        val affine = affine
        if (affine != null) {
            val (easting, northing) = affine.apply(x, y)
            return projectedToGeo(easting, northing)
        }
        return bilinearPageToGeo(x, y)
    }

    /**
     * The page-to-projected transform, inverted once.
     *
     * This used to be inverted inside [geoToPage], allocating a fresh matrix
     * for every point projected. Measured, that is roughly half again to three
     * times the cost of a projection, and it does not change once the frame is
     * built, so there was never a reason to pay it. The larger cost is the
     * projection itself, which is why callers with many points to place are
     * expected to do it once rather than on every frame.
     */
    private val inverseAffine: Affine? by lazy { affine?.invert() }

    /**
     * Page position of a geographic point, or null when it falls outside this
     * frame's coverage.
     */
    fun geoToPage(latitude: Double, longitude: Double): Pair<Double, Double>? {
        val affine = affine
        val point = if (affine != null) {
            val projection = projection ?: return null
            val (easting, northing) = projection.forward(latitude, longitude)
            inverseAffine?.apply(easting, northing) ?: return null
        } else {
            bilinearGeoToPage(latitude, longitude) ?: return null
        }
        return point
    }

    fun containsGeo(latitude: Double, longitude: Double): Boolean {
        val (x, y) = geoToPage(latitude, longitude) ?: return false
        val slack = 1e-6
        return x >= box.left - slack && x <= box.right + slack &&
            y >= box.bottom - slack && y <= box.top + slack
    }

    /** Axis-aligned geographic extent of the frame's corners. */
    fun geographicBounds(): DoubleArray {
        val lats = geoCorners.map { it.latitude }
        val lons = geoCorners.map { it.longitude }
        return doubleArrayOf(lats.min(), lons.min(), lats.max(), lons.max())
    }

    private fun projectedToGeo(easting: Double, northing: Double): GeoPoint? {
        val projection = projection ?: return null
        // Invert numerically: the forward series is cheap and this converges in
        // a handful of steps over a single map sheet, which avoids carrying a
        // second series expansion that would need its own verification.
        var latitude = geoCorners.map { it.latitude }.average()
        var longitude = geoCorners.map { it.longitude }.average()
        repeat(12) {
            val (currentEasting, currentNorthing) = projection.forward(latitude, longitude)
            val deltaEasting = easting - currentEasting
            val deltaNorthing = northing - currentNorthing
            if (abs(deltaEasting) < 1e-4 && abs(deltaNorthing) < 1e-4) return@repeat

            // Local metres-per-degree, evaluated by finite difference.
            val step = 1e-6
            val (eastingDLat, northingDLat) = projection.forward(latitude + step, longitude)
            val (eastingDLon, northingDLon) = projection.forward(latitude, longitude + step)
            val a = (eastingDLat - currentEasting) / step
            val b = (eastingDLon - currentEasting) / step
            val c = (northingDLat - currentNorthing) / step
            val d = (northingDLon - currentNorthing) / step
            val determinant = a * d - b * c
            if (abs(determinant) < 1e-12) return@repeat
            latitude += (deltaEasting * d - deltaNorthing * b) / determinant
            longitude += (deltaNorthing * a - deltaEasting * c) / determinant
        }
        return GeoPoint(latitude, longitude)
    }

    private fun bilinearPageToGeo(x: Double, y: Double): GeoPoint? {
        if (geoCorners.size != 4) return null
        val u = if (box.width != 0.0) (x - box.left) / box.width else 0.0
        val v = if (box.height != 0.0) (y - box.bottom) / box.height else 0.0
        // localCorners order is (0,0) (0,1) (1,1) (1,0).
        val (ll, ul, ur, lr) = listOf(geoCorners[0], geoCorners[1], geoCorners[2], geoCorners[3])
        val latitude = (1 - u) * (1 - v) * ll.latitude + (1 - u) * v * ul.latitude +
            u * v * ur.latitude + u * (1 - v) * lr.latitude
        val longitude = (1 - u) * (1 - v) * ll.longitude + (1 - u) * v * ul.longitude +
            u * v * ur.longitude + u * (1 - v) * lr.longitude
        return GeoPoint(latitude, longitude)
    }

    private fun bilinearGeoToPage(latitude: Double, longitude: Double): Pair<Double, Double>? {
        if (geoCorners.size != 4) return null
        // Invert the bilinear map by descent; the cell is near-rectangular so
        // this settles quickly.
        var u = 0.5
        var v = 0.5
        repeat(24) {
            val guess = bilinearPageToGeo(box.left + u * box.width, box.bottom + v * box.height)
                ?: return null
            val deltaLat = latitude - guess.latitude
            val deltaLon = longitude - guess.longitude
            if (abs(deltaLat) < 1e-10 && abs(deltaLon) < 1e-10) return@repeat
            val step = 1e-5
            val byU = bilinearPageToGeo(
                box.left + (u + step) * box.width, box.bottom + v * box.height
            ) ?: return null
            val byV = bilinearPageToGeo(
                box.left + u * box.width, box.bottom + (v + step) * box.height
            ) ?: return null
            val a = (byU.latitude - guess.latitude) / step
            val b = (byV.latitude - guess.latitude) / step
            val c = (byU.longitude - guess.longitude) / step
            val d = (byV.longitude - guess.longitude) / step
            val determinant = a * d - b * c
            if (abs(determinant) < 1e-18) return@repeat
            u += (deltaLat * d - deltaLon * b) / determinant
            v += (deltaLon * a - deltaLat * c) / determinant
        }
        return box.left + u * box.width to box.bottom + v * box.height
    }

    private operator fun <T> List<T>.component4(): T = this[3]
}

/** A 2-D affine transform, fitted by least squares. */
data class Affine(
    val a: Double, val b: Double, val c: Double,
    val d: Double, val e: Double, val f: Double
) {
    fun apply(x: Double, y: Double): Pair<Double, Double> =
        a * x + b * y + c to d * x + e * y + f

    fun invert(): Affine? {
        val determinant = a * e - b * d
        if (abs(determinant) < 1e-12) return null
        val ia = e / determinant
        val ib = -b / determinant
        val id = -d / determinant
        val ie = a / determinant
        return Affine(ia, ib, -(ia * c + ib * f), id, ie, -(id * c + ie * f))
    }

    companion object {
        /** Least-squares fit of source points to target points. */
        fun fit(
            source: List<Pair<Double, Double>>,
            target: List<Pair<Double, Double>>
        ): Affine? {
            if (source.size < 3 || source.size != target.size) return null
            var sxx = 0.0; var sxy = 0.0; var sx = 0.0
            var syy = 0.0; var sy = 0.0; var n = 0.0
            for ((x, y) in source) {
                sxx += x * x; sxy += x * y; sx += x
                syy += y * y; sy += y; n += 1.0
            }
            val normal = arrayOf(
                doubleArrayOf(sxx, sxy, sx),
                doubleArrayOf(sxy, syy, sy),
                doubleArrayOf(sx, sy, n)
            )
            val rhsU = DoubleArray(3)
            val rhsV = DoubleArray(3)
            for (i in source.indices) {
                val (x, y) = source[i]
                val (u, v) = target[i]
                rhsU[0] += x * u; rhsU[1] += y * u; rhsU[2] += u
                rhsV[0] += x * v; rhsV[1] += y * v; rhsV[2] += v
            }
            val first = solve3(normal, rhsU) ?: return null
            val second = solve3(normal, rhsV) ?: return null
            return Affine(first[0], first[1], first[2], second[0], second[1], second[2])
        }

        private fun solve3(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
            val m = Array(3) { r -> DoubleArray(4) { c -> if (c < 3) matrix[r][c] else rhs[r] } }
            for (col in 0 until 3) {
                var pivot = col
                for (row in col + 1 until 3) {
                    if (abs(m[row][col]) > abs(m[pivot][col])) pivot = row
                }
                if (abs(m[pivot][col]) < 1e-14) return null
                val swap = m[col]; m[col] = m[pivot]; m[pivot] = swap
                for (row in 0 until 3) {
                    if (row == col) continue
                    val factor = m[row][col] / m[col][col]
                    for (k in col until 4) m[row][k] -= factor * m[col][k]
                }
            }
            return DoubleArray(3) { m[it][3] / m[it][it] }
        }
    }
}
