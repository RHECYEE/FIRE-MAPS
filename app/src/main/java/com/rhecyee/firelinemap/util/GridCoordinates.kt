package com.rhecyee.firelinemap.util

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A position on the Universal Transverse Mercator grid. */
data class UtmPosition(
    val zone: Int,
    /** Latitude band letter, C through X. Not the hemisphere. */
    val band: Char,
    val easting: Double,
    val northing: Double,
    val northernHemisphere: Boolean
) {
    /** "11T 449975 5006004", which is how it goes over a radio. */
    fun format(): String = "$zone$band ${easting.toInt()} ${northing.toInt()}"
}

/**
 * UTM and MGRS, both directions.
 *
 * Carried because these are what aviation and military resources work in.
 * A helicopter asking for a spot wants a grid, and a crew that can only read
 * degrees has to have somebody else convert -- on a radio, from memory, which
 * is where a digit goes missing.
 *
 * WGS84 throughout, which is what a phone's receiver reports and what every
 * agency product this app touches is published in.
 */
object GridCoordinates {

    private const val A = 6_378_137.0
    private const val F = 1.0 / 298.257223563
    private const val K0 = 0.9996
    private val E2 = F * (2 - F)
    private val EP2 = E2 / (1 - E2)

    /** Latitude bands, south to north. I and O are skipped: they read as 1 and 0. */
    const val BANDS = "CDEFGHJKLMNPQRSTUVWX"

    /**
     * The zone a longitude falls in.
     *
     * With the two exceptions the standard carries: south-west Norway widens
     * zone 32, and Svalbard rearranges 31 through 37. Both are far outside
     * anywhere this app is used, and both are in the standard, so a grid
     * printed here matches a grid printed anywhere else.
     */
    fun zoneFor(latitude: Double, longitude: Double): Int {
        val normalised = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        var zone = (floor((normalised + 180.0) / 6.0).toInt() + 1).coerceIn(1, 60)
        if (latitude in 56.0..64.0 && normalised in 3.0..12.0) zone = 32
        if (latitude in 72.0..84.0) {
            zone = when {
                normalised in 0.0..9.0 -> 31
                normalised in 9.0..21.0 -> 33
                normalised in 21.0..33.0 -> 35
                normalised in 33.0..42.0 -> 37
                else -> zone
            }
        }
        return zone
    }

    fun bandFor(latitude: Double): Char {
        if (latitude < -80.0 || latitude > 84.0) return '?'
        val index = ((latitude + 80.0) / 8.0).toInt().coerceIn(0, BANDS.lastIndex)
        return BANDS[index]
    }

    /** Latitude and longitude to a grid position. */
    fun toUtm(latitude: Double, longitude: Double): UtmPosition? {
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        if (latitude < -80.0 || latitude > 84.0) return null

        val zone = zoneFor(latitude, longitude)
        val centralMeridian = Math.toRadians((zone - 1) * 6.0 - 180.0 + 3.0)
        val phi = Math.toRadians(latitude)
        val lambda = Math.toRadians(longitude)

        val sinPhi = sin(phi)
        val cosPhi = cos(phi)
        val tanPhi = tan(phi)

        val n = A / sqrt(1 - E2 * sinPhi * sinPhi)
        val t = tanPhi * tanPhi
        val c = EP2 * cosPhi * cosPhi
        var deltaLambda = lambda - centralMeridian
        // Keep the difference in range, so a zone chosen across the date line
        // does not produce an easting a quarter of the world out.
        while (deltaLambda > PI) deltaLambda -= 2 * PI
        while (deltaLambda < -PI) deltaLambda += 2 * PI
        val a1 = cosPhi * deltaLambda

        val m = A * (
            (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2.pow(3) / 256) * phi -
                (3 * E2 / 8 + 3 * E2 * E2 / 32 + 45 * E2.pow(3) / 1024) * sin(2 * phi) +
                (15 * E2 * E2 / 256 + 45 * E2.pow(3) / 1024) * sin(4 * phi) -
                (35 * E2.pow(3) / 3072) * sin(6 * phi)
            )

        val easting = K0 * n * (
            a1 + (1 - t + c) * a1.pow(3) / 6 +
                (5 - 18 * t + t * t + 72 * c - 58 * EP2) * a1.pow(5) / 120
            ) + 500_000.0

        var northing = K0 * (
            m + n * tanPhi * (
                a1 * a1 / 2 + (5 - t + 9 * c + 4 * c * c) * a1.pow(4) / 24 +
                    (61 - 58 * t + t * t + 600 * c - 330 * EP2) * a1.pow(6) / 720
                )
            )
        if (latitude < 0) northing += 10_000_000.0

        return UtmPosition(
            zone = zone,
            band = bandFor(latitude),
            easting = easting,
            northing = northing,
            northernHemisphere = latitude >= 0
        )
    }

    /** A grid position back to latitude and longitude. */
    fun fromUtm(
        zone: Int,
        easting: Double,
        northing: Double,
        northernHemisphere: Boolean
    ): Pair<Double, Double>? {
        if (zone !in 1..60) return null
        if (!easting.isFinite() || !northing.isFinite()) return null

        val x = easting - 500_000.0
        val y = if (northernHemisphere) northing else northing - 10_000_000.0
        val centralMeridian = Math.toRadians((zone - 1) * 6.0 - 180.0 + 3.0)

        val e1 = (1 - sqrt(1 - E2)) / (1 + sqrt(1 - E2))
        val m = y / K0
        val mu = m / (A * (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2.pow(3) / 256))

        val phi1 = mu +
            (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) +
            (21 * e1 * e1 / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
            (151 * e1.pow(3) / 96) * sin(6 * mu) +
            (1097 * e1.pow(4) / 512) * sin(8 * mu)

        val sinPhi1 = sin(phi1)
        val cosPhi1 = cos(phi1)
        val tanPhi1 = tan(phi1)

        val n1 = A / sqrt(1 - E2 * sinPhi1 * sinPhi1)
        val t1 = tanPhi1 * tanPhi1
        val c1 = EP2 * cosPhi1 * cosPhi1
        val r1 = A * (1 - E2) / (1 - E2 * sinPhi1 * sinPhi1).pow(1.5)
        val d = x / (n1 * K0)

        val latitude = phi1 - (n1 * tanPhi1 / r1) * (
            d * d / 2 -
                (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * EP2) * d.pow(4) / 24 +
                (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * EP2 - 3 * c1 * c1) *
                d.pow(6) / 720
            )

        val longitude = centralMeridian + (
            d - (1 + 2 * t1 + c1) * d.pow(3) / 6 +
                (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * EP2 + 24 * t1 * t1) * d.pow(5) / 120
            ) / cosPhi1

        val degreesLatitude = Math.toDegrees(latitude)
        val degreesLongitude = Math.toDegrees(longitude)
        if (!degreesLatitude.isFinite() || !degreesLongitude.isFinite()) return null
        return degreesLatitude to degreesLongitude
    }

    /** Column letters, in three sets that repeat every three zones. */
    private const val COLUMNS = "ABCDEFGHJKLMNPQRSTUVWXYZ"

    /** Row letters. Twenty of them, cycling every two million metres. */
    private const val ROWS = "ABCDEFGHJKLMNPQRSTV"

    /**
     * A grid reference, to the given number of digits per axis.
     *
     * Five digits is a metre, four is ten metres, three is a hundred. Aviation
     * usually works in six figures total -- three and three -- which puts a
     * helicopter inside a hundred metres of a spot, and that is close enough
     * to see it.
     */
    fun toMgrs(latitude: Double, longitude: Double, digits: Int = 5): String? {
        val utm = toUtm(latitude, longitude) ?: return null
        if (utm.band == '?') return null
        val places = digits.coerceIn(1, 5)

        val columnIndex = (utm.easting / 100_000.0).toInt()
        if (columnIndex !in 1..8) return null
        // Sets of eight, repeating every three zones.
        val columnOffset = ((utm.zone - 1) % 3) * 8
        val column = COLUMNS[(columnOffset + columnIndex - 1) % COLUMNS.length]

        // Rows cycle every two million metres, and every other zone starts the
        // cycle five letters along.
        val rowIndex = ((utm.northing % 2_000_000.0) / 100_000.0).toInt()
        val rowOffset = if (utm.zone % 2 == 0) 5 else 0
        val row = ROWS[(rowIndex + rowOffset) % ROWS.length]

        val divisor = 10.0.pow(5 - places)
        val east = ((utm.easting % 100_000.0) / divisor).toInt()
        val north = ((utm.northing % 100_000.0) / divisor).toInt()

        return "${utm.zone}${utm.band}$column$row" +
            east.toString().padStart(places, '0') +
            north.toString().padStart(places, '0')
    }

    /** Reads a grid reference back. Spaces anywhere are ignored. */
    fun fromMgrs(input: String): Pair<Double, Double>? {
        val text = input.filterNot { it.isWhitespace() }.uppercase()
        if (text.length < 5) return null

        var index = 0
        while (index < text.length && text[index].isDigit()) index++
        if (index == 0 || index > 2) return null
        val zone = text.substring(0, index).toIntOrNull() ?: return null
        if (zone !in 1..60) return null

        if (index >= text.length) return null
        val band = text[index]
        if (band !in BANDS) return null
        index++

        if (index + 1 >= text.length) return null
        val column = text[index]
        val row = text[index + 1]
        index += 2

        val digits = text.substring(index)
        if (digits.isEmpty() || digits.length % 2 != 0 || digits.length > 10) return null
        if (!digits.all { it.isDigit() }) return null
        val places = digits.length / 2

        val columnOffset = ((zone - 1) % 3) * 8
        val columnIndex = COLUMNS.indexOf(column)
        if (columnIndex < 0) return null
        val eastSquare = ((columnIndex - columnOffset + COLUMNS.length) % COLUMNS.length) + 1
        if (eastSquare !in 1..8) return null

        val rowOffset = if (zone % 2 == 0) 5 else 0
        val rowIndex = ROWS.indexOf(row)
        if (rowIndex < 0) return null
        val northSquare = (rowIndex - rowOffset + ROWS.length) % ROWS.length

        val multiplier = 10.0.pow(5 - places)
        val easting = eastSquare * 100_000.0 +
            digits.substring(0, places).toDouble() * multiplier
        val squareNorthing = northSquare * 100_000.0 +
            digits.substring(places).toDouble() * multiplier

        // The row letters repeat every two million metres, so the band decides
        // which repetition is meant. Without this a reference lands anywhere
        // on a column two thousand kilometres long.
        val bandIndex = BANDS.indexOf(band)
        val bandSouthLatitude = bandIndex * 8.0 - 80.0
        val approximate = toUtm(bandSouthLatitude, (zone - 1) * 6.0 - 180.0 + 3.0)
            ?: return null
        val base = floor(approximate.northing / 2_000_000.0) * 2_000_000.0
        var northing = base + squareNorthing
        // The band may straddle a cycle boundary; take whichever repetition
        // actually lands inside it.
        val northern = band >= 'N'
        for (candidate in listOf(northing, northing + 2_000_000.0, northing - 2_000_000.0)) {
            if (candidate < 0) continue
            val point = fromUtm(zone, easting, candidate, northern) ?: continue
            if (point.first >= bandSouthLatitude - 0.6 &&
                point.first <= bandSouthLatitude + 8.6
            ) {
                return point
            }
        }
        northing = base + squareNorthing
        return fromUtm(zone, easting, northing, northern)
    }
}
