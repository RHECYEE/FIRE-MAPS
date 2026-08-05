package com.rhecyee.firelinemap.measure

import com.rhecyee.firelinemap.map.Earth
import com.rhecyee.firelinemap.map.MapCoverage
import com.rhecyee.firelinemap.map.degreesToRadians
import com.rhecyee.firelinemap.map.radiansToDegrees
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sin
import kotlin.math.sqrt

/** A point being measured. [elevationMeters] is filled in asynchronously. */
data class MeasurePoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double? = null
)

enum class MeasureMode {
    /** Distance, bearing and slope along a path. Two points is a straight line. */
    DISTANCE,

    /** Enclosed area. The ring is closed back to the first point. */
    AREA
}

enum class DistanceUnit(val label: String, val perMeter: Double) {
    FEET("ft", 3.280839895),

    /**
     * The surveyor's chain, sixty-six feet.
     *
     * Still the unit fireline is cut and reported in: crews call a hundred
     * chains of line, not sixty-six hundred feet, and a division break gets
     * passed over a radio in chains. Eighty chains to the mile, which is why
     * it survives -- the arithmetic works out in the head.
     */
    CHAINS("ch", 1.0 / 20.1168),

    MILES("mi", 1.0 / 1609.344),
    METERS("m", 1.0),
    KILOMETERS("km", 0.001),
    NAUTICAL_MILES("NM", 1.0 / 1852.0);

    fun from(meters: Double): Double = meters * perMeter

    fun next(): DistanceUnit = entries[(ordinal + 1) % entries.size]

    companion object {
        /** Feet in a mile, and the point where feet stop being readable. */
        const val FEET_PER_MILE = 5280.0

        /**
         * A distance in the unit that suits its size.
         *
         * Feet up to a mile and miles past it. "8190 ft" is a number nobody
         * converts in their head on a radio; "1.6 mi" is the same distance and
         * is immediately a drive. Chains are never chosen automatically --
         * they are what line is reported in, not what travel is, so they stay
         * a deliberate choice.
         */
        fun readable(meters: Double): String {
            // Rounded before comparing. A mile is 5279.9999 feet in floating
            // point, so comparing the raw value prints "5280 ft" at exactly
            // the distance that is supposed to become miles.
            val feet = kotlin.math.round(FEET.from(meters))
            if (feet < FEET_PER_MILE) return "${feet.toInt()} ft"
            val miles = MILES.from(meters)
            return "${kotlin.math.round(miles * 100) / 100.0} mi"
        }

        /** The same distance in chains, for line. */
        fun inChains(meters: Double): String =
            "${kotlin.math.round(CHAINS.from(meters) * 10) / 10.0} ch"
    }
}

enum class AreaUnit(val label: String, val perSquareMeter: Double) {
    ACRES("ac", 1.0 / 4046.8564224),
    HECTARES("ha", 1.0 / 10_000.0),
    SQUARE_MILES("sq mi", 1.0 / 2_589_988.110336),
    SQUARE_FEET("sq ft", 10.763910417);

    fun from(squareMeters: Double): Double = squareMeters * perSquareMeter

    fun next(): AreaUnit = entries[(ordinal + 1) % entries.size]
}

/** One leg between consecutive points. */
data class MeasureSegment(
    val index: Int,
    val distanceMeters: Double,
    val bearingDegrees: Double,
    val riseMeters: Double?
) {
    /** Rise over run as a percentage, the form cut slope is usually quoted in. */
    val slopePercent: Double?
        get() {
            val rise = riseMeters ?: return null
            if (distanceMeters <= 0.0) return null
            return rise / distanceMeters * 100.0
        }

    val slopeDegrees: Double?
        get() {
            val rise = riseMeters ?: return null
            if (distanceMeters <= 0.0) return null
            return radiansToDegrees(atan(rise / distanceMeters))
        }
}

/** The computed result of a measurement. */
data class MeasureResult(
    val mode: MeasureMode,
    val segments: List<MeasureSegment>,
    val totalDistanceMeters: Double,
    val areaSquareMeters: Double?,
    val gainMeters: Double?,
    val lossMeters: Double?
) {
    val pointCount: Int get() = segments.size + 1

    /** Straight-line grade from the first point to the last. */
    val overallSlopePercent: Double?
        get() {
            val rises = segments.mapNotNull { it.riseMeters }
            if (rises.size != segments.size || segments.isEmpty()) return null
            if (totalDistanceMeters <= 0.0) return null
            return rises.sum() / totalDistanceMeters * 100.0
        }
}

/**
 * Accumulates tapped points and measures them.
 *
 * Geodesic throughout: distances are computed on the ellipsoid rather than
 * from screen pixels or the sheet's printed scale, so a measurement means the
 * same thing whatever the map was drawn at and however far it has been zoomed.
 */
class MeasureSession(
    var mode: MeasureMode = MeasureMode.DISTANCE
) {
    private val points = mutableListOf<MeasurePoint>()

    val currentPoints: List<MeasurePoint> get() = points.toList()
    val size: Int get() = points.size
    val isEmpty: Boolean get() = points.isEmpty()

    /** True once there is enough to report something. */
    val isMeasurable: Boolean
        get() = if (mode == MeasureMode.AREA) points.size >= 3 else points.size >= 2

    fun add(latitude: Double, longitude: Double) {
        points += MeasurePoint(latitude, longitude)
    }

    fun undo(): Boolean {
        if (points.isEmpty()) return false
        points.removeAt(points.size - 1)
        return true
    }

    fun clear() = points.clear()

    /** Fills in an elevation once it has been looked up. */
    fun setElevation(index: Int, elevationMeters: Double) {
        if (index !in points.indices) return
        points[index] = points[index].copy(elevationMeters = elevationMeters)
    }

    fun result(): MeasureResult {
        val segments = mutableListOf<MeasureSegment>()
        var total = 0.0
        var gain = 0.0
        var loss = 0.0
        var everyElevationKnown = points.size >= 2

        for (i in 0 until points.size - 1) {
            val from = points[i]
            val to = points[i + 1]
            val distance = MapCoverage.distanceMeters(
                from.latitude, from.longitude, to.latitude, to.longitude
            )
            val bearing = MapCoverage.bearingDegrees(
                from.latitude, from.longitude, to.latitude, to.longitude
            )
            val rise = if (from.elevationMeters != null && to.elevationMeters != null) {
                to.elevationMeters - from.elevationMeters
            } else {
                everyElevationKnown = false
                null
            }
            if (rise != null) {
                if (rise >= 0) gain += rise else loss += -rise
            }
            total += distance
            segments += MeasureSegment(i, distance, bearing, rise)
        }

        // The closing leg counts toward the perimeter of an area, but is not a
        // measured segment the operator drew.
        if (mode == MeasureMode.AREA && points.size >= 3) {
            total += MapCoverage.distanceMeters(
                points.last().latitude, points.last().longitude,
                points.first().latitude, points.first().longitude
            )
        }

        return MeasureResult(
            mode = mode,
            segments = segments,
            totalDistanceMeters = total,
            areaSquareMeters = if (mode == MeasureMode.AREA) area() else null,
            gainMeters = if (everyElevationKnown && segments.isNotEmpty()) gain else null,
            lossMeters = if (everyElevationKnown && segments.isNotEmpty()) loss else null
        )
    }

    /**
     * Spherical excess area of the ring, in square metres.
     *
     * Uses a sphere rather than the ellipsoid. At the size of a division or a
     * spot fire the difference is a fraction of a percent, well inside the
     * rounding that acreage is reported at, and it avoids tying the
     * measurement to any one projection.
     */
    private fun area(): Double? {
        if (points.size < 3) return null
        var total = 0.0
        for (i in points.indices) {
            val current = points[i]
            val next = points[(i + 1) % points.size]
            total += degreesToRadians(next.longitude - current.longitude) *
                (2.0 + sin(degreesToRadians(current.latitude)) + sin(degreesToRadians(next.latitude)))
        }
        return abs(total * Earth.RADIUS_METERS * Earth.RADIUS_METERS / 2.0)
    }

    companion object {
        /**
         * Slope-corrected distance, given a plan distance and a rise.
         *
         * Ground distance on a steep pitch exceeds map distance, which is what
         * matters when the figure is being used to estimate how much line a
         * crew has to cut.
         */
        fun slopeDistance(planMeters: Double, riseMeters: Double): Double =
            sqrt(planMeters * planMeters + riseMeters * riseMeters)
    }
}
