package com.rhecyee.firelinemap.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackDetectorTest {

    private val startLat = 45.20575
    private val startLon = -117.6370

    /** Metres north of the start, as a latitude. */
    private fun north(meters: Double) = startLat + meters / 111_194.93

    private fun detector(
        stopThresholdMillis: Long = 300_000,
        minimumTrackDistanceMeters: Double = 100.0,
        minimumTrackMillis: Long = 60_000
    ) = TrackDetector(
        TrackDetectionSettings(
            stopThresholdMillis = stopThresholdMillis,
            minimumTrackDistanceMeters = minimumTrackDistanceMeters,
            minimumTrackMillis = minimumTrackMillis
        )
    )

    /** Walks north at [speed] m/s, one fix per [stepSeconds]. */
    private fun walk(
        detector: TrackDetector,
        fromMillis: Long,
        seconds: Int,
        speed: Double,
        stepSeconds: Int = 5,
        accuracy: Float = 8f
    ): List<TrackEvent> {
        val events = mutableListOf<TrackEvent>()
        var elapsed = 0
        while (elapsed <= seconds) {
            events += detector.onFix(
                Fix(
                    latitude = north(speed * elapsed),
                    longitude = startLon,
                    timeMillis = fromMillis + elapsed * 1000L,
                    accuracyMeters = accuracy
                )
            )
            elapsed += stepSeconds
        }
        return events
    }

    /** Sits still, with the small wander a real receiver produces. */
    private fun idle(
        detector: TrackDetector,
        fromMillis: Long,
        seconds: Int,
        atMeters: Double = 0.0,
        stepSeconds: Int = 5,
        wanderMeters: Double = 2.0
    ): List<TrackEvent> {
        val events = mutableListOf<TrackEvent>()
        var elapsed = 0
        var flip = 1
        while (elapsed <= seconds) {
            events += detector.onFix(
                Fix(
                    latitude = north(atMeters + wanderMeters * flip),
                    longitude = startLon,
                    timeMillis = fromMillis + elapsed * 1000L,
                    accuracyMeters = 8f
                )
            )
            flip = -flip
            elapsed += stepSeconds
        }
        return events
    }

    @Test
    fun sittingStillNeverOpensATrack() {
        val detector = detector()
        idle(detector, 0, seconds = 1_800)

        assertFalse(detector.isRecording)
        assertEquals(0.0, detector.currentDistanceMeters, 0.001)
    }

    @Test
    fun sustainedMovementOpensATrack() {
        val detector = detector()
        val events = walk(detector, 0, seconds = 120, speed = 1.4)

        assertTrue(detector.isRecording)
        assertTrue(events.any { it is TrackEvent.Started })
    }

    @Test
    fun briefMovementDoesNotOpenATrack() {
        val detector = detector()
        // Ten seconds of walking, well under the thirty-second confirmation.
        walk(detector, 0, seconds = 10, speed = 1.4)

        assertFalse(detector.isRecording)
    }

    @Test
    fun theTrackStartsWhenMovementBeganNotWhenItWasConfirmed() {
        val detector = detector()
        val events = walk(detector, 100_000, seconds = 120, speed = 1.4)
        val started = events.filterIsInstance<TrackEvent.Started>().first()

        // Movement began at the first moving fix, not thirty seconds later.
        assertTrue(
            "track opened at ${started.atMillis}",
            started.atMillis <= 100_000 + 10_000
        )
    }

    @Test
    fun aShortPauseDoesNotEndTheTrack() {
        val detector = detector(stopThresholdMillis = 300_000)
        walk(detector, 0, seconds = 120, speed = 1.4)
        assertTrue(detector.isRecording)

        // Four minutes stopped at a gate, under the five-minute threshold.
        idle(detector, 125_000, seconds = 240, atMeters = 168.0)

        assertTrue("a gate should not end the track", detector.isRecording)
    }

    @Test
    fun stoppingForTheConfiguredThresholdEndsTheTrack() {
        val detector = detector(stopThresholdMillis = 120_000)
        walk(detector, 0, seconds = 200, speed = 1.4)
        val events = idle(detector, 210_000, seconds = 300, atMeters = 280.0)

        val ended = events.filterIsInstance<TrackEvent.Ended>().firstOrNull()
        assertTrue("expected the track to close", ended != null)
        assertFalse(detector.isRecording)
        assertTrue(ended!!.kept)
    }

    @Test
    fun theStopThresholdIsRespectedWhenChanged() {
        // The same movement, closed sooner purely because the setting is shorter.
        val quick = detector(stopThresholdMillis = 60_000)
        walk(quick, 0, seconds = 200, speed = 1.4)
        idle(quick, 210_000, seconds = 90, atMeters = 280.0)
        assertFalse("short threshold should have closed the track", quick.isRecording)

        val patient = detector(stopThresholdMillis = 600_000)
        walk(patient, 0, seconds = 200, speed = 1.4)
        idle(patient, 210_000, seconds = 90, atMeters = 280.0)
        assertTrue("long threshold should still be recording", patient.isRecording)
    }

    @Test
    fun theSavedTrackEndsAtLastMovementNotWhenTheTimerExpired() {
        val detector = detector(stopThresholdMillis = 120_000)
        walk(detector, 0, seconds = 200, speed = 1.4)
        val events = idle(detector, 210_000, seconds = 300, atMeters = 280.0)
        val track = events.filterIsInstance<TrackEvent.Ended>().first().track

        // Movement stopped around 200 s; the track must not carry the two
        // minutes of waiting that followed.
        assertTrue(
            "track ended at ${track.endedAt}, carrying dead time",
            track.endedAt <= 215_000
        )
    }

    @Test
    fun trivialWanderingIsDiscardedRatherThanSaved() {
        val detector = detector(
            stopThresholdMillis = 60_000,
            minimumTrackDistanceMeters = 500.0
        )
        walk(detector, 0, seconds = 120, speed = 1.4) // ~168 m
        val events = idle(detector, 130_000, seconds = 120, atMeters = 168.0)

        val ended = events.filterIsInstance<TrackEvent.Ended>().first()
        assertFalse("168 m should not be kept when the floor is 500 m", ended.kept)
    }

    @Test
    fun stationaryReceiverWanderAddsNoDistanceWhileRecording() {
        val detector = detector(stopThresholdMillis = 600_000)
        walk(detector, 0, seconds = 120, speed = 1.4)
        val afterWalking = detector.currentDistanceMeters

        // One window past the stop, the detector has caught up. Movement is
        // judged over a trailing window, so a few metres of the transition are
        // still counted; what matters is that the total then stops growing.
        idle(detector, 130_000, seconds = 30, atMeters = 168.0, wanderMeters = 3.0)
        val afterSettling = detector.currentDistanceMeters
        assertEquals("transition should cost at most one window", afterWalking, afterSettling, 12.0)

        // Eight more minutes parked, still inside the stop threshold.
        idle(detector, 165_000, seconds = 480, atMeters = 168.0, wanderMeters = 3.0)

        assertTrue("expected the track to still be open", detector.isRecording)
        assertEquals(
            "parked drift accumulated into the distance total",
            afterSettling,
            detector.currentDistanceMeters,
            0.001
        )
    }

    @Test
    fun parkedOvernightIsNotSavedAsTravel() {
        val detector = detector(stopThresholdMillis = 300_000)
        walk(detector, 0, seconds = 120, speed = 1.4)

        // Hours parked at ICP. The track closes on the threshold, and the
        // drift that follows must not open a new one.
        val events = idle(detector, 130_000, seconds = 14_400, atMeters = 168.0, wanderMeters = 3.0)

        val ended = events.filterIsInstance<TrackEvent.Ended>()
        assertEquals("the track should close exactly once", 1, ended.size)
        assertEquals(168.0, ended.single().track.distanceMeters, 5.0)
        assertFalse("drift must not reopen a track", detector.isRecording)
    }

    @Test
    fun wildlyInaccurateFixesAreRejected() {
        val detector = detector()
        walk(detector, 0, seconds = 120, speed = 1.4)
        val before = detector.currentDistanceMeters

        // A canopy fix reporting a position kilometres away with poor accuracy.
        detector.onFix(
            Fix(north(4_000.0), startLon, 130_000, accuracyMeters = 400f)
        )

        assertEquals(before, detector.currentDistanceMeters, 0.001)
    }

    @Test
    fun distanceApproximatesTheGroundActuallyCovered() {
        val detector = detector()
        walk(detector, 0, seconds = 300, speed = 1.4) // 1.4 m/s for 300 s = 420 m

        assertEquals(420.0, detector.currentDistanceMeters, 25.0)
    }

    @Test
    fun elapsedAndMovingTimeAreReportedSeparately() {
        val detector = detector(stopThresholdMillis = 300_000)
        walk(detector, 0, seconds = 120, speed = 1.4)
        idle(detector, 125_000, seconds = 200, atMeters = 168.0)
        walk(detector, 330_000, seconds = 120, speed = 1.4)

        val ended = detector.finish() as TrackEvent.Ended
        val track = ended.track

        // Elapsed spans the pause; moving time excludes it. Elapsed stays the
        // authoritative travel figure, with moving time as secondary detail.
        assertTrue(track.elapsedMillis > track.movingMillis)
        assertTrue(track.averageMovingSpeedMetersPerSecond > track.averageSpeedMetersPerSecond)
    }

    @Test
    fun finishClosesAnOpenTrackForShutdown() {
        val detector = detector()
        walk(detector, 0, seconds = 200, speed = 1.4)

        val ended = detector.finish()
        assertTrue(ended is TrackEvent.Ended)
        assertFalse(detector.isRecording)
        assertEquals(TrackEvent.None, detector.finish())
    }

    @Test
    fun aSecondTripOpensAfterTheFirstOneClosed() {
        val detector = detector(stopThresholdMillis = 60_000)
        walk(detector, 0, seconds = 200, speed = 1.4)
        idle(detector, 210_000, seconds = 120, atMeters = 280.0)
        assertFalse(detector.isRecording)

        val events = walk(detector, 400_000, seconds = 150, speed = 1.4)
        assertTrue(events.any { it is TrackEvent.Started })
        assertTrue(detector.isRecording)
    }
}
