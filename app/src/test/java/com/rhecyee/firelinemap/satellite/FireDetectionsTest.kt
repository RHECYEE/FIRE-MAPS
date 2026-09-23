package com.rhecyee.firelinemap.satellite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class FireDetectionsTest {

    private val edge = DetectionTileRequest.WORLD_EDGE_METERS

    @Test
    fun theWholeWorldIsOneTileAtZoomNought() {
        val (west, south, east, north) = DetectionTileRequest.boundsOf(0, 0, 0).toList()
        assertEquals(-edge, west, 1.0)
        assertEquals(-edge, south, 1.0)
        assertEquals(edge, east, 1.0)
        assertEquals(edge, north, 1.0)
    }

    @Test
    fun theTopLeftTileIsTheNorthWestQuarter() {
        val (west, south, east, north) = DetectionTileRequest.boundsOf(1, 0, 0).toList()
        assertEquals(-edge, west, 1.0)
        assertEquals(0.0, south, 1.0)
        assertEquals(0.0, east, 1.0)
        assertEquals(edge, north, 1.0)
    }

    @Test
    fun neighbouringTilesMeetExactly() {
        // A gap would show as a seam of missing detections down the map, and
        // an overlap would draw the same hotspot twice.
        val here = DetectionTileRequest.boundsOf(7, 20, 49)
        val right = DetectionTileRequest.boundsOf(7, 21, 49)
        val below = DetectionTileRequest.boundsOf(7, 20, 50)

        assertEquals("east edge meets its neighbour's west", here[2], right[0], 1e-6)
        assertEquals("south edge meets the tile below", here[1], below[3], 1e-6)
    }

    @Test
    fun aTileIsSquareOnTheGround() {
        val (west, south, east, north) = DetectionTileRequest.boundsOf(9, 91, 200).toList()
        assertEquals(east - west, north - south, 1e-6)
    }

    @Test
    fun theBoundsMatchARequestKnownToReturnDetections() {
        // z6/10/24 over northern California, checked against the live service:
        // this exact box came back with detections in it. If the arithmetic
        // drifts, this is the test that says so.
        val bounds = DetectionTileRequest.boundsOf(6, 10, 24)
        assertEquals(-13_775_787.0, bounds[0], 1.0)
        assertEquals(4_383_205.0, bounds[1], 1.0)
        assertEquals(-13_149_615.0, bounds[2], 1.0)
        assertEquals(5_009_377.0, bounds[3], 1.0)
    }

    @Test
    fun theRequestNamesTheLayerTheDateAndTheBox() {
        val url = DetectionTileRequest.url(
            SatelliteSource.VIIRS_NOAA20, "2026-09-22", 6, 10, 24
        )
        assertTrue(url.contains("LAYERS=VIIRS_NOAA20_Thermal_Anomalies_375m_All_v2_NRT"))
        assertTrue(url.contains("TIME=2026-09-22"))
        assertTrue(url.contains("CRS=EPSG:3857"))
        assertTrue(url.contains("WIDTH=256"))
        assertTrue(url.contains("TRANSPARENT=true"))
        assertTrue("bbox is west,south,east,north", url.contains("BBOX=-13775786"))
        // No key, no token, no account: the layer has to work for somebody who
        // cannot get onto agency infrastructure.
        assertFalse(url.contains("MAP_KEY", ignoreCase = true))
        assertFalse(url.contains("api_key", ignoreCase = true))
    }

    @Test
    fun everySourceAsksForANearRealTimeLayer() {
        // The science-quality equivalents run years behind. Shipping one of
        // those by accident would put a six-year-old pass on a live fire.
        SatelliteSource.entries.forEach {
            assertTrue("${it.label} is not NRT", it.layerId.endsWith("_NRT"))
        }
    }

    @Test
    fun passesAreDatedInUtcNotLocalTime() {
        // A night shift working past local midnight must not be sent looking
        // for tomorrow's pass, or yesterday's, depending on the time zone.
        val default = TimeZone.getDefault()
        try {
            // 2026-09-22 23:30 UTC. In Los Angeles that is still the 22nd;
            // in Sydney it is already the 23rd. The pass is the 22nd for both.
            val instant = 1_790_724_600_000L
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val west = DetectionTileCache.today(instant)
            TimeZone.setDefault(TimeZone.getTimeZone("Australia/Sydney"))
            val east = DetectionTileCache.today(instant)
            assertEquals(west, east)
        } finally {
            TimeZone.setDefault(default)
        }
    }

    @Test
    fun theCaptionAlwaysNamesThePassAndNeverClaimsToBeLive() {
        val age = DetectionAge("2026-09-22", fetchedAt = null)
        val caption = age.caption(listOf(SatelliteSource.VIIRS_NOAA20))

        assertTrue(caption.contains("2026-09-22"))
        assertTrue(caption.contains("VIIRS NOAA-20"))
        assertFalse(caption.contains("live", ignoreCase = true))
        assertTrue(caption.contains("not yet fetched"))
    }

    @Test
    fun theCaptionSaysHowLongAgoItLoaded() {
        val now = 1_790_724_600_000L
        val threeHoursBefore = now - 3 * 60 * 60 * 1000L
        val age = DetectionAge("2026-09-22", fetchedAt = threeHoursBefore)

        assertEquals(3L, age.hoursSinceFetch(now))
        assertTrue(
            age.caption(SatelliteSource.DEFAULT).contains("${SatelliteSource.DEFAULT.size} satellites")
        )
    }

    @Test
    fun theDefaultSelectionIsTheThreeViirsBirds() {
        // MODIS is off unless asked for: at a kilometre it is most of a
        // division, and it is carried for its overpass times rather than to
        // be read as a location.
        assertEquals(3, SatelliteSource.DEFAULT.size)
        assertFalse(SatelliteSource.MODIS in SatelliteSource.DEFAULT)
        SatelliteSource.DEFAULT.forEach { assertEquals(375, it.resolutionMeters) }
    }

    @Test
    fun sourcesRestoreFromTheirStoredNames() {
        val stored = setOf("VIIRS_NOAA20", "MODIS", "SOMETHING_RETIRED")
        val restored = SatelliteSource.from(stored)
        assertEquals(setOf(SatelliteSource.VIIRS_NOAA20, SatelliteSource.MODIS), restored)
    }
}
