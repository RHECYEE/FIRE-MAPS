package com.rhecyee.firelinemap.location

import com.rhecyee.firelinemap.map.MapCoverage

/** One position report fed to the detector. */
data class Fix(
    val latitude: Double,
    val longitude: Double,
    val timeMillis: Long,
    val accuracyMeters: Float = 10f,
    /** Receiver-reported ground speed, when available. */
    val speedMetersPerSecond: Double? = null
)

/**
 * Thresholds governing automatic track detection.
 *
 * [stopThresholdMillis] is the setting the operator is expected to change.
 * A short value splits one shift into many fragments whenever traffic, a
 * gate, or a patient packaging holds things up; a long value merges genuinely
 * separate trips. There is no correct default for every assignment, which is
 * why it is exposed rather than fixed.
 */
data class TrackDetectionSettings(
    /** Ground speed at or above which the operator counts as moving. ~1.5 mph. */
    val movingSpeedMetersPerSecond: Double = 0.7,

    /** Movement must persist this long before a track opens. */
    val startSustainedMillis: Long = 30_000,

    /** Stationary for this long ends the track. */
    val stopThresholdMillis: Long = 300_000,

    /**
     * Fixes worse than this are discarded outright.
     *
     * Under canopy or in a drainage a receiver will report positions hundreds
     * of metres out. Those must never reach the distance total.
     */
    val maxUsableAccuracyMeters: Float = 50f,

    /**
     * Net displacement over [movementWindowMillis] below which the operator is
     * treated as stationary.
     *
     * Applied to net displacement across a window rather than to each step.
     * A stationary receiver wanders continuously but with no net direction, so
     * its displacement over half a minute stays near zero while real walking
     * accumulates steadily. Judging each step instead would either let parked
     * drift add miles overnight or, with a floor high enough to stop that,
     * reject genuine walking -- at a five-second update a person on foot moves
     * about seven metres, which is inside the noise of a single fix.
     */
    val noiseFloorMeters: Double = 10.0,

    /** Window over which net displacement is measured. */
    val movementWindowMillis: Long = 20_000,

    /** Tracks shorter than these are not worth keeping. */
    val minimumTrackDistanceMeters: Double = 100.0,
    val minimumTrackMillis: Long = 60_000,

    /**
     * Experimental. Split the track into segments when travel passes through a
     * drop point read off the map sheet.
     *
     * Off by default: the drop points come from matching symbol colour on a
     * rendered page, so they are provisional, and a wrong one silently
     * mis-segments a shift's travel.
     */
    val segmentAtDropPoints: Boolean = false,

    /** How close counts as passing through a drop point. */
    val dropPointRadiusMeters: Double = 60.0
)

/** A drop point the detector may segment on. */
data class SegmentAnchor(
    val id: String,
    val latitude: Double,
    val longitude: Double
)

/** One leg of a track, bounded by drop points or by the track's own ends. */
data class TrackSegment(
    val startedAt: Long,
    val endedAt: Long,
    val distanceMeters: Double,
    /** The drop point that closed this segment, if one did. */
    val endedAtDropPointId: String? = null
) {
    val elapsedMillis: Long get() = (endedAt - startedAt).coerceAtLeast(0)
}

/** A completed track, as detected. */
data class DetectedTrack(
    val startedAt: Long,
    /** Time of the last real movement, not when the stop threshold expired. */
    val endedAt: Long,
    val distanceMeters: Double,
    val movingMillis: Long,
    val pausedMillis: Long,
    val points: List<Fix>,
    val segments: List<TrackSegment> = emptyList()
) {
    /** Start of travel to end of travel, including every pause within. */
    val elapsedMillis: Long get() = (endedAt - startedAt).coerceAtLeast(0)

    /** Elapsed less the time spent parked past the stop threshold. */
    val activeMillis: Long get() = (elapsedMillis - pausedMillis).coerceAtLeast(0)

    val averageSpeedMetersPerSecond: Double
        get() = if (elapsedMillis > 0) distanceMeters / (elapsedMillis / 1000.0) else 0.0

    val averageMovingSpeedMetersPerSecond: Double
        get() = if (movingMillis > 0) distanceMeters / (movingMillis / 1000.0) else 0.0
}

sealed interface TrackEvent {
    /** Nothing changed. */
    data object None : TrackEvent

    /** A track just opened. */
    data class Started(val atMillis: Long) : TrackEvent

    /** A point was appended to the open track. */
    data class Extended(val distanceMeters: Double, val pointCount: Int) : TrackEvent

    /**
     * Travel stopped for longer than the threshold. The track stays open.
     */
    data class Paused(val atMillis: Long) : TrackEvent

    /** Travel resumed after a pause. */
    data class Resumed(val atMillis: Long, val pausedMillis: Long) : TrackEvent

    /** A segment closed because travel passed through a drop point. */
    data class Segmented(val segment: TrackSegment) : TrackEvent

    /** A track closed. [kept] is false when it was too short to be worth saving. */
    data class Ended(val track: DetectedTrack, val kept: Boolean) : TrackEvent
}

/**
 * Opens and closes tracks from a stream of position fixes.
 *
 * Deliberately free of Android types so the thresholds can be exercised
 * directly. Movement detection driven by real GPS is easy to get subtly wrong
 * and almost impossible to debug from a device.
 */
class TrackDetector(
    var settings: TrackDetectionSettings = TrackDetectionSettings()
) {
    private var recording = false
    private var paused = false
    private var startedAt = 0L
    private var lastMovementAt = 0L
    private var pausedSince = 0L
    private var pausedMillis = 0L
    private var distanceMeters = 0.0
    private var movingMillis = 0L
    private val points = mutableListOf<Fix>()

    private val segments = mutableListOf<TrackSegment>()
    private var segmentStartedAt = 0L
    private var segmentDistanceAtStart = 0.0
    private var lastAnchorId: String? = null

    /** Drop points travel may be segmented on. Empty disables segmenting. */
    var anchors: List<SegmentAnchor> = emptyList()

    /** Fixes seen while stationary, kept so an opening track has its true start. */
    private val candidate = mutableListOf<Fix>()
    private var candidateMovingSince: Long? = null

    private var lastAccepted: Fix? = null

    /** Recent fixes spanning [TrackDetectionSettings.movementWindowMillis]. */
    private val window = ArrayDeque<Fix>()

    val isRecording: Boolean get() = recording
    val isPaused: Boolean get() = paused
    val currentDistanceMeters: Double get() = distanceMeters
    val currentPointCount: Int get() = points.size
    val currentSegmentCount: Int get() = segments.size

    /** Points recorded so far, for drawing the line as it is laid down. */
    val currentTrace: List<Pair<Double, Double>>
        get() = points.map { it.latitude to it.longitude }

    val currentMovingMillis: Long get() = movingMillis
    val currentPausedMillis: Long get() = pausedMillis
    val currentStartedAt: Long get() = startedAt
    fun currentElapsedMillis(now: Long): Long =
        if (recording) (now - startedAt).coerceAtLeast(0) else 0

    fun onFix(fix: Fix): TrackEvent {
        if (fix.accuracyMeters > settings.maxUsableAccuracyMeters) return TrackEvent.None

        val previous = lastAccepted
        lastAccepted = fix

        window.addLast(fix)
        while (window.size > 1 &&
            fix.timeMillis - window.first().timeMillis > settings.movementWindowMillis
        ) {
            window.removeFirst()
        }

        if (previous == null) {
            if (recording) points += fix else candidate += fix
            return TrackEvent.None
        }

        val step = MapCoverage.distanceMeters(
            previous.latitude, previous.longitude, fix.latitude, fix.longitude
        )
        val deltaMillis = (fix.timeMillis - previous.timeMillis).coerceAtLeast(0)
        val moving = isMoving(fix)

        return if (recording) {
            advance(fix, step, deltaMillis, moving)
        } else {
            considerStarting(fix, moving)
        }
    }

    /**
     * Whether the operator is travelling, judged over the movement window.
     *
     * Both conditions must hold: the net displacement has to clear the noise
     * floor, and the resulting speed has to clear the walking threshold. The
     * receiver's own speed is preferred when it offers one, but the
     * displacement test still applies -- a parked receiver will occasionally
     * report a speed it does not have.
     */
    private fun isMoving(fix: Fix): Boolean {
        val oldest = window.first()
        val spanMillis = fix.timeMillis - oldest.timeMillis
        if (spanMillis <= 0) return false

        val displacement = MapCoverage.distanceMeters(
            oldest.latitude, oldest.longitude, fix.latitude, fix.longitude
        )
        if (displacement < settings.noiseFloorMeters) return false

        val windowSpeed = displacement / (spanMillis / 1000.0)
        val speed = fix.speedMetersPerSecond ?: windowSpeed
        return speed >= settings.movingSpeedMetersPerSecond
    }

    private fun movementBeganAt(): Long = window.first().timeMillis

    private fun considerStarting(fix: Fix, moving: Boolean): TrackEvent {
        candidate += fix
        if (candidate.size > 64) candidate.removeAt(0)

        if (!moving) {
            candidateMovingSince = null
            return TrackEvent.None
        }

        val since = candidateMovingSince ?: movementBeganAt().also { candidateMovingSince = it }
        if (fix.timeMillis - since < settings.startSustainedMillis) return TrackEvent.None

        recording = true
        paused = false
        startedAt = since
        lastMovementAt = fix.timeMillis
        pausedMillis = 0L
        distanceMeters = 0.0
        movingMillis = 0L
        points.clear()
        segments.clear()
        segmentStartedAt = since
        segmentDistanceAtStart = 0.0
        lastAnchorId = null
        points += candidate.filter { it.timeMillis >= since }
        candidate.clear()
        candidateMovingSince = null

        for (i in 1 until points.size) {
            distanceMeters += MapCoverage.distanceMeters(
                points[i - 1].latitude, points[i - 1].longitude,
                points[i].latitude, points[i].longitude
            )
        }
        return TrackEvent.Started(startedAt)
    }

    private fun advance(
        fix: Fix,
        step: Double,
        deltaMillis: Long,
        moving: Boolean
    ): TrackEvent {
        points += fix
        if (moving) {
            distanceMeters += step
            movingMillis += deltaMillis
            lastMovementAt = fix.timeMillis

            if (paused) {
                // Travel resumed. The stop is recorded as a pause inside this
                // track rather than having closed it.
                paused = false
                val held = (fix.timeMillis - pausedSince).coerceAtLeast(0)
                pausedMillis += held
                return TrackEvent.Resumed(fix.timeMillis, held)
            }

            anchorAt(fix)?.let { anchor ->
                if (anchor.id != lastAnchorId) {
                    lastAnchorId = anchor.id
                    return closeSegment(fix.timeMillis, anchor.id)
                }
            }
            return TrackEvent.Extended(distanceMeters, points.size)
        }

        if (!paused && fix.timeMillis - lastMovementAt >= settings.stopThresholdMillis) {
            paused = true
            pausedSince = lastMovementAt
            return TrackEvent.Paused(lastMovementAt)
        }
        return TrackEvent.Extended(distanceMeters, points.size)
    }

    /** The drop point this fix is passing through, if segmenting is enabled. */
    private fun anchorAt(fix: Fix): SegmentAnchor? {
        if (!settings.segmentAtDropPoints || anchors.isEmpty()) return null
        return anchors.firstOrNull { anchor ->
            MapCoverage.distanceMeters(
                fix.latitude, fix.longitude, anchor.latitude, anchor.longitude
            ) <= settings.dropPointRadiusMeters
        }
    }

    private fun closeSegment(atMillis: Long, anchorId: String?): TrackEvent {
        val segment = TrackSegment(
            startedAt = segmentStartedAt,
            endedAt = atMillis,
            distanceMeters = distanceMeters - segmentDistanceAtStart,
            endedAtDropPointId = anchorId
        )
        segments += segment
        segmentStartedAt = atMillis
        segmentDistanceAtStart = distanceMeters
        return TrackEvent.Segmented(segment)
    }

    /**
     * Closes the track.
     *
     * A stop only ever pauses; nothing but an explicit finish ends a track, so
     * a shift stays one record with its pauses inside it rather than becoming
     * a scatter of fragments.
     */
    fun finish(): TrackEvent {
        if (!recording) return TrackEvent.None

        if (paused) {
            // Do not carry the trailing stop into the total.
            paused = false
        }
        if (lastMovementAt > segmentStartedAt) {
            segments += TrackSegment(
                startedAt = segmentStartedAt,
                endedAt = lastMovementAt,
                distanceMeters = distanceMeters - segmentDistanceAtStart,
                endedAtDropPointId = null
            )
        }

        val kept = points.filter { it.timeMillis <= lastMovementAt }
        val track = DetectedTrack(
            startedAt = startedAt,
            endedAt = lastMovementAt,
            distanceMeters = distanceMeters,
            movingMillis = movingMillis,
            pausedMillis = pausedMillis,
            points = if (kept.size >= 2) kept else points.toList(),
            segments = segments.toList()
        )

        recording = false
        points.clear()
        segments.clear()
        candidate.clear()
        candidateMovingSince = null
        distanceMeters = 0.0
        movingMillis = 0L
        pausedMillis = 0L
        lastAnchorId = null

        val worthKeeping = track.distanceMeters >= settings.minimumTrackDistanceMeters &&
            track.elapsedMillis >= settings.minimumTrackMillis
        return TrackEvent.Ended(track, worthKeeping)
    }
}
