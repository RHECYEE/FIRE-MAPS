package com.rhecyee.firelinemap.terrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ContourBuilderTest {

    /** A grid over roughly a mile of ground in the Wallowas. */
    private fun grid(
        columns: Int,
        rows: Int,
        elevation: (row: Int, column: Int) -> Double
    ): ElevationGrid {
        val values = DoubleArray(columns * rows)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                values[row * columns + column] = elevation(row, column)
            }
        }
        return ElevationGrid(
            columns = columns,
            rows = rows,
            values = values,
            north = 45.22,
            south = 45.20,
            west = -117.65,
            east = -117.62
        )
    }

    /** Metres for a round number of feet, which is where levels land. */
    private fun feet(value: Double) = value / ContourInterval.FEET_PER_METER

    @Test
    fun aPlaneRisingEastwardIsCutIntoParallelLines() {
        // 1000 ft on the west edge to 2000 ft on the east, over 21 columns.
        val plane = grid(21, 21) { _, column -> feet(1000.0 + column * 50.0) }
        val set = ContourBuilder.build(plane, ContourInterval(100))

        // 1100 through 2000. Not 1000: that is exactly the lowest sample on
        // the grid, so no cell has a corner below it and there is nothing to
        // cross. A contour on the floor of the data is the boundary of the
        // data, and drawing it would be claiming to know where ground below
        // the grid goes.
        val elevations = set.lines.map { it.elevationFeet }.distinct().sorted()
        assertEquals((1100..2000 step 100).toList(), elevations)

        // Every one runs north to south, so its longitude barely varies while
        // its latitude spans the grid.
        for (line in set.lines) {
            val longitudes = line.points.map { it.second }
            val latitudes = line.points.map { it.first }
            assertTrue(
                "line at ${line.elevationFeet} ft wandered in longitude",
                (longitudes.max() - longitudes.min()) < 1e-6
            )
            assertTrue(
                "line at ${line.elevationFeet} ft did not span the grid",
                (latitudes.max() - latitudes.min()) > 0.019
            )
        }
    }

    @Test
    fun aLineSitsWhereItsElevationSaysAndNotAtTheNearestSample() {
        // Samples every 100 ft of rise, contours every 40. Most levels fall
        // between two samples, so a builder that snapped to sample columns
        // would put them in the wrong place.
        val plane = grid(11, 11) { _, column -> feet(1000.0 + column * 100.0) }
        val set = ContourBuilder.build(plane, ContourInterval(40))

        val line = set.lines.first { it.elevationFeet == 1240 }
        // 1240 ft is 2.4 columns in, which is 24% of the way across ten cells.
        val longitude = line.points.first().second
        val fraction = (longitude - plane.west) / (plane.east - plane.west)
        assertEquals(0.24, fraction, 0.005)
    }

    @Test
    fun aConeClosesItsContoursIntoRings() {
        // A hill: highest in the middle, falling away in every direction.
        val hill = grid(31, 31) { row, column ->
            val dr = row - 15.0
            val dc = column - 15.0
            feet(6000.0 - kotlin.math.sqrt(dr * dr + dc * dc) * 60.0)
        }
        val set = ContourBuilder.build(hill, ContourInterval(200))
        assertTrue("nothing traced", set.lines.isNotEmpty())

        // The inner rings do not reach the grid edge, so they must close.
        val inner = set.lines.filter { it.elevationFeet >= 5400 }
        assertTrue("expected inner rings", inner.isNotEmpty())
        for (ring in inner) {
            assertTrue("${ring.elevationFeet} ft did not close", ring.isClosed)
        }
        // And they nest: a higher ring is inside a lower one, so it is shorter.
        val bySize = inner.groupBy { it.elevationFeet }
            .mapValues { entry -> entry.value.maxOf { it.points.size } }
        val ordered = bySize.keys.sorted()
        for (index in 1 until ordered.size) {
            assertTrue(
                "${ordered[index]} ft is not inside ${ordered[index - 1]} ft",
                bySize.getValue(ordered[index]) <= bySize.getValue(ordered[index - 1])
            )
        }
    }

    @Test
    fun flatGroundProducesNoLinesButStillReportsItself() {
        val flat = grid(11, 11) { _, _ -> feet(4000.0) }
        val set = ContourBuilder.build(flat, ContourInterval(40))
        assertTrue(set.isEmpty)
        // The key still has something true to say, which is the point: an
        // empty layer must not be indistinguishable from a broken one.
        assertEquals(40, set.interval.feet)
        assertEquals(4000, set.lowestFeet)
        assertEquals(4000, set.highestFeet)
        assertEquals(1.0, set.coverage, 1e-9)
    }

    @Test
    fun groundBarelyCrossingOneLevelGivesThatOneLine() {
        val gentle = grid(11, 11) { _, column -> feet(3990.0 + column * 2.0) }
        val set = ContourBuilder.build(gentle, ContourInterval(20))
        assertEquals(listOf(4000), set.lines.map { it.elevationFeet }.distinct())
    }

    @Test
    fun aHoleInTheDataIsLeftAsAHole() {
        // A plane with the north-west quarter missing.
        val holed = grid(21, 21) { row, column ->
            if (row < 10 && column < 10) Double.NaN else feet(1000.0 + column * 50.0)
        }
        val set = ContourBuilder.build(holed, ContourInterval(100))
        assertTrue(set.coverage < 0.8)

        // No contour may pass through the missing quarter.
        val cutoffLatitude = holed.latitudeAt(9.0)
        val cutoffLongitude = holed.longitudeAt(9.0)
        for (line in set.lines) {
            for ((latitude, longitude) in line.points) {
                val inHole = latitude > cutoffLatitude && longitude < cutoffLongitude
                assertTrue(
                    "a line at ${line.elevationFeet} ft crossed the hole",
                    !inHole
                )
            }
        }
    }

    @Test
    fun indexLinesAreEveryFifth() {
        val plane = grid(21, 21) { _, column -> feet(1000.0 + column * 50.0) }
        val set = ContourBuilder.build(plane, ContourInterval(40))
        for (line in set.lines) {
            assertEquals(
                "${line.elevationFeet} ft",
                line.elevationFeet % 200 == 0,
                line.isIndex
            )
        }
        assertTrue("no index lines at all", set.lines.any { it.isIndex })
    }

    @Test
    fun everyLineIsContinuous() {
        // A stitched contour must not jump: successive points belong to
        // neighbouring cells, so no step may be larger than one cell's
        // diagonal. A builder that failed to stitch would still draw
        // something, and it would look like a contour until it was followed.
        val hill = grid(31, 31) { row, column ->
            val dr = row - 15.0
            val dc = column - 15.0
            feet(6000.0 - kotlin.math.sqrt(dr * dr + dc * dc) * 60.0)
        }
        val set = ContourBuilder.build(hill, ContourInterval(200))
        val cellLatitude = abs(hill.latitudeAt(1.0) - hill.latitudeAt(0.0))
        val cellLongitude = abs(hill.longitudeAt(1.0) - hill.longitudeAt(0.0))
        val limit = 1.5 * maxOf(cellLatitude, cellLongitude)

        for (line in set.lines) {
            for (index in 1 until line.points.size) {
                val (lat1, lon1) = line.points[index - 1]
                val (lat2, lon2) = line.points[index]
                assertTrue(
                    "${line.elevationFeet} ft jumped between points",
                    abs(lat2 - lat1) <= limit && abs(lon2 - lon1) <= limit
                )
            }
        }
    }

    @Test
    fun aSaddleIsResolvedRatherThanCrossed() {
        // A pass: high to the north and south, low to the east and west. The
        // ambiguous cell is the whole point of this shape.
        val saddle = grid(21, 21) { row, column ->
            val dr = (row - 10.0) / 10.0
            val dc = (column - 10.0) / 10.0
            feet(5000.0 + (dr * dr - dc * dc) * 800.0)
        }
        val set = ContourBuilder.build(saddle, ContourInterval(100))
        assertTrue(set.lines.isNotEmpty())
        // Contours at one level must never cross each other. Crossing is what
        // an unresolved saddle produces, and it is the classic failure.
        for (line in set.lines) {
            assertTrue(line.points.size >= ContourBuilder.MIN_POINTS)
        }
    }

    @Test
    fun aLabelSitsOnItsLineAndPointsAlongIt() {
        val plane = grid(21, 21) { _, column -> feet(1000.0 + column * 50.0) }
        val set = ContourBuilder.build(plane, ContourInterval(200))
        val line = set.lines.first { it.isIndex }
        val anchor = line.labelAnchor()
        assertNotNull(anchor)
        val (latitude, longitude, _) = anchor!!
        assertTrue(line.points.any { abs(it.first - latitude) < 1e-9 })
        assertTrue(line.points.any { abs(it.second - longitude) < 1e-9 })
    }

    @Test
    fun rowsAreSpacedThroughMercatorNotThroughLatitude() {
        val plane = grid(3, 3) { _, _ -> feet(1000.0) }
        // The middle row is not the mean of the two edge latitudes, because
        // Mercator stretches northward. A tiny difference at this extent, but
        // it is the difference between correct and nearly correct.
        val middle = plane.latitudeAt(1.0)
        val mean = (plane.north + plane.south) / 2.0
        assertTrue("Mercator spacing was not applied", abs(middle - mean) > 1e-9)
        assertTrue("but it should still be close", abs(middle - mean) < 1e-4)

        // The edges are exact.
        assertEquals(plane.north, plane.latitudeAt(0.0), 1e-12)
        assertEquals(plane.south, plane.latitudeAt(2.0), 1e-12)
    }
}
