package com.rhecyee.firelinemap.share

import com.rhecyee.firelinemap.map.MapCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Thinning a track so it fits in a message.
 *
 * A picture message is capped somewhere around three hundred kilobytes on most
 * carriers and refused above it, so an unthinned shift is a shift that does
 * not send. What must survive is the shape: bends, switchbacks and
 * turnarounds. What goes is the straight running in between.
 */
class TrackSimplifyTest {

    private val lat = 45.20575
    private val lon = -117.63700

    private fun north(meters: Double) = lat + meters / 111_194.93
    private fun east(meters: Double) = lon + meters / (111_194.93 * 0.7009)

    /** A straight run north, sampled every five metres. */
    private fun straight(count: Int) =
        (0 until count).map { SharePoint(north(it * 5.0), lon) }

    @Test
    fun aStraightRunCollapsesToItsEnds() {
        val thinned = TrackSimplify.simplify(straight(200), toleranceMeters = 5.0)
        assertEquals("a straight line needs two points", 2, thinned.size)
    }

    @Test
    fun theStartAndTheEndAreNeverDropped() {
        val points = straight(200)
        val thinned = TrackSimplify.simplify(points, toleranceMeters = 50.0)
        assertEquals(points.first(), thinned.first())
        assertEquals(points.last(), thinned.last())
    }

    /**
     * The thing that must not be lost.
     *
     * A switchback is the whole reason to look at a track. Thinning that
     * straightens one has turned a road nobody can drive into a road that
     * looks fine.
     */
    @Test
    fun aSwitchbackSurvives() {
        val out = (0..20).map { SharePoint(north(it * 10.0), lon) }
        val back = (1..20).map { SharePoint(north(200.0 - it * 10.0), east(30.0)) }
        val thinned = TrackSimplify.simplify(out + back, toleranceMeters = 10.0)

        assertTrue("the turn must be kept", thinned.size >= 3)
        // The apex is the furthest point north; it has to still be there.
        val apex = thinned.maxByOrNull { it.latitude }!!
        assertEquals(north(200.0), apex.latitude, 1e-6)
    }

    /**
     * The guarantee Douglas-Peucker actually makes.
     *
     * Not that every point stays near a kept vertex -- on a straight run only
     * the two ends are kept and the middle is legitimately far from both. What
     * must hold is that no original point ends up further than the tolerance
     * from the *line* that now stands in for it. Checked here against an
     * independently written planar projection, so a mistake in the production
     * geometry cannot hide behind the same mistake in the test.
     */
    @Test
    fun noPointStraysFurtherFromTheKeptLineThanTheToleranceAllows() {
        // A gently curving road, which is the case where a careless
        // implementation quietly cuts a corner.
        val curve = (0..300).map { step ->
            val along = step * 5.0
            SharePoint(north(along), east(along * along / 4000.0))
        }
        val tolerance = 10.0
        val thinned = TrackSimplify.simplify(curve, tolerance)
        assertTrue("should have thinned something", thinned.size < curve.size)

        val worst = curve.maxOf { point -> metresFromPolyline(point, thinned) }
        assertTrue("a point ended up %.1f m off the kept line".format(worst), worst <= tolerance)
    }

    /** Local metres east and north of the track's origin. */
    private fun plane(point: SharePoint): Pair<Double, Double> {
        val metresPerDegreeLat = 111_194.93
        val metresPerDegreeLon = metresPerDegreeLat * kotlin.math.cos(Math.toRadians(lat))
        return (point.longitude - lon) * metresPerDegreeLon to
            (point.latitude - lat) * metresPerDegreeLat
    }

    private fun metresFromPolyline(point: SharePoint, line: List<SharePoint>): Double {
        if (line.size < 2) return 0.0
        val (px, py) = plane(point)
        var best = Double.MAX_VALUE
        for (index in 0 until line.lastIndex) {
            val (ax, ay) = plane(line[index])
            val (bx, by) = plane(line[index + 1])
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared <= 0.0) 0.0
            else (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
            val nearestX = ax + t * dx
            val nearestY = ay + t * dy
            val distance = kotlin.math.hypot(px - nearestX, py - nearestY)
            if (distance < best) best = distance
        }
        return best
    }

    @Test
    fun aTrackTooShortToThinIsLeftAlone() {
        val two = straight(2)
        assertEquals(two, TrackSimplify.simplify(two, 10.0))
        assertEquals(emptyList<SharePoint>(), TrackSimplify.simplify(emptyList(), 10.0))
        // A tolerance of nothing means keep everything.
        assertEquals(50, TrackSimplify.simplify(straight(50), 0.0).size)
    }

    @Test
    fun aPackageThatAlreadyFitsIsSentUntouched() {
        val pkg = SharePackage("x", tracks = listOf(ShareTrack("t", "T", straight(10))))
        val result = TrackSimplify.toFit(pkg, budgetBytes = 100_000) {
            GpxFormat.write(it).length
        }
        assertTrue(!result.wasSimplified)
        assertTrue(result.fits)
        assertEquals(10, result.pkg.tracks.first().points.size)
        assertEquals("Full detail", result.describe(pkg))
    }

    /**
     * The case this exists for: a shift that will not send.
     */
    @Test
    fun aShiftIsThinnedUntilItFitsAPictureMessage() {
        // Eight hours at a fix every five seconds, wandering as a road does.
        val points = (0 until 5_760).map { step ->
            val along = step * 3.0
            SharePoint(
                north(along),
                east(120.0 * kotlin.math.sin(step / 90.0)),
                timeMillis = 1_754_390_000_000L + step * 5_000L
            )
        }
        val pkg = SharePackage("Burnt Creek 2026", tracks = listOf(ShareTrack("t", "Shift", points)))
        val budget = 250_000

        val full = GpxFormat.write(pkg).length
        assertTrue("the fixture must actually be too big, was $full", full > budget)

        val result = TrackSimplify.toFit(pkg, budget) { GpxFormat.write(it).length }
        assertTrue(result.fits)
        assertTrue(result.wasSimplified)
        assertTrue(GpxFormat.write(result.pkg).length <= budget)
        // And it is still a track, not two points.
        assertTrue(result.pkg.tracks.first().points.size > 20)
        assertTrue(result.describe(pkg).contains("Thinned to fit"))
    }

    @Test
    fun theOperatorIsToldWhatWasGivenUp() {
        val points = (0 until 2_000).map { step ->
            SharePoint(north(step * 3.0), east(80.0 * kotlin.math.sin(step / 40.0)))
        }
        val pkg = SharePackage("x", tracks = listOf(ShareTrack("t", "T", points)))
        val result = TrackSimplify.toFit(pkg, 40_000) { GpxFormat.write(it).length }
        val said = result.describe(pkg)
        assertTrue(said, said.contains("2000 points"))
        assertTrue(said, said.contains("m of the real line"))
    }

    @Test
    fun aPackageThatCannotBeMadeToFitSaysSoRatherThanBeingFlattened() {
        // Two thousand pins. Nothing here is a track, so thinning cannot help,
        // and the honest answer is that this has to go as a file.
        val pins = (0 until 2_000).map {
            SharePin("p$it", "Pin number $it", north(it * 20.0), lon)
        }
        val pkg = SharePackage("x", pins = pins)
        val result = TrackSimplify.toFit(pkg, 10_000) { GpxFormat.write(it).length }
        assertTrue("must admit it does not fit", !result.fits)
    }
}
