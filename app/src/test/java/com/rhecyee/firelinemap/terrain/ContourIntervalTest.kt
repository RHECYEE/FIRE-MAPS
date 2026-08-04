package com.rhecyee.firelinemap.terrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContourIntervalTest {

    @Test
    fun theIntervalTightensAsTheViewCloses() {
        var previous = Int.MAX_VALUE
        for (zoom in 8..16) {
            val feet = ContourIntervals.baseFeetForZoom(zoom)
            assertTrue("zoom $zoom went coarser than $previous", feet <= previous)
            previous = feet
        }
        // And the ends are sensible: a district view is not cut every twenty
        // feet, and standing on a piece of line is not cut every five hundred.
        assertEquals(500, ContourIntervals.baseFeetForZoom(8))
        assertEquals(20, ContourIntervals.baseFeetForZoom(16))
    }

    @Test
    fun everyIntervalOfferedIsOnTheQuadrangleLadder() {
        for (zoom in 0..20) {
            assertTrue(
                "zoom $zoom is off the ladder",
                ContourIntervals.baseFeetForZoom(zoom) in ContourIntervals.LADDER
            )
        }
    }

    @Test
    fun steepGroundIsCoarsenedSoTheLinesStaySeparable() {
        // Four thousand feet of relief on one screen, at a zoom whose base is
        // forty feet: a hundred lines, which is a grey wash.
        val interval = ContourIntervals.forView(zoom = 14, reliefFeet = 4000.0)
        assertTrue("stayed at ${interval.feet} ft", interval.feet > 40)
        assertTrue(4000.0 / interval.feet <= ContourIntervals.MAX_LINES)
    }

    @Test
    fun flatGroundIsRefinedSoThereIsSomethingToSee() {
        // Thirty feet of relief across wheat ground at a close zoom. At the
        // base interval that is one line, or none.
        val interval = ContourIntervals.forView(zoom = 14, reliefFeet = 30.0)
        assertTrue("stayed at ${interval.feet} ft", interval.feet < 40)
        assertTrue(interval.feet >= ContourIntervals.LADDER.first())
    }

    @Test
    fun refinementStopsAtTheAccuracyOfTheData() {
        // Dead flat. Refining without a floor would end up drawing the
        // vertical noise of the elevation model as if it were terrain.
        val interval = ContourIntervals.forView(zoom = 16, reliefFeet = 0.5)
        assertEquals(ContourIntervals.LADDER.first(), interval.feet)
    }

    @Test
    fun aViewWithNoDataYetFallsBackToZoomAlone() {
        assertEquals(
            ContourIntervals.baseFeetForZoom(13),
            ContourIntervals.forView(13, null).feet
        )
        assertEquals(
            ContourIntervals.baseFeetForZoom(13),
            ContourIntervals.forView(13, 0.0).feet
        )
    }

    @Test
    fun theChosenIntervalAlwaysDrawsAReadableNumberOfLines() {
        // Across the range of ground this app is used on, from the Columbia
        // basin to the Wallowas, and every zoom the map offers.
        for (zoom in 9..16) {
            for (relief in listOf(20.0, 80.0, 200.0, 600.0, 1500.0, 4000.0, 9000.0)) {
                val interval = ContourIntervals.forView(zoom, relief)
                val lines = relief / interval.feet
                val atFloor = interval.feet == ContourIntervals.LADDER.first()
                val atCeiling = interval.feet == ContourIntervals.LADDER.last()
                if (!atFloor) {
                    assertTrue(
                        "z$zoom relief $relief gave $lines lines at ${interval.feet} ft",
                        lines >= ContourIntervals.MIN_LINES
                    )
                }
                if (!atCeiling) {
                    assertTrue(
                        "z$zoom relief $relief gave $lines lines at ${interval.feet} ft",
                        lines <= ContourIntervals.MAX_LINES
                    )
                }
            }
        }
    }

    @Test
    fun indexLinesAreEveryFifthAndAreNamedAsSuch() {
        val interval = ContourInterval(40)
        assertEquals(200, interval.indexFeet)
        assertTrue(interval.isIndex(2000))
        assertTrue(interval.isIndex(0))
        assertTrue(!interval.isIndex(2040))
        assertEquals("40 ft · index 200 ft", interval.describe())
    }

    @Test
    fun theIntervalConvertsToMetresForTheGrid() {
        assertEquals(40 / 3.280839895, ContourInterval(40).meters, 1e-9)
    }
}
