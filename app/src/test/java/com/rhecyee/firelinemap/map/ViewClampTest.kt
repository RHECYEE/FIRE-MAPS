package com.rhecyee.firelinemap.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How far the map may be panned.
 *
 * Given its own tests because this rule has now been the cause of three
 * separate faults, each of which presented as something else entirely: a map
 * that teleported after a search, a map that would not pan down or right, and
 * a map that went blank once zoomed past a certain point.
 */
class ViewClampTest {

    /** A portrait phone. */
    private val viewportWidth = 1080f
    private val viewportHeight = 2000f

    /** A Burnt Creek sheet rendered at 2048 wide, fitted, then zoomed. */
    private fun contentAt(scale: Float): Pair<Float, Float> {
        val fit = minOf(viewportWidth / 2048f, viewportHeight / 2650f)
        return 2048f * fit * scale to 2650f * fit * scale
    }

    private fun clamp(offsetX: Float, offsetY: Float, scale: Float): Pair<Float, Float> {
        val (width, height) = contentAt(scale)
        return ViewClamp.clamp(offsetX, offsetY, width, height, viewportWidth, viewportHeight)
    }

    @Test
    fun aPanInsideTheLimitIsLeftAlone() {
        val (x, y) = clamp(300f, -450f, scale = 1f)
        assertEquals(300f, x, 0f)
        assertEquals(-450f, y, 0f)
    }

    @Test
    fun aPanStopsAtTheEdgeRatherThanBeingRefused() {
        // The version before this returned the offset unchanged whenever a
        // drag would cross the limit, so the pan stopped wherever the finger
        // happened to be instead of at the edge, and pushing further did
        // nothing at all. The map felt like it did not want to go that way.
        val (width, _) = contentAt(1f)
        val edge = ViewClamp.limit(width, viewportWidth)
        val (x, _) = clamp(edge * 4f, 0f, scale = 1f)
        assertEquals(edge, x, 0.01f)
    }

    @Test
    fun theLimitIsSymmetric() {
        val (right, down) = clamp(999_999f, 999_999f, scale = 3f)
        val (left, up) = clamp(-999_999f, -999_999f, scale = 3f)
        assertEquals(right, -left, 0.01f)
        assertEquals(down, -up, 0.01f)
    }

    /**
     * The blank-on-zoom fault.
     *
     * A pinch scales the pan along with the content, so zooming in with the
     * fingers anywhere but the middle of the screen grows the offset. The
     * previous rule widened its own limit to whatever the offset already was,
     * which meant it ratcheted: every zoom grew the pan, the limit grew to
     * match, and nothing ever brought it back. Zoom in far enough from a
     * corner and the sheet ended up entirely off screen.
     */
    @Test
    fun zoomingFromACornerCannotWalkTheSheetOffScreen() {
        var offsetX = 0f
        var scale = 1f
        // Twenty pinch steps with the fingers at the left edge of the screen,
        // which is the worst case for the focal-point correction.
        repeat(20) {
            val next = (scale * 1.25f).coerceAtMost(12f)
            val applied = next / scale
            scale = next
            // What the focal-point maths produces before clamping.
            val focusOffset = -viewportWidth / 2f
            offsetX = offsetX * applied + focusOffset * (1f - applied)
            offsetX = clamp(offsetX, 0f, scale).first
            assertTrue("the pan ran away at scale $scale", offsetX.isFinite())
        }
    }

    @Test
    fun aPanIsAlwaysBoundedEvenThoughItIsNoLongerFenced() {
        // The point of a bound now is only that the view cannot be lost. It
        // has to stay finite and it has to stay within reach of a gesture.
        for (step in 0..24) {
            val scale = 1f + step * 0.5f
            val (width, height) = contentAt(scale)
            for (sign in listOf(-1f, 1f)) {
                val (x, y) = clamp(sign * 1e7f, sign * 1e7f, scale)
                assertTrue(x.isFinite() && y.isFinite())
                assertTrue(kotlin.math.abs(x) <= ViewClamp.limit(width, viewportWidth) + 1f)
                assertTrue(kotlin.math.abs(y) <= ViewClamp.limit(height, viewportHeight) + 1f)
            }
        }
    }

    /**
     * The fence is gone, deliberately.
     *
     * This used to hold a fifth of the sheet on screen at all times, which
     * fenced the operator to the product: with a position off the neatline --
     * ICP, the drive in, a spot across the road, which is most of a shift --
     * the map would not go there. Terrain is drawn wherever the view is, so
     * there is always ground out here and tools to work on it with.
     */
    @Test
    fun theViewCanTravelWellClearOfTheSheet() {
        for (scale in listOf(1f, 2f, 6f, 12f)) {
            val (width, _) = contentAt(scale)
            val limit = ViewClamp.limit(width, viewportWidth)
            // Measured from the content's edge, in screens.
            val beyond = (limit - width / 2f) / viewportWidth
            assertEquals("scale $scale", ViewClamp.OFF_CONTENT_SCREENS, beyond, 0.01f)
            assertTrue("must be real travel, not a token", beyond >= 4f)
        }
    }

    @Test
    fun everyCornerOfTheSheetIsReachableAtEveryZoom() {
        // At high zoom most of the sheet is off screen by definition, and the
        // operator still has to be able to put any part of it under the middle
        // of the view -- which is what centring on a position near an edge
        // does, and what panning to a division on the far side needs.
        for (scale in listOf(1f, 2f, 6f, 12f)) {
            val (width, height) = contentAt(scale)
            assertTrue(
                "scale $scale across",
                ViewClamp.limit(width, viewportWidth) >= width / 2f
            )
            assertTrue(
                "scale $scale down",
                ViewClamp.limit(height, viewportHeight) >= height / 2f
            )
        }
    }

    @Test
    fun aDegenerateViewportDoesNotProduceANonsenseLimit() {
        val (x, y) = ViewClamp.clamp(500f, 500f, 0f, 0f, 0f, 0f)
        assertEquals(0f, x, 0f)
        assertEquals(0f, y, 0f)
    }

    /**
     * The whole of the blank-map fault, in one place.
     *
     * A pinch's centroid comes back as a pair of NaNs whenever no pointer was
     * down both this event and last -- which is the instant a finger lifts.
     * Multiplying that by anything, zero included, gives NaN, and coercing NaN
     * returns NaN because every comparison against it is false. So it passes
     * through the bound untouched, settles into the view and never leaves.
     * From there every tile projects to NaN and is discarded as off screen,
     * the view corners will not convert so nothing is even fetched, and the
     * map is blank with a full cache behind it -- a hundred and sixty-four
     * tiles held, none drawn, nothing fetching, which is exactly what the
     * screen reported. Only centring recovered it, because that is the one
     * path that builds the pan from scratch rather than from itself.
     */
    @Test
    fun aPanThatIsNotANumberDoesNotSurviveTheClamp() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val (x, y) = clamp(bad, bad, scale = 2f)
            assertTrue("x was $x", x.isFinite())
            assertTrue("y was $y", y.isFinite())
            // And it lands on the content, not merely somewhere representable.
            assertEquals(0f, x, 0f)
            assertEquals(0f, y, 0f)
        }
    }

    @Test
    fun oneBadAxisDoesNotTakeTheOtherWithIt() {
        val (x, y) = clamp(Float.NaN, 300f, scale = 1f)
        assertTrue(x.isFinite())
        assertEquals("the good axis must be left alone", 300f, y, 0f)
    }

    @Test
    fun aContentSizeThatIsNotANumberGivesNoLimitRatherThanANaNOne() {
        assertEquals(0f, ViewClamp.limit(Float.NaN, viewportWidth), 0f)
        assertEquals(0f, ViewClamp.limit(1000f, Float.NaN), 0f)
    }
}
