package com.rhecyee.firelinemap.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
