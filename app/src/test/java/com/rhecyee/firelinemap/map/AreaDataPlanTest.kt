package com.rhecyee.firelinemap.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileMathTest {

    private val incidentMap = GeoBounds(45.10, -117.75, 45.32, -117.50)

    @Test
    fun tileIndicesFollowWebMercatorConvention() {
        // Zoom 0 is a single tile.
        assertEquals(0, TileMath.tileX(-117.6, 0))
        assertEquals(0, TileMath.tileY(45.2, 0))

        // At zoom 1 the Wallowa-Whitman is in the northwest quadrant.
        assertEquals(0, TileMath.tileX(-117.6, 1))
        assertEquals(0, TileMath.tileY(45.2, 1))

        // East of Greenwich and south of the equator lands in the far quadrant.
        assertEquals(1, TileMath.tileX(10.0, 1))
        assertEquals(1, TileMath.tileY(-45.0, 1))
    }

    @Test
    fun tileYIncreasesSouthward() {
        val north = TileMath.tileY(45.32, 14)
        val south = TileMath.tileY(45.10, 14)
        assertTrue("expected the southern edge to have the larger Y", south > north)
    }

    @Test
    fun singleZoomTileCountMatchesTheGridItSpans() {
        val zoom = 12
        val minX = TileMath.tileX(incidentMap.west, zoom)
        val maxX = TileMath.tileX(incidentMap.east, zoom)
        val minY = TileMath.tileY(incidentMap.north, zoom)
        val maxY = TileMath.tileY(incidentMap.south, zoom)
        val expected = (maxX - minX + 1).toLong() * (maxY - minY + 1).toLong()

        assertEquals(expected, TileMath.tileCount(incidentMap, zoom))
    }

    @Test
    fun zoomRangeCountIsTheSumOfItsLevels() {
        val expected = (0..10).sumOf { TileMath.tileCount(incidentMap, it) }
        assertEquals(expected, TileMath.tileCount(incidentMap, 0, 10))
    }

    @Test
    fun bufferExpandsOnEverySide() {
        val buffered = TileMath.buffer(incidentMap, 5_000.0)

        assertTrue(buffered.south < incidentMap.south)
        assertTrue(buffered.north > incidentMap.north)
        assertTrue(buffered.west < incidentMap.west)
        assertTrue(buffered.east > incidentMap.east)

        // 5 km is about 0.045 degrees of latitude.
        assertEquals(0.0449, incidentMap.south - buffered.south, 0.002)
    }

    @Test
    fun bufferAtLeastReachesTheRequestedDistanceInLongitude() {
        val meters = 5_000.0
        val buffered = TileMath.buffer(incidentMap, meters)

        // Measured along the northern edge, where longitude degrees are shortest.
        val actual = MapCoverage.distanceMeters(
            incidentMap.north, incidentMap.west,
            incidentMap.north, buffered.west
        )
        assertTrue("buffer fell short: $actual m", actual >= meters - 1.0)
    }

    @Test
    fun bufferIsClampedToValidCoordinates() {
        val nearPole = GeoBounds(84.9, 179.5, 85.0, 179.9)
        val buffered = TileMath.buffer(nearPole, 200_000.0)

        assertTrue(buffered.north <= TileMath.MAX_LATITUDE)
        assertTrue(buffered.east <= 180.0)
    }

    @Test
    fun aroundBuildsABoxCentredOnThePosition() {
        val bounds = TileMath.around(45.20575, -117.6370, 15_000.0)

        assertTrue(bounds.contains(45.20575, -117.6370))
        assertEquals(45.20575, (bounds.south + bounds.north) / 2, 1e-9)
        assertEquals(-117.6370, (bounds.west + bounds.east) / 2, 1e-9)
    }
}

class AreaDataPlannerTest {

    private val incidentMap = GeoBounds(45.10, -117.75, 45.32, -117.50)

    @Test
    fun planPrefersTheIncidentMapExtent() {
        val plan = AreaDataPlanner.plan(incidentMap, currentLatitude = 40.0, currentLongitude = -100.0)

        assertNotNull(plan)
        // The plan covers the map, not the position.
        assertTrue(plan!!.bounds.contains(45.20, -117.60))
    }

    @Test
    fun planFallsBackToPositionBeforeAnyMapIsImported() {
        val plan = AreaDataPlanner.plan(null, currentLatitude = 45.20575, currentLongitude = -117.6370)

        assertNotNull(plan)
        assertTrue(plan!!.bounds.contains(45.20575, -117.6370))
    }

    @Test
    fun planIsUnavailableWithoutAMapOrAFix() {
        assertNull(AreaDataPlanner.plan(null))
        assertNull(AreaDataPlanner.plan(null, currentLatitude = 45.2))
    }

    @Test
    fun planCoversGroundBeyondTheMapEdge() {
        val plan = AreaDataPlanner.plan(incidentMap)!!

        // The off-map positions the coverage model warns about are downloaded too.
        assertTrue(plan.bounds.contains(45.34, -117.60))
        assertTrue(plan.bounds.south < incidentMap.south)
    }

    @Test
    fun estimateForAnOperationalAreaStaysInTensOfMegabytes() {
        val plan = AreaDataPlanner.plan(incidentMap)!!
        val megabytes = plan.estimatedBytes / (1024.0 * 1024.0)

        // Vector overzoom is what keeps this from being a several-hundred-megabyte
        // download; if a source's zoom cap regresses, this is the guard that trips.
        assertTrue("estimate was $megabytes MB", megabytes in 0.5..80.0)
    }

    @Test
    fun totalsAreTheSumOfTheSourcePlans() {
        val plan = AreaDataPlanner.plan(incidentMap)!!

        assertEquals(plan.sourcePlans.sumOf { it.tileCount }, plan.totalTiles)
        assertEquals(plan.sourcePlans.sumOf { it.estimatedBytes }, plan.estimatedBytes)
        assertEquals(2, plan.sourcePlans.size)
    }

    @Test
    fun requiredStorageExceedsTheEstimate() {
        val plan = AreaDataPlanner.plan(incidentMap)!!

        assertTrue(plan.requiredBytes > plan.estimatedBytes)
    }
}

class AreaDataStateResolverTest {

    private val plan = AreaDataPlanner.plan(GeoBounds(45.10, -117.75, 45.32, -117.50))!!
    private val ampleStorage = plan.requiredBytes * 4

    @Test
    fun noPlanMeansNoTarget() {
        val state = AreaDataStateResolver.resolve(null, Connectivity.UNMETERED, ampleStorage)
        assertEquals(AreaDataState.NoTarget, state)
    }

    @Test
    fun unmeteredConnectionOffersTheDownload() {
        val state = AreaDataStateResolver.resolve(plan, Connectivity.UNMETERED, ampleStorage)

        assertTrue(state is AreaDataState.Available)
        assertEquals(false, (state as AreaDataState.Available).metered)
    }

    @Test
    fun meteredConnectionStillOffersTheDownloadButIsFlagged() {
        val state = AreaDataStateResolver.resolve(plan, Connectivity.METERED, ampleStorage)

        // A hotspot is often the only connection at ICP, so this must not block.
        assertTrue(state is AreaDataState.Available)
        assertEquals(true, (state as AreaDataState.Available).metered)
    }

    @Test
    fun noConnectionReportsTheReasonAndKeepsThePlan() {
        val state = AreaDataStateResolver.resolve(plan, Connectivity.NONE, ampleStorage)

        assertTrue(state is AreaDataState.NoConnection)
        // The size is still known, so the button can say what it is waiting to fetch.
        assertEquals(plan, (state as AreaDataState.NoConnection).plan)
    }

    @Test
    fun insufficientStorageIsReportedAheadOfConnectivity() {
        val state = AreaDataStateResolver.resolve(plan, Connectivity.NONE, freeBytes = 1_000)

        assertTrue(state is AreaDataState.InsufficientStorage)
        assertTrue((state as AreaDataState.InsufficientStorage).shortfallBytes > 0)
    }

    @Test
    fun partialDownloadResumesRatherThanRestarting() {
        val state = AreaDataStateResolver.resolve(
            plan, Connectivity.METERED, ampleStorage, existingBytes = 4_000_000
        )

        assertTrue(state is AreaDataState.Interrupted)
        assertEquals(4_000_000L, (state as AreaDataState.Interrupted).downloadedBytes)
    }

    @Test
    fun completedDownloadReportsReady() {
        val state = AreaDataStateResolver.resolve(
            plan, Connectivity.NONE, ampleStorage,
            existingBytes = plan.estimatedBytes, isComplete = true
        )

        // Being offline is irrelevant once the data is on the device.
        assertTrue(state is AreaDataState.Ready)
    }

    @Test
    fun downloadInFlightReportsProgress() {
        val half = plan.estimatedBytes / 2
        val state = AreaDataStateResolver.resolve(
            plan, Connectivity.UNMETERED, ampleStorage,
            existingBytes = half, isDownloading = true
        )

        assertTrue(state is AreaDataState.Downloading)
        assertEquals(0.5f, (state as AreaDataState.Downloading).fraction, 0.01f)
    }

    @Test
    fun progressFractionIsClamped() {
        val state = AreaDataState.Downloading(plan, downloadedBytes = plan.estimatedBytes * 3)
        assertEquals(1.0f, state.fraction, 1e-6f)
    }

    @Test
    fun byteFormattingIsReadableOnAButton() {
        assertEquals("512 KB", AreaDataStateResolver.formatBytes(512 * 1024))
        assertEquals("24 MB", AreaDataStateResolver.formatBytes(24L * 1024 * 1024))
        assertEquals("1.5 GB", AreaDataStateResolver.formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))
    }
}
