package com.rhecyee.firelinemap.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevationTilesTest {

    // ---- decoding ----

    @Test
    fun `terrarium pixels decode to the elevations the archive publishes`() {
        // Read out of the real tile covering the ground this was built for --
        // terrarium/13/1678/3206, the ridge above camp. A decode written from
        // the formula alone and never checked against a served tile is how an
        // app ends up confidently drawing the wrong contours.
        assertEquals(2489.226562, ElevationTiles.decodeTerrarium(137, 185, 58), 1e-6)
        assertEquals(2315.210938, ElevationTiles.decodeTerrarium(137, 11, 54), 1e-6)
        assertEquals(2155.367188, ElevationTiles.decodeTerrarium(136, 107, 94), 1e-6)
        assertEquals(2331.148438, ElevationTiles.decodeTerrarium(137, 27, 38), 1e-6)
    }

    @Test
    fun `the packed pixel form agrees with the channel form`() {
        val red = 137
        val green = 27
        val blue = 38
        val packed = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        assertEquals(
            ElevationTiles.decodeTerrarium(red, green, blue),
            ElevationTiles.decodeTerrarium(packed),
            0.0
        )
    }

    @Test
    fun `the encoding spans from below sea level to above any summit`() {
        // Zero metres is the offset itself, so a mistake in it shows up here
        // as a shoreline drawn across dry ground.
        assertEquals(0.0, ElevationTiles.decodeTerrarium(128, 0, 0), 1e-9)
        // The Dead Sea shore, about -430 m, has to be representable.
        assertTrue(ElevationTiles.decodeTerrarium(0, 0, 0) < -430.0)
        // As does Everest.
        assertTrue(ElevationTiles.decodeTerrarium(255, 255, 255) > 8849.0)
    }

    @Test
    fun `the blue channel carries the fraction of a metre`() {
        val whole = ElevationTiles.decodeTerrarium(137, 27, 0)
        val fraction = ElevationTiles.decodeTerrarium(137, 27, 128)
        assertEquals(0.5, fraction - whole, 1e-9)
    }

    // ---- zoom selection ----

    @Test
    fun `zoom is chosen to be no coarser than the samples asked for`() {
        val latitude = 38.9
        for (target in listOf(2.0, 5.0, 12.0, 40.0, 200.0)) {
            val zoom = ElevationTiles.zoomFor(latitude, target)
            assertTrue(zoom in ElevationTiles.MIN_ZOOM..ElevationTiles.MAX_ZOOM)
            val resolution = ElevationTiles.metersPerPixel(latitude, zoom)
            // Either it meets the target, or it is already as deep as the
            // archive goes and there is nothing better to be had.
            assertTrue(
                "z$zoom gives ${resolution}m for a ${target}m target",
                resolution <= target || zoom == ElevationTiles.MAX_ZOOM
            )
        }
    }

    @Test
    fun `a finer request never picks a shallower zoom`() {
        val latitude = 38.9
        val zooms = listOf(400.0, 100.0, 25.0, 6.0, 1.0).map {
            ElevationTiles.zoomFor(latitude, it)
        }
        assertEquals(zooms.sorted(), zooms)
    }

    @Test
    fun `zoom never runs past where the archive has tiles`() {
        // Sixteen and beyond are 404s; asking for a centimetre must not go there.
        assertEquals(ElevationTiles.MAX_ZOOM, ElevationTiles.zoomFor(38.9, 0.01))
        assertEquals(ElevationTiles.MAX_ZOOM, ElevationTiles.zoomFor(38.9, 0.0))
        assertEquals(ElevationTiles.MAX_ZOOM, ElevationTiles.zoomFor(38.9, -5.0))
        assertEquals(15, ElevationTiles.MAX_ZOOM)
    }

    @Test
    fun `zoom never drops so low that a sample covers a mile`() {
        assertEquals(ElevationTiles.MIN_ZOOM, ElevationTiles.zoomFor(38.9, 100_000.0))
    }

    @Test
    fun `resolution matches the published metres per pixel at the equator`() {
        // The Web Mercator constant: one zoom-zero pixel is 156 km of equator.
        assertEquals(156_543.03392, ElevationTiles.metersPerPixel(0.0, 0), 1e-5)
        assertEquals(
            156_543.03392 / 32_768.0,
            ElevationTiles.metersPerPixel(0.0, 15),
            1e-9
        )
    }

    @Test
    fun `resolution tightens away from the equator`() {
        assertTrue(
            ElevationTiles.metersPerPixel(38.9, 15) <
                ElevationTiles.metersPerPixel(0.0, 15)
        )
        // Around four metres at fifteen in the mountain west: fine enough that
        // a twenty foot contour is never limited by the data.
        val resolution = ElevationTiles.metersPerPixel(38.9, 15)
        assertTrue("$resolution m", resolution in 3.0..5.0)
    }
}
