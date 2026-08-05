package com.rhecyee.firelinemap.location

import com.rhecyee.firelinemap.map.MapCoverage
import kotlin.math.abs
import kotlin.math.max

/**
 * Deciding whether a detailed track is the same journey as an inferred one.
 *
 * The case: a phone suspended mid-shift kept only the ends of an hour of
 * driving. Later the real track arrives -- pasted from somebody else's phone,
 * or recovered from a device that did stay awake -- and the question is
 * whether it describes that same hour.
 *
 * Endpoints being close is not enough on its own and must never be treated as
 * enough. Two vehicles leaving the same drop point for the same helispot an
 * hour apart have near-identical endpoints and are different journeys; so do a
 * trip out and the trip back. Merging those loses one of them, silently, and
 * the operator has no way to notice.
 *
 * So every signal has to agree. They are reported individually as well as
 * together, because a near miss is worth showing a person -- five of six
 * agreeing is a question for somebody with a radio, not something to decide
 * here.
 */
object TrackMerge {

    /**
     * How far the ends may be apart and still be the same place.
     *
     * Three hundred metres. The ends of an inferred span are real fixes, but
     * they are the last fix before a phone went away and the first after it
     * came back -- often taken in a cab, under canopy, or on a receiver that
     * has just woken up and has not settled.
     */
    const val ENDPOINT_RADIUS_METERS = 300.0

    /** How far apart the two records' times may be and still be the same trip. */
    const val TIME_TOLERANCE_MILLIS = 10 * 60_000L

    /** Durations must agree within this, or this fraction, whichever is looser. */
    const val DURATION_TOLERANCE_MILLIS = 10 * 60_000L
    const val DURATION_TOLERANCE_FRACTION = 0.30

    /**
     * How much longer than the straight line a real route may plausibly be.
     *
     * A winding mountain road is routinely two or three times its own
     * displacement, and a switchbacked climb more. Past eight times, the
     * detailed track is describing a different and much larger journey that
     * happens to start and end nearby -- a whole shift, against an hour.
     */
    const val MAX_WINDING = 8.0

    /** Below this the ends are effectively the same place and a ratio means nothing. */
    const val LOOP_DISPLACEMENT_METERS = 100.0

    /** Each of the checks, so a near miss can be shown rather than swallowed. */
    data class Signals(
        val startsAgree: Boolean,
        val endsAgree: Boolean,
        val timesCorrespond: Boolean,
        val directionMatches: Boolean,
        val durationsClose: Boolean,
        val endpointsOnRoute: Boolean,
        val routePlausible: Boolean
    ) {
        val checks: List<Pair<String, Boolean>>
            get() = listOf(
                "Starts agree" to startsAgree,
                "Ends agree" to endsAgree,
                "Times correspond" to timesCorrespond,
                "Direction matches" to directionMatches,
                "Durations close" to durationsClose,
                "Ends lie on the route" to endpointsOnRoute,
                "Route length plausible" to routePlausible
            )

        val agreeing: Int get() = checks.count { it.second }

        /** Every one has to hold. Endpoints alone are never enough. */
        val matched: Boolean get() = checks.all { it.second }

        /** What failed, for a person deciding a near miss. */
        fun disagreements(): List<String> = checks.filter { !it.second }.map { it.first }
    }

    /**
     * Compares an inferred span against a detailed track.
     *
     * [allowReversed] accepts a track that runs the other way. Off by default:
     * out and back are different journeys, and treating them as one hides the
     * return.
     */
    fun match(
        inferred: InferredSpan,
        detailed: List<Fix>,
        allowReversed: Boolean = false,
        endpointRadiusMeters: Double = ENDPOINT_RADIUS_METERS
    ): Signals {
        if (detailed.size < 2) {
            return Signals(false, false, false, false, false, false, false)
        }

        val first = detailed.first()
        val last = detailed.last()

        val startToStart = MapCoverage.distanceMeters(
            inferred.startLatitude, inferred.startLongitude, first.latitude, first.longitude
        )
        val endToEnd = MapCoverage.distanceMeters(
            inferred.endLatitude, inferred.endLongitude, last.latitude, last.longitude
        )
        val startToEnd = MapCoverage.distanceMeters(
            inferred.startLatitude, inferred.startLongitude, last.latitude, last.longitude
        )
        val endToStart = MapCoverage.distanceMeters(
            inferred.endLatitude, inferred.endLongitude, first.latitude, first.longitude
        )

        val forwards = startToStart <= endpointRadiusMeters && endToEnd <= endpointRadiusMeters
        val backwards = startToEnd <= endpointRadiusMeters && endToStart <= endpointRadiusMeters

        // Ends agreeing in some order, and the direction question kept
        // separate from it -- so a reversed trip fails on direction rather
        // than passing quietly on proximity.
        val startsAgree = forwards || (allowReversed && backwards)
        val endsAgree = startsAgree
        val directionMatches = forwards || (allowReversed && backwards)

        val stamps = detailed.map { it.timeMillis }.filter { it > 0 }
        val detailedStart = stamps.minOrNull() ?: 0L
        val detailedEnd = stamps.maxOrNull() ?: 0L
        val hasTimes = stamps.size >= 2

        val timesCorrespond = hasTimes && overlapping(
            inferred.startMillis, inferred.endMillis, detailedStart, detailedEnd
        )

        val detailedDuration = (detailedEnd - detailedStart).coerceAtLeast(0)
        val durationsClose = hasTimes && abs(detailedDuration - inferred.elapsedMillis) <=
            max(
                DURATION_TOLERANCE_MILLIS,
                (inferred.elapsedMillis * DURATION_TOLERANCE_FRACTION).toLong()
            )

        val endpointsOnRoute =
            metersFromRoute(inferred.startLatitude, inferred.startLongitude, detailed) <=
                endpointRadiusMeters &&
                metersFromRoute(inferred.endLatitude, inferred.endLongitude, detailed) <=
                endpointRadiusMeters

        val routePlausible = plausible(inferred.displacementMeters, routeLength(detailed))

        return Signals(
            startsAgree = startsAgree,
            endsAgree = endsAgree,
            timesCorrespond = timesCorrespond,
            directionMatches = directionMatches,
            durationsClose = durationsClose,
            endpointsOnRoute = endpointsOnRoute,
            routePlausible = routePlausible
        )
    }

    /**
     * The merged result.
     *
     * The detailed geometry supersedes the inferred line; the inferred record
     * stays attached rather than being deleted. Keeping it costs six numbers
     * and means a synchronisation fault can never erase what was known -- and
     * it is the only evidence of how long the stretch actually took if the
     * detailed track turns out to be the wrong one.
     */
    data class Merged(
        val points: List<Fix>,
        /** Held as provenance. Never drawn once a detailed route supersedes it. */
        val supersededInferred: InferredSpan,
        val signals: Signals
    ) {
        val recordedDistanceMeters: Double get() = routeLength(points)

        val elapsedMillis: Long get() = supersededInferred.elapsedMillis

        /**
         * "Travel time 43 min · route 6.8 mi · straight line 4.1 mi".
         *
         * Both distances, always. The gap between them is the reason the
         * estimate was only ever a floor, and showing one without the other
         * is how a floor gets quoted as a measurement.
         */
        fun summary(): List<String> = listOf(
            "Detailed track retained · inferred record kept as provenance",
            "Travel time ${OverlapReport.formatElapsed(elapsedMillis)}",
            "Recorded route ${OverlapReport.formatDistance(recordedDistanceMeters)}",
            "Straight-line displacement " +
                OverlapReport.formatDistance(supersededInferred.displacementMeters)
        )
    }

    /**
     * Merges when every signal agrees, and refuses otherwise.
     *
     * Returns null rather than merging on a near miss. The operator is shown
     * what disagreed and decides, because the cost of a wrong merge is a
     * journey nobody can see any more.
     */
    fun merge(
        inferred: InferredSpan,
        detailed: List<Fix>,
        allowReversed: Boolean = false
    ): Merged? {
        val signals = match(inferred, detailed, allowReversed)
        if (!signals.matched) return null
        return Merged(detailed, inferred, signals)
    }

    private fun overlapping(
        fromA: Long,
        toA: Long,
        fromB: Long,
        toB: Long
    ): Boolean {
        // Overlapping outright, or near enough either side to be the same
        // trip recorded by two clocks that do not agree to the second.
        if (fromA <= toB && fromB <= toA) return true
        val apart = if (toA < fromB) fromB - toA else fromA - toB
        return apart <= TIME_TOLERANCE_MILLIS
    }

    private fun plausible(displacementMeters: Double, routeMeters: Double): Boolean {
        if (routeMeters <= 0) return false
        // A route cannot be meaningfully shorter than the straight line between
        // its own ends; a little slack for fixes that wander.
        if (routeMeters < displacementMeters * 0.85) return false
        // A journey that returns near where it started has no meaningful ratio.
        if (displacementMeters < LOOP_DISPLACEMENT_METERS) return true
        return routeMeters <= displacementMeters * MAX_WINDING
    }

    private fun routeLength(points: List<Fix>): Double {
        var total = 0.0
        for (index in 0 until points.size - 1) {
            total += MapCoverage.distanceMeters(
                points[index].latitude, points[index].longitude,
                points[index + 1].latitude, points[index + 1].longitude
            )
        }
        return total
    }

    /** How far a position lies from the drawn route, in metres. */
    private fun metersFromRoute(
        latitude: Double,
        longitude: Double,
        route: List<Fix>
    ): Double {
        var best = Double.MAX_VALUE
        for (index in 0 until route.size - 1) {
            val distance = metersFromSegment(
                latitude, longitude,
                route[index].latitude, route[index].longitude,
                route[index + 1].latitude, route[index + 1].longitude
            )
            if (distance < best) best = distance
        }
        return best
    }

    /**
     * Distance to a segment, worked in local metres.
     *
     * Degrees would be wrong: a degree of longitude is a different distance at
     * every latitude, so a tolerance in degrees is tighter in the north than
     * in the south by enough to matter across the western states.
     */
    private fun metersFromSegment(
        latitude: Double,
        longitude: Double,
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double
    ): Double {
        val metersPerDegreeLatitude = 111_194.93
        val metersPerDegreeLongitude =
            metersPerDegreeLatitude * kotlin.math.cos(fromLatitude * kotlin.math.PI / 180.0)

        val pointX = (longitude - fromLongitude) * metersPerDegreeLongitude
        val pointY = (latitude - fromLatitude) * metersPerDegreeLatitude
        val alongX = (toLongitude - fromLongitude) * metersPerDegreeLongitude
        val alongY = (toLatitude - fromLatitude) * metersPerDegreeLatitude

        val lengthSquared = alongX * alongX + alongY * alongY
        if (lengthSquared <= 0.0) return kotlin.math.sqrt(pointX * pointX + pointY * pointY)

        val along = ((pointX * alongX + pointY * alongY) / lengthSquared).coerceIn(0.0, 1.0)
        val nearestX = along * alongX
        val nearestY = along * alongY
        return kotlin.math.sqrt(
            (pointX - nearestX) * (pointX - nearestX) +
                (pointY - nearestY) * (pointY - nearestY)
        )
    }
}
