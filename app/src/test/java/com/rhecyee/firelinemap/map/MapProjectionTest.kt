package com.rhecyee.firelinemap.map

import com.rhecyee.firelinemap.geopdf.GeoPoint
import com.rhecyee.firelinemap.geopdf.MapFrame
import com.rhecyee.firelinemap.geopdf.PageBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Ground to the canvas and back, in both modes.
 *
 * The sheet mode is the one the app had; the ground mode is what lets it work
 * before anybody has a product. Both have to agree about where things are, or
 * importing a sheet mid-incident would move every pin and track already
 * placed.
 */
class MapProjectionTest {

    private val wkt = "PROJCS[\"NAD_1983_UTM_Zone_11N\"," +
        "GEOGCS[\"GCS_North_American_1983\",DATUM[\"D_North_American_1983\"," +
        "SPHEROID[\"GRS_1980\",6378137.0,298.257222101]]]," +
        "PROJECTION[\"Transverse_Mercator\"],PARAMETER[\"False_Easting\",500000.0]," +
        "PARAMETER[\"False_Northing\",0.0],PARAMETER[\"Central_Meridian\",-117.0]," +
        "PARAMETER[\"Scale_Factor\",0.9996],PARAMETER[\"Latitude_Of_Origin\",0.0]," +
        "UNIT[\"Meter\",1.0]]"

    private val sheet = SheetProjection(
        frame = MapFrame(
            name = "Burnt Creek",
            box = PageBox(0.0, 0.0, 720.0, 1008.0),
            geoCorners = listOf(
                GeoPoint(45.61569, -117.40092),
                GeoPoint(45.86105, -117.40268),
                GeoPoint(45.86171, -117.10787),
                GeoPoint(45.61635, -117.10739)
            ),
            localCorners = listOf(0.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0, 1.0 to 0.0),
            wkt = wkt
        ),
        pageWidthPoints = 720,
        pageHeightPoints = 1008,
        contentWidth = 2048f,
        contentHeight = 2867f
    )

    private val ground = GroundProjection(45.7385, -117.2551)

    @Test
    fun groundRoundTripsThroughTheUnitSquare() {
        for (projection in listOf<MapProjection>(sheet, ground)) {
            val latitude = 45.7100
            val longitude = -117.3000
            val unit = projection.toUnit(latitude, longitude)
            assertNotNull(projection.toString(), unit)
            val back = projection.toGeo(unit!!.first, unit.second)
            assertNotNull(back)
            // A ten-thousandth of a degree is about eleven metres; this is far
            // inside that.
            assertEquals(latitude, back!!.first, 1e-6)
            assertEquals(longitude, back.second, 1e-6)
        }
    }

    @Test
    fun theAnchorSitsInTheMiddleOfTheGround() {
        val unit = ground.toUnit(ground.centreLatitude, ground.centreLongitude)
        assertNotNull(unit)
        assertEquals(0.5f, unit!!.first, 1e-5f)
        assertEquals(0.5f, unit.second, 1e-5f)
    }

    @Test
    fun theGroundIsSquareAndCoversTheSpanItClaims() {
        val west = ground.toGeo(0f, 0.5f)!!
        val east = ground.toGeo(1f, 0.5f)!!
        val north = ground.toGeo(0.5f, 0f)!!
        val south = ground.toGeo(0.5f, 1f)!!

        val across = MapCoverage.distanceMeters(west.first, west.second, east.first, east.second)
        val down = MapCoverage.distanceMeters(north.first, north.second, south.first, south.second)

        assertEquals(GroundProjection.DEFAULT_SPAN_METERS, across, across * 0.01)
        // Square on the ground, not merely square in the projection. Mercator
        // is conformal, so this holds near the anchor; it is what lets a
        // measured distance read the same whichever way it is drawn.
        assertEquals(across, down, across * 0.01)
        assertEquals(GroundProjection.DEFAULT_SPAN_METERS, ground.contentSpanMeters, 1.0)
    }

    @Test
    fun northIsUpAndEastIsRightInBothModes() {
        for (projection in listOf<MapProjection>(sheet, ground)) {
            val here = projection.toUnit(45.72, -117.30)!!
            val north = projection.toUnit(45.75, -117.30)!!
            val east = projection.toUnit(45.72, -117.25)!!
            assertTrue("north must be up", north.second < here.second)
            assertTrue("east must be right", east.first > here.first)
        }
    }

    @Test
    fun groundOutsideTheContentStillHasAPlace() {
        // Off the sheet is normal -- ICP and the drive in usually are -- and
        // off the working area has to point somewhere rather than vanish.
        val faraway = ground.toUnit(46.5, -118.5)
        assertNotNull(faraway)
        assertTrue("should be outside the square", faraway!!.first < 0f || faraway.second < 0f)

        val offSheet = sheet.toUnit(45.40, -117.60)
        assertNotNull(offSheet)
    }

    @Test
    fun theGroundKnowsHowFarItsAnchorIs() {
        assertEquals(
            0.0,
            ground.metersFromCentre(ground.centreLatitude, ground.centreLongitude),
            0.5
        )
        // A degree of latitude is about 111 km.
        val away = ground.metersFromCentre(ground.centreLatitude + 0.1, ground.centreLongitude)
        assertEquals(11_100.0, away, 200.0)
        assertTrue(
            "a move this size must re-anchor",
            ground.metersFromCentre(ground.centreLatitude + 0.5, ground.centreLongitude) >
                ground.spanMeters * GroundProjection.REANCHOR_FRACTION
        )
    }

    @Test
    fun theGroundKnowsWhatItIsAndWhatItIsNot() {
        assertTrue(!ground.hasSheet)
        assertTrue(sheet.hasSheet)
    }

    /**
     * The zoom range is narrow on purpose, and the ladder is what widens it.
     *
     * A projection covers a fixed patch of ground, so pushing its scale far in
     * either direction grows the drawn content past the precision a float has
     * to place things in -- which is a map that visibly shakes. Stepping the
     * span instead keeps the content near the viewport's own size at every
     * zoom, which is what lets the range run from a couple of blocks to the
     * whole country.
     */
    @Test
    fun theSpanLadderCoversBlockToCountryInStepsThatPreserveTheView() {
        val ladder = GroundProjection.SPAN_LADDER
        assertTrue("must reach street scale", ladder.first() <= 2_000.0)
        assertTrue("must reach the country", ladder.last() >= 4_000_000.0)

        // Every step is the same factor, which is what makes a step invisible:
        // the visible ground is the span over the scale, so moving both by the
        // same factor leaves it unchanged.
        for (index in 1 until ladder.size) {
            assertEquals(
                "rung $index",
                GroundProjection.SPAN_STEP,
                ladder[index] / ladder[index - 1],
                1e-9
            )
        }
        // And the scale range is exactly one step, so the rungs meet without
        // a gap the zoom could fall into.
        assertEquals(
            GroundProjection.SPAN_STEP,
            (ground.maxScale / ground.minScale).toDouble(),
            1e-6
        )
    }

    @Test
    fun everyRungIsFoundAgainFromItsOwnSpan() {
        GroundProjection.SPAN_LADDER.forEachIndexed { index, span ->
            assertEquals(index, GroundProjection.rungFor(span))
        }
        // And a span between rungs picks the nearer one rather than failing.
        assertEquals(0, GroundProjection.rungFor(1.0))
        assertEquals(
            GroundProjection.SPAN_LADDER.lastIndex,
            GroundProjection.rungFor(50_000_000.0)
        )
    }

    @Test
    fun aSheetWithNoPageSizeRefusesRatherThanDividingByIt() {
        val broken = SheetProjection(sheet.let { _ ->
            MapFrame(
                name = null,
                box = PageBox(0.0, 0.0, 720.0, 1008.0),
                geoCorners = emptyList(),
                localCorners = emptyList(),
                wkt = null
            )
        }, 0, 0, 100f, 100f)
        assertNull(broken.toUnit(45.7, -117.3))
        assertNull(broken.toGeo(0.5f, 0.5f))
    }

    @Test
    fun aNaNPositionIsRefusedRatherThanDrawnSomewhere() {
        assertNull(ground.toUnit(Double.NaN, -117.3))
        assertNull(ground.toUnit(45.7, Double.NaN))
        assertNull(ground.toGeo(Float.NaN, 0.5f))
    }

    /**
     * The two modes must agree.
     *
     * Importing a sheet part way through an incident switches which of these
     * is in use. If they disagreed, every pin, track and measurement already
     * placed would appear to move -- and nobody would know which position was
     * the real one.
     */
    @Test
    fun aSheetAndPlainGroundPutTheSamePositionsInTheSameOrder() {
        val positions = listOf(
            45.65 to -117.35,
            45.70 to -117.30,
            45.75 to -117.25,
            45.80 to -117.20
        )
        val onSheet = positions.map { sheet.toUnit(it.first, it.second)!! }
        val onGround = positions.map { ground.toUnit(it.first, it.second)!! }

        // Not the same coordinates -- different content, different extent --
        // but the same arrangement: same order left to right and top to
        // bottom, and the same relative spacing along the run.
        for (index in 1 until positions.size) {
            assertTrue(onSheet[index].first > onSheet[index - 1].first)
            assertTrue(onGround[index].first > onGround[index - 1].first)
            assertTrue(onSheet[index].second < onSheet[index - 1].second)
            assertTrue(onGround[index].second < onGround[index - 1].second)
        }

        // And the same shape: the ratio of the first gap to the last matches.
        fun gap(points: List<Pair<Float, Float>>, index: Int): Float {
            val dx = points[index].first - points[index - 1].first
            val dy = points[index].second - points[index - 1].second
            return kotlin.math.hypot(dx, dy)
        }
        val sheetRatio = gap(onSheet, 1) / gap(onSheet, 3)
        val groundRatio = gap(onGround, 1) / gap(onGround, 3)
        assertEquals(sheetRatio.toDouble(), groundRatio.toDouble(), 0.02)
    }

    @Test
    fun everyRowSpacingFollowsMercatorRatherThanLatitude() {
        // Equal steps of latitude are not equal steps down the screen: the
        // projection stretches northward. Small over one working area, but it
        // is the difference between correct and nearly correct, and terrain
        // tiles are cut this way so anything else would leave them misaligned.
        val south = ground.toUnit(45.40, -117.2551)!!.second
        val middle = ground.toUnit(45.70, -117.2551)!!.second
        val north = ground.toUnit(46.00, -117.2551)!!.second
        val lower = middle - south
        val upper = north - middle
        assertTrue("spacing must not be uniform", abs(lower - upper) > 1e-6f)
        assertTrue("but it must be close", abs(lower - upper) < 0.01f)
    }
}
