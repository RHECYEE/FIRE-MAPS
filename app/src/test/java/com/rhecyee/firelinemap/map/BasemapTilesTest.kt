package com.rhecyee.firelinemap.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

class BasemapTilesTest {

    private val latitude = 45.20575
    private val longitude = -117.6370

    @Test
    fun tileIndicesAgreeWithTheProjectionUsedForPlanning() {
        // The basemap and the download planner must index the same grid, or
        // preloaded regions would not line up with what gets drawn.
        for (zoom in listOf(4, 8, 12, 15)) {
            assertEquals(TileMath.tileX(longitude, zoom), BasemapTileCache.tileX(longitude, zoom))
            assertEquals(TileMath.tileY(latitude, zoom), BasemapTileCache.tileY(latitude, zoom))
        }
    }

    @Test
    fun tileCornersBoundTheirOwnTile() {
        val zoom = 12
        val x = BasemapTileCache.tileX(longitude, zoom)
        val y = BasemapTileCache.tileY(latitude, zoom)

        val west = BasemapTileCache.tileWest(x, zoom)
        val east = BasemapTileCache.tileWest(x + 1, zoom)
        val north = BasemapTileCache.tileNorth(y, zoom)
        val south = BasemapTileCache.tileNorth(y + 1, zoom)

        assertTrue("longitude outside its tile", longitude in west..east)
        assertTrue("latitude outside its tile", latitude in south..north)
        assertTrue("north must exceed south", north > south)
    }

    @Test
    fun resolutionHalvesWithEachZoomLevel() {
        val coarse = BasemapTileCache.metersPerPixel(latitude, 10)
        val fine = BasemapTileCache.metersPerPixel(latitude, 11)
        assertEquals(coarse / 2.0, fine, 1e-9)
    }

    @Test
    fun zoomIsChosenToMatchTheResolutionOnScreen() {
        // Roughly 10 m per screen pixel should land around zoom 13-14 at this
        // latitude; the chosen level must be at least as detailed as asked.
        val zoom = BasemapTileCache.zoomFor(latitude, 10.0)
        assertTrue("chose zoom $zoom", BasemapTileCache.metersPerPixel(latitude, zoom) <= 10.0)
        assertTrue(
            "chose more detail than needed",
            zoom == 0 || BasemapTileCache.metersPerPixel(latitude, zoom - 1) > 10.0
        )
    }

    @Test
    fun aVeryWideViewFallsBackToTheCoarsestUsefulLevel() {
        assertEquals(0, BasemapTileCache.zoomFor(latitude, 1_000_000.0))
    }

    @Test
    fun theZoomCeilingIsRespected() {
        assertTrue(BasemapTileCache.zoomFor(latitude, 0.001, max = 12) <= 12)
    }

    @Test
    fun theFractionalZoomIsTheCeilingTakenByTheIntegerChoice() {
        // All inside the ceiling, where the two agree by construction.
        for (target in listOf(3.0, 10.0, 47.0, 300.0)) {
            val exact = BasemapTileCache.fractionalZoom(latitude, target)
            val chosen = BasemapTileCache.zoomFor(latitude, target)
            assertEquals("target $target", ceil(exact).toInt(), chosen)
        }
    }

    /**
     * The bug this guards: a pinch resting on a level boundary flipped the
     * tile level every frame, and each flip swapped in a whole screen of
     * terrain at a different resolution. On the phone it looked like the map
     * was being replaced several times a second.
     */
    @Test
    fun aViewSittingOnALevelBoundaryDoesNotFlipLevels() {
        val boundary = BasemapTileCache.metersPerPixel(latitude, 13)
        var level = BasemapTileCache.zoomForStable(latitude, boundary, previous = null)
        assertEquals(13, level)

        // Wobble either side of the boundary the way two fingers do.
        val wobble = listOf(0.98, 1.02, 0.995, 1.04, 0.96, 1.01)
        for (factor in wobble) {
            level = BasemapTileCache.zoomForStable(latitude, boundary * factor, previous = level)
            assertEquals("flipped at factor $factor", 13, level)
        }
    }

    @Test
    fun aRealZoomStillMovesTheLevel() {
        val start = BasemapTileCache.metersPerPixel(latitude, 13)
        // Zooming in a full level and a bit, which is past any margin.
        val closer = BasemapTileCache.zoomForStable(latitude, start / 2.5, previous = 13)
        assertTrue("stayed at 13", closer > 13)

        val further = BasemapTileCache.zoomForStable(latitude, start * 4.0, previous = 13)
        assertTrue("stayed at 13", further < 13)
    }

    @Test
    fun aLevelIsHeldOnlyWhileItIsCloseEnoughToRight() {
        // Never more than the margin coarser, nor a level and margin finer,
        // than what the view actually wants.
        val start = BasemapTileCache.metersPerPixel(latitude, 12)
        for (step in 1..40) {
            val target = start / (1.0 + step * 0.15)
            val level = BasemapTileCache.zoomForStable(latitude, target, previous = 12)
            val exact = BasemapTileCache.fractionalZoom(latitude, target)
            assertTrue(
                "level $level for exact $exact",
                level >= exact - 1 - BasemapTileCache.ZOOM_HYSTERESIS &&
                    level <= exact + 1 + BasemapTileCache.ZOOM_HYSTERESIS
            )
        }
    }

    @Test
    fun withNothingHeldTheStableChoiceIsThePlainOne() {
        for (target in listOf(1.0, 12.0, 500.0)) {
            assertEquals(
                BasemapTileCache.zoomFor(latitude, target),
                BasemapTileCache.zoomForStable(latitude, target, previous = null)
            )
        }
    }
}
