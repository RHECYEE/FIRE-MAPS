package com.rhecyee.firelinemap.share

import com.rhecyee.firelinemap.util.CoordinateParseResult
import com.rhecyee.firelinemap.util.CoordinateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Positions as a text message.
 *
 * The file is the better answer when it arrives. This is the answer when it
 * does not, which is often: a picture message is capped on most carriers, an
 * unrecognised file type is refused outright, and the person receiving it may
 * have nothing that opens a GPX.
 */
class ShareTextTest {

    private val dropPoint = SharePin(
        id = "p1",
        title = "DP 12",
        latitude = 45.20575,
        longitude = -117.63700,
        symbolId = "drop_point",
        note = "Turnaround for tenders"
    )

    @Test
    fun aPositionReadsTheWayItGoesOverARadio() {
        val text = ShareText.degreesDecimalMinutes(45.20575, -117.63700)
        assertEquals("N 45 12.345 W 117 38.220", text)
    }

    @Test
    fun bothHemispheresAreWrittenOutRatherThanImplied() {
        assertTrue(ShareText.degreesDecimalMinutes(-33.8688, 151.2093).startsWith("S 33 "))
        assertTrue(ShareText.degreesDecimalMinutes(-33.8688, 151.2093).contains("E 151 "))
    }

    /**
     * The whole point of the text form.
     *
     * Whoever receives this types it back into their own copy. If this app
     * cannot read what this app writes, the feature is a decoration.
     */
    @Test
    fun whatIsSentCanBeTypedStraightBackIn() {
        val positions = listOf(
            45.20575 to -117.63700,
            48.75000 to -113.80000,
            25.76170 to -80.19180
        )
        positions.forEach { (latitude, longitude) ->
            val text = ShareText.degreesDecimalMinutes(latitude, longitude)
            val parsed = CoordinateParser.parse(text)
            assertTrue("could not read back \"$text\"", parsed is CoordinateParseResult.Success)
            val back = (parsed as CoordinateParseResult.Success).coordinate
            // Three decimal minutes is about two metres.
            assertEquals(text, latitude, back.latitude, 0.0001)
            assertEquals(text, longitude, back.longitude, 0.0001)
        }
    }

    @Test
    fun aPinCarriesItsNameItsPositionAndItsGrid() {
        val line = ShareText.pin(dropPoint)
        assertTrue(line.startsWith("DP 12"))
        assertTrue(line.contains("N 45 12.345"))
        assertTrue(line.contains("W 117 38.220"))
        // The grid, because aviation asks for one and cannot use degrees.
        assertTrue("expected an MGRS grid in: $line", line.contains("11T"))
        assertTrue(line.contains("Turnaround for tenders"))
    }

    @Test
    fun theGridCanBeLeftOffWhenTheMessageHasToBeShort() {
        val line = ShareText.pin(dropPoint, includeGrid = false)
        assertTrue(line.contains("N 45 12.345"))
        assertTrue(!line.contains("11T"))
    }

    @Test
    fun aMessageLeadsWithTheIncidentSoItMakesSenseOnItsOwn() {
        val pkg = SharePackage(
            incidentName = "Burnt Creek 2026",
            pins = listOf(dropPoint),
            author = "L. Yee"
        )
        val message = ShareText.message(pkg)
        assertTrue(message.startsWith("Burnt Creek 2026"))
        assertTrue(message.contains("DP 12"))
        assertTrue("who sent it matters", message.contains("L. Yee"))
    }

    /**
     * A track cannot be typed out, and pretending otherwise is worse than
     * saying so. The summary gives the name, the length and where it started;
     * anybody who needs the shape needs the file.
     */
    @Test
    fun aTrackIsSummarisedAndSaysWhereTheShapeIs() {
        val pkg = SharePackage(
            incidentName = "Burnt Creek 2026",
            tracks = listOf(
                ShareTrack(
                    id = "t", name = "Div Z morning",
                    distanceMeters = 6437.4,
                    points = List(400) { SharePoint(45.2 + it * 1e-4, -117.6) }
                )
            )
        )
        val message = ShareText.message(pkg)
        assertTrue(message.contains("Div Z morning"))
        assertTrue("distance in miles, was: $message", message.contains("4.0 mi"))
        assertTrue(message.contains("400 points"))
        assertTrue(message.contains("need the GPX file"))
    }

    @Test
    fun theAppSaysHowManyPartsAMessageWillArriveIn() {
        assertEquals(1, ShareText.segments("short"))
        assertEquals(1, ShareText.segments("x".repeat(160)))
        assertEquals(2, ShareText.segments("x".repeat(161)))
        assertEquals(2, ShareText.segments("x".repeat(306)))
        assertEquals(3, ShareText.segments("x".repeat(307)))
    }

    @Test
    fun aHandfulOfPinsFitsInAMessageWorthSending() {
        val pkg = SharePackage(
            incidentName = "Burnt Creek 2026",
            pins = (1..4).map {
                SharePin("p$it", "DP $it", 45.20 + it * 0.01, -117.63 - it * 0.01)
            }
        )
        val message = ShareText.message(pkg)
        assertTrue(
            "four pins should be comfortable, was ${message.length} characters",
            ShareText.isComfortable(message)
        )
    }

    @Test
    fun aMinuteThatRoundsUpDoesNotProduceSixtyMinutes() {
        // 45.99999 degrees is 45 degrees 59.9994 minutes. Rounded carelessly
        // this reads "45 60.000", which is not a coordinate.
        val text = ShareText.degreesDecimalMinutes(45.999_99, -117.0)
        assertTrue("was $text", !text.contains("60.000"))
        val parsed = CoordinateParser.parse(text)
        assertTrue(parsed is CoordinateParseResult.Success)
    }

    @Test
    fun minutesKeepTheirTrailingZerosSoTheyCannotBeMisread() {
        // "N 45 12.3" and "N 45 12.300" are the same number, but read off a
        // screen in a hurry the short one invites a missing digit.
        val text = ShareText.degreesDecimalMinutes(45.205, -117.5)
        assertTrue("was $text", text.contains("12.300"))
        assertTrue("was $text", text.contains("30.000"))
    }
}
