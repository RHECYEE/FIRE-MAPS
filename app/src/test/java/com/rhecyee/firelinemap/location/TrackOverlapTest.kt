package com.rhecyee.firelinemap.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading several passes over the same ground as one answer.
 *
 * The question this exists for is travel time. A road driven five times is
 * five answers to how long it takes, and the useful number only appears when
 * they are read together.
 */
class TrackOverlapTest {

    private val roadLat = 45.20575
    private val roadLon = -117.63700

    private fun north(meters: Double) = roadLat + meters / 111_194.93

    /**
     * A real epoch, not zero.
     *
     * Zero is what the app uses for "time not known", so a fixture starting
     * there has its first fix silently discarded and every elapsed time comes
     * out one interval short. No receiver has ever reported 1970.
     */
    private val base = 1_754_390_000_000L

    /** A run north along the road at [speed] m/s, one fix every five seconds. */
    private fun run(
        id: String,
        name: String,
        speed: Double,
        fromMillis: Long,
        offsetMeters: Double = 0.0,
        seconds: Int = 200
    ): TrackLine {
        val points = mutableListOf<Fix>()
        var elapsed = 0
        while (elapsed <= seconds) {
            points += Fix(
                latitude = north(speed * elapsed - 100.0),
                longitude = roadLon + offsetMeters / (111_194.93 * 0.7009),
                timeMillis = base + fromMillis + elapsed * 1000L
            )
            elapsed += 5
        }
        return TrackLine(id, name, points)
    }

    @Test
    fun oneTrackThroughASpotIsNotAnOverlap() {
        val report = TrackOverlap.at(
            roadLat, roadLon, listOf(run("a", "Monday", 8.0, 0))
        )
        assertEquals(1, report.passes.size)
        assertFalse(report.isOverlap)
    }

    @Test
    fun everyTrackThroughTheSpotIsListedSeparately() {
        val tracks = listOf(
            run("a", "Monday", 8.0, 0),
            run("b", "Tuesday", 9.0, 86_400_000L),
            run("c", "Wednesday", 7.0, 172_800_000L)
        )
        val report = TrackOverlap.at(roadLat, roadLon, tracks)

        assertTrue(report.isOverlap)
        assertEquals(3, report.passes.size)
        assertEquals(
            setOf("Monday", "Tuesday", "Wednesday"),
            report.passes.map { it.trackName }.toSet()
        )
        // Each keeps its own time, so the list can say which day it was.
        assertEquals(3, report.passes.mapNotNull { it.atMillis }.toSet().size)
    }

    @Test
    fun theAverageIsAcrossThePasses() {
        val tracks = listOf(
            run("a", "Monday", 8.0, 0),
            run("b", "Tuesday", 12.0, 86_400_000L)
        )
        val report = TrackOverlap.at(roadLat, roadLon, tracks)
        val average = report.averageSpeedMetersPerSecond
        assertNotNull(average)
        assertEquals(10.0, average!!, 0.5)
    }

    /**
     * A stop must not be averaged in.
     *
     * Somewhere a crew parks -- a drop point, a gate, the ICP -- and half the
     * passes read near zero. Averaging those in says a road takes an hour when
     * driving it takes ten minutes, which is the number that would end up in a
     * briefing.
     */
    @Test
    fun aParkedPassDoesNotDragTheAverageDown() {
        val parked = TrackLine(
            "p", "Parked at the gate",
            (0..40).map { step ->
                Fix(north((step % 2) * 1.5), roadLon, base + step * 5000L)
            }
        )
        val tracks = listOf(run("a", "Monday", 8.0, 0), parked)
        val report = TrackOverlap.at(roadLat, roadLon, tracks)

        assertEquals(2, report.passes.size)
        assertEquals(1, report.stoppedCount)
        // The moving pass alone, not the mean of eight and nothing.
        assertEquals(8.0, report.averageSpeedMetersPerSecond!!, 0.6)
    }

    @Test
    fun aSpotWhereEverybodyStopsSaysSoRatherThanReportingNoSpeed() {
        val parked = (1..3).map { index ->
            TrackLine(
                "p$index", "Pass $index",
                (0..40).map { step ->
                    Fix(north((step % 2) * 1.5), roadLon, base + step * 5000L)
                }
            )
        }
        val report = TrackOverlap.at(roadLat, roadLon, parked)
        assertNull(report.averageSpeedMetersPerSecond)
        assertTrue(report.describe().contains("all stopped here"))
    }

    @Test
    fun aTrackOnADifferentRoadIsNotCountedAsThisOne() {
        // Two hundred metres east: a parallel road, or the switchback below.
        val tracks = listOf(
            run("a", "This road", 8.0, 0),
            run("b", "The next one over", 8.0, 0, offsetMeters = 200.0)
        )
        val report = TrackOverlap.at(roadLat, roadLon, tracks)
        assertEquals(1, report.passes.size)
        assertEquals("This road", report.passes.first().trackName)
    }

    @Test
    fun ordinaryScatterBetweenTwoPassesStillMatches() {
        // Twelve metres apart is opposite lanes plus a poor fix, and is the
        // same road by any reading.
        val tracks = listOf(
            run("a", "Out", 8.0, 0),
            run("b", "Back", 8.0, 3_600_000L, offsetMeters = 12.0)
        )
        val report = TrackOverlap.at(roadLat, roadLon, tracks)
        assertEquals(2, report.passes.size)
    }

    @Test
    fun theNearestPassIsListedFirst() {
        val tracks = listOf(
            run("far", "Thirty out", 8.0, 0, offsetMeters = 30.0),
            run("near", "Right on it", 8.0, 0, offsetMeters = 1.0)
        )
        val report = TrackOverlap.at(roadLat, roadLon, tracks)
        assertEquals("Right on it", report.passes.first().trackName)
        assertTrue(report.passes[0].closestMeters < report.passes[1].closestMeters)
    }

    @Test
    fun aTrackWithNoTimesStillCountsAsAPassButOffersNoSpeed() {
        val untimed = TrackLine(
            "u", "Imported, no times",
            (0..40).map { step -> Fix(north(-100.0 + step * 10.0), roadLon, 0L) }
        )
        val report = TrackOverlap.at(roadLat, roadLon, listOf(untimed))
        assertEquals(1, report.passes.size)
        assertNull(report.passes.first().speedMetersPerSecond)
        assertNull(report.passes.first().atMillis)
    }

    @Test
    fun aSpotNoTrackWentNearReportsNothing() {
        val tracks = listOf(run("a", "Monday", 8.0, 0))
        val report = TrackOverlap.at(north(50_000.0), roadLon, tracks)
        assertTrue(report.passes.isEmpty())
        assertFalse(report.isOverlap)
        assertEquals("No tracks here", report.describe())
    }

    @Test
    fun speedIsMeasuredEvenWhenOnlyOneFixLandsInsideTheCircle() {
        // At sixty miles an hour a vehicle crosses forty metres in under two
        // seconds, so at a five second update exactly one fix is inside. The
        // fixes either side are what make a speed possible at all.
        val fast = run("f", "Highway", 27.0, 0)
        val report = TrackOverlap.at(roadLat, roadLon, listOf(fast))
        val speed = report.passes.first().speedMetersPerSecond
        assertNotNull("a single fix inside must still yield a speed", speed)
        assertEquals(27.0, speed!!, 2.0)
    }

    @Test
    fun speedReadsInMilesPerHourBecauseThatIsWhatGoesOverTheRadio() {
        assertEquals("18 mph", OverlapReport.formatSpeed(8.0))
        assertEquals("60 mph", OverlapReport.formatSpeed(26.8224))
        // Below walking pace the decimal matters: two versus three miles an
        // hour is a hand crew versus a dozer.
        assertEquals("2.2 mph", OverlapReport.formatSpeed(1.0))
    }

    @Test
    fun aNonsensePositionIsRefusedRatherThanMatchingEverything() {
        val tracks = listOf(run("a", "Monday", 8.0, 0))
        assertTrue(TrackOverlap.at(Double.NaN, roadLon, tracks).passes.isEmpty())
        assertTrue(TrackOverlap.at(roadLat, Double.NaN, tracks).passes.isEmpty())
    }

    @Test
    fun theReadoutSaysTheCountAndTheAverageTogether() {
        val tracks = listOf(
            run("a", "Monday", 8.0, 0),
            run("b", "Tuesday", 8.0, 86_400_000L),
            run("c", "Wednesday", 8.0, 172_800_000L),
            run("d", "Thursday", 8.0, 259_200_000L)
        )
        val report = TrackOverlap.at(roadLat, roadLon, tracks)
        assertEquals("4 tracks · 18 mph average", report.describe())
    }

    /**
     * The whole-track figures, which is what the readout leads with.
     *
     * The speed through one corner answers a different question from how long
     * the road takes end to end, and the second is the one somebody writes
     * down. Both are reported.
     */
    @Test
    fun eachPassCarriesItsOwnDistanceTimeAndAverage() {
        // Two hundred seconds at eight metres a second is 1,600 m.
        val report = TrackOverlap.at(roadLat, roadLon, listOf(run("a", "Monday", 8.0, 0)))
        val pass = report.passes.first()
        assertEquals(1_600.0, pass.trackDistanceMeters, 30.0)
        assertEquals(200_000L, pass.trackElapsedMillis)
        assertEquals(8.0, pass.trackAverageSpeed!!, 0.3)
    }

    @Test
    fun theTopLineAveragesTheWholeTracksNotJustTheCorner() {
        val tracks = listOf(
            run("a", "Monday", 6.0, 0),
            run("b", "Tuesday", 10.0, 86_400_000L)
        )
        val report = TrackOverlap.at(roadLat, roadLon, tracks)
        assertEquals(8.0, report.averageTrackSpeed!!, 0.3)
        assertEquals(200_000L, report.averageElapsedMillis)
        assertEquals(400_000L, report.totalElapsedMillis)
        // 200 s at 6 m/s plus 200 s at 10 m/s.
        assertEquals(3_200.0, report.totalDistanceMeters, 60.0)
    }

    @Test
    fun aTrackWithNoTimesContributesNothingToTheAveragesRatherThanAZero() {
        val untimed = TrackLine(
            "u", "No times",
            (0..40).map { step -> Fix(north(-100.0 + step * 8.0), roadLon, 0L) }
        )
        val report = TrackOverlap.at(
            roadLat, roadLon, listOf(run("a", "Monday", 8.0, 0), untimed)
        )
        assertEquals(2, report.passes.size)
        // The timed one alone, not the mean of eight and nothing.
        assertEquals(8.0, report.averageTrackSpeed!!, 0.3)
        assertEquals(200_000L, report.averageElapsedMillis)
    }

    @Test
    fun figuresReadTheWayTheyAreWrittenDown() {
        assertEquals("1:00:00", OverlapReport.formatElapsed(3_600_000))
        assertEquals("20:00", OverlapReport.formatElapsed(1_200_000))
        assertEquals("00:07", OverlapReport.formatElapsed(7_000))
        assertEquals("1.0 mi", OverlapReport.formatDistance(1609.344))
        assertEquals("12.4 mi", OverlapReport.formatDistance(20_000.0))
        assertEquals("80 m", OverlapReport.formatDistance(80.0))
    }
}
