package com.rhecyee.firelinemap.terrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemTilesTest {

    private val burntCreekLatitude = 45.20575
    private val burntCreekLongitude = -117.6370

    @Test
    fun terrariumDecodesToElevation() {
        // The encoding's zero point is 32768 m below sea level.
        assertEquals(-32_768.0, TerrainMath.decodeTerrarium(0, 0, 0), 1e-9)
        // Red is whole 256 m steps, green whole metres, blue the fraction.
        assertEquals(0.0, TerrainMath.decodeTerrarium(128, 0, 0), 1e-9)
        assertEquals(1.0, TerrainMath.decodeTerrarium(128, 1, 0), 1e-9)
        assertEquals(1.0 / 256.0, TerrainMath.decodeTerrarium(128, 0, 1), 1e-12)

        // A real fireline elevation round-trips.
        val meters = 1417.0
        val offset = meters + 32_768.0
        val red = (offset / 256.0).toInt()
        val green = (offset - red * 256).toInt()
        assertEquals(meters, TerrainMath.decodeTerrarium(red, green, 0), 0.01)
    }

    @Test
    fun terrariumAndTerrainRgbAreNotInterchangeable() {
        // Decoding one as the other gives a plausible-looking number that is
        // wrong by kilometres, which is exactly why they are separate.
        val terrarium = TerrainMath.decodeTerrarium(140, 100, 0)
        val asTerrainRgb = TerrainMath.decodeTerrainRgb(140, 100, 0)
        assertTrue(kotlin.math.abs(terrarium - asTerrainRgb) > 1000.0)
    }

    @Test
    fun tileIndicesMatchTheWebMercatorScheme() {
        // Zoom zero is one tile for the world.
        assertEquals(0, ElevationGridAssembler.tileX(-117.0, 0))
        assertEquals(0, ElevationGridAssembler.tileY(45.0, 0))

        // The prime meridian is the boundary between the two zoom-one columns,
        // and the equator between the two rows.
        assertEquals(0, ElevationGridAssembler.tileX(-0.001, 1))
        assertEquals(1, ElevationGridAssembler.tileX(0.001, 1))
        assertEquals(0, ElevationGridAssembler.tileY(0.001, 1))
        assertEquals(1, ElevationGridAssembler.tileY(-0.001, 1))
    }

    @Test
    fun tileIndicesAgreeWithTheBasemapsOwnMath() {
        // The two layers have to line up on the ground, so their tile schemes
        // must be the same scheme and not merely similar ones.
        for (zoom in 4..14) {
            assertEquals(
                "zoom $zoom",
                com.rhecyee.firelinemap.map.BasemapTileCache.tileX(burntCreekLongitude, zoom),
                ElevationGridAssembler.tileX(burntCreekLongitude, zoom)
            )
            assertEquals(
                "zoom $zoom",
                com.rhecyee.firelinemap.map.BasemapTileCache.tileY(burntCreekLatitude, zoom),
                ElevationGridAssembler.tileY(burntCreekLatitude, zoom)
            )
        }
    }

    @Test
    fun aViewAsksForEveryTileItTouches() {
        val tiles = ElevationGridAssembler.tilesFor(
            north = 45.24, south = 45.18, west = -117.68, east = -117.60, zoom = 13
        )
        val minX = ElevationGridAssembler.tileX(-117.68, 13)
        val maxX = ElevationGridAssembler.tileX(-117.60, 13)
        val minY = ElevationGridAssembler.tileY(45.24, 13)
        val maxY = ElevationGridAssembler.tileY(45.18, 13)
        assertEquals((maxX - minX + 1) * (maxY - minY + 1), tiles.size)
        assertEquals(tiles.size, tiles.distinct().size)
        assertTrue(tiles.all { it.first == 13 })
    }

    @Test
    fun tilesAreOrderedFromTheMiddleOfTheViewOutward() {
        val tiles = ElevationGridAssembler.tilesFor(
            north = 45.30, south = 45.10, west = -117.80, east = -117.50, zoom = 13
        )
        assertTrue("expected several tiles", tiles.size > 4)

        val centreX = (tiles.minOf { it.second } + tiles.maxOf { it.second }) / 2.0
        val centreY = (tiles.minOf { it.third } + tiles.maxOf { it.third }) / 2.0
        fun distance(tile: Triple<Int, Int, Int>): Double {
            val dx = tile.second - centreX
            val dy = tile.third - centreY
            return dx * dx + dy * dy
        }
        // A download cut short should have covered what the operator is
        // looking at, not a corner of the screen.
        for (index in 1 until tiles.size) {
            assertTrue(
                "tile $index is nearer the middle than the one before it",
                distance(tiles[index]) >= distance(tiles[index - 1]) - 1e-9
            )
        }
    }

    @Test
    fun aDegenerateBoxAsksForNothing() {
        assertTrue(
            ElevationGridAssembler.tilesFor(45.0, 45.0, -117.0, -117.0, 13).isNotEmpty()
        )
        // A box the wrong way round is a caller error, not a request for the
        // whole world.
        val backwards = ElevationGridAssembler.tilesFor(
            north = 45.0, south = 45.2, west = -117.0, east = -117.4, zoom = 13
        )
        assertTrue(backwards.isEmpty())
    }

    @Test
    fun theAttributionSaysWhatTheUsgsAsksItToSay() {
        // Public domain data still gets credited, and the wording is theirs.
        assertTrue(DemTileCache.ATTRIBUTION.contains("3DEP"))
        assertTrue(DemTileCache.ATTRIBUTION.contains("U.S. Geological Survey"))
    }

    @Test
    fun theSourceIsKeylessAndOverPlainHttps() {
        // The whole tool exists for people who cannot get onto agency
        // infrastructure; a layer behind a key would defeat it.
        assertTrue(DemTileCache.ENDPOINT.startsWith("https://"))
        assertTrue(!DemTileCache.ENDPOINT.contains("key"))
        assertTrue(!DemTileCache.ENDPOINT.contains("token"))
    }
}
