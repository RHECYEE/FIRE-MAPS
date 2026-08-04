package com.rhecyee.firelinemap.location

import android.content.Context

/**
 * Persists the operator-adjustable track detection thresholds.
 *
 * Plain preferences rather than the incident database: these are a property of
 * how this device is being used on this assignment, not of any one incident,
 * and they must be readable from the recording service without waiting on a
 * database open.
 */
class TrackSettingsStore(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences("track_detection", Context.MODE_PRIVATE)

    var autoRecordEnabled: Boolean
        get() = preferences.getBoolean(KEY_AUTO, true)
        set(value) = preferences.edit().putBoolean(KEY_AUTO, value).apply()

    /** The stop duration that ends a track, in seconds. */
    var stopThresholdSeconds: Int
        get() = preferences.getInt(KEY_STOP, DEFAULT_STOP_SECONDS)
        set(value) = preferences.edit()
            .putInt(KEY_STOP, value.coerceIn(MIN_STOP_SECONDS, MAX_STOP_SECONDS))
            .apply()

    fun settings(): TrackDetectionSettings =
        TrackDetectionSettings(stopThresholdMillis = stopThresholdSeconds * 1000L)

    companion object {
        private const val KEY_AUTO = "auto_record"
        private const val KEY_STOP = "stop_threshold_seconds"

        const val DEFAULT_STOP_SECONDS = 300

        /**
         * Anything shorter than a minute fragments a shift into noise; a stop
         * longer than an hour is a different assignment, not a pause.
         */
        const val MIN_STOP_SECONDS = 60
        const val MAX_STOP_SECONDS = 3_600

        val CHOICES_SECONDS = listOf(60, 120, 300, 600, 900, 1_800, 3_600)

        fun describe(seconds: Int): String = when {
            seconds < 60 -> "$seconds sec"
            seconds % 60 == 0 && seconds < 3_600 -> "${seconds / 60} min"
            seconds == 3_600 -> "1 hour"
            else -> "${seconds / 60} min"
        }
    }
}
