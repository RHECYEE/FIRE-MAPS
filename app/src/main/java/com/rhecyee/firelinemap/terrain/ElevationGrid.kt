package com.rhecyee.firelinemap.terrain

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tan

/**
 * A rectangle of ground sampled on a regular grid.
 *
 * Rows run north to south and columns west to east, which is the order tiles
 * arrive in. Values are metres above sea level; NaN marks a sample that never
 * came, so a hole in the data stays a hole rather than becoming a cliff.
 *
 * Rows are spaced evenly in Web Mercator rather than in latitude, because that
 * is how the source tiles are cut. Interpolating in latitude instead would put
 * every contour progressively out of place from the middle of the view toward
 * its edges -- a small error at the top of a screen, but exactly the sort that
 * gets discovered by someone standing on the wrong side of a ridge.
 */
class ElevationGrid(
    val columns: Int,
    val rows: Int,
    val values: DoubleArray,
    val north: Double,
    val south: Double,
    val west: Double,
    val east: Double
) {
    init {
        require(columns >= 2 && rows >= 2) { "a grid needs at least one cell" }
        require(values.size == columns * rows) { "values do not match the grid" }
    }

    private val northY = mercatorY(north)
    private val southY = mercatorY(south)

    fun at(row: Int, column: Int): Double = values[row * columns + column]

    /** Longitude of a fractional column, which is linear in Mercator. */
    fun longitudeAt(column: Double): Double =
        west + (east - west) * (column / (columns - 1).toDouble())

    /** Latitude of a fractional row, taken through Mercator. */
    fun latitudeAt(row: Double): Double {
        val y = northY + (southY - northY) * (row / (rows - 1).toDouble())
        return inverseMercatorY(y)
    }

    /** Lowest and highest sample, or null when nothing came at all. */
    fun relief(): Pair<Double, Double>? {
        var low = Double.MAX_VALUE
        var high = -Double.MAX_VALUE
        for (value in values) {
            if (value.isNaN()) continue
            if (value < low) low = value
            if (value > high) high = value
        }
        return if (low > high) null else low to high
    }

    /** How much of the grid actually has data behind it. */
    fun coverage(): Double {
        if (values.isEmpty()) return 0.0
        return values.count { !it.isNaN() } / values.size.toDouble()
    }

    companion object {
        fun mercatorY(latitude: Double): Double {
            val clamped = latitude.coerceIn(-85.05112878, 85.05112878)
            return ln(tan(PI / 4.0 + Math.toRadians(clamped) / 2.0))
        }

        fun inverseMercatorY(y: Double): Double =
            Math.toDegrees(2.0 * atan(exp(y)) - PI / 2.0)
    }
}
