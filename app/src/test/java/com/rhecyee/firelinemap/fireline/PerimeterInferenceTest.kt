package com.rhecyee.firelinemap.fireline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerimeterInferenceTest {

    private val lat = 45.20575
    private val lon = -117.6370

    private fun north(meters: Double) = lat + meters / 111_194.93
    private fun east(meters: Double) = lon + meters / (111_194.93 * Math.cos(Math.toRadians(lat)))

    private fun at(northMeters: Double, eastMeters: Double) =
        FirelineVertex(north(northMeters), east(eastMeters))

    private var counter = 0

    private fun fire(vararg points: FirelineVertex) =
        FirelineFeature("f${counter++}", FirelineKind.FIRE, points.toList())

    private fun clean(vararg points: FirelineVertex) =
        FirelineFeature("c${counter++}", FirelineKind.NOT_FIRE, points.toList())

    /** Whether a position falls inside the inferred fire, holes excluded. */
    private fun InferredPerimeter.covers(point: FirelineVertex): Boolean {
        val enclosing = rings.count { PerimeterInference.ringContains(it.points, point) }
        return enclosing % 2 == 1
    }

    @Test
    fun nothingBurningInfersNothing() {
        val result = PerimeterInference.infer(
            listOf(clean(at(0.0, 0.0)), clean(at(500.0, 500.0)))
        )
        assertTrue(result.isEmpty)
        assertEquals(0.0, result.areaSquareMeters, 0.0)
    }

    @Test
    fun pointsAloneInferABlobAroundThem() {
        val features = listOf(
            fire(at(0.0, 0.0)),
            fire(at(400.0, 0.0)),
            fire(at(400.0, 400.0)),
            fire(at(0.0, 400.0))
        )
        val result = PerimeterInference.infer(features, reachMeters = 300.0)

        assertEquals(1, result.polygonCount)
        // Every dropped point is fire, and the middle of the square is
        // enclosed by them.
        assertTrue(result.covers(at(0.0, 0.0)))
        assertTrue(result.covers(at(200.0, 200.0)))
        // A kilometre away is well past the reach.
        assertFalse(result.covers(at(2_000.0, 2_000.0)))
    }

    @Test
    fun reachBoundsHowFarTheFireIsAssumedToGo() {
        val features = listOf(fire(at(0.0, 0.0)))

        val tight = PerimeterInference.infer(features, reachMeters = 200.0)
        val loose = PerimeterInference.infer(features, reachMeters = 1_500.0)

        assertTrue(tight.covers(at(50.0, 0.0)))
        assertFalse(tight.covers(at(400.0, 0.0)))
        assertTrue(loose.covers(at(400.0, 0.0)))
        assertTrue(loose.areaSquareMeters > tight.areaSquareMeters * 10)
    }

    @Test
    fun aCleanPointHoldsThePerimeterOff() {
        val features = listOf(
            fire(at(0.0, 0.0)),
            clean(at(600.0, 0.0))
        )
        val result = PerimeterInference.infer(features, reachMeters = 400.0)

        assertTrue(result.covers(at(0.0, 0.0)))
        // Away from the negative the fire is free to run.
        assertTrue(result.covers(at(-100.0, 0.0)))
        // Towards it the perimeter stops at the halfway line, and the ground
        // at the negative itself is never claimed.
        assertFalse(result.covers(at(300.0, 0.0)))
        assertFalse(result.covers(at(600.0, 0.0)))
    }

    @Test
    fun negativesBetweenTwoGroupsSplitThePerimeter() {
        val features = listOf(
            // Fire either side of a gap.
            fire(at(0.0, 0.0)), fire(at(300.0, 0.0)), fire(at(-300.0, 0.0)),
            fire(at(0.0, 3_000.0)), fire(at(300.0, 3_000.0)), fire(at(-300.0, 3_000.0)),
            // Held apart by clean ground down the middle.
            clean(at(-900.0, 1_500.0)), clean(at(0.0, 1_500.0)), clean(at(900.0, 1_500.0))
        )
        val result = PerimeterInference.infer(features, reachMeters = 900.0)

        assertEquals(2, result.polygonCount)
        assertTrue(result.covers(at(0.0, 0.0)))
        assertTrue(result.covers(at(0.0, 3_000.0)))
        assertFalse(result.covers(at(0.0, 1_500.0)))
    }

    @Test
    fun aSegmentCountsAlongItsWholeRunNotJustItsEnds() {
        val alongARoad = fire(
            at(0.0, 0.0), at(0.0, 500.0), at(0.0, 1_000.0), at(0.0, 1_500.0)
        )
        val result = PerimeterInference.infer(listOf(alongARoad), reachMeters = 200.0)

        assertTrue(result.covers(at(0.0, 250.0)))
        assertTrue(result.covers(at(0.0, 750.0)))
        assertTrue(result.covers(at(30.0, 750.0)))
        assertFalse(result.covers(at(600.0, 750.0)))
    }

    @Test
    fun moreEvidenceTightensTheInference() {
        // Four corners with nothing said about the middle, versus the same
        // four corners plus the edges walked between them. The second is a
        // truer shape, so it should not simply be the first made bigger.
        val corners = listOf(
            fire(at(0.0, 0.0)), fire(at(0.0, 2_000.0)),
            fire(at(2_000.0, 2_000.0)), fire(at(2_000.0, 0.0))
        )
        val sparse = PerimeterInference.infer(corners, reachMeters = 1_500.0)

        val walked = corners + listOf(
            fire(at(0.0, 1_000.0)), fire(at(1_000.0, 2_000.0)),
            fire(at(2_000.0, 1_000.0)), fire(at(1_000.0, 0.0))
        )
        val dense = PerimeterInference.infer(walked, reachMeters = 800.0)

        assertEquals(1, sparse.polygonCount)
        assertTrue(dense.polygonCount >= 1)
        // The dense run, held to a short reach, claims far less ground than
        // the sparse one guessing at 1500 m.
        assertTrue(dense.areaSquareMeters < sparse.areaSquareMeters)
        // But it still covers every point that was actually walked.
        assertTrue(dense.covers(at(0.0, 1_000.0)))
        assertTrue(dense.covers(at(2_000.0, 1_000.0)))
    }

    @Test
    fun acreageIsReportedForAKnownSquare() {
        // A 1000 m square walked on all four sides, inferred at a short reach,
        // should come out near its own area rather than orders off it.
        val ring = mutableListOf<FirelineVertex>()
        for (i in 0..20) ring += at(0.0, i * 50.0)
        for (i in 1..20) ring += at(i * 50.0, 1_000.0)
        for (i in 19 downTo 0) ring += at(1_000.0, i * 50.0)
        for (i in 19 downTo 1) ring += at(i * 50.0, 0.0)

        val result = PerimeterInference.infer(
            listOf(FirelineFeature("ring", FirelineKind.FIRE, ring)),
            reachMeters = 60.0
        )

        assertEquals(1, result.polygonCount)
        // The middle was never walked and is never in shot of a point, so the
        // only thing that can fill it is the enclosure. This assertion is the
        // whole reason for the flood fill.
        assertTrue(result.covers(at(500.0, 500.0)))

        val acres = result.areaSquareMeters / 4046.8564224
        // A square kilometre is 247 acres. The skirt left around the walked
        // line adds a little to that; what it must not do is halve it, which
        // is what a perimeter with a hole punched through the unwalked middle
        // would report.
        assertTrue("acres was $acres", acres in 240.0..320.0)
    }

    @Test
    fun defaultReachFollowsTheSpacingWhenNothingContradictsIt() {
        val tight = listOf(fire(at(0.0, 0.0)), fire(at(0.0, 120.0)), fire(at(120.0, 0.0)))
        val spread = listOf(fire(at(0.0, 0.0)), fire(at(0.0, 2_000.0)), fire(at(2_000.0, 0.0)))

        assertTrue(
            PerimeterInference.defaultReachMeters(tight) <
                PerimeterInference.defaultReachMeters(spread)
        )
    }

    @Test
    fun defaultReachOpensUpOnceThereIsSomethingToPushBack() {
        val features = listOf(
            fire(at(0.0, 0.0)), fire(at(0.0, 120.0)),
            clean(at(1_500.0, 0.0))
        )
        // With a negative a kilometre and a half out, the operator is saying
        // the edge is somewhere between the two, so the reach has to open up
        // past the spacing of the fire points to go looking for it.
        assertTrue(PerimeterInference.defaultReachMeters(features) > 300.0)
    }
}
