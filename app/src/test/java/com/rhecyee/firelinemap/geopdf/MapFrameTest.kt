package com.rhecyee.firelinemap.geopdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The page-to-ground transform, which everything drawn goes through.
 *
 * Worth pinning hard: every contour point, every track point and the position
 * dot itself are placed by this, so an error here is not a cosmetic one -- it
 * puts a crew in the wrong drainage.
 */
class MapFrameTest {

    private val wkt = "PROJCS[\"NAD_1983_UTM_Zone_11N\"," +
        "GEOGCS[\"GCS_North_American_1983\",DATUM[\"D_North_American_1983\"," +
        "SPHEROID[\"GRS_1980\",6378137.0,298.257222101]]]," +
        "PROJECTION[\"Transverse_Mercator\"],PARAMETER[\"False_Easting\",500000.0]," +
        "PARAMETER[\"False_Northing\",0.0],PARAMETER[\"Central_Meridian\",-117.0]," +
        "PARAMETER[\"Scale_Factor\",0.9996],PARAMETER[\"Latitude_Of_Origin\",0.0]," +
        "UNIT[\"Meter\",1.0]]"

    /** The Burnt Creek sheet's corners, in the order /LPTS gives them. */
    private val frame = MapFrame(
        name = "Burnt Creek",
        box = PageBox(36.0, 36.0, 756.0, 1044.0),
        geoCorners = listOf(
            GeoPoint(45.61569, -117.40092),
            GeoPoint(45.86105, -117.40268),
            GeoPoint(45.86171, -117.10787),
            GeoPoint(45.61635, -117.10739)
        ),
        localCorners = listOf(0.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0, 1.0 to 0.0),
        wkt = wkt
    )

    @Test
    fun theFrameProjectsRatherThanInterpolating() {
        assertNotNull(frame.projection)
        assertTrue("a projected sheet must not fall back to corners", frame.usesProjection)
    }

    @Test
    fun aPositionRoundTripsThroughThePage() {
        // Somewhere inside the sheet, taken back and forth.
        val latitude = 45.7385
        val longitude = -117.2551
        val page = frame.geoToPage(latitude, longitude)
        assertNotNull(page)
        val back = frame.pageToGeo(page!!.first, page.second)
        assertNotNull(back)
        // Well inside a metre, which is finer than any receiver reports.
        assertEquals(latitude, back!!.latitude, 1e-6)
        assertEquals(longitude, back.longitude, 1e-6)
    }

    @Test
    fun theCornersLandOnTheCornersOfTheBox() {
        val corners = frame.pageCorners
        for (index in frame.geoCorners.indices) {
            val geo = frame.geoCorners[index]
            val page = frame.geoToPage(geo.latitude, geo.longitude)
            assertNotNull("corner $index did not project", page)
            // A least-squares fit over four corners of a real sheet will not
            // land exactly, but it must be inside a point of the page -- about
            // a third of a millimetre in print.
            assertEquals("corner $index x", corners[index].first, page!!.first, 1.0)
            assertEquals("corner $index y", corners[index].second, page.second, 1.0)
        }
    }

    /**
     * Part of the fix for the zoom crash.
     *
     * The transform's inverse used to be rebuilt inside every call. It is now
     * computed once, so what this guards is that caching it did not change a
     * single answer.
     */
    @Test
    fun repeatedProjectionsAgreeExactly() {
        val points = listOf(
            45.62 to -117.39,
            45.70 to -117.30,
            45.7385 to -117.2551,
            45.85 to -117.11
        )
        val first = points.map { frame.geoToPage(it.first, it.second) }
        repeat(50) {
            points.forEachIndexed { index, (latitude, longitude) ->
                val again = frame.geoToPage(latitude, longitude)
                assertEquals(first[index]?.first ?: 0.0, again?.first ?: 0.0, 0.0)
                assertEquals(first[index]?.second ?: 0.0, again?.second ?: 0.0, 0.0)
            }
        }
    }

    @Test
    fun manyProjectionsStayWithinAFrameBudget() {
        // A screenful of contours is around twenty thousand points. This much
        // work is fine once, when the lines change; it was being done on every
        // frame instead, which is what stopped the app responding while it was
        // being zoomed. Not a benchmark -- a guard against this call quietly
        // becoming expensive enough that doing it per frame looks survivable.
        val start = System.nanoTime()
        var sink = 0.0
        repeat(20_000) { index ->
            val latitude = 45.62 + (index % 100) * 0.002
            val longitude = -117.39 + (index % 137) * 0.002
            frame.geoToPage(latitude, longitude)?.let { sink += it.first }
        }
        val millis = (System.nanoTime() - start) / 1_000_000.0
        assertTrue("projections did something", sink != 0.0)
        assertTrue("20k projections took ${millis.toInt()} ms", millis < 2_000.0)
    }

    @Test
    fun groundOutsideTheSheetStillProjectsSoItCanBePointedAt() {
        // Off the neatline is normal -- ICP and the drive in usually are -- so
        // this must give an answer rather than refusing.
        val page = frame.geoToPage(45.50, -117.60)
        assertNotNull(page)
        assertTrue("should be outside the box", !frame.containsGeo(45.50, -117.60))
        assertTrue(frame.containsGeo(45.7385, -117.2551))
    }

    @Test
    fun aFrameWithNoUsableWktFallsBackToItsCorners() {
        val plain = frame.copy(wkt = null)
        assertTrue("must not claim a projection", !plain.usesProjection)
        val latitude = 45.7385
        val longitude = -117.2551
        val page = plain.geoToPage(latitude, longitude)
        assertNotNull(page)
        val back = plain.pageToGeo(page!!.first, page.second)
        assertNotNull(back)
        assertTrue(abs(back!!.latitude - latitude) < 1e-6)
        assertTrue(abs(back.longitude - longitude) < 1e-6)
    }
}
