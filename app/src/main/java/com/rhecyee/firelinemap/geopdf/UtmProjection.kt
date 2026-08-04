package com.rhecyee.firelinemap.geopdf

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A transverse Mercator projection recovered from a GeoPDF's WKT string.
 *
 * Incident products carry their coordinate system as a WKT literal rather than
 * an EPSG code -- both Burnt Creek products this was written against do, and
 * neither carries an /EPSG entry at all -- so the parameters are read straight
 * out of the WKT text.
 *
 * Only transverse Mercator is handled. That is what UTM is, and UTM is what
 * wildfire incident products are drawn in. Anything else reports itself as
 * unsupported so the caller can fall back to interpolation rather than
 * silently projecting with the wrong maths.
 */
data class UtmProjection(
    val name: String,
    val semiMajorAxis: Double,
    val inverseFlattening: Double,
    val centralMeridian: Double,
    val scaleFactor: Double,
    val falseEasting: Double,
    val falseNorthing: Double,
    val latitudeOfOrigin: Double
) {
    private val flattening get() = 1.0 / inverseFlattening
    private val eccentricitySquared get() = flattening * (2.0 - flattening)

    /** Forward projection: geographic degrees to projected metres. */
    fun forward(latitude: Double, longitude: Double): Pair<Double, Double> {
        val e2 = eccentricitySquared
        val ep2 = e2 / (1.0 - e2)
        val latRad = Math.toRadians(latitude)
        val lonRad = Math.toRadians(longitude)
        val lon0 = Math.toRadians(centralMeridian)

        val sinLat = sin(latRad)
        val cosLat = cos(latRad)
        val tanLat = tan(latRad)

        val n = semiMajorAxis / sqrt(1.0 - e2 * sinLat * sinLat)
        val t = tanLat * tanLat
        val c = ep2 * cosLat * cosLat
        val a = cosLat * wrapRadians(lonRad - lon0)
        val m = meridionalArc(latRad, e2)
        val m0 = meridionalArc(Math.toRadians(latitudeOfOrigin), e2)

        val a2 = a * a
        val a3 = a2 * a
        val a4 = a3 * a
        val a5 = a4 * a
        val a6 = a5 * a

        val easting = scaleFactor * n * (
            a + (1.0 - t + c) * a3 / 6.0 +
                (5.0 - 18.0 * t + t * t + 72.0 * c - 58.0 * ep2) * a5 / 120.0
            ) + falseEasting

        val northing = scaleFactor * (
            m - m0 + n * tanLat * (
                a2 / 2.0 +
                    (5.0 - t + 9.0 * c + 4.0 * c * c) * a4 / 24.0 +
                    (61.0 - 58.0 * t + t * t + 600.0 * c - 330.0 * ep2) * a6 / 720.0
                )
            ) + falseNorthing

        return easting to northing
    }

    private fun meridionalArc(latRad: Double, e2: Double): Double {
        val e4 = e2 * e2
        val e6 = e4 * e2
        val a0 = 1.0 - e2 / 4.0 - 3.0 * e4 / 64.0 - 5.0 * e6 / 256.0
        val a2 = 3.0 / 8.0 * (e2 + e4 / 4.0 + 15.0 * e6 / 128.0)
        val a4 = 15.0 / 256.0 * (e4 + 3.0 * e6 / 4.0)
        val a6 = 35.0 * e6 / 3072.0
        return semiMajorAxis * (
            a0 * latRad - a2 * sin(2.0 * latRad) + a4 * sin(4.0 * latRad) - a6 * sin(6.0 * latRad)
            )
    }

    private fun wrapRadians(value: Double): Double {
        var result = value
        while (result > Math.PI) result -= 2.0 * Math.PI
        while (result <= -Math.PI) result += 2.0 * Math.PI
        return result
    }

    companion object {
        /**
         * Reads a transverse Mercator definition out of a WKT string.
         *
         * Returns null when the WKT names a projection this does not implement,
         * which is the signal to fall back to corner interpolation.
         */
        fun fromWkt(wkt: String?): UtmProjection? {
            if (wkt.isNullOrBlank()) return null
            if (!wkt.contains("Transverse_Mercator", ignoreCase = true)) return null

            val spheroid = Regex("""SPHEROID\s*\[\s*"[^"]*"\s*,\s*([0-9.eE+-]+)\s*,\s*([0-9.eE+-]+)""")
                .find(wkt)
            val axis = spheroid?.groupValues?.get(1)?.toDoubleOrNull() ?: 6378137.0
            val invFlattening = spheroid?.groupValues?.get(2)?.toDoubleOrNull() ?: 298.257222101

            // A spheroid with zero flattening would be a sphere; guard the reciprocal.
            if (invFlattening <= 0.0) return null

            return UtmProjection(
                name = Regex("""PROJCS\s*\[\s*"([^"]*)"""").find(wkt)?.groupValues?.get(1)
                    ?: "Unknown",
                semiMajorAxis = axis,
                inverseFlattening = invFlattening,
                centralMeridian = parameter(wkt, "Central_Meridian") ?: return null,
                scaleFactor = parameter(wkt, "Scale_Factor") ?: 1.0,
                falseEasting = parameter(wkt, "False_Easting") ?: 0.0,
                falseNorthing = parameter(wkt, "False_Northing") ?: 0.0,
                latitudeOfOrigin = parameter(wkt, "Latitude_Of_Origin") ?: 0.0
            )
        }

        private fun parameter(wkt: String, name: String): Double? =
            Regex("""PARAMETER\s*\[\s*"$name"\s*,\s*([0-9.eE+-]+)""", RegexOption.IGNORE_CASE)
                .find(wkt)?.groupValues?.get(1)?.toDoubleOrNull()
    }
}
