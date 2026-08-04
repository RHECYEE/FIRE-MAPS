package com.rhecyee.firelinemap.terrain

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Which way a slope faces. */
enum class Aspect(val label: String, val abbreviation: String) {
    NORTH("North", "N"),
    NORTHEAST("Northeast", "NE"),
    EAST("East", "E"),
    SOUTHEAST("Southeast", "SE"),
    SOUTH("South", "S"),
    SOUTHWEST("Southwest", "SW"),
    WEST("West", "W"),
    NORTHWEST("Northwest", "NW"),

    /** Ground flat enough that it does not face anywhere in particular. */
    FLAT("Flat", "—");

    companion object {
        fun fromDegrees(degrees: Double?): Aspect {
            if (degrees == null) return FLAT
            val normalised = ((degrees % 360.0) + 360.0) % 360.0
            return when {
                normalised < 22.5 || normalised >= 337.5 -> NORTH
                normalised < 67.5 -> NORTHEAST
                normalised < 112.5 -> EAST
                normalised < 157.5 -> SOUTHEAST
                normalised < 202.5 -> SOUTH
                normalised < 247.5 -> SOUTHWEST
                normalised < 292.5 -> WEST
                else -> NORTHWEST
            }
        }
    }
}

/** Terrain at a point, as a crew would want it stated. */
data class TerrainReading(
    val elevationMeters: Double,
    val slopePercent: Double?,
    val aspectDegrees: Double?
) {
    val elevationFeet: Double get() = elevationMeters * 3.280839895

    val slopeDegrees: Double?
        get() = slopePercent?.let { Math.toDegrees(atan(it / 100.0)) }

    val aspect: Aspect get() = Aspect.fromDegrees(aspectDegrees)

    /** "6,842 ft · 31% · SW". */
    fun summary(): String = buildString {
        append("%,d ft".format(elevationFeet.roundToInt()))
        slopePercent?.let { append(" · ${it.roundToInt()}%") }
        if (aspect != Aspect.FLAT) append(" · ${aspect.abbreviation}")
    }
}

/**
 * Slope and aspect from a patch of elevations.
 *
 * Horn's method over a three by three window, which is what GDAL and Esri both
 * use, so a figure here matches one taken off a desktop from the same data.
 *
 * Slope is reported as a percentage because that is how cut slope, engine
 * limits and dozer limits are quoted on a fire. Degrees are available from the
 * reading for anyone who wants them.
 */
object TerrainMath {

    /** Terrain-RGB encodes elevation across the three colour channels. */
    fun decodeTerrainRgb(red: Int, green: Int, blue: Int): Double =
        -10_000.0 + ((red * 256 * 256 + green * 256 + blue) * 0.1)

    /**
     * @param grid nine elevations in metres, row-major, north row first.
     * @param cellSizeMeters ground distance between neighbouring samples.
     */
    fun slopePercent(grid: DoubleArray, cellSizeMeters: Double): Double? {
        if (grid.size != 9 || cellSizeMeters <= 0) return null
        if (grid.any { it.isNaN() }) return null
        val (dzdx, dzdy) = gradients(grid, cellSizeMeters)
        return hypot(dzdx, dzdy) * 100.0
    }

    /**
     * Direction of steepest descent, degrees clockwise from north.
     *
     * Null on ground flat enough that any answer would be noise.
     */
    fun aspectDegrees(
        grid: DoubleArray,
        cellSizeMeters: Double,
        flatThresholdPercent: Double = 0.5
    ): Double? {
        if (grid.size != 9 || cellSizeMeters <= 0) return null
        if (grid.any { it.isNaN() }) return null
        val (dzdx, dzdy) = gradients(grid, cellSizeMeters)
        if (hypot(dzdx, dzdy) * 100.0 < flatThresholdPercent) return null

        // Downhill is the negative of the gradient; north is zero and the
        // compass runs clockwise, which is the other way round from maths.
        var degrees = Math.toDegrees(Math.atan2(dzdy, -dzdx))
        degrees = 90.0 - degrees
        return ((degrees % 360.0) + 360.0) % 360.0
    }

    fun reading(
        grid: DoubleArray,
        cellSizeMeters: Double
    ): TerrainReading? {
        if (grid.size != 9) return null
        val centre = grid[4]
        if (centre.isNaN()) return null
        return TerrainReading(
            elevationMeters = centre,
            slopePercent = slopePercent(grid, cellSizeMeters),
            aspectDegrees = aspectDegrees(grid, cellSizeMeters)
        )
    }

    /** Horn's weighted differences, returned as rise over run. */
    private fun gradients(grid: DoubleArray, cell: Double): Pair<Double, Double> {
        // a b c
        // d e f
        // g h i
        val a = grid[0]; val b = grid[1]; val c = grid[2]
        val d = grid[3]; /* e */          val f = grid[5]
        val g = grid[6]; val h = grid[7]; val i = grid[8]

        val dzdx = ((c + 2 * f + i) - (a + 2 * d + g)) / (8.0 * cell)
        // Grid rows run north to south, so this is already rise toward the north.
        val dzdy = ((g + 2 * h + i) - (a + 2 * b + c)) / (8.0 * cell)
        return dzdx to dzdy
    }

    /**
     * Elevation gain and loss along a run of samples.
     *
     * A threshold is applied because elevation data is noisy: without one, a
     * flat road accumulates hundreds of feet of imaginary climb.
     */
    fun gainAndLoss(
        elevations: List<Double>,
        thresholdMeters: Double = 3.0
    ): Pair<Double, Double> {
        if (elevations.size < 2) return 0.0 to 0.0
        var gain = 0.0
        var loss = 0.0
        var reference = elevations.first()
        for (value in elevations.drop(1)) {
            val change = value - reference
            if (abs(change) < thresholdMeters) continue
            if (change > 0) gain += change else loss += -change
            reference = value
        }
        return gain to loss
    }
}
