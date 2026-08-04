package com.rhecyee.firelinemap.map

/**
 * How far the map may be panned.
 *
 * Pulled out of the canvas and given tests because it has now been the cause
 * of three separate faults, each of which looked like something else: a map
 * that teleported, a map that refused to pan down or right, and a map that
 * went blank on zoom.
 *
 * The rule is one sentence. Some of the content stays on screen, always. Off
 * the sheet is normal -- ICP and the drive in usually are, and there is
 * terrain drawn out there -- but the sheet has to stay findable without
 * needing a button pressed to get it back.
 */
object ViewClamp {

    /**
     * How much of the screen must still be showing content.
     *
     * A fifth. At the limit the other four fifths are ground beyond the sheet,
     * which is where ICP and the drive in usually are, so there is real room
     * to work off the neatline -- and the sheet stays in the corner of the eye
     * rather than having to be hunted for.
     *
     * The fraction is of the screen, not of the content, so this holds at
     * every zoom: pan as far as it allows at any scale and exactly a fifth of
     * the screen still has sheet on it.
     */
    const val MIN_CONTENT_ON_SCREEN = 0.2f

    /**
     * The furthest the pan may go on one axis.
     *
     * Derived from the visibility rule rather than picked: the content spans
     * half its width either side of centre, the screen reaches half its own
     * width past that, and [MIN_CONTENT_ON_SCREEN] of the screen is held back.
     */
    fun limit(contentSize: Float, viewportSize: Float): Float =
        ((contentSize + viewportSize) / 2f - viewportSize * MIN_CONTENT_ON_SCREEN)
            .coerceAtLeast(0f)

    /**
     * Bounds a pan.
     *
     * Deliberately has no memory of where the view already was.
     *
     * A previous version widened the limit to whatever the current offset
     * happened to be, so that centring on a position off the sheet would not
     * be hauled back. It had the effect of ratcheting: a pinch scales the pan
     * along with the content, so zooming in with the fingers anywhere but the
     * middle of the screen grew the offset, and the limit grew with it and
     * never let go. Zoom in far enough from a corner and the sheet ended up
     * entirely off screen with nothing to bring it back -- which is what
     * "exceeding a zoom threshold turns the map grey" was.
     *
     * Programmatic centring clamps through here too, so nothing can put the
     * view somewhere a pan cannot recover from.
     */
    fun clamp(
        offsetX: Float,
        offsetY: Float,
        contentWidth: Float,
        contentHeight: Float,
        viewportWidth: Float,
        viewportHeight: Float
    ): Pair<Float, Float> {
        val limitX = limit(contentWidth, viewportWidth)
        val limitY = limit(contentHeight, viewportHeight)
        return offsetX.coerceIn(-limitX, limitX) to offsetY.coerceIn(-limitY, limitY)
    }

    /**
     * Whether any of the content would still be on screen at this pan.
     *
     * Not used to bound anything -- it is what the bound is for -- but it says
     * plainly what the limit is protecting, and the tests check the two agree.
     */
    fun contentIsVisible(
        offsetX: Float,
        contentWidth: Float,
        viewportWidth: Float
    ): Boolean {
        val originX = (viewportWidth - contentWidth) / 2f + offsetX
        return originX < viewportWidth && originX + contentWidth > 0f
    }
}
