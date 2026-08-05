package com.rhecyee.firelinemap.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Suspended-phone records, and when a real track may replace one.
 *
 * Two failures to avoid, in opposite directions. Refusing to merge leaves the
 * operator with a duplicate of every journey. Merging on weak evidence deletes
 * a journey nobody can see any more -- and endpoint proximity alone is weak
 * evidence, because two vehicles leaving the same drop point for the same
 * helispot an hour apart have near-identical endpoints.
 */
class TrackMergeTest {

    private val lat = 45.20575
    private val lon = -117.63700
    private val start = 1_754_390_000_000L

    private fun north(meters: Double) = lat + meters / 111_194.93
    private fun east(meters: Double) = lon + meters / (111_194.93 * 0.7009)

    /** A straight run north, a fix every five seconds. */
    private fun run(
        meters: Double,
        seconds: Int,
        fromMillis: Long = start,
        eastOffset: Double = 0.0
    ): List<Fix> {
        val steps = seconds / 5
        return (0..steps).map { step ->
            Fix(
                north(meters * step / steps),
                east(eastOffset),
                fromMillis + step * 5_000L
            )
        }
    }

    /** The phone was away for this stretch; only its ends are known. */
    private fun suspended(meters: Double, seconds: Int, fromMillis: Long = start) =
        InferredSpan(
            startLatitude = north(0.0), startLongitude = lon, startMillis = fromMillis,
            endLatitude = north(meters), endLongitude = lon,
            endMillis = fromMillis + seconds * 1000L
        )

    // ------------------------------------------------------- what it claims

    /**
     * The wording is the feature.
     *
     * Two points give displacement over time. They do not give the distance
     * actually travelled, so they cannot give an average travel speed -- a
     * winding road is routinely two or three times its own straight line.
     */
    @Test
    fun anInferredSpanNeverClaimsToKnowTheAverageTravelSpeed() {
        val span = suspended(meters = 4_800.0, seconds = 1_200)
        assertEquals(4_800.0, span.displacementMeters, 60.0)
        assertEquals(1_200_000L, span.elapsedMillis)
        // Displacement over elapsed, and it is named that way everywhere.
        assertEquals(4.0, span.estimatedSpeedMetersPerSecond!!, 0.1)

        val said = span.describe()
        assertTrue(said, said.contains("Not recorded"))
        assertTrue(said, said.contains("straight line"))
        assertTrue("must read as a floor, not a measurement", said.contains("at least"))
        assertFalse(said.contains("average"))
    }

    @Test
    fun aSpanWithNoElapsedTimeOffersNoSpeedAtAll() {
        val instant = InferredSpan(lat, lon, start, north(100.0), lon, start)
        assertNull(instant.estimatedSpeedMetersPerSecond)
    }

    // -------------------------------------------------------- the record model

    @Test
    fun aRunOfFixesWithNoSilenceInItIsSimplyRecorded() {
        val record = TrackRecord.of(run(1_000.0, 300))
        assertEquals(Provenance.RECORDED, record.provenance)
        assertTrue(record.gaps.isEmpty())
        assertEquals(1_000.0, record.recordedDistanceMeters, 20.0)
    }

    /**
     * The case a suspended browser actually produces: recorded, away, recorded.
     */
    @Test
    fun recordedEitherSideOfASilenceIsHybrid() {
        val before = run(500.0, 120)
        val after = run(500.0, 120, fromMillis = start + 2_400_000L).map {
            it.copy(latitude = it.latitude + 4_000.0 / 111_194.93)
        }
        val record = TrackRecord.of(before + after)

        assertEquals(Provenance.HYBRID, record.provenance)
        assertEquals(1, record.gaps.size)
        // The gap is not counted as ground the receiver followed.
        assertEquals(1_000.0, record.recordedDistanceMeters, 40.0)
        assertTrue(record.estimatedGapMeters > 3_000.0)

        val summary = record.summary()
        assertTrue(summary.toString(), summary.any { it.contains("Partly recorded") })
        assertTrue(summary.toString(), summary.any { it.contains("estimated") })
    }

    @Test
    fun twoPointsAndNothingBetweenThemIsInferredNotRecorded() {
        val ends = listOf(
            Fix(north(0.0), lon, start),
            Fix(north(4_000.0), lon, start + 1_200_000L)
        )
        val record = TrackRecord.of(ends)
        assertEquals(Provenance.INFERRED, record.provenance)
        assertEquals(1, record.gaps.size)
        // None of it was followed, so none of it counts as recorded.
        assertEquals(0.0, record.recordedDistanceMeters, 0.001)
    }

    @Test
    fun aTrackWithNoTimesIsNotCarvedIntoImaginaryGaps() {
        val untimed = (0..40).map { Fix(north(it * 25.0), lon, 0L) }
        assertEquals(Provenance.RECORDED, TrackRecord.of(untimed).provenance)
    }

    // --------------------------------------------------------------- merging

    /** The same journey, recorded properly by somebody else. */
    private fun sameJourney() = run(4_000.0, 1_200)

    @Test
    fun aDetailedTrackOfTheSameJourneyMerges() {
        val span = suspended(4_000.0, 1_200)
        val merged = TrackMerge.merge(span, sameJourney())
        assertNotNull(merged)
        assertTrue(merged!!.signals.matched)
        assertEquals(7, merged.signals.agreeing)
        // The inferred record is kept, not deleted.
        assertEquals(span, merged.supersededInferred)
    }

    /**
     * The heart of it: matching ends are not sufficient.
     *
     * Two vehicles leaving the same drop point for the same helispot an hour
     * apart have near-identical endpoints and are different journeys.
     */
    @Test
    fun endpointsAloneDoNotMerge() {
        val span = suspended(4_000.0, 1_200)
        // Same ends, an hour and a half later.
        val later = run(4_000.0, 1_200, fromMillis = start + 5_400_000L)
        val signals = TrackMerge.match(span, later)

        assertTrue("the ends do agree", signals.startsAgree && signals.endsAgree)
        assertFalse("but the times do not", signals.timesCorrespond)
        assertFalse(signals.matched)
        assertNull(TrackMerge.merge(span, later))
        assertTrue(signals.disagreements().toString(),
            signals.disagreements().contains("Times correspond"))
    }

    @Test
    fun theReturnTripIsNotTheSameAsTheTripOut() {
        val span = suspended(4_000.0, 1_200)
        val backwards = sameJourney().reversed().mapIndexed { index, fix ->
            fix.copy(timeMillis = start + index * 5_000L)
        }
        assertNull("out and back are two journeys", TrackMerge.merge(span, backwards))
        assertFalse(TrackMerge.match(span, backwards).directionMatches)
        // Unless it is explicitly allowed.
        assertTrue(TrackMerge.match(span, backwards, allowReversed = true).directionMatches)
    }

    @Test
    fun aJourneyOfADifferentLengthOfTimeDoesNotMerge() {
        val span = suspended(4_000.0, 1_200)
        // The same ground, but taking four times as long: a walk, not a drive.
        val slow = run(4_000.0, 4_800)
        val signals = TrackMerge.match(span, slow)
        assertFalse(signals.durationsClose)
        assertFalse(signals.matched)
    }

    @Test
    fun aRouteThatCouldNotHaveBeenDrivenBetweenThoseEndsDoesNotMerge() {
        // Ends in the right places, but the route wanders forty kilometres --
        // a whole shift, not the hour that was missed.
        val span = suspended(4_000.0, 1_200)
        val wandering = (0..240).map { step ->
            Fix(
                north(if (step <= 120) step * 160.0 else (240 - step) * 160.0 + 4_000.0),
                east(if (step % 2 == 0) 0.0 else 40.0),
                start + step * 5_000L
            )
        }
        assertFalse(TrackMerge.match(span, wandering).routePlausible)
        assertNull(TrackMerge.merge(span, wandering))
    }

    @Test
    fun aRouteShorterThanTheStraightLineBetweenItsOwnEndsIsRefused() {
        val span = suspended(4_000.0, 1_200)
        // Only travels a quarter of the way, so it cannot be that journey.
        val short = run(1_000.0, 1_200)
        assertFalse(TrackMerge.match(span, short).routePlausible)
    }

    @Test
    fun aWindingRoadStillMerges() {
        // Eight miles of switchbacks between ends three miles apart, which is
        // exactly the case a naive length check would throw away.
        val span = suspended(4_800.0, 1_500)
        val winding = (0..300).map { step ->
            Fix(
                north(step * 16.0),
                east(600.0 * kotlin.math.sin(step / 6.0)),
                start + step * 5_000L
            )
        }
        val signals = TrackMerge.match(span, winding)
        assertTrue(signals.routePlausible)
        assertTrue(signals.disagreements().toString(), signals.matched)
    }

    @Test
    fun aTrackPastNowhereNearTheInferredEndsDoesNotMerge() {
        val span = suspended(4_000.0, 1_200)
        val elsewhere = run(4_000.0, 1_200, eastOffset = 5_000.0)
        val signals = TrackMerge.match(span, elsewhere)
        assertFalse(signals.startsAgree)
        assertFalse(signals.endpointsOnRoute)
        assertFalse(signals.matched)
    }

    @Test
    fun aNearMissSaysWhatDisagreedRatherThanJustRefusing() {
        val span = suspended(4_000.0, 1_200)
        val slow = run(4_000.0, 4_800)
        val signals = TrackMerge.match(span, slow)
        assertTrue(signals.agreeing >= 5)
        assertEquals(listOf("Durations close"), signals.disagreements())
    }

    /**
     * Both distances, always.
     *
     * The gap between the route and the straight line is the reason the
     * estimate was only ever a floor. Showing one without the other is how a
     * floor gets quoted as a measurement in a briefing.
     */
    @Test
    fun theMergedReadoutShowsRouteAndStraightLineSideBySide() {
        val winding = (0..516).map { step ->
            Fix(
                north(step * 9.3),
                east(400.0 * kotlin.math.sin(step / 8.0)),
                start + step * 5_000L
            )
        }
        // The inferred ends are real fixes from this same journey -- the last
        // before the phone went away and the first after it came back -- so
        // they are taken from the route rather than invented near it.
        val span = InferredSpan(
            startLatitude = winding.first().latitude,
            startLongitude = winding.first().longitude,
            startMillis = winding.first().timeMillis,
            endLatitude = winding.last().latitude,
            endLongitude = winding.last().longitude,
            endMillis = winding.last().timeMillis
        )
        val merged = TrackMerge.merge(span, winding)
        assertNotNull(merged)
        val said = merged!!.summary().joinToString(" | ")
        assertTrue(said, said.contains("Recorded route"))
        assertTrue(said, said.contains("Straight-line displacement"))
        assertTrue(said, said.contains("Travel time"))
        assertTrue(said, said.contains("provenance"))
        assertTrue(merged.recordedDistanceMeters > span.displacementMeters)
    }

    @Test
    fun aDetailedTrackOfOnePointCannotBeMatchedAgainstAnything() {
        val span = suspended(4_000.0, 1_200)
        assertFalse(TrackMerge.match(span, listOf(Fix(lat, lon, start))).matched)
        assertNull(TrackMerge.merge(span, emptyList()))
    }
}
