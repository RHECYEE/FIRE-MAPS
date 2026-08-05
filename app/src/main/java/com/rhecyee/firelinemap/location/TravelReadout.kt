package com.rhecyee.firelinemap.location

import com.rhecyee.firelinemap.measure.DistanceUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * What a recording in progress is doing, in words.
 *
 * Shared between the phone and the browser so the two never describe the same
 * shift differently. The figures are the point of automatic recording: a
 * number that reads "armed but not yet moving" has to be visibly different
 * from one that reads "not working", and both have to be different from a
 * receiver that is reporting nothing at all.
 *
 * Moving time is kept beside elapsed time rather than instead of it. Elapsed
 * is how long the shift took; moving is how long it took to cover the ground,
 * and on a division with gates, traffic and a patient packaging in the middle
 * those are different questions with different answers. Both averages follow
 * from them, and quoting one without saying which it is is how a road gets
 * reported as half the speed it drives.
 */
object TravelReadout {

    /** One live readout, already worded. */
    data class Figures(
        val state: String,
        val accent: Accent,
        val recording: Boolean,
        /** Wall-clock time from the first fix to the last. */
        val elapsed: String,
        /** Time actually spent moving, which is the shorter of the two. */
        val moving: String,
        /** Only when some time was actually spent stopped. */
        val paused: String?,
        val distance: String,
        val chains: String,
        /** Distance over elapsed time -- what the shift averaged. */
        val averageSpeed: String,
        /** Distance over moving time -- what the ground drives at. */
        val movingSpeed: String,
        val points: Int,
        /**
         * Why nothing is being recorded yet, when nothing is.
         *
         * Null once a track is open. Before that it is the only thing on the
         * panel worth reading.
         */
        val waiting: String?,
        val diagnostics: String?
    )

    enum class Accent { RECORDING, PAUSED, IDLE }

    /**
     * Builds the readout from what the detector knows.
     *
     * Takes plain values rather than a state class so the browser can call it
     * with what its own recorder holds. Two implementations of "how fast was
     * that" eventually disagree, and the disagreement turns up in a briefing.
     */
    @Suppress("LongParameterList")
    fun of(
        recording: Boolean,
        paused: Boolean,
        armed: Boolean,
        elapsedMillis: Long,
        distanceMeters: Double,
        movingMillis: Long,
        pausedMillis: Long,
        pointCount: Int,
        fixCount: Int = 0,
        rejectedCount: Int = 0,
        lastAccuracyMeters: Double = 0.0,
        lastSpeedMetersPerSecond: Double = 0.0,
        movingNow: Boolean = false,
        movingHeldMillis: Long = 0L,
        startSustainedMillis: Long = TrackDetectionSettings().startSustainedMillis
    ): Figures {
        val confirmSeconds = (startSustainedMillis / 1000).toInt()

        val state = when {
            paused -> "TRAVEL PAUSED"
            recording -> "TRAVEL RECORDING"
            armed -> "WATCHING FOR TRAVEL — start moving"
            else -> "NOT RECORDING"
        }

        val accent = when {
            paused -> Accent.PAUSED
            recording -> Accent.RECORDING
            else -> Accent.IDLE
        }

        // Before a track opens, the panel's whole job is to say which of the
        // three failure shapes this is: no fixes, fixes but stationary, or
        // moving and nearly confirmed.
        val waiting = when {
            recording -> null
            !armed -> "Press auto record to arm."
            fixCount == 0 -> "No position fixes received yet."
            movingNow -> "Moving at ${mph(lastSpeedMetersPerSecond)} — confirming " +
                "(${(movingHeldMillis / 1000).coerceAtMost(confirmSeconds.toLong())}" +
                " of $confirmSeconds s)"
            else -> "Stationary — a track opens after $confirmSeconds s of movement."
        }

        val diagnostics = if (recording || !armed) {
            null
        } else {
            buildString {
                append("$fixCount fixes · ±${lastAccuracyMeters.roundToInt()} m · ")
                append(mph(lastSpeedMetersPerSecond))
                if (rejectedCount > 0) append(" · $rejectedCount too inaccurate")
            }
        }

        return Figures(
            state = state,
            accent = accent,
            recording = recording,
            elapsed = clock(elapsedMillis),
            moving = clock(movingMillis),
            paused = if (pausedMillis > 0) clock(pausedMillis) else null,
            distance = DistanceUnit.readable(distanceMeters),
            chains = DistanceUnit.inChains(distanceMeters),
            averageSpeed = mph(speed(distanceMeters, elapsedMillis)),
            movingSpeed = mph(speed(distanceMeters, movingMillis)),
            points = pointCount,
            waiting = waiting,
            diagnostics = diagnostics
        )
    }

    private fun speed(distanceMeters: Double, millis: Long): Double =
        if (millis > 0) distanceMeters / (millis / 1000.0) else 0.0

    /** Miles per hour, because that is what a vehicle speedometer reads. */
    fun mph(metersPerSecond: Double): String {
        val value = metersPerSecond * 2.236936
        val tenths = (value * 10).roundToLong()
        return "${tenths / 10}.${tenths % 10} mph"
    }

    /** hh:mm:ss, so a column of times can be compared down the page. */
    fun clock(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return "${pad(hours)}:${pad(minutes)}:${pad(seconds)}"
    }

    private fun pad(value: Long): String = if (value < 10) "0$value" else value.toString()
}
