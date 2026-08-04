package com.rhecyee.firelinemap.terrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrainMathTest {

    /** Ten metres between samples, which is 3DEP's usual resolution. */
    private val cell = 10.0

    /** Builds a 3x3 window, north row first. */
    private fun grid(vararg values: Double) = values

    @Test
    fun flatGroundHasNoSlopeAndNoAspect() {
        val flat = grid(
            1000.0, 1000.0, 1000.0,
            1000.0, 1000.0, 1000.0,
            1000.0, 1000.0, 1000.0
        )
        assertEquals(0.0, TerrainMath.slopePercent(flat, cell)!!, 1e-9)
        assertNull("flat ground faces nowhere", TerrainMath.aspectDegrees(flat, cell))
        assertEquals(Aspect.FLAT, TerrainMath.reading(flat, cell)!!.aspect)
    }

    @Test
    fun aFortyFiveDegreePitchIsOneHundredPercent() {
        // Ten metres of rise over ten metres of run, east to west.
        val pitch = grid(
            1010.0, 1000.0, 990.0,
            1010.0, 1000.0, 990.0,
            1010.0, 1000.0, 990.0
        )
        val slope = TerrainMath.slopePercent(pitch, cell)!!
        assertEquals(100.0, slope, 0.5)
        assertEquals(45.0, TerrainMath.reading(pitch, cell)!!.slopeDegrees!!, 0.5)
    }

    @Test
    fun groundFallingWestFacesWest() {
        // High on the east, low on the west: downhill is west.
        val slope = grid(
            990.0, 1000.0, 1010.0,
            990.0, 1000.0, 1010.0,
            990.0, 1000.0, 1010.0
        )
        assertEquals(Aspect.WEST, TerrainMath.reading(slope, cell)!!.aspect)
    }

    @Test
    fun groundFallingSouthFacesSouth() {
        // High on the north row, low on the south row.
        val slope = grid(
            1010.0, 1010.0, 1010.0,
            1000.0, 1000.0, 1000.0,
            990.0, 990.0, 990.0
        )
        assertEquals(Aspect.SOUTH, TerrainMath.reading(slope, cell)!!.aspect)
    }

    @Test
    fun groundFallingNorthFacesNorth() {
        val slope = grid(
            990.0, 990.0, 990.0,
            1000.0, 1000.0, 1000.0,
            1010.0, 1010.0, 1010.0
        )
        assertEquals(Aspect.NORTH, TerrainMath.reading(slope, cell)!!.aspect)
    }

    @Test
    fun groundFallingOnADiagonalFacesThatWay() {
        // High in the northwest, low in the southeast.
        val southeast = grid(
            1020.0, 1010.0, 1000.0,
            1010.0, 1000.0, 990.0,
            1000.0, 990.0, 980.0
        )
        assertEquals(Aspect.SOUTHEAST, TerrainMath.reading(southeast, cell)!!.aspect)

        // High in the northeast, low in the southwest.
        val southwest = grid(
            1000.0, 1010.0, 1020.0,
            990.0, 1000.0, 1010.0,
            980.0, 990.0, 1000.0
        )
        assertEquals(Aspect.SOUTHWEST, TerrainMath.reading(southwest, cell)!!.aspect)
    }

    @Test
    fun aGentleSlopeIsGentle() {
        // One metre over ten is ten percent.
        val gentle = grid(
            1001.0, 1000.0, 999.0,
            1001.0, 1000.0, 999.0,
            1001.0, 1000.0, 999.0
        )
        assertEquals(10.0, TerrainMath.slopePercent(gentle, cell)!!, 0.2)
    }

    @Test
    fun aCoarserCellGivesAGentlerSlopeForTheSameRise() {
        val pitch = grid(
            1010.0, 1000.0, 990.0,
            1010.0, 1000.0, 990.0,
            1010.0, 1000.0, 990.0
        )
        val fine = TerrainMath.slopePercent(pitch, 10.0)!!
        val coarse = TerrainMath.slopePercent(pitch, 30.0)!!
        assertTrue("30 m sampling should read gentler", coarse < fine)
        assertEquals(fine / 3.0, coarse, 0.5)
    }

    @Test
    fun missingSamplesGiveNoAnswerRatherThanAWrongOne() {
        val holed = grid(
            1000.0, Double.NaN, 1000.0,
            1000.0, 1000.0, 1000.0,
            1000.0, 1000.0, 1000.0
        )
        assertNull(TerrainMath.slopePercent(holed, cell))
        assertNull(TerrainMath.aspectDegrees(holed, cell))

        // The centre sample is still good, so the elevation is still worth
        // reporting; it is the slope that cannot be known.
        val reading = TerrainMath.reading(holed, cell)
        assertNotNull(reading)
        assertEquals(1000.0, reading!!.elevationMeters, 1e-9)
        assertNull(reading.slopePercent)
        assertEquals(Aspect.FLAT, reading.aspect)

        // A missing centre leaves nothing to report at all.
        val hollow = holed.copyOf().also { it[4] = Double.NaN }
        assertNull(TerrainMath.reading(hollow, cell))
    }

    @Test
    fun aMalformedWindowIsRejected() {
        assertNull(TerrainMath.slopePercent(doubleArrayOf(1.0, 2.0), cell))
        assertNull(TerrainMath.slopePercent(DoubleArray(9), 0.0))
    }

    @Test
    fun terrainRgbDecodesToElevation() {
        // The encoding's zero point is 10 km below sea level.
        assertEquals(-10_000.0, TerrainMath.decodeTerrainRgb(0, 0, 0), 1e-9)
        // One step of blue is a tenth of a metre.
        assertEquals(-9_999.9, TerrainMath.decodeTerrainRgb(0, 0, 1), 1e-9)
        // A realistic fireline elevation round-trips.
        val metres = 1417.0
        val encoded = ((metres + 10_000.0) / 0.1).toInt()
        val decoded = TerrainMath.decodeTerrainRgb(
            (encoded shr 16) and 0xFF, (encoded shr 8) and 0xFF, encoded and 0xFF
        )
        assertEquals(metres, decoded, 0.05)
    }

    @Test
    fun theSummaryReadsTheWayItWouldBeSaid() {
        val reading = TerrainReading(
            elevationMeters = 2085.4, slopePercent = 31.0, aspectDegrees = 225.0
        )
        val summary = reading.summary()
        assertTrue("was: $summary", summary.contains("6,842 ft"))
        assertTrue("was: $summary", summary.contains("31%"))
        assertTrue("was: $summary", summary.contains("SW"))
    }

    @Test
    fun flatGroundIsNotGivenAFalseAspectInTheSummary() {
        val summary = TerrainReading(1000.0, 0.0, null).summary()
        assertTrue(summary.contains("ft"))
        assertTrue("flat ground should not claim a direction", !summary.contains("·  "))
        assertTrue(!summary.contains("N ·"))
    }

    @Test
    fun gainAndLossIgnoreNoise() {
        // A flat road, with the receiver wandering a metre either way.
        val noisy = listOf(1000.0, 1001.0, 999.5, 1000.5, 999.0, 1000.0)
        val (gain, loss) = TerrainMath.gainAndLoss(noisy)
        assertEquals("noise must not become climb", 0.0, gain, 0.001)
        assertEquals(0.0, loss, 0.001)
    }

    @Test
    fun gainAndLossFollowRealGround() {
        val climb = listOf(1000.0, 1050.0, 1100.0, 1080.0, 1120.0)
        val (gain, loss) = TerrainMath.gainAndLoss(climb)
        assertEquals(140.0, gain, 0.001)
        assertEquals(20.0, loss, 0.001)
    }

    @Test
    fun aspectBucketsCoverTheWholeCompass() {
        assertEquals(Aspect.NORTH, Aspect.fromDegrees(0.0))
        assertEquals(Aspect.NORTHEAST, Aspect.fromDegrees(45.0))
        assertEquals(Aspect.EAST, Aspect.fromDegrees(90.0))
        assertEquals(Aspect.SOUTHEAST, Aspect.fromDegrees(135.0))
        assertEquals(Aspect.SOUTH, Aspect.fromDegrees(180.0))
        assertEquals(Aspect.SOUTHWEST, Aspect.fromDegrees(225.0))
        assertEquals(Aspect.WEST, Aspect.fromDegrees(270.0))
        assertEquals(Aspect.NORTHWEST, Aspect.fromDegrees(315.0))
        // Wraps rather than falling off either end.
        assertEquals(Aspect.NORTH, Aspect.fromDegrees(359.9))
        assertEquals(Aspect.NORTH, Aspect.fromDegrees(720.0))
        assertEquals(Aspect.NORTH, Aspect.fromDegrees(-5.0))
        assertEquals(Aspect.FLAT, Aspect.fromDegrees(null))
    }

    @Test
    fun elevationConvertsToFeet() {
        assertEquals(3280.84, TerrainReading(1000.0, null, null).elevationFeet, 0.01)
    }
}
