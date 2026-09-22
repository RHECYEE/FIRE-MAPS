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

    // ---- pressing Record ----

    @Test
    fun `pressing record opens a track without waiting to be convinced`() {
        // The confirmation window is thirty seconds of sustained movement. A
        // person who pressed the button has already decided; making them hold
        // a speed for half a minute first is how "I pressed record, drove,
        // pressed stop" ends with nothing at all.
        val detector = detector()
        assertFalse(detector.isRecording)

        val event = detector.begin(1_000L)

        assertTrue("record did not open a track", event is TrackEvent.Started)
        assertTrue(detector.isRecording)
        assertTrue(detector.startedByRequest)
    }

    @Test
    fun `a short drive that was asked for is kept`() {
        // Eighty metres down a spur and back out. Under both floors, and
        // exactly the thing that looked like the recording being deleted.
        val detector = detector()
        detector.begin(0L)
        walk(detector, fromMillis = 0L, seconds = 20, speed = 4.0)

        val ended = detector.finish()

        assertTrue(ended is TrackEvent.Ended)
        ended as TrackEvent.Ended
        assertTrue("a drive someone asked for was thrown away", ended.kept)
        assertTrue(ended.track.points.size >= 2)
    }

    @Test
    fun `a short drive nobody asked for is still discarded`() {
        // The floors are still doing their job for the detector's own guesses:
        // a shunt around a turnaround is not a drive worth filing.
        val detector = detector()
        walk(detector, fromMillis = 0L, seconds = 40, speed = 4.0)
        assertTrue("the detector should have opened this itself", detector.isRecording)
        assertFalse(detector.startedByRequest)

        // Barely moving from here on, so it stays under the distance floor.
        val ended = detector.finish()

        assertTrue(ended is TrackEvent.Ended)
        assertFalse((ended as TrackEvent.Ended).kept)
    }

    @Test
    fun `a requested drive records the ground it covers`() {
        val detector = detector()
        detector.begin(0L)
        walk(detector, fromMillis = 0L, seconds = 300, speed = 12.0)

        val ended = detector.finish() as TrackEvent.Ended

        assertTrue(ended.kept)
        assertTrue("no distance was accumulated", ended.track.distanceMeters > 1_000.0)
        assertTrue(ended.track.points.size > 10)
    }

    @Test
    fun `pressing record twice does not throw the first half away`() {
        val detector = detector()
        detector.begin(0L)
        walk(detector, fromMillis = 0L, seconds = 120, speed = 12.0)
        val soFar = detector.currentDistanceMeters
        assertTrue(soFar > 100.0)

        val again = detector.begin(200_000L)

        assertEquals(TrackEvent.None, again)
        assertEquals(soFar, detector.currentDistanceMeters, 0.001)
    }

    @Test
    fun `stopping when nothing was ever open says so rather than nothing`() {
        // The service turns this into a message on the car. What it must not
        // be is an Ended that quietly files an empty track.
        val detector = detector()
        val ended = detector.finish()
        assertEquals(TrackEvent.None, ended)
    }

    @Test
    fun `a requested track that is finished can be started again`() {
        val detector = detector()
        detector.begin(0L)
        walk(detector, fromMillis = 0L, seconds = 60, speed = 10.0)
        detector.finish()

        assertFalse(detector.isRecording)
        assertFalse("the request flag outlived its track", detector.startedByRequest)

        val second = detector.begin(500_000L)
        assertTrue(second is TrackEvent.Started)
        assertTrue(detector.startedByRequest)
    }

    @Test
    fun `record picks up the fixes already in hand rather than starting blank`() {
        // The receiver has been running since the app opened. Those fixes are
        // where the vehicle actually was, so the line starts there instead of
        // wherever it happened to be when a thumb found the button.
        val detector = detector()
        walk(detector, fromMillis = 0L, seconds = 40, speed = 0.2, stepSeconds = 10)

        val started = detector.begin(41_000L) as TrackEvent.Started

        assertTrue(
            "the track began at the button press, not at the first fix",
            started.atMillis <= 1_000L
        )
    }


    // ---- legs bounded by the vehicle shutting down ----

    /**
     * A vehicle whose position runs continuously across a whole shift.
     *
     * [walk] above restarts at the origin on every call, which is fine for a
     * single run and wrong for anything with more than one leg: the second
     * call teleports back, and the jump lands in the distance total. That
     * inflated distance is enough to hide a segment boundary going missing.
     */
    private inner class Shift(val detector: TrackDetector) {
        var clock = 0L
            private set
        private var meters = 0.0

        fun drive(seconds: Int, speed: Double = 12.0) {
            repeat(seconds / 5) {
                clock += 5_000L
                meters += speed * 5
                detector.onFix(
                    Fix(
                        latitude = north(meters),
                        longitude = startLon,
                        timeMillis = clock,
                        accuracyMeters = 8f
                    )
                )
            }
        }

        /** Parked with the engine running, before the key turns. */
        fun park(seconds: Int = 30) = drive(seconds, speed = 0.0)

        fun wait(seconds: Int) {
            clock += seconds * 1000L
        }
    }

    /** Drives, parks, and reports what the last accepted fix's speed was. */
    private fun driveAndPark(detector: TrackDetector, seconds: Int = 200): Long {
        detector.begin(0L)
        walk(detector, fromMillis = 0L, seconds = seconds, speed = 12.0)
        // Two stationary fixes, which is what parking looks like before the key
        // turns: the receiver reports no speed worth the name.
        var t = seconds * 1000L
        repeat(2) {
            t += 5_000L
            detector.onFix(
                Fix(
                    latitude = north(12.0 * seconds),
                    longitude = startLon,
                    timeMillis = t,
                    accuracyMeters = 8f,
                    speedMetersPerSecond = 0.0
                )
            )
        }
        return t
    }

    @Test
    fun `losing the head unit ends the leg where the vehicle stopped`() {
        val detector = detector()
        val parkedAt = driveAndPark(detector)

        val event = detector.vehicleStopped(parkedAt + 8_000L)

        assertTrue("no leg was closed", event is TrackEvent.Segmented)
        val segment = (event as TrackEvent.Segmented).segment
        assertEquals(SegmentBoundary.VEHICLE_STOPPED, segment.endedBy)
        assertTrue("the leg covered no ground", segment.distanceMeters > 1_000.0)
        // Closed at the last real movement, not at the moment the key turned:
        // the manoeuvring and idling in between belong to neither leg.
        assertTrue(segment.endedAt <= parkedAt)
        assertEquals(1, detector.currentSegmentCount)
    }

    @Test
    fun `the track carries on across a vehicle stop rather than ending`() {
        val detector = detector()
        val parkedAt = driveAndPark(detector)
        detector.vehicleStopped(parkedAt + 8_000L)

        assertTrue("the track was closed instead of segmented", detector.isRecording)
    }

    @Test
    fun `standing at a drop point is not counted into the next leg`() {
        // Half an hour talking to people is not driving. Counted into the leg
        // that follows, every leg after the first reads far slower than it was.
        val detector = detector()
        val parkedAt = driveAndPark(detector)
        detector.vehicleStopped(parkedAt + 8_000L)

        val backInTheTruck = parkedAt + 1_800_000L
        detector.vehicleStarted(backInTheTruck)
        walk(detector, fromMillis = backInTheTruck, seconds = 200, speed = 12.0)

        val ended = detector.finish() as TrackEvent.Ended
        val second = ended.track.segments.last()
        assertTrue(
            "the wait was billed to the drive: ${second.elapsedMillis} ms",
            second.elapsedMillis < 600_000L
        )
    }

    @Test
    fun `a cable coming loose at road speed does not break the leg`() {
        // USB in a truck is not a reliable connection. A lead that drops at
        // forty miles an hour would otherwise cut a drive in half, silently.
        val detector = detector()
        detector.begin(0L)
        walk(detector, fromMillis = 0L, seconds = 200, speed = 12.0)

        val event = detector.vehicleStopped(201_000L)

        assertEquals(TrackEvent.None, event)
        assertEquals(0, detector.currentSegmentCount)
    }

    @Test
    fun `a cable coming loose is not remembered as a stop`() {
        // If it were, the reconnect a second later would move the leg's start
        // forward and throw away everything driven up to that point.
        val detector = detector()
        detector.begin(0L)
        val shift = Shift(detector)
        shift.drive(seconds = 200)
        val covered = detector.currentDistanceMeters
        assertTrue(covered > 2_000.0)

        detector.vehicleStopped(shift.clock)
        detector.vehicleStarted(shift.clock + 1_000L)
        shift.drive(seconds = 100)

        val ended = detector.finish() as TrackEvent.Ended
        assertEquals(1, ended.track.segments.size)
        assertTrue(
            "ground driven before the glitch was dropped: ${ended.track.segments}",
            ended.track.segments.single().distanceMeters >= covered
        )
    }

    @Test
    fun `losing the head unit twice does not file an empty leg`() {
        val detector = detector()
        detector.begin(0L)
        val shift = Shift(detector)
        shift.drive(seconds = 200)
        shift.park()

        detector.vehicleStopped(shift.clock)
        val again = detector.vehicleStopped(shift.clock + 12_000L)

        assertEquals(TrackEvent.None, again)
        assertEquals(1, detector.currentSegmentCount)
    }

    @Test
    fun `a second disconnect with no reconnect between does not file another leg`() {
        // Wireless Android Auto drops, comes back without a state change the
        // phone sees, drops again -- with driving in between. One arrival
        // happened, so one boundary belongs there.
        val detector = detector()
        detector.begin(0L)
        val shift = Shift(detector)
        shift.drive(seconds = 200)
        shift.park()
        detector.vehicleStopped(shift.clock)
        assertEquals(1, detector.currentSegmentCount)

        // Driving on without the vehicle ever being seen to start again.
        shift.drive(seconds = 200)
        shift.park()
        val again = detector.vehicleStopped(shift.clock)

        assertEquals(TrackEvent.None, again)
        assertEquals("a leg was filed for a vehicle that never started", 1,
            detector.currentSegmentCount)
    }

    @Test
    fun `plugging a phone in partway through a drive does not carve up the leg`() {
        val detector = detector()
        detector.begin(0L)
        val shift = Shift(detector)
        shift.drive(seconds = 200)
        val covered = detector.currentDistanceMeters

        // No disconnect preceded this, so it is somebody plugging in, not a
        // vehicle starting. It must not move the leg's start forward.
        detector.vehicleStarted(shift.clock)
        shift.drive(seconds = 200)

        val ended = detector.finish() as TrackEvent.Ended
        assertEquals(1, ended.track.segments.size)
        val leg = ended.track.segments.single()
        assertEquals("the leg lost its beginning", 0L, leg.startedAt)
        assertTrue(
            "the ground driven before plugging in was dropped: $leg",
            leg.distanceMeters > covered * 1.8
        )
    }

    @Test
    fun `a head unit lost before anything was driven files nothing`() {
        val detector = detector()
        detector.begin(0L)

        val event = detector.vehicleStopped(1_000L)

        assertEquals(TrackEvent.None, event)
        assertEquals(0, detector.currentSegmentCount)
    }

    @Test
    fun `a head unit lost while nothing is recording does nothing`() {
        val detector = detector()
        assertEquals(TrackEvent.None, detector.vehicleStopped(1_000L))
    }

    @Test
    fun `turning the split off leaves the track whole`() {
        val detector = TrackDetector(
            TrackDetectionSettings(segmentAtVehicleStops = false)
        )
        val parkedAt = driveAndPark(detector)

        assertEquals(TrackEvent.None, detector.vehicleStopped(parkedAt + 8_000L))
        assertEquals(0, detector.currentSegmentCount)
    }

    @Test
    fun `a shift of drop point runs comes back as one track of many legs`() {
        // What this is actually for: drive, park, talk, get back in, drive on.
        // Positions have to run continuously across the whole shift -- a leg
        // that restarts at the origin is a jump of tens of kilometres, which
        // the cable-fault guard would rightly refuse to call an arrival.
        val detector = detector()
        var clock = 0L
        var meters = 0.0

        fun fix(speed: Double, seconds: Int) {
            repeat(seconds / 5) {
                clock += 5_000L
                meters += speed * 5
                detector.onFix(
                    Fix(
                        latitude = north(meters),
                        longitude = startLon,
                        timeMillis = clock,
                        accuracyMeters = 8f
                    )
                )
            }
        }

        detector.begin(0L)
        repeat(3) {
            fix(speed = 12.0, seconds = 200)   // the run out
            fix(speed = 0.0, seconds = 30)     // parked, engine idling down
            detector.vehicleStopped(clock)     // key off
            clock += 900_000L                  // stood there talking
            detector.vehicleStarted(clock)     // back in, engine on
        }

        val ended = detector.finish() as TrackEvent.Ended
        val byVehicle = ended.track.segments.count {
            it.endedBy == SegmentBoundary.VEHICLE_STOPPED
        }
        assertEquals("expected a leg a run: ${ended.track.segments}", 3, byVehicle)
        ended.track.segments.filter { it.endedBy == SegmentBoundary.VEHICLE_STOPPED }
            .forEach { segment ->
                assertTrue("a leg covered no ground: $segment", segment.distanceMeters > 1_000.0)
                // The standing around is outside the legs, not billed to them.
                assertTrue("the wait was billed to a leg: $segment",
                    segment.elapsedMillis < 600_000L)
            }
        // Still one track, not three.
        assertTrue(ended.kept)
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
