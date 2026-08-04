package com.rhecyee.firelinemap.terrain

import com.rhecyee.firelinemap.geopdf.GeoPoint
import com.rhecyee.firelinemap.geopdf.MapFrame
import com.rhecyee.firelinemap.geopdf.PageBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning cut contours into something a draw can use without doing any work.
 *
 * This runs on a worker, once per cut. It used to run during composition and
 * inside the draw, where a zoom gesture repeated it for every frame; that is
 * what stopped the app responding while the map was being zoomed.
 */
class ContourProjectorTest {

    private val wkt = "PROJCS[\"NAD_1983_UTM_Zone_11N\"," +
        "GEOGCS[\"GCS_North_American_1983\",DATUM[\"D_North_American_1983\"," +
        "SPHEROID[\"GRS_1980\",6378137.0,298.257222101]]]," +
        "PROJECTION[\"Transverse_Mercator\"],PARAMETER[\"False_Easting\",500000.0]," +
        "PARAMETER[\"False_Northing\",0.0],PARAMETER[\"Central_Meridian\",-117.0]," +
        "PARAMETER[\"Scale_Factor\",0.9996],PARAMETER[\"Latitude_Of_Origin\",0.0]," +
        "UNIT[\"Meter\",1.0]]"

    private val frame = MapFrame(
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
    )

    private fun line(elevation: Int, isIndex: Boolean, points: Int) = ContourLine(
        elevationFeet = elevation,
        isIndex = isIndex,
        points = (0 until points).map {
            45.65 + it * 0.0005 to -117.35 + it * 0.0005
        }
    )

    private fun set(vararg lines: ContourLine) =
        ContourSet(ContourInterval(40), lines.toList(), 4000, 6000, 1.0)

    @Test
    fun pointsComeOutAsFractionsOfThePage() {
        val render = ContourProjector.project(set(line(4000, true, 20)), frame, 720, 1008)
        assertEquals(1, render.lines.size)
        val projected = render.lines.first()
        assertEquals(20, projected.xs.size)
        assertEquals(20, projected.ys.size)
        for (index in projected.xs.indices) {
            assertTrue("x ${projected.xs[index]} off the page", projected.xs[index] in 0f..1f)
            assertTrue("y ${projected.ys[index]} off the page", projected.ys[index] in 0f..1f)
        }
        // Running north-east across the sheet: rightward and upward, and up on
        // a page is a falling y fraction.
        assertTrue(projected.xs.last() > projected.xs.first())
        assertTrue(projected.ys.last() < projected.ys.first())
    }

    @Test
    fun theIntervalAndRangeSurviveForTheKey() {
        val render = ContourProjector.project(set(line(4000, true, 10)), frame, 720, 1008)
        assertEquals(40, render.interval.feet)
        assertEquals(4000, render.lowestFeet)
        assertEquals(6000, render.highestFeet)
    }

    @Test
    fun onlyIndexLinesCarryALabelAndItLiesTheRightWayUp() {
        val render = ContourProjector.project(
            set(line(4000, true, 20), line(4040, false, 20)), frame, 720, 1008
        )
        val index = render.lines.first { it.isIndex }
        val plain = render.lines.first { !it.isIndex }
        assertTrue("an index line must be labelled", index.hasLabel)
        assertTrue("a plain line must not be", !plain.hasLabel)
        // Never upside down: a number read the wrong way up is a number
        // misread, and these are elevations.
        assertTrue("was ${index.labelDegrees}", index.labelDegrees in -90f..90f)
    }

    @Test
    fun aLineTooShortToLabelIsStillDrawn() {
        val render = ContourProjector.project(set(line(4000, true, 2)), frame, 720, 1008)
        assertEquals(1, render.lines.size)
        assertTrue(!render.lines.first().hasLabel)
    }

    @Test
    fun aSingleP0intIsNotALine() {
        val render = ContourProjector.project(set(line(4000, true, 1)), frame, 720, 1008)
        assertTrue(render.isEmpty)
    }

    /**
     * The guard against a bad elevation grid.
     *
     * A corrupt tile or a nodata band read as terrain can ask for far more
     * line than any screen can show. Drawing it would take the app down rather
     * than merely look wrong.
     */
    @Test
    fun theBudgetCapsTotalPointsAndSpendsItOnIndexLinesFirst() {
        val many = (0 until 40).map { line(4000 + it * 40, it % 5 == 0, 100) }
        val render = ContourProjector.project(
            ContourSet(ContourInterval(40), many, 4000, 6000, 1.0),
            frame, 720, 1008, maxPoints = 500
        )
        val total = render.lines.sumOf { it.xs.size }
        assertTrue("kept $total points against a budget of 500", total <= 500)
        assertTrue("kept nothing at all", render.lines.isNotEmpty())
        // What survives must be the lines carrying the numbers.
        assertTrue(
            "the budget was spent on unlabelled lines",
            render.lines.all { it.isIndex }
        )
    }

    @Test
    fun cancellationStopsItPartWayRatherThanFinishing() {
        val many = (0 until 40).map { line(4000 + it * 40, true, 100) }
        var calls = 0
        val render = ContourProjector.project(
            ContourSet(ContourInterval(40), many, 4000, 6000, 1.0),
            frame, 720, 1008
        ) { calls++ < 5 }
        assertTrue("did not stop early", render.lines.size < many.size)
    }

    @Test
    fun aPageWithNoSizeYieldsNothingRatherThanDividingByIt() {
        assertTrue(ContourProjector.project(set(line(4000, true, 10)), frame, 0, 1008).isEmpty)
        assertTrue(ContourProjector.project(set(line(4000, true, 10)), frame, 720, 0).isEmpty)
    }

    /**
     * Compared by identity, deliberately.
     *
     * Compose checks state equality on every recomposition, which during a
     * pinch is every frame. A structural comparison here would walk tens of
     * thousands of points sixty times a second, which on its own is enough to
     * stop the interface answering.
     */
    @Test
    fun twoRendersOfTheSameLinesAreNotEqual() {
        val source = set(line(4000, true, 20))
        val first = ContourProjector.project(source, frame, 720, 1008)
        val second = ContourProjector.project(source, frame, 720, 1008)
        assertTrue("equality must stay cheap", first != second)
        assertTrue(first == first)
    }
}
