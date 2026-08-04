package com.rhecyee.firelinemap.geopdf

import com.rhecyee.firelinemap.map.MapCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Exercised against a real Burnt Creek incident product.
 *
 * The fixture is the 2026-07-28 transportation sheet, chosen because it is
 * the awkward case: one page carrying a main map and two detail insets, each
 * georeferenced separately and at completely different extents.
 */
class GeoPdfReaderTest {

    private fun fixture(): ByteArray =
        javaClass.getResourceAsStream("/geopdf/transportation_burntcreek_20260728.pdf")
            ?.readBytes()
            ?: error("missing GeoPDF fixture")

    private val document by lazy { GeoPdfReader.read(fixture()) }

    @Test
    fun realIncidentProductIsRecognisedAsGeoreferenced() {
        assertEquals(PdfKind.GEOREFERENCED, document.kind)
        assertTrue(document.isGeoreferenced)
    }

    @Test
    fun allThreeFramesOnTheSheetAreFound() {
        // A main map plus two insets. None may be silently dropped: the insets
        // are real frames, they are simply not the one to position against.
        assertEquals(3, document.frames.size)
    }

    @Test
    fun theLargestFrameIsChosenAsPrimary() {
        val primary = document.primaryFrame
        assertNotNull(primary)
        assertTrue(document.frames.all { it.box.area <= primary!!.box.area })

        // The main sheet spans the whole incident, far wider than either inset.
        val bounds = primary!!.geographicBounds()
        assertEquals(45.374, bounds[0], 0.01)
        assertEquals(-117.536, bounds[1], 0.01)
        assertEquals(45.832, bounds[2], 0.01)
        assertEquals(-116.935, bounds[3], 0.01)
    }

    @Test
    fun insetsAreReportedSeparatelyFromTheMainMap() {
        assertEquals(2, document.insetFrames.size)
        // Each inset covers a small fraction of the sheet's ground.
        val primaryArea = document.primaryFrame!!.box.area
        assertTrue(document.insetFrames.all { it.box.area < primaryArea / 2 })
    }

    @Test
    fun projectionIsRecoveredFromTheEmbeddedWkt() {
        val projection = document.primaryFrame!!.projection
        assertNotNull(projection)
        assertEquals("NAD_1983_UTM_Zone_11N", projection!!.name)
        assertEquals(-117.0, projection.centralMeridian, 1e-9)
        assertEquals(0.9996, projection.scaleFactor, 1e-9)
        assertEquals(500000.0, projection.falseEasting, 1e-6)
        assertTrue(document.primaryFrame!!.usesProjection)
    }

    @Test
    fun frameCornersRoundTripToTheirPagePositions() {
        val frame = document.primaryFrame!!
        val expected = frame.pageCorners

        frame.geoCorners.forEachIndexed { index, corner ->
            val actual = frame.geoToPage(corner.latitude, corner.longitude)
            assertNotNull(actual)
            val error = hypot(
                actual!!.first - expected[index].first,
                actual.second - expected[index].second
            )
            // The published corners are rounded to five decimal places, which
            // is itself about a metre on the ground; this is that limit, not
            // transform error.
            assertTrue("corner $index off by $error pt", error < 0.05)
        }
    }

    @Test
    fun pageAndGeographicTransformsAreInverses() {
        val frame = document.primaryFrame!!
        var worst = 0.0
        for (fx in listOf(0.1, 0.35, 0.5, 0.75, 0.9)) {
            for (fy in listOf(0.1, 0.5, 0.9)) {
                val x = frame.box.left + fx * frame.box.width
                val y = frame.box.bottom + fy * frame.box.height
                val geo = frame.pageToGeo(x, y)!!
                val back = frame.geoToPage(geo.latitude, geo.longitude)!!
                worst = maxOf(worst, hypot(back.first - x, back.second - y))
            }
        }
        assertTrue("round trip drifted $worst pt", worst < 1e-3)
    }

    /**
     * The reason the projection is parsed at all.
     *
     * A UTM sheet's neatline is not axis-aligned in latitude and longitude --
     * the meridians converge across it -- so treating the geographic bounding
     * box as a linear stand-in puts the position materially off. On this sheet
     * that error reaches roughly 90 m, which is well outside anything that
     * could be called practical map accuracy on a fireline.
     */
    @Test
    fun naiveBoundingBoxInterpolationWouldBeMateriallyWrong() {
        val frame = document.primaryFrame!!
        val bounds = frame.geographicBounds()
        var worstMeters = 0.0
        for (fx in 0..10) {
            for (fy in 0..10) {
                val x = frame.box.left + fx / 10.0 * frame.box.width
                val y = frame.box.bottom + fy / 10.0 * frame.box.height
                val projected = frame.pageToGeo(x, y)!!
                val naiveLat = bounds[0] + (y - frame.box.bottom) / frame.box.height *
                    (bounds[2] - bounds[0])
                val naiveLon = bounds[1] + (x - frame.box.left) / frame.box.width *
                    (bounds[3] - bounds[1])
                worstMeters = maxOf(
                    worstMeters,
                    MapCoverage.distanceMeters(
                        projected.latitude, projected.longitude, naiveLat, naiveLon
                    )
                )
            }
        }
        assertTrue("naive interpolation was only $worstMeters m off", worstMeters > 50.0)
    }

    @Test
    fun positionsInsideAndOutsideTheFrameAreDistinguished() {
        val frame = document.primaryFrame!!
        val bounds = frame.geographicBounds()
        val midLat = (bounds[0] + bounds[2]) / 2
        val midLon = (bounds[1] + bounds[3]) / 2

        assertTrue(frame.containsGeo(midLat, midLon))
        // A degree north is off the sheet entirely.
        assertTrue(!frame.containsGeo(bounds[2] + 1.0, midLon))
    }

    @Test
    fun frameNameIsDecodedFromUtf16() {
        // The product stores its frame name as a UTF-16BE string with a BOM.
        assertEquals("Transpo_2026_Burnt", document.primaryFrame!!.name)
    }
}

/** Structural cases built as minimal PDFs so they stay small and explicit. */
class GeoPdfReaderStructureTest {

    private fun pdfWithViewports(viewports: String): ByteArray =
        ("%PDF-1.7\n1 0 obj\n<</Type /Page/MediaBox [0 0 612 792]/VP[$viewports]>>\nendobj\n" +
            "trailer<</Root 1 0 R>>\n%%EOF\n").toByteArray(Charsets.ISO_8859_1)

    private val wkt = "PROJCS[\"NAD_1983_UTM_Zone_11N\"," +
        "GEOGCS[\"GCS_North_American_1983\",DATUM[\"D_North_American_1983\"," +
        "SPHEROID[\"GRS_1980\",6378137.0,298.257222101]]]," +
        "PROJECTION[\"Transverse_Mercator\"],PARAMETER[\"False_Easting\",500000.0]," +
        "PARAMETER[\"False_Northing\",0.0],PARAMETER[\"Central_Meridian\",-117.0]," +
        "PARAMETER[\"Scale_Factor\",0.9996],PARAMETER[\"Latitude_Of_Origin\",0.0]," +
        "UNIT[\"Meter\",1.0]]"

    private fun viewport(box: String) =
        "<</Type /Viewport/BBox [$box]/Measure<</Type /Measure/Subtype /GEO" +
            "/Bounds [0 0 0 1 1 1 1 0 0 0]" +
            "/GPTS [ 45.61569 -117.40092 45.86105 -117.40268 45.86171 -117.10787 " +
            "45.61635 -117.10739]/LPTS [ 0 0 0 1 1 1 1 0]" +
            "/GCS<</Type /PROJCS/WKT ($wkt)>>>>>>"

    @Test
    fun repeatedIdenticalViewportsCollapseToOneFrame() {
        // The operations sheet declares its single frame three times over.
        val document = GeoPdfReader.read(
            pdfWithViewports(viewport("36 234 1260 1692").repeat(3))
        )
        assertEquals(1, document.frames.size)
    }

    @Test
    fun distinctViewportsAreKept() {
        val document = GeoPdfReader.read(
            pdfWithViewports(viewport("36 234 1260 1692") + viewport("40 240 400 500"))
        )
        assertEquals(2, document.frames.size)
    }

    @Test
    fun tenElementBoundsArrayIsAccepted() {
        // Real products close the /Bounds ring, giving ten numbers where the
        // specification's example shows eight.
        val document = GeoPdfReader.read(pdfWithViewports(viewport("36 234 1260 1692")))
        assertEquals(PdfKind.GEOREFERENCED, document.kind)
        assertEquals(4, document.frames.single().geoCorners.size)
    }

    @Test
    fun aPdfWithoutViewportsIsPlain() {
        val document = GeoPdfReader.read(
            "%PDF-1.7\n1 0 obj\n<</Type /Page/MediaBox [0 0 612 792]>>\nendobj\n%%EOF\n"
                .toByteArray(Charsets.ISO_8859_1)
        )
        assertEquals(PdfKind.PLAIN, document.kind)
        assertNull(document.primaryFrame)
        assertTrue(document.frames.isEmpty())
    }

    @Test
    fun aViewportMissingItsGeographicPointsIsIgnored() {
        val document = GeoPdfReader.read(
            pdfWithViewports("<</Type /Viewport/BBox [0 0 10 10]/Measure<</Subtype /GEO>>>>")
        )
        assertEquals(PdfKind.PLAIN, document.kind)
    }

    @Test
    fun unsupportedProjectionFallsBackToInterpolationRatherThanWrongMaths() {
        val lambert = wkt.replace("Transverse_Mercator", "Lambert_Conformal_Conic")
        val document = GeoPdfReader.read(
            pdfWithViewports(
                viewport("36 234 1260 1692").replace(wkt, lambert)
            )
        )
        val frame = document.frames.single()
        assertNull(frame.projection)
        assertTrue(!frame.usesProjection)

        // Interpolation still places corners correctly; it is the interior
        // that degrades, which is the honest trade rather than projecting with
        // the wrong formulae.
        val corner = frame.geoCorners[0]
        val page = frame.geoToPage(corner.latitude, corner.longitude)!!
        assertTrue(abs(page.first - frame.box.left) < 0.5)
    }
}
