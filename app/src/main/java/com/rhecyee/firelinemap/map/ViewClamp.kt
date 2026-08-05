package com.rhecyee.firelinemap.map

/**
 * How far the map may be panned.
 *
 * Pulled out of the canvas and given tests because it has now been the cause
 * of three separate faults, each of which looked like something else: a map
 * that teleported, a map that refused to pan down or right, and a map that
 * went blank on zoom.
 *
 * The rule is one sentence. The view may travel a long way past the content
 * but not without limit. Off the sheet is normal -- ICP and the drive in
 * usually are -- and terrain is drawn out there, so there is always ground to
 * work on; what a bound prevents is a pan large enough to be unrecoverable.
 */
object ViewClamp {

    /**
     * How far past the content the view may travel, in screens.
     *
     * Generous, deliberately. This used to hold a fifth of the sheet on
     * screen at all times, which fenced the operator to the product: with a
     * position off the neatline -- ICP, the drive in, a spot across the road,
     * which is most of a shift -- the map would not go there. The sheet is not
     * the world. Terrain is drawn wherever the view is, so there is always
     * ground out here to work on and tools to work on it with.
     *
     * A bound remains, because an unbounded pan is a pan that can be lost. At
     * a dozen screens from the content the sheet is far behind you and the
     * view is still somewhere a gesture can return from.
     */
    const val OFF_CONTENT_SCREENS = 12f

    /**
     * The furthest the pan may go on one axis.
     *
     * Half the content, because the pan is measured from its centre, plus the
     * allowance for travelling beyond it.
     */
    fun limit(contentSize: Float, viewportSize: Float): Float {
        if (!contentSize.isFinite() || !viewportSize.isFinite()) return 0f
        return (contentSize / 2f + viewportSize * OFF_CONTENT_SCREENS).coerceAtLeast(0f)
    }

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
        // A pan that is not a number is not a pan.
        //
        // Coercing NaN silently returns NaN -- comparisons against it are all
        // false, so it passes through every bound untouched. One NaN reaching
        // here settles into the view and never leaves: everything drawn
        // projects to NaN and is discarded as off screen, so the map goes
        // blank with a full cache behind it. Whatever produced it, it stops
        // here, and the middle of the content is the one answer that is always
        // safe.
        val x = if (offsetX.isFinite()) offsetX else 0f
        val y = if (offsetY.isFinite()) offsetY else 0f
        val limitX = limit(contentWidth, viewportWidth)
        val limitY = limit(contentHeight, viewportHeight)
        return x.coerceIn(-limitX, limitX) to y.coerceIn(-limitY, limitY)
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
