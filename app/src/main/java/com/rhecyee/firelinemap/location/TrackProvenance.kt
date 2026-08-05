package com.rhecyee.firelinemap.location

import com.rhecyee.firelinemap.map.MapCoverage

/**
 * How much of a track the device actually saw.
 *
 * Kept as a first-class property rather than inferred from the shape, because
 * the difference is invisible on a map and decisive on the ground. A line
 * drawn between two points the phone recorded an hour apart looks exactly like
 * a line it followed the whole way.
 */
enum class Provenance(val label: String) {
    /** Every point came from the receiver. */
    RECORDED("Recorded"),

    /** Only the ends are real. The line between them is a guess. */
    INFERRED("Inferred"),

    /** Recorded either side of a stretch that was not. */
    HYBRID("Partly recorded");
}

/**
 * A stretch the device did not record, described by its ends.
 *
 * This is what survives a phone being suspended. iOS stops a web app the
 * moment it is backgrounded or the screen locks, so a shift can end up as two
 * recorded pieces with an hour of driving between them that nothing observed.
 * Throwing that hour away loses real information -- somebody did travel it,
 * and roughly how long it took is known. Drawing it as an ordinary line
 * invents information that was never there.
 *
 * So it is kept, and it is labelled.
 */
data class InferredSpan(
    val startLatitude: Double,
    val startLongitude: Double,
    val startMillis: Long,
    val endLatitude: Double,
    val endLongitude: Double,
    val endMillis: Long
) {
    val elapsedMillis: Long get() = (endMillis - startMillis).coerceAtLeast(0)

    /** Straight line between the ends. Not the distance travelled. */
    val displacementMeters: Double
        get() = MapCoverage.distanceMeters(
            startLatitude, startLongitude, endLatitude, endLongitude
        )

    /**
     * Displacement over elapsed time, and nothing more.
     *
     * Deliberately not called an average travel speed, because it is not one.
     * With two points the distance actually covered is unknown: a winding road
     * can be eight miles between ends that are three miles apart, and the real
     * average would then be nearly three times this.
     *
     * What it is, exactly, is a floor. Whatever route was taken cannot be
     * shorter than the straight line, so the true average was at least this
     * and probably more. That makes it safe for the one thing it gets used for
     * -- was this stretch driven or walked -- and unsafe for planning a
     * turnaround time, which is why the wording never softens.
     */
    val estimatedSpeedMetersPerSecond: Double?
        get() {
            if (elapsedMillis <= 0) return null
            val speed = displacementMeters / (elapsedMillis / 1000.0)
            return if (speed.isFinite()) speed else null
        }

    /** Always says what it is. */
    fun describe(): String {
        val time = OverlapReport.formatElapsed(elapsedMillis)
        val straight = OverlapReport.formatDistance(displacementMeters)
        val speed = estimatedSpeedMetersPerSecond
            ?: return "Not recorded · $time · $straight straight line"
        return "Not recorded · $time · $straight straight line · " +
            "at least ${OverlapReport.formatSpeed(speed)}"
    }

    /** The two-point line that stands in for it, marked for what it is. */
    fun asPoints(): List<Fix> = listOf(
        Fix(startLatitude, startLongitude, startMillis),
        Fix(endLatitude, endLongitude, endMillis)
    )
}

/**
 * A track, with what was recorded and what was not held apart.
 *
 * The hybrid case is the common one on a phone that suspends: the beginning is
 * recorded, the app is put away, and the end is recorded after it is opened
 * again. One clean route is what the operator wants to see; pretending the
 * middle was observed is what they must not be shown.
 */
data class TrackRecord(
    val points: List<Fix>,
    /** Stretches between recorded points that nothing observed. */
    val gaps: List<InferredSpan> = emptyList()
) {
    val provenance: Provenance
        get() = when {
            gaps.isEmpty() -> Provenance.RECORDED
            // Two points and a gap between them is all guess and no record.
            points.size <= 2 && gaps.size == 1 -> Provenance.INFERRED
            else -> Provenance.HYBRID
        }

    /** Ground the receiver actually followed, gaps left out. */
    val recordedDistanceMeters: Double
        get() {
            var total = 0.0
            for (index in 0 until points.size - 1) {
                if (isGap(points[index].timeMillis, points[index + 1].timeMillis)) continue
                total += MapCoverage.distanceMeters(
                    points[index].latitude, points[index].longitude,
                    points[index + 1].latitude, points[index + 1].longitude
                )
            }
            return total
        }

    /** Straight lines across the gaps. An estimate, and named as one. */
    val estimatedGapMeters: Double get() = gaps.sumOf { it.displacementMeters }

    val elapsedMillis: Long
        get() {
            val stamps = points.map { it.timeMillis }.filter { it > 0 }
            if (stamps.size < 2) return 0
            return (stamps.max() - stamps.min()).coerceAtLeast(0)
        }

    private fun isGap(from: Long, to: Long): Boolean =
        gaps.any { it.startMillis == from && it.endMillis == to }

    /**
     * The lines a readout should show, in order.
     *
     * Written here rather than in each screen so the phone and the browser say
     * the same thing about the same track -- and so the word "estimated" can
     * never be dropped by one of them.
     */
    fun summary(): List<String> = buildList {
        add(provenance.label)
        add("Recorded route ${OverlapReport.formatDistance(recordedDistanceMeters)}")
        if (gaps.isNotEmpty()) {
            add(
                "Not recorded: ${gaps.size} gap${if (gaps.size == 1) "" else "s"}, " +
                    "${OverlapReport.formatDistance(estimatedGapMeters)} straight line " +
                    "(estimated)"
            )
        }
        if (elapsedMillis > 0) add("Travel time ${OverlapReport.formatElapsed(elapsedMillis)}")
    }

    companion object {
        /**
         * Longest silence that is still one continuous run of fixes.
         *
         * The same two minutes the detector uses. Past it the receiver was not
         * reporting rather than the operator not moving, and nothing can be
         * said about the ground in between.
         */
        const val GAP_MILLIS = 120_000L

        /**
         * Builds a record from a run of fixes, finding the gaps in it.
         *
         * Derived rather than recorded alongside, so a track that came from
         * anywhere -- a file, a pasted message, an older version of this app --
         * gets the same treatment as one recorded here.
         */
        fun of(points: List<Fix>, gapMillis: Long = GAP_MILLIS): TrackRecord {
            if (points.size < 2) return TrackRecord(points)
            val gaps = mutableListOf<InferredSpan>()
            for (index in 0 until points.size - 1) {
                val from = points[index]
                val to = points[index + 1]
                if (from.timeMillis <= 0 || to.timeMillis <= 0) continue
                if (to.timeMillis - from.timeMillis <= gapMillis) continue
                gaps += InferredSpan(
                    startLatitude = from.latitude,
                    startLongitude = from.longitude,
                    startMillis = from.timeMillis,
                    endLatitude = to.latitude,
                    endLongitude = to.longitude,
                    endMillis = to.timeMillis
                )
            }
            return TrackRecord(points, gaps)
        }
    }
}
