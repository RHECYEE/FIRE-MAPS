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

            val (width, _) = contentAt(scale)
            assertTrue(
                "the sheet left the screen at scale $scale, offset $offsetX",
                ViewClamp.contentIsVisible(offsetX, width, viewportWidth)
            )
        }
    }

    @Test
    fun theLimitAlwaysKeepsSomethingOnScreen() {
        // Across every zoom the sheet allows, a pan taken to its limit in any
        // direction must still leave content in view. This is the property the
        // limit exists for; if these two ever disagree the limit is wrong.
        for (step in 0..24) {
            val scale = 1f + step * 0.5f
            val (width, height) = contentAt(scale)
            for (sign in listOf(-1f, 1f)) {
                val (x, y) = clamp(sign * 1e7f, sign * 1e7f, scale)
                assertTrue(
                    "scale $scale, x $x",
                    ViewClamp.contentIsVisible(x, width, viewportWidth)
                )
                assertTrue(
                    "scale $scale, y $y",
                    ViewClamp.contentIsVisible(y, height, viewportHeight)
                )
            }
        }
    }

    @Test
    fun thereIsRoomToWorkOffTheSheet() {
        // Off the neatline is normal -- ICP and the drive in usually sit
        // outside it -- so the limit has to allow real travel out there, not
        // merely a pixel of overlap. At the limit, four fifths of the screen
        // is ground beyond the content, and that holds at every zoom.
        for (scale in listOf(1f, 2f, 6f, 12f)) {
            val (width, _) = contentAt(scale)
            val limit = ViewClamp.limit(width, viewportWidth)
            val originX = (viewportWidth - width) / 2f + limit
            assertEquals(
                "scale $scale",
                viewportWidth * (1f - ViewClamp.MIN_CONTENT_ON_SCREEN),
                originX,
                0.5f
            )
        }
    }

    @Test
    fun theLimitGrowsWithTheContentSoZoomingInDoesNotPenTheView() {
        // At high zoom most of the sheet is off screen by definition, and the
        // operator still has to reach its far corner.
        val atOne = ViewClamp.limit(contentAt(1f).first, viewportWidth)
        val atTwelve = ViewClamp.limit(contentAt(12f).first, viewportWidth)
        assertTrue(atTwelve > atOne * 5f)

        // Far enough to put the sheet's own corner under the middle of the
        // screen, which is what centring on a position near the edge needs.
        val (width, _) = contentAt(12f)
        assertTrue(atTwelve >= width / 2f)
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
            // And it lands somewhere with ground in it, not merely somewhere
            // representable.
            val (width, height) = contentAt(2f)
            assertTrue(ViewClamp.contentIsVisible(x, width, viewportWidth))
            assertTrue(ViewClamp.contentIsVisible(y, height, viewportHeight))
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
