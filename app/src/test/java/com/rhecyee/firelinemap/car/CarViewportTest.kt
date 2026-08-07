package com.rhecyee.firelinemap.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Where things land on a car display.
 *
 * All of this is arithmetic, and arithmetic can be checked on a desk. The
 * alternative is discovering that the track is drawn a mile off the road while
 * driving, which is the worst place there is to find out.
 */
class CarViewportTest {

    private fun view(
        latitude: Double = 45.60,
        longitude: Double = -117.25,
        zoom: Double = 13.0,
        width: Int = 800,
        height: Int = 480
    ) = CarViewport(latitude, longitude, zoom, width, height)

    // ------------------------------------------------------------ the seam

    @Test
    fun theCentreOfTheViewIsTheCentreOfTheDisplay() {
        val viewport = view()
        val at = viewport.toScreen(45.60, -117.25)
        assertEquals(400f, at.x, 0.01f)
        assertEquals(240f, at.y, 0.01f)
    }

    /**
     * The one property everything else rests on.
     *
     * A pin dropped at a pixel has to draw back to that pixel. If this drifts,
     * every position on the display is wrong by the same amount and nothing on
     * screen says so.
     */
    @Test
    fun groundAndScreenRoundTrip() {
        val viewport = view()
        for (x in listOf(0f, 137f, 400f, 799f)) {
            for (y in listOf(0f, 91f, 240f, 479f)) {
                val ground = viewport.toGround(x, y)
                val back = viewport.toScreen(ground.latitude, ground.longitude)
                assertEquals("x at $x,$y", x, back.x, 0.01f)
                assertEquals("y at $x,$y", y, back.y, 0.01f)
            }
        }
    }

    @Test
    fun northIsUpAndEastIsRight() {
        val viewport = view()
        val north = viewport.toScreen(45.61, -117.25)
        val east = viewport.toScreen(45.60, -117.24)
        assertTrue("north should be above centre", north.y < 240f)
        assertTrue("east should be right of centre", east.x > 400f)
    }

    // -------------------------------------------------------------- zoom

    /** A level of zoom is a doubling. Anything else and the scale bar lies. */
    @Test
    fun oneZoomLevelHalvesTheGroundPerPixel() {
        val close = view(zoom = 14.0).metresPerPixel()
        val wide = view(zoom = 13.0).metresPerPixel()
        assertEquals(2.0, wide / close, 1e-9)
    }

    /** Fractional zoom is what a pinch produces; it must not snap or jump. */
    @Test
    fun halfALevelIsBetweenTheTwo() {
        val half = view(zoom = 13.5).metresPerPixel()
        val wide = view(zoom = 13.0).metresPerPixel()
        val close = view(zoom = 14.0).metresPerPixel()
        assertTrue(half < wide && half > close)
        // The square root of two, because zoom is a power of two.
        assertEquals(1.41421356, wide / half, 1e-5)
    }

    @Test
    fun zoomIsClampedRatherThanAllowedToRunAway() {
        assertEquals(CarViewport.MIN_ZOOM, CarViewport.clampZoom(-4.0), 1e-9)
        assertEquals(CarViewport.MAX_ZOOM, CarViewport.clampZoom(99.0), 1e-9)
        assertEquals(12.5, CarViewport.clampZoom(12.5), 1e-9)
    }

    // ------------------------------------------------------------- tiles

    @Test
    fun theTilesCoverTheDisplay() {
        val viewport = view()
        val tiles = viewport.tiles()
        assertTrue("expected some tiles", tiles.isNotEmpty())

        // Every corner of the display has to be inside some tile, or there is
        // a hole in the map.
        for (corner in listOf(0f to 0f, 799f to 0f, 0f to 479f, 799f to 479f)) {
            val covered = tiles.any { tile ->
                corner.first >= tile.left - 0.5f &&
                    corner.first <= tile.left + tile.size + 0.5f &&
                    corner.second >= tile.top - 0.5f &&
                    corner.second <= tile.top + tile.size + 0.5f
            }
            assertTrue("corner $corner not covered", covered)
        }
    }

    @Test
    fun tilesAreAtTheZoomBeingLookedAt() {
        assertEquals(13, view(zoom = 13.0).tiles().first().zoom)
        // A fractional zoom still fetches whole tiles, scaled to fit.
        assertEquals(13, view(zoom = 13.7).tiles().first().zoom)
    }

    @Test
    fun aFractionalZoomScalesTheTilesRatherThanLeavingSeams() {
        val tiles = view(zoom = 13.5).tiles()
        val size = tiles.first().size
        assertTrue("tile should be larger than 256 at 13.5", size > 256f)
        assertTrue("and smaller than 512", size < 512f)
        // Neighbouring tiles must abut exactly, or the map shows grid lines.
        val row = tiles.filter { it.y == tiles.first().y }.sortedBy { it.left }
        for (index in 1 until row.size) {
            assertEquals(row[index - 1].left + size, row[index].left, 0.5f)
        }
    }

    /**
     * A bad viewport must not be able to ask for the world.
     *
     * On a head unit the map is the only thing on screen. If drawing it stalls
     * there is nothing else to look at, so an absurd request returns nothing
     * and the previous frame stays up.
     */
    @Test
    fun anAbsurdViewAsksForNoTilesRatherThanAllOfThem() {
        val silly = CarViewport(45.6, -117.25, 13.0, 100_000, 100_000)
        assertTrue(silly.tiles().isEmpty())
    }

    @Test
    fun anEmptyDisplayAsksForNothing() {
        assertTrue(view(width = 0, height = 0).tiles().isEmpty())
    }

    // ----------------------------------------------------------- culling

    @Test
    fun somethingWellOffScreenIsNotDrawn() {
        val viewport = view()
        assertTrue(viewport.isVisible(45.60, -117.25))
        assertFalse(viewport.isVisible(44.00, -101.00))
    }

    /**
     * The margin exists so a symbol half off the edge still draws its half.
     * Without it, a drop point slides out of existence before it leaves.
     */
    @Test
    fun somethingJustOffTheEdgeStillDraws() {
        val viewport = view()
        val justOff = viewport.toGround(-20f, 240f)
        assertTrue(viewport.isVisible(justOff.latitude, justOff.longitude))
        val wellOff = viewport.toGround(-400f, 240f)
        assertFalse(viewport.isVisible(wellOff.latitude, wellOff.longitude))
    }

    // ------------------------------------------------------------ ground

    /**
     * The scale has to be right, not merely consistent.
     *
     * Checked against the known figure for Web Mercator at the equator, and
     * against the cosine falloff by latitude -- the same numbers the phone and
     * the browser use, so a road is the same length on all three.
     */
    @Test
    fun theGroundScaleMatchesWebMercator() {
        val equator = CarViewport(0.0, 0.0, 0.0, 256, 256).metresPerPixel()
        assertEquals(156543.03392, equator, 1e-5)

        val atFortyFive = CarViewport(45.0, 0.0, 0.0, 256, 256).metresPerPixel()
        assertEquals(156543.03392 * 0.70710678, atFortyFive, 1e-3)
    }

    /** A screen's width in metres should be believable for a vehicle display. */
    @Test
    fun aTypicalViewCoversATypicalDistance() {
        val viewport = view(zoom = 13.0)
        val across = viewport.metresPerPixel() * viewport.widthPixels
        // 800 px at zoom 13 near 45 degrees is a few kilometres.
        assertTrue("was $across m", across > 5_000 && across < 12_000)
    }

    @Test
    fun theViewCanBeMovedWithoutDistortion() {
        val here = view()
        val there = here.copy(latitude = 45.70, longitude = -117.10)
        // The same two positions must stay the same distance apart on screen.
        fun separation(viewport: CarViewport): Float {
            val a = viewport.toScreen(45.60, -117.25)
            val b = viewport.toScreen(45.61, -117.24)
            return abs(a.x - b.x) + abs(a.y - b.y)
        }
        assertEquals(separation(here), separation(there), 0.01f)
    }
}
