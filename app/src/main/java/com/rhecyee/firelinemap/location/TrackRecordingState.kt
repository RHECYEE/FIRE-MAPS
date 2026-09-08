package com.rhecyee.firelinemap.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A snapshot of travel in progress, for the screen to read. */
data class LiveTrack(
    val recording: Boolean = false,
    val paused: Boolean = false,
    val startedAt: Long = 0L,
    val lastFixAt: Long = 0L,
    val distanceMeters: Double = 0.0,
    val movingMillis: Long = 0L,
    val pausedMillis: Long = 0L,
    val segmentCount: Int = 0,
    /** Latitude and longitude pairs, in order. */
    val points: List<Pair<Double, Double>> = emptyList(),

    /**
     * Diagnostics for the armed-but-not-recording state.
     *
     * Without these, "waiting for movement to be confirmed", "the service is
     * getting no fixes at all" and "every fix is being rejected as too
     * inaccurate" all look identical on screen, which makes the difference
     * impossible to report from a vehicle.
     */
    val fixCount: Int = 0,
    val rejectedCount: Int = 0,
    val lastAccuracyMeters: Float = 0f,
    val lastSpeedMetersPerSecond: Double = 0.0,
    val movingNow: Boolean = false,
    val movingHeldMillis: Long = 0L
) {
    val elapsedMillis: Long get() = (lastFixAt - startedAt).coerceAtLeast(0)

    val averageSpeedMetersPerSecond: Double
        get() = if (elapsedMillis > 0) distanceMeters / (elapsedMillis / 1000.0) else 0.0

    val movingSpeedMetersPerSecond: Double
        get() = if (movingMillis > 0) distanceMeters / (movingMillis / 1000.0) else 0.0
}

/**
 * Live recording state, published by the service and read by the screen.
 *
 * Kept in memory alongside the database rather than instead of it. The
 * database is what survives the process being killed; this is what lets the
 * screen show a line and a distance without waiting on a write, which the
 * operator needs to see immediately to trust that recording is happening at
 * all.
 */
object TrackRecordingState {
    private val _live = MutableStateFlow(LiveTrack())
    val live: StateFlow<LiveTrack> = _live.asStateFlow()

    /**
     * Whether the service is watching for travel.
     *
     * Distinct from [LiveTrack.recording], which is only true once movement has
     * actually opened a track. Two surfaces can now arm this -- the phone and
     * the car display -- so the fact has to come from the service rather than
     * from either screen's own idea of what it last pressed.
     */
    private val _armed = MutableStateFlow(false)
    val armed: StateFlow<Boolean> = _armed.asStateFlow()

    fun setArmed(value: Boolean) {
        _armed.value = value
    }

    /**
     * What became of the last recording, for the screen that was not watching.
     *
     * A track shorter than the detector's threshold is discarded on purpose --
     * it is a car park manoeuvre, not travel -- but the discard was silent, and
     * from the car it was indistinguishable from the recording being thrown
     * away. Saying which happened is the difference between a rule and a fault.
     */
    private val _lastOutcome = MutableStateFlow<String?>(null)
    val lastOutcome: StateFlow<String?> = _lastOutcome.asStateFlow()

    fun reportOutcome(message: String?) {
        _lastOutcome.value = message
    }

    fun update(value: LiveTrack) {
        _live.value = value
    }

    fun clear() {
        _live.value = LiveTrack()
    }
}
