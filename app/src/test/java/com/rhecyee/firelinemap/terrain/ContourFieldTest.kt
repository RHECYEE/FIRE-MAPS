package com.rhecyee.firelinemap.terrain

import com.rhecyee.firelinemap.map.ElevationGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ContourFieldTest {

    private companion object {
        const val RAMP_BASE_FEET = 15.0
    }

    private val north = 39.0
    private val south = 38.9
    private val west = -120.1
    private val east = -120.0

    /**
     * A grid climbing eastward, a hundred feet a column, starting at fifteen.
     *
     * Deliberately offset off a multiple of the interval. Elevations are held
     * as metres in a Float, so a column placed at exactly 1,000 feet comes
     * back as 999.99994 and the top line disappears -- a property of the
     * fixture, not of the ground, and not something to write a test around.
     */
    private fun ramp(width: Int = 11, height: Int = 11, feetPerColumn: Double = 100.0) =
        grid(width, height) { column, _ ->
            (RAMP_BASE_FEET + column * feetPerColumn) / ContourGenerator.FEET_PER_METER
        }

    private fun grid(
        width: Int,
        height: Int,
        meters: (column: Int, row: Int) -> Double,
    ) = ElevationGrid(
        values = FloatArray(width * height) { meters(it % width, it / width).toFloat() },
        width = width,
        height = height,
        north = north,
        south = south,
        west = west,
        east = east,
        zoom = 14,
        coverage = 1f
    )

    @Test
    fun `contours come back in feet on round multiples of the interval`() {
        val contours = ContourField.build(ramp(), intervalFeet = 40)

        assertTrue(contours.isNotEmpty())
        assertTrue(
            "levels drifted off the interval: " + contours.map { it.elevationFeet },
            contours.all { it.elevationFeet % 40.0 == 0.0 }
        )
        // The ramp climbs 15 to 1,015 feet, so the first and last lines sit
        // just inside that.
        assertEquals(40.0, contours.minOf { it.elevationFeet }, 1e-6)
        assertEquals(1000.0, contours.maxOf { it.elevationFeet }, 1e-6)
    }

    @Test
    fun `a contour lands at the longitude the elevation actually reaches`() {
        // Columns run 15, 115, 215, 315 ... so 240 feet falls a quarter of
        // the way from the third column to the fourth.
        val contours = ContourField.build(ramp(), intervalFeet = 40)
        val line = contours.single { it.elevationFeet == 240.0 }

        val expected = west + (east - west) * 2.25 / 10.0
        for (vertex in line.points) {
            // A ten-thousandth of a degree either way is a centimetre; the
            // Float the elevation is held in is worth about that much.
            assertEquals(expected, vertex.longitude, 1e-7)
        }
        // Running down the grid, it spans the full height of the window.
        assertEquals(north, line.points.maxOf { it.latitude }, 1e-9)
        assertEquals(south, line.points.minOf { it.latitude }, 1e-9)
    }

    @Test
    fun `row zero is the north edge`() {
        // Elevation rising southward: the low line must sit at the north end.
        val contours = ContourField.build(
            grid(11, 11) { _, row ->
                (RAMP_BASE_FEET + row * 100.0) / ContourGenerator.FEET_PER_METER
            },
            intervalFeet = 100
        )
        val low = contours.filter { it.elevationFeet == 100.0 }
        val high = contours.filter { it.elevationFeet == 900.0 }
        assertTrue(low.isNotEmpty() && high.isNotEmpty())
        assertTrue(
            "north and south are the wrong way round",
            low.first().points.first().latitude > high.first().points.first().latitude
        )
    }

    @Test
    fun `every fifth line is flagged as an index contour`() {
        val contours = ContourField.build(ramp(), intervalFeet = 40)
        assertTrue(contours.single { it.elevationFeet == 200.0 }.index)
        assertFalse(contours.single { it.elevationFeet == 240.0 }.index)
        assertTrue(contours.single { it.elevationFeet == 400.0 }.index)
    }

    @Test
    fun `a summit comes back as a closed ring`() {
        val size = 41
        val centre = (size - 1) / 2.0
        val contours = ContourField.build(
            grid(size, size) { column, row ->
                val feet = 8000.0 - hypot(column - centre, row - centre) * 37.0
                feet / ContourGenerator.FEET_PER_METER
            },
            intervalFeet = 100
        )
        val ring = contours.first { it.elevationFeet == 7700.0 }
        assertTrue("a loop around a summit came back open", ring.closed)
        assertTrue(ring.points.size > 8)
    }

    @Test
    fun `holes in the elevation data do not become contours at sea level`() {
        // An unfetched tile reads NaN. Treated as zero it would draw a
        // shoreline across the middle of a mountain.
        val contours = ContourField.build(
            grid(11, 11) { column, _ ->
                if (column in 4..6) Double.NaN else 2200.0 + column
            },
            intervalFeet = 40
        )
        assertTrue(
            "a contour was drawn across missing data",
            contours.all { line -> line.points.all { it.longitude.isFinite() } }
        )
        assertTrue(contours.none { it.elevationFeet < 7000.0 })
    }

    @Test
    fun `a nonsense interval draws nothing rather than hanging`() {
        assertTrue(ContourField.build(ramp(), intervalFeet = 0).isEmpty())
        assertTrue(ContourField.build(ramp(), intervalFeet = -40).isEmpty())
    }

    @Test
    fun `a grid too small to contour is empty`() {
        assertTrue(ContourField.build(ramp(width = 1, height = 1), intervalFeet = 40).isEmpty())
    }

    @Test
    fun `flat ground draws nothing`() {
        val contours = ContourField.build(grid(11, 11) { _, _ -> 2200.0 }, intervalFeet = 40)
        assertTrue(contours.isEmpty())
    }

    // ---- fitting the interval to the relief ----

    @Test
    fun `a band that fits is used exactly as asked`() {
        assertEquals(40, ContourField.fittedInterval(7380.0, 7620.0, 40))
        assertEquals(20, ContourField.fittedInterval(7380.0, 7480.0, 20))
        assertEquals(200, ContourField.fittedInterval(2000.0, 9000.0, 200))
    }

    @Test
    fun `a view too wide for the band gets a coarser one rather than nothing`() {
        // Sea level to the crest at twenty feet is seven hundred lines, which
        // the generator refuses. Refusing quietly would read as contours being
        // broken, so the band opens up instead.
        val fitted = ContourField.fittedInterval(0.0, 14_000.0, 20)
        assertTrue("still asking for an impossible band: $fitted", fitted > 20)
        assertTrue(
            ContourGenerator.levels(0.0, 14_000.0, fitted.toDouble()).isNotEmpty()
        )
    }

    @Test
    fun `the fitted band is never finer than the one asked for`() {
        for (requested in ContourGenerator.INTERVALS_FEET) {
            for (relief in listOf(50.0, 500.0, 5_000.0, 50_000.0)) {
                val fitted = ContourField.fittedInterval(1000.0, 1000.0 + relief, requested)
                assertTrue(
                    "asked $requested over $relief feet and got $fitted",
                    fitted >= requested
                )
            }
        }
    }

    @Test
    fun `even a whole mountain range still gets lines`() {
        val fitted = ContourField.fittedInterval(-300.0, 20_000.0, 20)
        assertTrue(
            ContourGenerator.levels(-300.0, 20_000.0, fitted.toDouble()).isNotEmpty()
        )
    }

    @Test
    fun `zooming out coarsens the band and zooming back in restores it`() {
        val wide = ContourField.fittedInterval(4_000.0, 12_000.0, 40)
        val close = ContourField.fittedInterval(7_380.0, 7_620.0, 40)
        assertTrue(wide > close)
        assertEquals(40, close)
    }

    @Test
    fun `a wide window draws coarser lines instead of coming back empty`() {
        // The whole path, not just the arithmetic: eight thousand feet of
        // relief at a forty foot band.
        val contours = ContourField.build(
            grid(60, 20) { column, _ ->
                (2000.0 + column * 140.0) / ContourGenerator.FEET_PER_METER
            },
            intervalFeet = 40
        )
        assertTrue("a wide view came back with no contours at all", contours.isNotEmpty())
        val bands = contours.map { it.elevationFeet }.sorted().zipWithNext { a, b -> b - a }
        assertTrue("bands are not evenly spaced: $bands", bands.distinct().size <= 1)
        assertTrue("the band did not open up", bands.first() > 40.0)
    }

    @Test
    fun `a nonsense band is passed through untouched rather than fitted`() {
        assertEquals(0, ContourField.fittedInterval(0.0, 100.0, 0))
        assertEquals(-40, ContourField.fittedInterval(0.0, 100.0, -40))
    }

    // ---- interval suggestion ----

    @Test
    fun `the suggested interval keeps a readable number of lines on screen`() {
        for (relief in listOf(120.0, 400.0, 900.0, 2400.0, 6000.0)) {
            val interval = ContourField.suggestedInterval(relief)
            assertTrue(interval in ContourGenerator.INTERVALS_FEET)
            val lines = relief / interval
            assertTrue(
                "relief $relief gave $lines lines at a $interval foot interval",
                lines in 1.0..40.0
            )
        }
    }

    @Test
    fun `gentle ground gets a tighter interval than a canyon wall`() {
        assertTrue(
            ContourField.suggestedInterval(300.0) < ContourField.suggestedInterval(6000.0)
        )
    }

    @Test
    fun `no relief falls back to the interval a quad sheet uses`() {
        assertEquals(ContourField.DEFAULT_INTERVAL_FEET, ContourField.suggestedInterval(0.0))
        assertEquals(ContourField.DEFAULT_INTERVAL_FEET, ContourField.suggestedInterval(-10.0))
    }
}
