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
    val points: List<Pair<Double, Double>> = emptyList()
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

    fun update(value: LiveTrack) {
        _live.value = value
    }

    fun clear() {
        _live.value = LiveTrack()
    }
}
