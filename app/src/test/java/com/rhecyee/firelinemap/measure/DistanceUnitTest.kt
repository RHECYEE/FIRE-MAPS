package com.rhecyee.firelinemap.measure

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The units line and travel are actually reported in.
 *
 * Two different jobs. Travel wants feet until it becomes a drive and miles
 * after; line wants chains, because that is what a crew calls over a radio and
 * what a division break is measured in.
 */
class DistanceUnitTest {

    @Test
    fun aChainIsSixtySixFeet() {
        // The definition, not an approximation of it.
        assertEquals(66.0, DistanceUnit.FEET.from(20.1168), 0.0001)
        assertEquals(1.0, DistanceUnit.CHAINS.from(20.1168), 0.0001)
    }

    @Test
    fun thereAreEightyChainsInAMile() {
        // Which is the reason the unit survives: the arithmetic works in the
        // head. A hundred chains of line is a mile and a quarter.
        val mile = 1609.344
        assertEquals(80.0, DistanceUnit.CHAINS.from(mile), 0.001)
    }

    @Test
    fun travelReadsInFeetUntilItBecomesADrive() {
        assertEquals("500 ft", DistanceUnit.readable(152.4))
        assertEquals("5279 ft", DistanceUnit.readable(5279 / 3.280839895))
    }

    @Test
    fun pastAMileItReadsInMiles() {
        // 8190 ft is a number nobody converts on a radio; 1.55 mi is a drive.
        assertEquals("1.55 mi", DistanceUnit.readable(8190 / 3.280839895))
        assertEquals("1.0 mi", DistanceUnit.readable(1609.344))
    }

    @Test
    fun theSwitchHappensExactlyAtAMile() {
        val justUnder = (DistanceUnit.FEET_PER_MILE - 1) / 3.280839895
        val justOver = (DistanceUnit.FEET_PER_MILE + 1) / 3.280839895
        assertEquals("5279 ft", DistanceUnit.readable(justUnder))
        assertEquals("1.0 mi", DistanceUnit.readable(justOver))
    }

    @Test
    fun lineReadsInChainsWhateverItsLength() {
        // Never switched automatically: chains are what line is reported in,
        // and a crew asking for a figure in chains wants it in chains.
        assertEquals("5.0 ch", DistanceUnit.inChains(100.584))
        assertEquals("80.0 ch", DistanceUnit.inChains(1609.344))
    }
}
