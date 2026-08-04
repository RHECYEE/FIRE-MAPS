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
    val minimumTrackMillis: Long = 60_000
)

/** A completed track, as detected. */
data class DetectedTrack(
    val startedAt: Long,
    /** Time of the last real movement, not when the stop threshold expired. */
    val endedAt: Long,
    val distanceMeters: Double,
    val movingMillis: Long,
    val points: List<Fix>
) {
    /** Start of movement to end of movement, including any pauses within. */
    val elapsedMillis: Long get() = (endedAt - startedAt).coerceAtLeast(0)

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
    private var startedAt = 0L
    private var lastMovementAt = 0L
    private var distanceMeters = 0.0
    private var movingMillis = 0L
    private val points = mutableListOf<Fix>()

    /** Fixes seen while stationary, kept so an opening track has its true start. */
    private val candidate = mutableListOf<Fix>()
    private var candidateMovingSince: Long? = null

    private var lastAccepted: Fix? = null

    /** Recent fixes spanning [TrackDetectionSettings.movementWindowMillis]. */
    private val window = ArrayDeque<Fix>()

    val isRecording: Boolean get() = recording
    val currentDistanceMeters: Double get() = distanceMeters
    val currentPointCount: Int get() = points.size
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

    /** Timestamp at which the current run of movement began. */
    private fun movementBeganAt(): Long = window.first().timeMillis

    /**
     * Closes any open track, for shutdown or an explicit stop.
     *
     * Applies the same keep-or-discard rules as an automatic close.
     */
    fun finish(): TrackEvent {
        if (!recording) return TrackEvent.None
        return close()
    }

    private fun considerStarting(fix: Fix, moving: Boolean): TrackEvent {
        candidate += fix
        // Keep the buffer bounded; only the recent run matters.
        if (candidate.size > 64) candidate.removeAt(0)

        if (!moving) {
            candidateMovingSince = null
            return TrackEvent.None
        }

        // Anchor to the start of the window that first showed movement, so the
        // track opens where travel actually began rather than a window and a
        // confirmation delay later.
        val since = candidateMovingSince ?: movementBeganAt().also { candidateMovingSince = it }
        if (fix.timeMillis - since < settings.startSustainedMillis) return TrackEvent.None

        // Open the track at the moment movement began, not now, so the first
        // stretch of travel is not lost to the confirmation delay.
        recording = true
        startedAt = since
        lastMovementAt = fix.timeMillis
        distanceMeters = 0.0
        movingMillis = 0L
        points.clear()
        points += candidate.filter { it.timeMillis >= since }
        candidate.clear()
        candidateMovingSince = null

        // Recover the distance already covered during the confirmation window.
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
        // Distance is gated on the movement verdict rather than on a per-step
        // threshold, which is what keeps a parked receiver's drift out of the
        // total without also discarding real walking.
        if (moving) {
            distanceMeters += step
            movingMillis += deltaMillis
            lastMovementAt = fix.timeMillis
        }

        val stoppedFor = fix.timeMillis - lastMovementAt
        if (stoppedFor >= settings.stopThresholdMillis) return close()

        return TrackEvent.Extended(distanceMeters, points.size)
    }

    private fun close(): TrackEvent {
        // Trim the trailing stationary tail so the saved track ends where
        // movement ended rather than where the timer expired.
        val kept = points.filter { it.timeMillis <= lastMovementAt }
        val track = DetectedTrack(
            startedAt = startedAt,
            endedAt = lastMovementAt,
            distanceMeters = distanceMeters,
            movingMillis = movingMillis,
            points = if (kept.size >= 2) kept else points.toList()
        )

        recording = false
        points.clear()
        candidate.clear()
        candidateMovingSince = null
        distanceMeters = 0.0
        movingMillis = 0L

        val worthKeeping = track.distanceMeters >= settings.minimumTrackDistanceMeters &&
            track.elapsedMillis >= settings.minimumTrackMillis
        return TrackEvent.Ended(track, worthKeeping)
    }
}
