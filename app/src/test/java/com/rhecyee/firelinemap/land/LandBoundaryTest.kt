package com.rhecyee.firelinemap.land

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading unit outlines.
 *
 * The sample below is a live reply, copied unchanged: Catherine Creek State
 * Park as the service returns it for a view over the Wallowas. A parser
 * written against a guessed shape parses a guessed answer, and this one
 * decides where the map says one agency's ground ends and another's begins.
 */
class LandBoundaryTest {

    private val catherineCreek = """
        {"features":[{"attributes":{"Unit_Nm":"Catherine Creek State Park",
        "Mang_Name":"CITY","Mang_Type":"LOC"},"geometry":{"rings":[[
        [-117.7344,45.1492],[-117.7448,45.1485],[-117.7446,45.1556],
        [-117.7344,45.1556],[-117.7344,45.1492]]]}}]}
    """.trimIndent().replace("\n", "")

    @Test
    fun anOutlineIsReadWithItsNameAndItsRing() {
        val boundaries = LandBoundaryParser.parse(catherineCreek)
        assertEquals(1, boundaries.size)
        val unit = boundaries.first()
        assertEquals("Catherine Creek State Park", unit.name)
        assertEquals(1, unit.rings.size)
        assertEquals(5, unit.rings.first().size)

        // Longitude first, latitude second -- the order the service uses, and
        // the opposite of the order everything else in this app takes.
        val (longitude, latitude) = unit.rings.first().first()
        assertEquals(-117.7344, longitude, 1e-9)
        assertEquals(45.1492, latitude, 1e-9)
    }

    @Test
    fun severalUnitsComeBackSeparately() {
        val two = """
            {"features":[
            {"attributes":{"Unit_Nm":"Whitman National Forest","Mang_Name":"USFS"},
             "geometry":{"rings":[[[-117.6,45.2],[-117.5,45.2],[-117.5,45.3],[-117.6,45.2]]]}},
            {"attributes":{"Unit_Nm":"Newcastle Field Office","Mang_Name":"BLM"},
             "geometry":{"rings":[[[-117.4,45.1],[-117.3,45.1],[-117.3,45.2],[-117.4,45.1]]]}}]}
        """.trimIndent().replace("\n", "")
        val boundaries = LandBoundaryParser.parse(two)
        assertEquals(2, boundaries.size)
        assertEquals(LandAgency.USFS, boundaries[0].agency)
        assertEquals(LandAgency.BLM, boundaries[1].agency)
        // Each keeps its own outline rather than borrowing its neighbour's.
        assertEquals(-117.6, boundaries[0].rings.first().first().first, 1e-9)
        assertEquals(-117.4, boundaries[1].rings.first().first().first, 1e-9)
    }

    @Test
    fun aUnitWithSeveralRingsKeepsThemAll() {
        val multi = """
            {"features":[{"attributes":{"Unit_Nm":"Wallowa National Forest","Mang_Name":"USFS"},
            "geometry":{"rings":[
            [[-117.6,45.2],[-117.5,45.2],[-117.5,45.3],[-117.6,45.2]],
            [[-117.9,45.5],[-117.8,45.5],[-117.8,45.6],[-117.9,45.5]]]}}]}
        """.trimIndent().replace("\n", "")
        val unit = LandBoundaryParser.parse(multi).single()
        assertEquals(2, unit.rings.size)
        assertEquals(8, unit.pointCount)
    }

    @Test
    fun aRingTooShortToEncloseAnythingIsDropped() {
        val sliver = """
            {"features":[{"attributes":{"Unit_Nm":"Sliver","Mang_Name":"BLM"},
            "geometry":{"rings":[[[-117.6,45.2],[-117.5,45.2]]]}}]}
        """.trimIndent().replace("\n", "")
        assertTrue(LandBoundaryParser.parse(sliver).isEmpty())
    }

    @Test
    fun anEmptyOrBrokenResponseYieldsNothing() {
        assertTrue(LandBoundaryParser.parse("""{"features":[]}""").isEmpty())
        assertTrue(LandBoundaryParser.parse("").isEmpty())
        assertTrue(LandBoundaryParser.parse("not json").isEmpty())
        // Truncated mid-ring: nothing rather than a boundary that stops in
        // the middle of a drainage.
        assertTrue(
            LandBoundaryParser.parse(
                """{"features":[{"attributes":{"Unit_Nm":"X"},"geometry":{"rings":[[[-117.6"""
            ).isEmpty()
        )
    }

    @Test
    fun theBudgetStopsAWideViewFromReadingEverything() {
        // One national forest comes back with thousands of points even after
        // the service has generalised it. Past a ceiling the detail is finer
        // than the screen and is only costing frames.
        val ring = (0 until 400).joinToString(",") { "[-117.${500 + it},45.2]" }
        val big = """
            {"features":[{"attributes":{"Unit_Nm":"Big","Mang_Name":"USFS"},
            "geometry":{"rings":[[$ring]]}}]}
        """.trimIndent().replace("\n", "")
        val limited = LandBoundaryParser.parse(big, maxPoints = 100)
        assertTrue(limited.sumOf { it.pointCount } <= 100)
    }

    @Test
    fun generalisationFollowsHowMuchGroundIsOnScreen() {
        val wide = LandBoundaryParser.generalisationFor(0.5)
        val close = LandBoundaryParser.generalisationFor(0.01)
        assertTrue("a wide view must ask for a coarser outline", wide > close)
        // Never so coarse that a boundary people navigate by loses its corners,
        // and never finer than a screen can show.
        assertTrue(LandBoundaryParser.generalisationFor(50.0) <= 0.02)
        assertTrue(LandBoundaryParser.generalisationFor(0.0) >= 0.00005)
    }

    @Test
    fun theQueryAsksForTheViewAndForGeometry() {
        val url = LandBoundaryParser.boundariesUrl(45.3, 45.1, -117.8, -117.5, 0.001)
        assertTrue(url, url.startsWith("https://"))
        assertTrue(url, url.contains("returnGeometry=true"))
        assertTrue(url, url.contains("maxAllowableOffset=0.001"))
        assertTrue(url, url.contains("Unit_Nm"))
        assertTrue(url, url.contains("45.3") && url.contains("-117.8"))
        // Answers must come back in the coordinates everything else uses.
        assertTrue(url, url.contains("outSR=4326"))
    }

    @Test
    fun bracketMatchingHandlesNesting() {
        assertEquals(5, LandBoundaryParser.matchingBracket("[[1,2],[3]]", 1))
        assertEquals(10, LandBoundaryParser.matchingBracket("[[1,2],[3]]", 0))
        assertEquals(-1, LandBoundaryParser.matchingBracket("[[1,2]", 0))
        assertEquals(-1, LandBoundaryParser.matchingBracket("nope", 0))
    }
}
