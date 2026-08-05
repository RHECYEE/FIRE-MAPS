package com.rhecyee.firelinemap.location

import com.rhecyee.firelinemap.map.MapCoverage

/** A track, reduced to what overlap analysis needs. */
data class TrackLine(
    val id: String,
    val name: String,
    val points: List<Fix>
)

/** One track's pass through a spot. */
data class TrackPass(
    val trackId: String,
    val trackName: String,
    /** When this track went through, from the nearest fix. */
    val atMillis: Long?,
    /** Speed through the neighbourhood, or null when the track carries no times. */
    val speedMetersPerSecond: Double?,
    /** How close this track came to the spot. */
    val closestMeters: Double
) {
    /** True where the track was effectively stopped here. */
    val stopped: Boolean
        get() = speedMetersPerSecond != null && speedMetersPerSecond < STOPPED_BELOW

    companion object {
        /** Below this, the track was parked rather than travelling. ~1 mph. */
        const val STOPPED_BELOW = 0.45
    }
}

/**
 * What is at a spot when more than one track runs through it.
 *
 * The reason this exists is travel time. A road driven five times over a
 * fortnight is five answers to how long it takes, and the useful number is not
 * any one of them -- it is what the road actually runs at, which is only
 * visible once they are read together. Division supervisors ask this at every
 * briefing and the honest answer has always been a guess.
 */
data class OverlapReport(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    /** Every track through here, nearest first. */
    val passes: List<TrackPass>
) {
    val isOverlap: Boolean get() = passes.size > 1

    /**
     * Speed across every pass that was moving.
     *
     * Stops are left out on purpose. Somewhere a crew parks -- a drop point, a
     * gate, the ICP -- half the passes read near zero, and averaging those in
     * says a road takes an hour when driving it takes ten minutes. The stops
     * are still listed individually, because "everyone stops here" is worth
     * knowing on its own.
     */
    val averageSpeedMetersPerSecond: Double?
        get() {
            val moving = passes.mapNotNull { it.speedMetersPerSecond }
                .filter { it >= TrackPass.STOPPED_BELOW }
            if (moving.isEmpty()) return null
            return moving.sum() / moving.size
        }

    val stoppedCount: Int get() = passes.count { it.stopped }

    /** "4 tracks · 12 mph average", for the readout at the top of the list. */
    fun describe(): String {
        if (passes.isEmpty()) return "No tracks here"
        val head = "${passes.size} track${if (passes.size == 1) "" else "s"}"
        val speed = averageSpeedMetersPerSecond
            ?: return if (stoppedCount == passes.size) "$head · all stopped here" else head
        return "$head · ${formatSpeed(speed)} average"
    }

    companion object {
        private const val MPH_PER_METER_PER_SECOND = 2.2369362920544

        fun formatSpeed(metersPerSecond: Double): String {
            val mph = metersPerSecond * MPH_PER_METER_PER_SECOND
            // Whole numbers above walking pace; a decimal below it, where the
            // difference between two and three miles an hour is the difference
            // between a hand crew and a dozer line.
            return if (mph >= 5) "${kotlin.math.round(mph).toInt()} mph"
            else "${kotlin.math.round(mph * 10) / 10.0} mph"
        }
    }
}

/**
 * Finding the tracks that lie on top of each other.
 *
 * No de-duplication, deliberately. Two passes over the same road are two real
 * pieces of information, not a mistake to clean up, and a rule that decided
 * which to throw away would sooner or later throw away the one that mattered.
 * Anything genuinely unwanted the operator deletes.
 */
object TrackOverlap {

    /**
     * How close a track has to come to count as passing through.
     *
     * Forty metres. Wide enough that two passes down the same road match
     * despite ordinary GPS scatter and opposite lanes, tight enough that a
     * parallel road or a switchback below does not.
     */
    const val DEFAULT_RADIUS_METERS = 40.0

    /**
     * Every track through a spot, with what each was doing.
     *
     * [tracks] carries whole tracks; only the part near the spot is read.
     */
    fun at(
        latitude: Double,
        longitude: Double,
        tracks: List<TrackLine>,
        radiusMeters: Double = DEFAULT_RADIUS_METERS
    ): OverlapReport {
        if (!latitude.isFinite() || !longitude.isFinite()) {
            return OverlapReport(latitude, longitude, radiusMeters, emptyList())
        }
        val passes = tracks.mapNotNull { track ->
            passThrough(latitude, longitude, track, radiusMeters)
        }.sortedBy { it.closestMeters }
        return OverlapReport(latitude, longitude, radiusMeters, passes)
    }

    private fun passThrough(
        latitude: Double,
        longitude: Double,
        track: TrackLine,
        radiusMeters: Double
    ): TrackPass? {
        if (track.points.isEmpty()) return null

        var closest = Double.MAX_VALUE
        var closestIndex = -1
        var first = -1
        var last = -1

        track.points.forEachIndexed { index, fix ->
            val distance = MapCoverage.distanceMeters(
                latitude, longitude, fix.latitude, fix.longitude
            )
            if (distance < closest) {
                closest = distance
                closestIndex = index
            }
            if (distance <= radiusMeters) {
                if (first < 0) first = index
                last = index
            }
        }

        if (first < 0 || closestIndex < 0) return null

        return TrackPass(
            trackId = track.id,
            trackName = track.name,
            atMillis = track.points[closestIndex].timeMillis.takeIf { it > 0 },
            speedMetersPerSecond = speedThrough(track.points, first, last),
            closestMeters = closest
        )
    }

    /**
     * Speed across the run through the spot.
     *
     * Widened by one fix either side of the circle. At a five second update a
     * vehicle crosses forty metres in a single step, so the points strictly
     * inside are often one point and one point has no speed -- the fix before
     * and after are what give it a baseline to measure against.
     */
    private fun speedThrough(points: List<Fix>, first: Int, last: Int): Double? {
        val from = (first - 1).coerceAtLeast(0)
        val to = (last + 1).coerceAtMost(points.lastIndex)
        if (to <= from) return null

        val elapsedMillis = points[to].timeMillis - points[from].timeMillis
        if (elapsedMillis <= 0) return null

        var distance = 0.0
        for (index in from until to) {
            distance += MapCoverage.distanceMeters(
                points[index].latitude, points[index].longitude,
                points[index + 1].latitude, points[index + 1].longitude
            )
        }
        val speed = distance / (elapsedMillis / 1000.0)
        return if (speed.isFinite()) speed else null
    }
}
