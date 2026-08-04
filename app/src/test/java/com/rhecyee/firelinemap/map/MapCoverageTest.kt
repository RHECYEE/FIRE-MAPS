package com.rhecyee.firelinemap.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCoverageTest {

    /** Roughly a Burnt Creek sized operational area in the Wallowa-Whitman. */
    private val incidentMap = GeoBounds(
        south = 45.10,
        west = -117.75,
        north = 45.32,
        east = -117.50
    )

    private fun terrain(
        bounds: GeoBounds,
        maxZoom: Int,
        complete: Boolean = true
    ) = TerrainRegion("region", bounds, minZoom = 0, maxZoom = maxZoom, complete = complete)

    @Test
    fun positionInsideActiveMapIsOnMap() {
        val status = MapCoverage.resolve(45.20575, -117.6370, incidentMap, emptyList())

        assertEquals(IncidentMapCoverage.ON_MAP, status.incident)
        assertFalse(status.needsOffMapWarning)
        assertNull(status.metersOffMap)
    }

    @Test
    fun positionOutsideActiveMapWarnsWithDistanceAndBearingBack() {
        // Due north of the map's northern edge.
        val status = MapCoverage.resolve(45.42, -117.60, incidentMap, emptyList())

        assertEquals(IncidentMapCoverage.OFF_MAP, status.incident)
        assertTrue(status.needsOffMapWarning)

        // 0.10 degrees of latitude is about 11.1 km.
        val meters = requireNonNull(status.metersOffMap)
        assertEquals(11_120.0, meters, 100.0)

        // The way back onto the map is due south.
        assertEquals(180.0, requireNonNull(status.bearingToMapDegrees), 0.5)
    }

    @Test
    fun offMapDistanceMeasuresToNearestCornerWhenDiagonal() {
        // Northeast of the map corner, so the nearest point is the corner itself.
        val status = MapCoverage.resolve(45.40, -117.40, incidentMap, emptyList())

        assertEquals(IncidentMapCoverage.OFF_MAP, status.incident)
        val expected = MapCoverage.distanceMeters(45.40, -117.40, 45.32, -117.50)
        assertEquals(expected, requireNonNull(status.metersOffMap), 0.001)

        // Heading back is toward the southwest.
        val bearing = requireNonNull(status.bearingToMapDegrees)
        assertTrue("expected a southwesterly bearing, got $bearing", bearing in 180.0..270.0)
    }

    @Test
    fun noActiveMapIsDistinctFromBeingOffTheMap() {
        val status = MapCoverage.resolve(45.20575, -117.6370, null, emptyList())

        assertEquals(IncidentMapCoverage.NO_ACTIVE_MAP, status.incident)
        // Nothing to be off of, so no misleading "get back to the map" prompt.
        assertFalse(status.needsOffMapWarning)
        assertNull(status.bearingToMapDegrees)
    }

    @Test
    fun terrainIsDetailedWhenAPreloadedRegionCoversThePosition() {
        val regions = listOf(terrain(GeoBounds(45.0, -118.0, 45.5, -117.0), maxZoom = 14))

        val status = MapCoverage.resolve(45.42, -117.60, incidentMap, regions)

        // Off the incident map, but the operator still gets terrain underneath.
        assertEquals(IncidentMapCoverage.OFF_MAP, status.incident)
        assertEquals(TerrainCoverage.DETAILED, status.terrain)
    }

    @Test
    fun lowZoomRegionIsOrientationOnly() {
        val regions = listOf(terrain(GeoBounds(44.0, -120.0, 46.0, -116.0), maxZoom = 8))

        val status = MapCoverage.resolve(45.42, -117.60, incidentMap, regions)

        assertEquals(TerrainCoverage.COARSE, status.terrain)
    }

    @Test
    fun detailedRegionWinsOverOverlappingCoarseRegion() {
        val regions = listOf(
            terrain(GeoBounds(44.0, -120.0, 46.0, -116.0), maxZoom = 8),
            terrain(GeoBounds(45.0, -118.0, 45.5, -117.0), maxZoom = 15)
        )

        val status = MapCoverage.resolve(45.42, -117.60, incidentMap, regions)

        assertEquals(TerrainCoverage.DETAILED, status.terrain)
    }

    @Test
    fun incompleteRegionIsNeverTreatedAsCoverage() {
        val regions = listOf(
            terrain(GeoBounds(45.0, -118.0, 45.5, -117.0), maxZoom = 14, complete = false)
        )

        val status = MapCoverage.resolve(45.42, -117.60, incidentMap, regions)

        // A half-finished download must not read as "you have a map here".
        assertEquals(TerrainCoverage.NONE, status.terrain)
    }

    @Test
    fun positionOutsideEveryPreloadedRegionHasNoTerrain() {
        val regions = listOf(terrain(GeoBounds(45.0, -118.0, 45.5, -117.0), maxZoom = 14))

        val status = MapCoverage.resolve(43.0, -120.0, incidentMap, regions)

        assertEquals(TerrainCoverage.NONE, status.terrain)
    }

    private fun <T : Any> requireNonNull(value: T?): T {
        checkNotNull(value) { "expected a value to be present" }
        return value
    }
}
