package com.rhecyee.firelinemap.terrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class ContourGeneratorTest {

    private companion object {
        const val TOP_LEFT = "top-left"
        const val TOP_RIGHT = "top-right"
        const val BOTTOM_LEFT = "bottom-left"
        const val BOTTOM_RIGHT = "bottom-right"
    }

    // ---- levels ----

    @Test
    fun `levels sit on multiples of the interval`() {
        // Real ground: a ridge running 7,383 to 7,606 feet at a forty foot band.
        val levels = ContourGenerator.levels(7383.0, 7606.0, 40.0)
        assertEquals(listOf(7400.0, 7440.0, 7480.0, 7520.0, 7560.0, 7600.0), levels)
    }

    @Test
    fun `levels are empty when no multiple falls inside the range`() {
        assertEquals(emptyList<Double>(), ContourGenerator.levels(7401.0, 7439.0, 40.0))
    }

    @Test
    fun `levels refuse a range that would draw more lines than anyone can read`() {
        // Sea level to the top of Whitney at twenty feet is seven hundred lines.
        assertTrue(ContourGenerator.levels(0.0, 14_505.0, 20.0).isEmpty())
        // The same ground at five hundred feet is legible, so it is drawn.
        assertTrue(ContourGenerator.levels(0.0, 14_505.0, 500.0).isNotEmpty())
    }

    @Test
    fun `levels reject nonsense bounds and intervals`() {
        assertTrue(ContourGenerator.levels(100.0, 100.0, 40.0).isEmpty())
        assertTrue(ContourGenerator.levels(200.0, 100.0, 40.0).isEmpty())
        assertTrue(ContourGenerator.levels(0.0, 100.0, 0.0).isEmpty())
        assertTrue(ContourGenerator.levels(0.0, 100.0, -40.0).isEmpty())
    }

    @Test
    fun `levels work below sea level`() {
        assertEquals(
            listOf(-200.0, -100.0, 0.0, 100.0),
            ContourGenerator.levels(-260.0, 130.0, 100.0)
        )
    }

    // ---- trace ----

    @Test
    fun `a constant slope traces a straight line where the level crosses`() {
        // Elevation rises ten a column: 0 10 20 30 40 across, flat down.
        val width = 5
        val height = 3
        val grid = FloatArray(width * height) { (it % width) * 10f }

        // Deliberately not 25, which would fall on the midpoint of the cell it
        // crosses and let an interpolator that always answers "halfway" pass.
        val segments = ContourGenerator.trace(grid, width, height, 22.0)

        // One segment a row band, all of them on the same vertical line, two
        // tenths of the way across the cell between the twenty and the thirty.
        assertEquals(height - 1, segments.size)
        for (segment in segments) {
            assertEquals(2.2, segment.x1, 1e-9)
            assertEquals(2.2, segment.x2, 1e-9)
        }
        // And spanning the full height between them.
        val tops = segments.map { minOf(it.y1, it.y2) }.sorted()
        assertEquals(listOf(0.0, 1.0), tops)
    }

    @Test
    fun `a level below or above everything traces nothing`() {
        val grid = FloatArray(9) { (it % 3) * 10f }
        assertTrue(ContourGenerator.trace(grid, 3, 3, -5.0).isEmpty())
        assertTrue(ContourGenerator.trace(grid, 3, 3, 1000.0).isEmpty())
    }

    @Test
    fun `a plateau exactly at the level does not shimmer`() {
        // Every sample at 7,400 with a 7,400 line asked for: an unresolved
        // equality here draws a segment on every cell edge and the map fills
        // with hatching.
        val grid = FloatArray(16) { 7400f }
        assertTrue(ContourGenerator.trace(grid, 4, 4, 7400.0).isEmpty())
    }

    @Test
    fun `a mesa topping out at the level still gets its rim drawn`() {
        // The other half of the equality question. A bench that sits at exactly
        // 7,400 and drops away east has a 7,400 contour along its edge; treat
        // the samples on the bench as below the line and the rim vanishes,
        // which is how a flat top reads as no top at all.
        val width = 4
        val grid = floatArrayOf(
            7400f, 7400f, 7380f, 7360f,
            7400f, 7400f, 7380f, 7360f,
        )
        val segments = ContourGenerator.trace(grid, width, 2, 7400.0)

        assertEquals(1, segments.size)
        // Drawn on the lip, not out in the flat and not off in the fall.
        assertEquals(1.0, segments[0].x1, 1e-9)
        assertEquals(1.0, segments[0].x2, 1e-9)
    }

    @Test
    fun `a hill traces closed rings`() {
        // A cone. Every contour inside it is a loop, so each vertex has to be
        // shared by exactly two segments -- an unmatched endpoint is a gap.
        val size = 21
        val centre = (size - 1) / 2.0
        val grid = FloatArray(size * size) { index ->
            val x = (index % size) - centre
            val y = (index / size) - centre
            (1000.0 - hypot(x, y) * 30.0).toFloat()
        }

        val segments = ContourGenerator.trace(grid, size, size, 800.0)
        assertTrue(segments.isNotEmpty())

        val visits = HashMap<Pair<Double, Double>, Int>()
        for (segment in segments) {
            visits.merge(segment.x1 to segment.y1, 1, Int::plus)
            visits.merge(segment.x2 to segment.y2, 1, Int::plus)
        }
        val loose = visits.filterValues { it != 2 }
        assertTrue("unmatched contour endpoints: $loose", loose.isEmpty())
    }

    @Test
    fun `holes in the elevation data are skipped rather than fabricated`() {
        val width = 4
        val height = 2
        val grid = floatArrayOf(
            0f, 10f, 20f, 30f,
            0f, 10f, Float.NaN, 30f,
        )
        // The level crosses in the cell holding the hole and in the one before.
        val segments = ContourGenerator.trace(grid, width, height, 15.0)
        // Cells 0 and 2 do not straddle 15; cell 1 does but is spoiled by NaN.
        assertTrue(segments.isEmpty())
    }

    @Test
    fun `a grid too small to have a cell traces nothing`() {
        assertTrue(ContourGenerator.trace(floatArrayOf(1f), 1, 1, 0.5).isEmpty())
        assertTrue(ContourGenerator.trace(floatArrayOf(1f, 2f), 2, 1, 1.5).isEmpty())
    }

    @Test
    fun `a short array does not read off the end`() {
        assertTrue(ContourGenerator.trace(floatArrayOf(1f, 2f, 3f), 2, 2, 1.5).isEmpty())
    }

    // ---- saddles ----

    @Test
    fun `a saddle with a high centre cuts off the low corners`() {
        // High on one diagonal, low on the other: a pass between two knobs.
        // The centre reads high, so the high ground runs through the saddle and
        // it is the two low corners that end up in their own little basins.
        // Both resolutions are uncrossed, so "did not cross" proves nothing on
        // its own -- which corners get cut off is the whole question.
        val grid = floatArrayOf(
            10f, 0f,
            0f, 10f,
        )
        val segments = ContourGenerator.trace(grid, 2, 2, 5.0)
        assertEquals(2, segments.size)
        assertFalse("contours crossed in the saddle", crosses(segments[0], segments[1]))
        assertEquals(setOf(TOP_RIGHT, BOTTOM_LEFT), segments.map(::isolatedCorner).toSet())
    }

    @Test
    fun `the other diagonal saddle is resolved the same way round`() {
        val grid = floatArrayOf(
            0f, 10f,
            10f, 0f,
        )
        val segments = ContourGenerator.trace(grid, 2, 2, 5.0)
        assertEquals(2, segments.size)
        assertFalse("contours crossed in the saddle", crosses(segments[0], segments[1]))
        assertEquals(setOf(TOP_LEFT, BOTTOM_RIGHT), segments.map(::isolatedCorner).toSet())
    }

    @Test
    fun `a saddle with a low centre cuts off the high corners instead`() {
        // Same shape, but the corners no longer balance: the cell averages just
        // under the level, so the low ground is what runs through and the two
        // knobs are what get their own rings.
        val grid = floatArrayOf(
            10f, 0f,
            0f, 9f,
        )
        val segments = ContourGenerator.trace(grid, 2, 2, 5.0)
        assertEquals(2, segments.size)
        assertFalse(crosses(segments[0], segments[1]))
        assertEquals(setOf(TOP_LEFT, BOTTOM_RIGHT), segments.map(::isolatedCorner).toSet())
    }

    @Test
    fun `the low centre saddle on the other diagonal is resolved to match`() {
        val grid = floatArrayOf(
            0f, 10f,
            9f, 0f,
        )
        val segments = ContourGenerator.trace(grid, 2, 2, 5.0)
        assertEquals(2, segments.size)
        assertFalse(crosses(segments[0], segments[1]))
        assertEquals(setOf(TOP_RIGHT, BOTTOM_LEFT), segments.map(::isolatedCorner).toSet())
    }

    // ---- contours ----

    @Test
    fun `contours returns every band the ground crosses, in order`() {
        val width = 11
        val grid = FloatArray(width * 2) { 7380f + (it % width) * 25f }

        val lines = ContourGenerator.contours(grid, width, 2, 40.0)

        assertEquals(lines.map { it.elevation }.sorted(), lines.map { it.elevation })
        assertTrue(lines.all { it.segments.isNotEmpty() })
        assertTrue(lines.all { it.elevation % 40.0 == 0.0 })
        assertEquals(7400.0, lines.first().elevation, 1e-9)
    }

    @Test
    fun `contours over data that is all holes is empty rather than a crash`() {
        val grid = FloatArray(16) { Float.NaN }
        assertTrue(ContourGenerator.contours(grid, 4, 4, 40.0).isEmpty())
    }

    @Test
    fun `contours over flat ground draws nothing`() {
        val grid = FloatArray(16) { 7400f }
        assertTrue(ContourGenerator.contours(grid, 4, 4, 40.0).isEmpty())
    }

    // ---- index contours ----

    @Test
    fun `every fifth line is an index contour`() {
        val interval = 40.0
        assertTrue(ContourLine(7400.0, emptyList()).isIndex(interval))
        assertFalse(ContourLine(7440.0, emptyList()).isIndex(interval))
        assertFalse(ContourLine(7560.0, emptyList()).isIndex(interval))
        assertTrue(ContourLine(7600.0, emptyList()).isIndex(interval))
    }

    @Test
    fun `index contours hold below sea level too`() {
        assertTrue(ContourLine(-200.0, emptyList()).isIndex(40.0))
        assertFalse(ContourLine(-160.0, emptyList()).isIndex(40.0))
    }

    @Test
    fun `a nonsense interval is not an index`() {
        assertFalse(ContourLine(7400.0, emptyList()).isIndex(0.0))
        assertFalse(ContourLine(7400.0, emptyList()).isIndex(-40.0))
    }

    @Test
    fun `the offered intervals are the ones a topo sheet uses`() {
        assertTrue(ContourGenerator.INTERVALS_FEET.contains(40))
        assertEquals(ContourGenerator.INTERVALS_FEET.sorted(), ContourGenerator.INTERVALS_FEET)
    }

    @Test
    fun `feet per metre matches the survey foot conversion`() {
        assertEquals(3.280839895, ContourGenerator.FEET_PER_METER, 1e-9)
    }

    // ---- joining ----

    @Test
    fun `a slope joins into one stroke running the height of the grid`() {
        val width = 5
        val height = 6
        val grid = FloatArray(width * height) { (it % width) * 10f }

        val paths = ContourGenerator.join(ContourGenerator.trace(grid, width, height, 22.0))

        assertEquals(1, paths.size)
        val path = paths.single()
        assertFalse("an open line was called closed", path.closed)
        // Every cell band contributes one point pair, chained end to end.
        assertEquals(height, path.points.size)
        assertTrue(path.points.all { abs(it.x - 2.2) < 1e-9 })
        // Walked in order, top to bottom or bottom to top -- not shuffled.
        val ys = path.points.map { it.y }
        assertTrue("stroke is out of order: $ys", ys == ys.sorted() || ys == ys.sortedDescending())
    }

    @Test
    fun `a hill joins into a closed ring`() {
        val size = 21
        val centre = (size - 1) / 2.0
        val grid = FloatArray(size * size) { index ->
            val x = (index % size) - centre
            val y = (index / size) - centre
            (1000.0 - hypot(x, y) * 30.0).toFloat()
        }

        val paths = ContourGenerator.join(ContourGenerator.trace(grid, size, size, 800.0))

        assertEquals(1, paths.size)
        val ring = paths.single()
        assertTrue("a loop around a summit was left open", ring.closed)
        // Closed means the last point runs back to the first; it is not repeated.
        assertTrue(ring.points.first() != ring.points.last())
        // And it really does encircle the summit.
        assertTrue(ring.points.any { it.x < centre })
        assertTrue(ring.points.any { it.x > centre })
        assertTrue(ring.points.any { it.y < centre })
        assertTrue(ring.points.any { it.y > centre })
    }

    @Test
    fun `two separate hills join into two separate rings`() {
        // Two knobs at the same elevation, well apart: one line each, and no
        // stroke wandering from one to the other across the saddle.
        //
        // Thirty-seven a cell, not forty: at forty the ring lands at a radius
        // of exactly five, which puts two dozen grid samples precisely on the
        // 800 line -- every lattice point at distance five, the 3-4-5s
        // included. Those are junctions, and the walk stops at junctions on
        // purpose, so the ring comes apart into arcs. Pinned separately below.
        val width = 31
        val height = 15
        val grid = FloatArray(width * height) { index ->
            val x = (index % width).toDouble()
            val y = (index / width).toDouble()
            val a = 1000.0 - hypot(x - 7, y - 7) * 37.0
            val b = 1000.0 - hypot(x - 23, y - 7) * 37.0
            maxOf(a, b).toFloat()
        }

        val paths = ContourGenerator.join(ContourGenerator.trace(grid, width, height, 800.0))

        assertEquals(2, paths.size)
        assertTrue(paths.all { it.closed })
        val centresX = paths.map { path -> path.points.sumOf { it.x } / path.points.size }.sorted()
        assertTrue("rings did not sit over the two summits: $centresX", centresX[0] < 12)
        assertTrue("rings did not sit over the two summits: $centresX", centresX[1] > 18)
    }

    @Test
    fun `joining keeps every segment exactly once`() {
        val size = 24
        val grid = FloatArray(size * size) { index ->
            val x = (index % size).toDouble()
            val y = (index / size).toDouble()
            (700.0 + 60.0 * kotlin.math.sin(x / 3.0) * kotlin.math.cos(y / 4.0)).toFloat()
        }
        val segments = ContourGenerator.trace(grid, size, size, 710.0)
        assertTrue(segments.isNotEmpty())

        val paths = ContourGenerator.join(segments)

        // A stroke of n points spans n-1 segments, or n when it closes.
        val spanned = paths.sumOf { if (it.closed) it.points.size else it.points.size - 1 }
        assertEquals(segments.size, spanned)
    }

    @Test
    fun `a contour running through a grid corner breaks rather than guessing`() {
        // Four cells meet at the middle sample, and the level sits exactly on
        // it, so four strand-ends land on the same point. Which two continue
        // each other is genuinely undetermined -- the surface says a saddle is
        // there. Joining any two would invent a line the ground does not have.
        val grid = floatArrayOf(
            0f, 10f, 0f,
            0f, 5f, 0f,
            0f, 10f, 0f,
        )
        val segments = ContourGenerator.trace(grid, 3, 3, 5.0)
        val paths = ContourGenerator.join(segments)

        // All four cells put an end on the middle sample, because the level
        // sits exactly on it and the interpolation runs the whole way to the
        // corner.
        val middle = ContourPoint(1.0, 1.0)
        assertEquals(
            4,
            segments.count {
                ContourPoint(it.x1, it.y1) == middle || ContourPoint(it.x2, it.y2) == middle
            }
        )
        assertEquals("the junction was joined through", 4, paths.size)
        assertTrue(paths.none { it.closed })
        assertTrue(paths.all { it.points.size == 2 })
        // Nothing is dropped on the floor even where the walk gives up.
        val spanned = paths.sumOf { it.points.size - 1 }
        assertEquals(segments.size, spanned)
    }

    @Test
    fun `joining nothing yields nothing`() {
        assertTrue(ContourGenerator.join(emptyList()).isEmpty())
    }

    @Test
    fun `a lone segment survives joining as a two point stroke`() {
        val paths = ContourGenerator.join(listOf(ContourSegment(0.0, 0.0, 1.0, 1.0)))
        assertEquals(1, paths.size)
        assertEquals(2, paths.single().points.size)
        assertFalse(paths.single().closed)
    }

    // ---- helpers ----

    /** Which corner of a single cell a strand cuts off. */
    private fun isolatedCorner(segment: ContourSegment): String {
        val x = (segment.x1 + segment.x2) / 2
        val y = (segment.y1 + segment.y2) / 2
        return when {
            x < 0.5 && y < 0.5 -> TOP_LEFT
            x >= 0.5 && y < 0.5 -> TOP_RIGHT
            x < 0.5 -> BOTTOM_LEFT
            else -> BOTTOM_RIGHT
        }
    }

    /** True when two segments properly cross, which contours must never do. */
    private fun crosses(a: ContourSegment, b: ContourSegment): Boolean {
        fun side(px: Double, py: Double, qx: Double, qy: Double, rx: Double, ry: Double): Double =
            (qx - px) * (ry - py) - (qy - py) * (rx - px)

        val d1 = side(a.x1, a.y1, a.x2, a.y2, b.x1, b.y1)
        val d2 = side(a.x1, a.y1, a.x2, a.y2, b.x2, b.y2)
        val d3 = side(b.x1, b.y1, b.x2, b.y2, a.x1, a.y1)
        val d4 = side(b.x1, b.y1, b.x2, b.y2, a.x2, a.y2)
        if (abs(d1) < 1e-12 || abs(d2) < 1e-12 || abs(d3) < 1e-12 || abs(d4) < 1e-12) return false
        return (d1 > 0) != (d2 > 0) && (d3 > 0) != (d4 > 0)
    }
}
