package com.rhecyee.firelinemap.terrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContourWindowsTest {

    private val north = 38.9500
    private val south = 38.9000
    private val west = -120.1000
    private val east = -120.0500

    private fun window(
        north: Double = this.north,
        south: Double = this.south,
        west: Double = this.west,
        east: Double = this.east,
        interval: Int = 40,
    ) = ContourWindows.of(north, south, west, east, interval)

    @Test
    fun `the traced window covers everything on screen`() {
        val result = window()!!
        assertTrue("north edge left uncovered", result.north >= north)
        assertTrue("south edge left uncovered", result.south <= south)
        assertTrue("west edge left uncovered", result.west <= west)
        assertTrue("east edge left uncovered", result.east >= east)
    }

    @Test
    fun `the traced window reaches past the screen so a small pan finds lines`() {
        val result = window()!!
        assertTrue(result.north - result.south > (north - south) * (1 + ContourWindows.MARGIN))
        assertTrue(result.east - result.west > (east - west) * (1 + ContourWindows.MARGIN))
    }

    @Test
    fun `a nudge of the view does not change the request`() {
        // A finger moving the map a few metres must not cancel a trace.
        val first = window()
        val nudge = 0.00002
        val second = window(
            north = north + nudge, south = south + nudge,
            west = west + nudge, east = east + nudge
        )
        assertEquals(first, second)
    }

    @Test
    fun `a real pan does change the request`() {
        val first = window()!!
        // Most of a screen's width east.
        val shift = (east - west) * 0.9
        val second = window(west = west + shift, east = east + shift)!!
        assertTrue("the window did not follow the map", second.west > first.west)
    }

    @Test
    fun `a slight pinch does not change the request`() {
        val first = window()
        val second = window(
            north = north - 0.0001, south = south + 0.0001,
            west = west + 0.0001, east = east - 0.0001
        )
        assertEquals(first, second)
    }

    @Test
    fun `zooming in a long way does change the request`() {
        val first = window()!!
        val second = window(
            north = 38.9300, south = 38.9200,
            west = -120.0800, east = -120.0700
        )!!
        assertTrue(
            "the window did not tighten with the zoom",
            (second.north - second.south) < (first.north - first.south)
        )
    }

    @Test
    fun `the interval is carried through and changing it is a new request`() {
        assertEquals(40, window(interval = 40)!!.intervalFeet)
        assertTrue(window(interval = 40) != window(interval = 80))
    }

    @Test
    fun `spans land on the same steps whatever the view happens to be`() {
        // Every window at a given scale should be one of a small set of sizes,
        // so panning across country reuses traces rather than re-cutting them.
        val spans = (0..40).map { step ->
            val drift = step * 0.0007
            val result = window(
                north = north + drift, south = south + drift,
                west = west + drift, east = east + drift
            )!!
            Math.round((result.north - result.south) * 1e9)
        }.toSet()
        assertEquals("window heights drifted: $spans", 1, spans.size)
    }

    @Test
    fun `a window whose corners are the wrong way round is refused`() {
        assertNull(window(north = 38.9, south = 38.95))
        assertNull(window(west = -120.05, east = -120.10))
    }

    @Test
    fun `a degenerate window is refused rather than traced`() {
        assertNull(window(north = 38.9, south = 38.9))
        assertNull(window(west = -120.1, east = -120.1))
    }

    @Test
    fun `a nonsense interval is refused`() {
        assertNull(window(interval = 0))
        assertNull(window(interval = -40))
    }

    @Test
    fun `values that are not numbers are refused`() {
        assertNull(window(north = Double.NaN))
        assertNull(window(south = Double.NEGATIVE_INFINITY))
        assertNull(window(west = Double.NaN))
        assertNull(window(east = Double.POSITIVE_INFINITY))
    }

    @Test
    fun `latitude never runs off the top of the projection`() {
        val result = window(north = 84.99, south = 84.90, west = 10.0, east = 10.1)
        assertNotNull(result)
        assertTrue(result!!.north <= 85.0)
    }

    @Test
    fun `a whole continent still produces a window rather than nothing`() {
        // Sized down elsewhere -- refusing here would mean no lines at all on
        // a zoomed-out view, which reads as the feature being broken.
        val result = window(north = 49.0, south = 25.0, west = -125.0, east = -67.0)
        assertNotNull(result)
    }
}
