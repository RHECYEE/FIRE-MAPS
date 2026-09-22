package com.rhecyee.firelinemap.terrain

import com.rhecyee.firelinemap.map.Earth
import com.rhecyee.firelinemap.map.ElevationGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class TerrainShadingTest {

    /**
     * A grid whose cells are [cellMeters] square on the ground at
     * [centreLatitude], so a slope written into it comes back as itself.
     */
    private fun grid(
        width: Int = 9,
        height: Int = 9,
        centreLatitude: Double = 0.0,
        cellMeters: Double = 30.0,
        coverage: Float = 1f,
        elevation: (row: Int, column: Int) -> Float
    ): ElevationGrid {
        val degreesNorth = cellMeters / Earth.METERS_PER_DEGREE
        val degreesEast =
            cellMeters / (Earth.METERS_PER_DEGREE * cos(Math.toRadians(centreLatitude)))
        val values = FloatArray(width * height) { index ->
            elevation(index / width, index % width)
        }
        return ElevationGrid(
            values = values,
            width = width,
            height = height,
            north = centreLatitude + degreesNorth * (height - 1) / 2.0,
            south = centreLatitude - degreesNorth * (height - 1) / 2.0,
            west = -degreesEast * (width - 1) / 2.0,
            east = degreesEast * (width - 1) / 2.0,
            zoom = 14,
            coverage = coverage
        )
    }

    /** A plane descending [gradePercent] towards the east. */
    private fun eastwardSlope(gradePercent: Double, cellMeters: Double = 30.0) =
        grid(cellMeters = cellMeters) { _, column ->
            (1_000.0 - gradePercent / 100.0 * column * cellMeters).toFloat()
        }

    private fun ElevationGrid.centrePixel(options: ShadingOptions): Int {
        val shaded = TerrainShading.render(this, options)
        assertNotNull("nothing rendered", shaded)
        return shaded!!.pixels[(height / 2) * width + width / 2]
    }

    private fun alphaOf(pixel: Int) = (pixel ushr 24) and 0xFF
    private fun redOf(pixel: Int) = (pixel ushr 16) and 0xFF

    private val classesOnly =
        ShadingOptions(hillshade = false, slopeClasses = true)
    private val reliefOnly =
        ShadingOptions(hillshade = true, slopeClasses = false)

    // ---- slope ------------------------------------------------------------

    @Test
    fun aKnownGradeComesBackAsItself() {
        for (grade in listOf(10.0, 25.0, 50.0, 80.0, 120.0)) {
            val measured = TerrainShading.slopePercentAt(eastwardSlope(grade), 4, 4)
            assertNotNull(measured)
            assertEquals("grade $grade", grade, measured!!, 0.5)
        }
    }

    @Test
    fun theBandsSplitWhereTheySayTheyDo() {
        assertEquals(SlopeClass.GENTLE, SlopeClass.of(0.0))
        assertEquals(SlopeClass.GENTLE, SlopeClass.of(24.9))
        assertEquals(SlopeClass.MODERATE, SlopeClass.of(25.0))
        assertEquals(SlopeClass.MODERATE, SlopeClass.of(39.9))
        assertEquals(SlopeClass.STEEP, SlopeClass.of(40.0))
        assertEquals(SlopeClass.VERY_STEEP, SlopeClass.of(55.0))
        assertEquals(SlopeClass.EXTREME, SlopeClass.of(75.0))
        assertEquals(SlopeClass.EXTREME, SlopeClass.of(400.0))
    }

    @Test
    fun gentleGroundIsLeftAlone() {
        // The whole point of not tinting the bottom band: a map of mostly
        // gentle ground must not come back with a wash over all of it.
        val flat = grid { _, _ -> 1_000f }
        assertEquals(0, alphaOf(flat.centrePixel(classesOnly)))

        val gentle = eastwardSlope(15.0)
        assertEquals(0, alphaOf(gentle.centrePixel(classesOnly)))
    }

    @Test
    fun aSteepSlopeIsTintedWithItsOwnBand() {
        val pixel = eastwardSlope(60.0).centrePixel(classesOnly)
        assertEquals(SlopeClass.VERY_STEEP.colorArgb, pixel)
    }

    @Test
    fun theTintDeepensAsTheGroundSteepens() {
        val bands = listOf(30.0, 45.0, 65.0, 100.0).map {
            alphaOf(eastwardSlope(it).centrePixel(classesOnly))
        }
        assertEquals(bands.sorted(), bands)
        assertTrue("every band above gentle is tinted", bands.all { it > 0 })
    }

    // ---- relief -----------------------------------------------------------

    @Test
    fun theSunLightsOneSideAndNotTheOther() {
        // Sun sits in the northwest, so ground facing east is in shadow and
        // ground facing west is lit. Getting this backwards inverts every
        // ridge on the map into a gully.
        val facingEast = eastwardSlope(50.0).centrePixel(reliefOnly)
        val facingWest = eastwardSlope(-50.0).centrePixel(reliefOnly)

        assertTrue("east face should be shadowed", redOf(facingEast) < 64)
        assertTrue("east face should be drawn", alphaOf(facingEast) > 20)
        assertTrue("west face should be lit", redOf(facingWest) > 192)
    }

    @Test
    fun flatGroundCastsNoRelief() {
        val flat = grid { _, _ -> 1_000f }
        assertEquals(0, alphaOf(flat.centrePixel(reliefOnly)))
    }

    @Test
    fun steeperGroundCastsADeeperShadow() {
        val gentle = alphaOf(eastwardSlope(20.0).centrePixel(reliefOnly))
        val severe = alphaOf(eastwardSlope(90.0).centrePixel(reliefOnly))
        assertTrue("$severe should exceed $gentle", severe > gentle)
    }

    // ---- the two together -------------------------------------------------

    @Test
    fun aTintedBandStillShowsItsRelief() {
        val both = ShadingOptions(hillshade = true, slopeClasses = true)
        val shadowed = eastwardSlope(60.0).centrePixel(both)
        val lit = eastwardSlope(-60.0).centrePixel(both)

        // Same band, same tint, so if the relief were painted out by the tint
        // these would be identical.
        assertEquals(SlopeClass.VERY_STEEP, SlopeClass.of(60.0))
        assertTrue("relief must survive the tint", shadowed != lit)
        assertTrue("shadowed side is darker", redOf(shadowed) < redOf(lit))
    }

    // ---- the things that go wrong ----------------------------------------

    @Test
    fun groundThatHasNotArrivedIsNotShaded() {
        val partial = grid { row, column ->
            if (column > 5) Float.NaN else (1_000.0 - 0.6 * column * 30.0).toFloat()
        }
        val shaded = TerrainShading.render(partial, classesOnly, minimumCoverage = 0f)
        assertNotNull(shaded)
        // A sample with an unknown neighbour is left clear rather than being
        // shaded as though it were flat.
        assertEquals(0, alphaOf(shaded!!.pixels[4 * partial.width + 7]))
        assertTrue(alphaOf(shaded.pixels[4 * partial.width + 2]) > 0)
    }

    @Test
    fun aWindowThatIsMostlyMissingIsNotDrawnAtAll() {
        val sparse = grid(coverage = 0.2f) { _, column ->
            (1_000.0 - 0.6 * column * 30.0).toFloat()
        }
        assertNull(TerrainShading.render(sparse, classesOnly))
    }

    @Test
    fun nothingSwitchedOnRendersNothing() {
        val any = eastwardSlope(60.0)
        assertNull(
            TerrainShading.render(
                any, ShadingOptions(hillshade = false, slopeClasses = false)
            )
        )
    }

    @Test
    fun aDegreeOfLongitudeIsShorterUpNorth() {
        // The same drop across the same span of degrees is a steeper slope at
        // latitude than at the equator, because the ground under it is
        // narrower. Assuming otherwise flattens every slope on a northern map
        // by the cosine of where it is.
        val degrees = 0.002
        fun atLatitude(latitude: Double): Double {
            val values = FloatArray(81) { index ->
                (1_000.0 - 8.0 * (index % 9)).toFloat()
            }
            val halfNorth = degrees * 4
            return TerrainShading.slopePercentAt(
                ElevationGrid(
                    values = values, width = 9, height = 9,
                    north = latitude + halfNorth, south = latitude - halfNorth,
                    west = -degrees * 4, east = degrees * 4,
                    zoom = 14, coverage = 1f
                ),
                4, 4
            )!!
        }

        val equator = atLatitude(0.0)
        val northern = atLatitude(60.0)
        // cos(60) is a half, so the east-west run halves and the grade doubles.
        assertEquals(2.0, northern / equator, 0.05)
    }
}
