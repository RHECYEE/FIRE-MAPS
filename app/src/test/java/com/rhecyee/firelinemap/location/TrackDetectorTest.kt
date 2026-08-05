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
        // Five seconds of walking. Under the confirmation, and under the noise
        // floor too -- seven metres is inside what a receiver wanders while
        // parked, which is the test that does the real work here.
        walk(detector, 0, seconds = 5, speed = 1.4)

        assertFalse(detector.isRecording)
    }

    /**
     * Confirmation is ten seconds, not thirty.
     *
     * Half a minute meant walking off from a rig, looking down, and finding
     * nothing recording -- which reads as the feature being broken, and gets
     * the operator to start one by hand, which is the thing automatic
     * detection exists to avoid.
     */
    @Test
    fun walkingOffIsPickedUpWithinTenSeconds() {
        val detector = detector()
        walk(detector, 0, seconds = 10, speed = 1.4)

        assertTrue("should be recording by ten seconds", detector.isRecording)
    }

    /**
     * The quick start must not have been bought by loosening the noise floor.
     *
     * Confirming sooner is only safe while a parked receiver's wander is still
     * rejected on displacement. If this ever fails, a rig sitting at ICP
     * overnight will record miles it never drove.
     */
    @Test
    fun aQuickerStartStillRefusesAParkedReceiver() {
        val detector = detector()
        // Four hours parked, wandering three metres either way -- more drift
        // than a receiver with a clear sky produces.
        idle(detector, 0, seconds = 14_400, wanderMeters = 3.0)

        assertFalse(detector.isRecording)
        assertEquals(0.0, detector.currentDistanceMeters, 0.001)
    }

    @Test
    fun theTrackStartsWhenMovementBeganNotWhenItWasConfirmed() {
        val detector = detector()
        val events = walk(detector, 100_000, seconds = 120, speed = 1.4)
        val started = events.filterIsInstance<TrackEvent.Started>().first()

        // Movement began at the first moving fix, not once it was confirmed.
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
    fun stoppingForTheConfiguredThresholdPausesRatherThanEnding() {
        val detector = detector(stopThresholdMillis = 120_000)
        walk(detector, 0, seconds = 200, speed = 1.4)
        val events = idle(detector, 210_000, seconds = 300, atMeters = 280.0)

        assertTrue("expected a pause", events.any { it is TrackEvent.Paused })
        assertTrue("a stop must not close the track", detector.isRecording)
        assertTrue(detector.isPaused)
        assertTrue(events.none { it is TrackEvent.Ended })
    }

    @Test
    fun movingAgainResumesTheSameTrack() {
        val detector = detector(stopThresholdMillis = 120_000)
        walk(detector, 0, seconds = 200, speed = 1.4)
        idle(detector, 210_000, seconds = 300, atMeters = 280.0)
        assertTrue(detector.isPaused)

        val events = walk(detector, 520_000, seconds = 150, speed = 1.4)

        assertTrue("expected a resume", events.any { it is TrackEvent.Resumed })
        assertFalse(detector.isPaused)
        assertTrue(detector.isRecording)

        // Still one track, with the stop recorded inside it.
        val track = (detector.finish() as TrackEvent.Ended).track
        assertTrue("pause was not recorded", track.pausedMillis > 200_000)
        assertTrue(track.activeMillis < track.elapsedMillis)
    }

    @Test
    fun theStopThresholdIsRespectedWhenChanged() {
        // The same movement, paused sooner purely because the setting is shorter.
        val quick = detector(stopThresholdMillis = 60_000)
        walk(quick, 0, seconds = 200, speed = 1.4)
        idle(quick, 210_000, seconds = 90, atMeters = 280.0)
        assertTrue("short threshold should have paused", quick.isPaused)

        val patient = detector(stopThresholdMillis = 600_000)
        walk(patient, 0, seconds = 200, speed = 1.4)
        idle(patient, 210_000, seconds = 90, atMeters = 280.0)
        assertFalse("long threshold should not have paused", patient.isPaused)
    }

    @Test
    fun theSavedTrackEndsAtLastMovementNotWhenTheTimerExpired() {
        val detector = detector(stopThresholdMillis = 120_000)
        walk(detector, 0, seconds = 200, speed = 1.4)
        idle(detector, 210_000, seconds = 300, atMeters = 280.0)
        val track = (detector.finish() as TrackEvent.Ended).track

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
        idle(detector, 130_000, seconds = 120, atMeters = 168.0)

        val ended = detector.finish() as TrackEvent.Ended
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
    fun parkedOvernightPausesOnceAndAddsNothing() {
        val detector = detector(stopThresholdMillis = 300_000)
        walk(detector, 0, seconds = 120, speed = 1.4)

        // Hours parked at ICP. One pause, and the drift adds no distance.
        val events = idle(detector, 130_000, seconds = 14_400, atMeters = 168.0, wanderMeters = 3.0)

        assertEquals("should pause exactly once", 1, events.count { it is TrackEvent.Paused })
        val track = (detector.finish() as TrackEvent.Ended).track
        assertEquals(168.0, track.distanceMeters, 12.0)
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
    fun aNewTrackOpensOnlyAfterAnExplicitFinish() {
        val detector = detector(stopThresholdMillis = 60_000)
        walk(detector, 0, seconds = 200, speed = 1.4)
        idle(detector, 210_000, seconds = 120, atMeters = 280.0)
        detector.finish()
        assertFalse(detector.isRecording)

        val events = walk(detector, 400_000, seconds = 150, speed = 1.4)
        assertTrue(events.any { it is TrackEvent.Started })
        assertTrue(detector.isRecording)
    }

    @Test
    fun passingADropPointSegmentsTheTrackWhenEnabled() {
        val detector = TrackDetector(
            TrackDetectionSettings(segmentAtDropPoints = true, dropPointRadiusMeters = 60.0)
        )
        // A drop point 250 m along the route.
        detector.anchors = listOf(SegmentAnchor("dp-190", north(250.0), startLon))

        walk(detector, 0, seconds = 400, speed = 1.4)
        val track = (detector.finish() as TrackEvent.Ended).track

        assertTrue("expected a segment boundary", track.segments.size >= 2)
        assertEquals("dp-190", track.segments.first().endedAtDropPointId)
        // The last leg runs to the end of travel, not to a drop point.
        assertEquals(null, track.segments.last().endedAtDropPointId)
    }

    @Test
    fun dropPointSegmentingIsOffUnlessEnabled() {
        val detector = detector()
        detector.anchors = listOf(SegmentAnchor("dp-190", north(250.0), startLon))

        walk(detector, 0, seconds = 400, speed = 1.4)
        val track = (detector.finish() as TrackEvent.Ended).track

        assertEquals(1, track.segments.size)
        assertEquals(null, track.segments.single().endedAtDropPointId)
    }

    @Test
    fun thesameDropPointDoesNotSegmentTwiceWhileLingering() {
        val detector = TrackDetector(
            TrackDetectionSettings(segmentAtDropPoints = true, dropPointRadiusMeters = 120.0)
        )
        detector.anchors = listOf(SegmentAnchor("dp-190", north(250.0), startLon))

        // The radius is wide enough that several consecutive fixes fall inside it.
        walk(detector, 0, seconds = 400, speed = 1.4)
        val track = (detector.finish() as TrackEvent.Ended).track

        assertEquals(
            "one pass should produce one boundary",
            1,
            track.segments.count { it.endedAtDropPointId == "dp-190" }
        )
    }

    /**
     * The phone going to sleep in somebody's pocket.
     *
     * The receiver stops reporting whenever the system decides it should --
     * the screen sleeps, an app is throttled, a canyon takes the sky away --
     * and when it comes back the operator may be miles from where it stopped.
     * Nothing is known about the ground in between, and the one thing the
     * detector must not do is invent it.
     */
    @Test
    fun aSilenceIsAGapRatherThanAStraightLineAcrossIt() {
        val detector = detector()
        var events = walk(detector, 0L, seconds = 180, speed = 8.0)
        assertTrue("should be recording", detector.isRecording)
        val before = detector.currentDistanceMeters
        val pointsBefore = detector.currentPointCount

        // Twenty minutes of silence, and eleven kilometres further on.
        val resumeAt = 180_000L + 20 * 60_000L
        detector.onFix(
            Fix(
                latitude = north(11_000.0),
                longitude = startLon,
                timeMillis = resumeAt,
                accuracyMeters = 8f
            )
        )

        // The eleven kilometres nobody observed must not be added to the
        // track. It would read as a drive that happened, at a speed nobody
        // went, straight through ground nobody crossed.
        assertEquals(
            "distance grew across the gap",
            before,
            detector.currentDistanceMeters,
            0.5
        )
        assertTrue("the resumed position is still recorded", detector.currentPointCount > pointsBefore)

        // And the run picks up cleanly from where it came back.
        events = walk(detector, resumeAt + 5_000L, seconds = 120, speed = 8.0)
        assertTrue(detector.isRecording)
        assertTrue(
            "travel after the gap must be counted",
            detector.currentDistanceMeters > before + 500.0
        )
        assertTrue(events.isNotEmpty())
    }

    @Test
    fun anOrdinaryUpdateRateIsNeverTreatedAsAGap() {
        // The slowest power mode asks for a fix every thirty seconds, and the
        // recorder floors itself faster than that. A shift must never trip the
        // gap rule by simply being configured for battery life.
        val detector = detector()
        walk(detector, 0L, seconds = 600, speed = 8.0, stepSeconds = 30)
        assertTrue(detector.isRecording)
        // 600 s at 8 m/s is 4.8 km, all of it observed.
        assertEquals(4_800.0, detector.currentDistanceMeters, 200.0)
    }
}

class TrackDetectorSpeedTest {
    private val startLat = 45.20575
    private val startLon = -117.6370
    private fun north(meters: Double) = startLat + meters / 111_194.93

    /** Drives north at [speed] m/s with a fix every five seconds. */
    private fun drive(detector: TrackDetector, seconds: Int, speed: Double) {
        var elapsed = 0
        while (elapsed <= seconds) {
            detector.onFix(
                Fix(north(speed * elapsed), startLon, elapsed * 1000L, accuracyMeters = 5f)
            )
            elapsed += 5
        }
    }

    @Test
    fun movingAverageMatchesTheSpeedActuallyTravelled() {
        val detector = TrackDetector()
        // 30 m/s is a little over 67 mph.
        drive(detector, seconds = 300, speed = 30.0)

        val track = (detector.finish() as TrackEvent.Ended).track

        // The confirmation window's clock must be credited along with its
        // ground, or the moving average reads far higher than reality.
        assertEquals(30.0, track.averageMovingSpeedMetersPerSecond, 3.0)
        assertEquals(30.0, track.averageSpeedMetersPerSecond, 3.0)
    }

    @Test
    fun movingTimeIsNotLessThanTheTrackMinusItsPauses() {
        val detector = TrackDetector()
        drive(detector, seconds = 300, speed = 30.0)
        val track = (detector.finish() as TrackEvent.Ended).track

        assertTrue(
            "moving ${track.movingMillis} ms of ${track.elapsedMillis} ms elapsed",
            track.movingMillis >= track.elapsedMillis - 15_000
        )
    }

}
