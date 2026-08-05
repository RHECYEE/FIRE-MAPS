package com.rhecyee.firelinemap.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the live panel says, on both the phone and the browser.
 *
 * The two used to word this separately, which is how a browser comes to quote
 * a road at a different speed from the phone that drove it.
 */
class TravelReadoutTest {

    private fun recording(
        elapsedMillis: Long = 600_000L,      // ten minutes on the clock
        movingMillis: Long = 300_000L,       // five of them actually moving
        distanceMeters: Double = 1_609.344,  // a mile
        pausedMillis: Long = 300_000L
    ) = TravelReadout.of(
        recording = true,
        paused = false,
        armed = true,
        elapsedMillis = elapsedMillis,
        distanceMeters = distanceMeters,
        movingMillis = movingMillis,
        pausedMillis = pausedMillis,
        pointCount = 120
    )

    // ------------------------------------------------------- the two clocks

    /**
     * The distinction the panel exists for.
     *
     * A mile in ten minutes is six miles an hour. The same mile, driven in the
     * five minutes that were not spent at a gate, is twelve. Quoting one
     * without saying which is how a road gets reported at half the speed it
     * drives, so both are shown and both are labelled.
     */
    @Test
    fun movingTimeAndElapsedTimeAreBothKept() {
        val figures = recording()
        assertEquals("00:10:00", figures.elapsed)
        assertEquals("00:05:00", figures.moving)
        assertNotEquals(figures.averageSpeed, figures.movingSpeed)
        assertEquals("6.0 mph", figures.averageSpeed)
        assertEquals("12.0 mph", figures.movingSpeed)
    }

    @Test
    fun timeStoppedIsShownOnlyWhenThereWasSome() {
        assertEquals("00:05:00", recording().paused)
        assertNull(recording(pausedMillis = 0L).paused)
    }

    /** Line is called in chains whatever else the distance is quoted in. */
    @Test
    fun distanceCarriesChainsAsWell() {
        val figures = recording()
        assertEquals("1.0 mi", figures.distance)
        assertTrue(figures.chains, figures.chains.endsWith(" ch"))
    }

    @Test
    fun aStandingStartDoesNotDivideByZero() {
        val figures = TravelReadout.of(
            recording = true, paused = false, armed = true,
            elapsedMillis = 0L, distanceMeters = 0.0,
            movingMillis = 0L, pausedMillis = 0L, pointCount = 1
        )
        assertEquals("0.0 mph", figures.averageSpeed)
        assertEquals("0.0 mph", figures.movingSpeed)
        assertEquals("00:00:00", figures.elapsed)
    }

    // ---------------------------------------------- armed, but not recording

    /**
     * Three different problems that used to be one sentence.
     *
     * "No fixes at all", "fixes but stationary" and "moving, nearly confirmed"
     * need different actions from whoever is holding the phone, and they are
     * impossible to tell apart from a vehicle if they read the same.
     */
    @Test
    fun aReceiverReportingNothingSaysSo() {
        val figures = waiting(fixCount = 0)
        assertEquals("No position fixes received yet.", figures.waiting)
        assertEquals("WATCHING FOR TRAVEL — start moving", figures.state)
    }

    @Test
    fun standingStillSaysWhatWouldStartATrack() {
        val figures = waiting(fixCount = 20, movingNow = false)
        assertTrue(figures.waiting!!, figures.waiting!!.contains("a track opens after 10 s"))
    }

    @Test
    fun movingButUnconfirmedCountsItDown() {
        val figures = waiting(fixCount = 20, movingNow = true, movingHeldMillis = 4_000L)
        assertTrue(figures.waiting!!, figures.waiting!!.contains("confirming (4 of 10 s)"))
    }

    /**
     * The countdown follows the real threshold.
     *
     * The panel used to say "of 30 s" in fixed text. The threshold moved to
     * ten, and the panel went on promising thirty -- so it read as broken for
     * twenty seconds after it had already started working.
     */
    @Test
    fun theCountdownUsesTheThresholdActuallyInForce() {
        val figures = TravelReadout.of(
            recording = false, paused = false, armed = true,
            elapsedMillis = 0L, distanceMeters = 0.0, movingMillis = 0L,
            pausedMillis = 0L, pointCount = 0,
            fixCount = 5, movingNow = true, movingHeldMillis = 3_000L,
            startSustainedMillis = 45_000L
        )
        assertTrue(figures.waiting!!, figures.waiting!!.contains("of 45 s"))
    }

    @Test
    fun rejectedFixesAreCountedWhereSomebodyCanSeeThem() {
        val figures = waiting(fixCount = 30, rejected = 11)
        assertTrue(figures.diagnostics!!, figures.diagnostics!!.contains("11 too inaccurate"))
        assertTrue(figures.diagnostics!!, figures.diagnostics!!.contains("30 fixes"))
    }

    @Test
    fun anUnarmedRecorderSaysHowToArmIt() {
        val figures = TravelReadout.of(
            recording = false, paused = false, armed = false,
            elapsedMillis = 0L, distanceMeters = 0.0, movingMillis = 0L,
            pausedMillis = 0L, pointCount = 0
        )
        assertEquals("NOT RECORDING", figures.state)
        assertEquals("Press auto record to arm.", figures.waiting)
        // Nothing to diagnose when nothing was asked to run.
        assertNull(figures.diagnostics)
    }

    @Test
    fun aPausedTrackReadsAsPausedRatherThanStopped() {
        val figures = TravelReadout.of(
            recording = true, paused = true, armed = true,
            elapsedMillis = 60_000L, distanceMeters = 100.0,
            movingMillis = 30_000L, pausedMillis = 30_000L, pointCount = 10
        )
        assertEquals("TRAVEL PAUSED", figures.state)
        assertEquals(TravelReadout.Accent.PAUSED, figures.accent)
    }

    private fun waiting(
        fixCount: Int,
        movingNow: Boolean = false,
        movingHeldMillis: Long = 0L,
        rejected: Int = 0
    ) = TravelReadout.of(
        recording = false, paused = false, armed = true,
        elapsedMillis = 0L, distanceMeters = 0.0, movingMillis = 0L,
        pausedMillis = 0L, pointCount = 0,
        fixCount = fixCount, rejectedCount = rejected,
        lastAccuracyMeters = 6.0, lastSpeedMetersPerSecond = 2.0,
        movingNow = movingNow, movingHeldMillis = movingHeldMillis
    )
}
