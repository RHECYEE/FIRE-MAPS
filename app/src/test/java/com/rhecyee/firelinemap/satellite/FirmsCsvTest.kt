package com.rhecyee.firelinemap.satellite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmsCsvTest {

    /** VIIRS: letter confidence, bright_ti4/ti5, version carries the level. */
    private val viirs = """
        country_id,latitude,longitude,bright_ti4,scan,track,acq_date,acq_time,satellite,instrument,confidence,version,bright_ti5,frp,daynight
        USA,40.12345,-121.54321,340.5,0.42,0.39,2026-09-23,2112,N20,VIIRS,h,2.0URT,295.1,17.6,N
        USA,40.13000,-121.55000,320.1,0.42,0.39,2026-09-23,2112,N20,VIIRS,n,2.0NRT,290.4,4.2,N
        USA,40.14000,-121.56000,300.0,0.42,0.39,2026-09-23,0930,N20,VIIRS,l,2.0RT,288.0,0.9,D
    """.trimIndent()

    /** MODIS: a different schema for the same request. Confidence is 0-100. */
    private val modis = """
        latitude,longitude,brightness,scan,track,acq_date,acq_time,satellite,instrument,confidence,version,bright_t31,frp,daynight
        40.5,-121.2,330.1,1.1,1.0,2026-09-23,1845,Terra,MODIS,92,6.1NRT,295.0,25.4,D
        40.6,-121.3,310.0,1.1,1.0,2026-09-23,1845,Aqua,MODIS,45,6.1NRT,291.0,6.1,D
        40.7,-121.4,305.0,1.1,1.0,2026-09-23,1845,Aqua,MODIS,12,6.1NRT,289.0,1.0,D
    """.trimIndent()

    @Test
    fun readsViirsRows() {
        val detections = FirmsCsv.parse(viirs)
        assertEquals(3, detections.size)

        val first = detections.first()
        assertEquals(40.12345, first.latitude, 1e-6)
        assertEquals(-121.54321, first.longitude, 1e-6)
        assertEquals("2026-09-23", first.date)
        assertEquals("21:12Z", first.clock)
        assertEquals(DetectionConfidence.HIGH, first.confidence)
        assertEquals(17.6, first.powerMegawatts!!, 1e-6)
        assertEquals("N20", first.satellite)
        assertEquals(false, first.daytime)
    }

    @Test
    fun readsModisEvenThoughItsColumnsDiffer() {
        // Same request, different schema. Reading by position rather than by
        // name would have put brightness into the confidence field here.
        val detections = FirmsCsv.parse(modis)
        assertEquals(3, detections.size)
        assertEquals(40.5, detections[0].latitude, 1e-6)
        assertEquals("18:45Z", detections[0].clock)
        assertEquals(25.4, detections[0].powerMegawatts!!, 1e-6)
        assertEquals(true, detections[0].daytime)
    }

    @Test
    fun bothConfidenceEncodingsLandOnTheSameScale() {
        val letters = FirmsCsv.parse(viirs).map { it.confidence }
        assertEquals(
            listOf(
                DetectionConfidence.HIGH,
                DetectionConfidence.NOMINAL,
                DetectionConfidence.LOW
            ),
            letters
        )
        // 92, 45 and 12 out of a hundred have to grade the same way, or a
        // filter set to "nominal and above" would mean two different things
        // depending on which satellite saw the fire.
        val numbers = FirmsCsv.parse(modis).map { it.confidence }
        assertEquals(
            listOf(
                DetectionConfidence.HIGH,
                DetectionConfidence.NOMINAL,
                DetectionConfidence.LOW
            ),
            numbers
        )
    }

    @Test
    fun theProcessingLevelComesOutOfTheVersionString() {
        val levels = FirmsCsv.parse(viirs).map { it.level }
        assertEquals(
            listOf(
                ProcessingLevel.ULTRA_REAL_TIME,
                ProcessingLevel.NEAR_REAL_TIME,
                ProcessingLevel.REAL_TIME
            ),
            levels
        )
    }

    @Test
    fun nrtIsNotMistakenForRt() {
        // "NRT" contains "RT". Matching naively would file every processed
        // detection as a minute-old one, which is the wrong way round to be
        // wrong about.
        assertEquals(ProcessingLevel.NEAR_REAL_TIME, ProcessingLevel.parse("2.0NRT"))
        assertEquals(ProcessingLevel.REAL_TIME, ProcessingLevel.parse("2.0RT"))
        assertEquals(ProcessingLevel.ULTRA_REAL_TIME, ProcessingLevel.parse("2.0URT"))
        assertEquals(ProcessingLevel.STANDARD, ProcessingLevel.parse("2.0"))
        assertEquals(ProcessingLevel.UNKNOWN, ProcessingLevel.parse(null))
    }

    @Test
    fun anAbsentConfidenceIsNeverQuietlyUpgraded() {
        assertEquals(DetectionConfidence.UNKNOWN, DetectionConfidence.parse(null))
        assertEquals(DetectionConfidence.UNKNOWN, DetectionConfidence.parse(""))
        assertEquals(DetectionConfidence.UNKNOWN, DetectionConfidence.parse("   "))
        assertEquals(DetectionConfidence.UNKNOWN, DetectionConfidence.parse("rubbish"))
    }

    @Test
    fun rowsThatWouldPutAFireInTheWrongPlaceAreDropped() {
        val broken = """
            latitude,longitude,acq_date,acq_time,confidence,version
            ,-121.5,2026-09-23,2112,h,2.0NRT
            40.1,,2026-09-23,2112,h,2.0NRT
            notanumber,-121.5,2026-09-23,2112,h,2.0NRT
            95.0,-121.5,2026-09-23,2112,h,2.0NRT
            40.1,-200.0,2026-09-23,2112,h,2.0NRT
            40.1,-121.5,2026-09-23,2112,h,2.0NRT
        """.trimIndent()
        val detections = FirmsCsv.parse(broken)
        assertEquals("only the last row is usable", 1, detections.size)
        assertEquals(40.1, detections.first().latitude, 1e-6)
    }

    @Test
    fun aBodyWithNoRowsIsNotAnError() {
        // A quiet day is a real answer, and the commonest one.
        val headerOnly = "latitude,longitude,acq_date,acq_time,confidence,version"
        assertEquals(0, FirmsCsv.parse(headerOnly).size)
        assertEquals(0, FirmsCsv.parse("").size)
        assertEquals(0, FirmsCsv.parse("\n\n").size)
    }

    @Test
    fun anErrorBodyDoesNotParseAsDetections() {
        // What the service actually returns for a bad key, verified against it.
        assertEquals(0, FirmsCsv.parse("Invalid MAP_KEY.").size)
        assertEquals(0, FirmsCsv.parse("Invalid source.").size)
    }

    @Test
    fun missingColumnsLeaveFieldsAbsentRatherThanWrong() {
        val sparse = """
            latitude,longitude,acq_date
            40.1,-121.5,2026-09-23
        """.trimIndent()
        val one = FirmsCsv.parse(sparse).single()
        assertNull(one.timeUtc)
        assertNull(one.clock)
        assertNull(one.powerMegawatts)
        assertNull(one.daytime)
        assertEquals(DetectionConfidence.UNKNOWN, one.confidence)
        assertEquals(ProcessingLevel.UNKNOWN, one.level)
    }

    @Test
    fun theSummaryCarriesTheAgeAndTheCaveat() {
        val one = FirmsCsv.parse(viirs).first()
        val summary = one.summary()
        assertTrue(summary.contains("2026-09-23"))
        assertTrue(summary.contains("21:12Z"))
        assertTrue(summary.contains("High"))
        assertTrue(summary.contains("URT"))
    }

    @Test
    fun aPassTimeBeforeTenHundredKeepsItsLeadingZero() {
        // 0930 arrives as "930" from some exports. Dropping the pad would read
        // it as 93:0 and lose an hour of the night.
        val detections = FirmsCsv.parse(
            """
            latitude,longitude,acq_date,acq_time
            40.1,-121.5,2026-09-23,930
            """.trimIndent()
        )
        assertEquals("09:30Z", detections.single().clock)
    }

    @Test
    fun quotedCellsDoNotSplitARow() {
        val quoted = """
            latitude,longitude,acq_date,satellite
            40.1,-121.5,2026-09-23,"NOAA-20, primary"
        """.trimIndent()
        val one = FirmsCsv.parse(quoted).single()
        assertNotNull(one.satellite)
        assertEquals("NOAA-20, primary", one.satellite)
    }
}
