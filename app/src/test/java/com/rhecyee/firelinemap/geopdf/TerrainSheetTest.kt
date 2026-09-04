package com.rhecyee.firelinemap.geopdf

import com.rhecyee.firelinemap.map.MapCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The synthetic sheet terrain is drawn against.
 *
 * Everything on the map view -- terrain tiles, the position marker, tracks,
 * markers, tap-to-place -- is placed by converting through this frame. A fault
 * here does not blank the map, it silently draws the right things in the wrong
 * place, which is worse than drawing nothing.
 */
class TerrainSheetTest {

    private val latitude = 40.4231
    private val longitude = -121.5108

    @Test
    fun `the operator sits at the centre of the page`() {
        val frame = TerrainSheet.frame(latitude, longitude)
        val page = frame.geoToPage(latitude, longitude)
        assertNotNull(page)
        val half = TerrainSheet.PAGE_POINTS / 2.0
        assertEquals(half, page!!.first, 0.01)
        assertEquals(half, page.second, 0.01)
    }

    @Test
    fun `a position converts to the page and back to itself`() {
        val frame = TerrainSheet.frame(latitude, longitude)
        listOf(
            latitude to longitude,
            latitude + 0.05 to longitude - 0.08,
            latitude - 0.11 to longitude + 0.13
        ).forEach { (lat, lon) ->
            val page = frame.geoToPage(lat, lon)
            assertNotNull("no page point for $lat,$lon", page)
            val back = frame.pageToGeo(page!!.first, page.second)
            assertNotNull(back)
            assertEquals(lat, back!!.latitude, 1e-9)
            assertEquals(lon, back.longitude, 1e-9)
        }
    }

    @Test
    fun `north is up the page and east is across it`() {
        // A flipped axis would draw a coherent map of the wrong ground, so this
        // is asserted rather than assumed from the corner ordering.
        val frame = TerrainSheet.frame(latitude, longitude)
        val centre = frame.geoToPage(latitude, longitude)!!
        val north = frame.geoToPage(latitude + 0.05, longitude)!!
        val east = frame.geoToPage(latitude, longitude + 0.05)!!

        assertTrue("north should be further up the page", north.second > centre.second)
        assertEquals(centre.first, north.first, 0.01)
        assertTrue("east should be further across the page", east.first > centre.first)
        assertEquals(centre.second, east.second, 0.01)
    }

    @Test
    fun `the ground covered is square, not merely square in degrees`() {
        // Longitude degrees shrink with latitude. Without the cosine the page
        // would be square while the ground was not, and the terrain would be
        // stretched sideways by a third at these latitudes.
        listOf(0.0, 34.05, 40.4231, 61.2).forEach { lat ->
            val frame = TerrainSheet.frame(lat, longitude)
            val (south, west, north, east) = frame.geographicBounds().toList()
            val acrossMeters = MapCoverage.distanceMeters(lat, west, lat, east)
            val downMeters = MapCoverage.distanceMeters(south, longitude, north, longitude)
            assertEquals(
                "aspect at $lat degrees",
                1.0,
                acrossMeters / downMeters,
                0.02
            )
        }
    }

    @Test
    fun `the sheet spans the requested ground`() {
        val frame = TerrainSheet.frame(latitude, longitude)
        val (south, _, north, _) = frame.geographicBounds().toList()
        val downMeters = MapCoverage.distanceMeters(south, longitude, north, longitude)
        assertEquals(2 * TerrainSheet.HALF_SPAN_METERS, downMeters, 200.0)
    }

    @Test
    fun `ground beyond the sheet still has a page position`() {
        // Terrain is drawn across the whole canvas, not only inside the page
        // rectangle, so the transform has to keep working past the edge or the
        // view would end in a hard line of nothing partway across the display.
        val frame = TerrainSheet.frame(latitude, longitude)
        val faroff = frame.geoToPage(latitude + 1.5, longitude - 1.5)
        assertNotNull(faroff)
        assertTrue("should sit off the page", faroff!!.second > TerrainSheet.PAGE_POINTS)
        assertTrue("should sit off the page", faroff.first < 0.0)

        val back = frame.pageToGeo(faroff.first, faroff.second)!!
        assertEquals(latitude + 1.5, back.latitude, 1e-9)
        assertEquals(longitude - 1.5, back.longitude, 1e-9)
    }

    @Test
    fun `the sheet knows what ground it covers`() {
        val frame = TerrainSheet.frame(latitude, longitude)
        assertTrue(frame.containsGeo(latitude, longitude))
        assertTrue(frame.containsGeo(latitude + 0.1, longitude))
        assertFalse(frame.containsGeo(latitude + 2.0, longitude))
    }

    @Test
    fun `the ground is rebuilt only after travelling well off the anchor`() {
        // Rebuilding resets the canvas's pan and zoom, so it must not happen
        // because someone drove to the next drop point.
        assertFalse(TerrainSheet.needsReanchor(latitude, longitude, latitude, longitude))
        assertFalse(
            "5 km should not rebuild",
            TerrainSheet.needsReanchor(latitude, longitude, latitude + 0.045, longitude)
        )
        assertTrue(
            "30 km should rebuild",
            TerrainSheet.needsReanchor(latitude, longitude, latitude + 0.27, longitude)
        )
    }

    @Test
    fun `the anchor stays inside the ground it anchors`() {
        // The rebuild threshold has to be comfortably inside the span, or the
        // operator would reach the edge of the terrain before it was replaced.
        assertTrue(TerrainSheet.REANCHOR_METERS < TerrainSheet.HALF_SPAN_METERS)
    }

    @Test
    fun `a terrain sheet is recognisable and stable`() {
        val first = TerrainSheet.map(latitude, longitude)
        val second = TerrainSheet.map(latitude, longitude)

        assertEquals("same anchor must give the same identity", first.id, second.id)
        assertTrue(TerrainSheet.isTerrain(first.id))
        assertFalse(TerrainSheet.isTerrain("1748291042-3fa9b1c2"))
        assertFalse(TerrainSheet.isTerrain(null))

        // Formatted under a fixed locale on purpose. A locale that writes
        // decimals with a comma would otherwise produce a different identity
        // for the same ground, and the canvas would reset pan and zoom every
        // time the sheet was rebuilt from it.
        assertEquals("terrain@40.4231,-121.5108", first.id)
    }

    @Test
    fun `the identity does not follow the device locale`() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE)
            assertEquals("terrain@40.4231,-121.5108", TerrainSheet.map(latitude, longitude).id)
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    @Test
    fun `a terrain sheet presents as georeferenced with one frame`() {
        val map = TerrainSheet.map(latitude, longitude)
        assertEquals(PdfKind.GEOREFERENCED, map.kind)
        assertNotNull("the canvas draws against the primary frame", map.frame)
        assertTrue(map.document.insetFrames.isEmpty())
    }

    @Test
    fun `the poles do not produce an infinite sheet`() {
        val frame = TerrainSheet.frame(89.9, 0.0)
        val (south, west, north, east) = frame.geographicBounds().toList()
        assertTrue(north.isFinite() && south.isFinite())
        assertTrue(east.isFinite() && west.isFinite())
        assertTrue("longitude span must stay bounded", east - west < 360.0)
    }

    private operator fun <T> List<T>.component4(): T = this[3]
}
