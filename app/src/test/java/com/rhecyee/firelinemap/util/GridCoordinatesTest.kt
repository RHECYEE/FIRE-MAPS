package com.rhecyee.firelinemap.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UTM and MGRS, checked against an independent implementation.
 *
 * The four positions below were converted with pyproj -- PROJ's own transform,
 * which is what QGIS, GDAL and every agency product goes through. Matching it
 * to under a metre is the whole point: a grid read off this phone has to land
 * where the same grid read off a dispatcher's screen lands, or the two of us
 * are talking about different ground and neither of us knows it.
 */
class GridCoordinatesTest {

    /** latitude, longitude, zone, band, easting, northing. */
    private val reference = listOf(
        Reference("Burnt Creek", 45.20575, -117.63700, 11, 'T', 449974.7, 5006004.4),
        Reference("Eagle Butte", 44.99277, -101.24338, 14, 'T', 323165.0, 4984595.5),
        Reference("Glacier", 48.75000, -113.80000, 12, 'U', 294188.5, 5403447.4),
        Reference("Miami", 25.76170, -80.19180, 17, 'R', 581046.9, 2849542.5)
    )

    private data class Reference(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val zone: Int,
        val band: Char,
        val easting: Double,
        val northing: Double
    )

    @Test
    fun everyReferencePositionConvertsToTheGridProjSays() {
        reference.forEach {
            val utm = GridCoordinates.toUtm(it.latitude, it.longitude)
            assertNotNull(it.name, utm)
            assertEquals(it.name, it.zone, utm!!.zone)
            assertEquals(it.name, it.band, utm.band)
            // Half a metre. Anything looser and the metre digit of a
            // ten-figure grid is not trustworthy.
            assertEquals("${it.name} easting", it.easting, utm.easting, 0.5)
            assertEquals("${it.name} northing", it.northing, utm.northing, 0.5)
            assertTrue(utm.northernHemisphere)
        }
    }

    @Test
    fun theGridConvertsBackToWhereItCameFrom() {
        reference.forEach {
            val back = GridCoordinates.fromUtm(it.zone, it.easting, it.northing, true)
            assertNotNull(it.name, back)
            // A millionth of a degree is about a tenth of a metre.
            assertEquals("${it.name} latitude", it.latitude, back!!.first, 1e-6)
            assertEquals("${it.name} longitude", it.longitude, back.second, 1e-6)
        }
    }

    @Test
    fun aPositionSurvivesTheWholeRoundTripAcrossTheCountry() {
        var latitude = 25.0
        while (latitude <= 49.0) {
            var longitude = -124.0
            while (longitude <= -67.0) {
                val utm = GridCoordinates.toUtm(latitude, longitude)!!
                val back = GridCoordinates.fromUtm(
                    utm.zone, utm.easting, utm.northing, utm.northernHemisphere
                )!!
                assertEquals("$latitude $longitude", latitude, back.first, 1e-7)
                assertEquals("$latitude $longitude", longitude, back.second, 1e-7)
                longitude += 3.0
            }
            latitude += 2.0
        }
    }

    @Test
    fun theSouthernHemisphereUsesTheFalseNorthing() {
        // Sydney. South of the equator the northing is measured from ten
        // million rather than from nought, so a position just south of it
        // reads as a very large number rather than a negative one.
        val utm = GridCoordinates.toUtm(-33.8688, 151.2093)!!
        assertEquals(56, utm.zone)
        assertEquals('H', utm.band)
        assertTrue("must be the false northing", utm.northing > 6_000_000.0)
        assertTrue(!utm.northernHemisphere)

        val back = GridCoordinates.fromUtm(utm.zone, utm.easting, utm.northing, false)!!
        assertEquals(-33.8688, back.first, 1e-6)
        assertEquals(151.2093, back.second, 1e-6)
    }

    @Test
    fun zonesFallWhereTheStandardPutsThem() {
        assertEquals(1, GridCoordinates.zoneFor(0.0, -180.0))
        assertEquals(1, GridCoordinates.zoneFor(0.0, -177.0))
        assertEquals(31, GridCoordinates.zoneFor(0.0, 0.0))
        assertEquals(60, GridCoordinates.zoneFor(0.0, 179.9))
        // The two exceptions the standard carries. Nowhere near a fire we will
        // ever work, but a grid printed here has to match a grid printed
        // anywhere else or the format is not the format.
        assertEquals("south-west Norway widens 32", 32, GridCoordinates.zoneFor(60.0, 5.0))
        assertEquals("Svalbard rearranges", 33, GridCoordinates.zoneFor(78.0, 15.0))
        assertEquals("Svalbard rearranges", 35, GridCoordinates.zoneFor(78.0, 25.0))
    }

    @Test
    fun bandsAreEightDegreesEachAndSkipTheAmbiguousLetters() {
        assertEquals('C', GridCoordinates.bandFor(-80.0))
        assertEquals('N', GridCoordinates.bandFor(0.0))
        assertEquals('T', GridCoordinates.bandFor(45.0))
        assertEquals('U', GridCoordinates.bandFor(48.0))
        // I and O read as one and nought over a radio, which is why they are
        // not in the alphabet at all.
        assertTrue('I' !in GridCoordinates.BANDS)
        assertTrue('O' !in GridCoordinates.BANDS)
    }

    @Test
    fun aGridReferenceRoundTripsAtEveryPrecision() {
        reference.forEach { spot ->
            for (digits in 1..5) {
                val mgrs = GridCoordinates.toMgrs(spot.latitude, spot.longitude, digits)
                assertNotNull("${spot.name} at $digits", mgrs)
                val back = GridCoordinates.fromMgrs(mgrs!!)
                assertNotNull("${spot.name} at $digits: $mgrs", back)
                // A reference is the south-west corner of its square, so the
                // answer is within one square rather than exact. One digit is
                // a ten kilometre square; five is a metre.
                val squareMeters = Math.pow(10.0, (5 - digits).toDouble())
                val off = distanceMeters(
                    spot.latitude, spot.longitude, back!!.first, back.second
                )
                assertTrue(
                    "${spot.name} at $digits digits landed $off m away from $mgrs",
                    off <= squareMeters * 1.5 + 1.0
                )
            }
        }
    }

    @Test
    fun aFullReferenceReadsAsTheZoneBandAndSquareItShould() {
        // Burnt Creek. Zone and band come from the position; the square letters
        // come from which hundred-kilometre block of the zone it is in.
        val mgrs = GridCoordinates.toMgrs(45.20575, -117.63700, 5)!!
        assertTrue("was $mgrs", mgrs.startsWith("11T"))
        assertEquals("zone, band, two square letters, ten digits", 15, mgrs.length)
        assertTrue(mgrs.substring(3, 5).all { it.isLetter() })
        assertTrue(mgrs.substring(5).all { it.isDigit() })
    }

    @Test
    fun spacesInAReferenceAreIgnoredBecauseThatIsHowItIsWritten() {
        val tight = GridCoordinates.fromMgrs("11TNL4997506004")
        val spaced = GridCoordinates.fromMgrs("11T NL 49975 06004")
        val lower = GridCoordinates.fromMgrs("11t nl 49975 06004")
        assertNotNull(tight)
        assertEquals(tight!!.first, spaced!!.first, 1e-9)
        assertEquals(tight.second, spaced.second, 1e-9)
        assertEquals(tight.first, lower!!.first, 1e-9)
    }

    /**
     * The band is not decoration.
     *
     * Row letters repeat every two million metres, so without the band a
     * reference could be any of five places up a column two thousand kilometres
     * long. This is the check that the band actually resolves it.
     */
    @Test
    fun theBandDecidesWhichRepetitionOfTheRowLettersIsMeant() {
        reference.forEach { spot ->
            val mgrs = GridCoordinates.toMgrs(spot.latitude, spot.longitude, 5)!!
            val back = GridCoordinates.fromMgrs(mgrs)!!
            assertEquals("${spot.name} from $mgrs", spot.latitude, back.first, 1e-4)
            assertEquals("${spot.name} from $mgrs", spot.longitude, back.second, 1e-4)
        }
    }

    @Test
    fun everySquareInAWorkingAreaResolvesToItself() {
        // A grid walked across two zones and two bands, which is where the
        // column offset and the row offset both change.
        var latitude = 42.0
        while (latitude <= 49.0) {
            var longitude = -120.0
            while (longitude <= -108.0) {
                val mgrs = GridCoordinates.toMgrs(latitude, longitude, 5)
                assertNotNull("$latitude $longitude", mgrs)
                val back = GridCoordinates.fromMgrs(mgrs!!)
                assertNotNull("$mgrs", back)
                val off = distanceMeters(latitude, longitude, back!!.first, back.second)
                assertTrue("$mgrs landed $off m out", off < 2.0)
                longitude += 0.7
            }
            latitude += 0.5
        }
    }

    @Test
    fun rubbishIsRefusedRatherThanGuessedAt() {
        assertNull(GridCoordinates.fromMgrs(""))
        assertNull(GridCoordinates.fromMgrs("hello"))
        assertNull(GridCoordinates.fromMgrs("99TNL4997506004"))
        // I is not a band letter.
        assertNull(GridCoordinates.fromMgrs("11INL4997506004"))
        // An odd number of digits cannot be split between the two axes.
        assertNull(GridCoordinates.fromMgrs("11TNL499750600"))
        assertNull(GridCoordinates.toUtm(Double.NaN, -117.0))
        assertNull(GridCoordinates.toUtm(45.0, Double.NaN))
        // The grid does not cover the poles; UPS does, and this app never
        // goes there.
        assertNull(GridCoordinates.toUtm(86.0, -117.0))
        assertNull(GridCoordinates.toUtm(-85.0, -117.0))
        assertNull(GridCoordinates.fromUtm(0, 500_000.0, 5_000_000.0, true))
        assertNull(GridCoordinates.fromUtm(11, Double.NaN, 5_000_000.0, true))
    }

    @Test
    fun aGridReadsOutTheWayItGoesOverARadio() {
        val utm = GridCoordinates.toUtm(45.20575, -117.63700)!!
        assertEquals("11T 449974 5006004", utm.format())
    }

    private fun distanceMeters(
        aLat: Double,
        aLon: Double,
        bLat: Double,
        bLon: Double
    ): Double {
        val meanLatitude = Math.toRadians((aLat + bLat) / 2.0)
        val north = (bLat - aLat) * 111_320.0
        val east = (bLon - aLon) * 111_320.0 * Math.cos(meanLatitude)
        return Math.hypot(north, east)
    }
}
