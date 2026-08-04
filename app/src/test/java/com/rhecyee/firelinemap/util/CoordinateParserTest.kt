package com.rhecyee.firelinemap.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The same position, read out every way it might come over a radio.
 *
 * 45.719620 N, 117.267328 W is the worked example from the format list.
 */
class CoordinateParserTest {

    private val latitude = 45.719620
    private val longitude = -117.267328

    private fun success(input: String): ParsedCoordinate {
        val result = CoordinateParser.parse(input)
        assertTrue("$input did not parse: $result", result is CoordinateParseResult.Success)
        return (result as CoordinateParseResult.Success).coordinate
    }

    @Test
    fun bareDecimalDegrees() {
        val parsed = success("45.719620 117.267328")
        assertEquals(latitude, parsed.latitude, 1e-6)
        // Longitude defaults west; every incident this serves is out there.
        assertEquals(longitude, parsed.longitude, 1e-6)
        assertEquals(CoordinateInputFormat.DECIMAL_DEGREES, parsed.format)
        assertTrue("the hemisphere was assumed and should say so", parsed.assumedHemisphere)
    }

    @Test
    fun signedDecimalDegreesWithAComma() {
        val parsed = success("45.719620, -117.267328")
        assertEquals(latitude, parsed.latitude, 1e-6)
        assertEquals(longitude, parsed.longitude, 1e-6)
        assertTrue("a typed sign is not an assumption", !parsed.assumedHemisphere)
    }

    @Test
    fun decimalDegreesWithHemisphereLetters() {
        val parsed = success("45.719620 N, 117.267328 W")
        assertEquals(latitude, parsed.latitude, 1e-6)
        assertEquals(longitude, parsed.longitude, 1e-6)
        assertTrue(!parsed.assumedHemisphere)
    }

    @Test
    fun bareDegreesAndMinutes() {
        // "forty five, forty three point one seven seven..."
        val parsed = success("45 43.177 117 16.040")
        assertEquals(latitude, parsed.latitude, 1e-5)
        assertEquals(longitude, parsed.longitude, 1e-5)
        assertEquals(CoordinateInputFormat.DEGREES_MINUTES, parsed.format)
    }

    @Test
    fun degreesAndMinutesWithSymbols() {
        val parsed = success("45°43.177' N, 117°16.040' W")
        assertEquals(latitude, parsed.latitude, 1e-5)
        assertEquals(longitude, parsed.longitude, 1e-5)
    }

    @Test
    fun bareDegreesMinutesSeconds() {
        val parsed = success("45 43 10.6 117 16 02.4")
        assertEquals(latitude, parsed.latitude, 1e-5)
        assertEquals(longitude, parsed.longitude, 1e-5)
        assertEquals(CoordinateInputFormat.DEGREES_MINUTES_SECONDS, parsed.format)
    }

    @Test
    fun degreesMinutesSecondsWithSymbols() {
        val parsed = success("""45°43'10.6" N, 117°16'02.4" W""")
        assertEquals(latitude, parsed.latitude, 1e-5)
        assertEquals(longitude, parsed.longitude, 1e-5)
    }

    @Test
    fun signedDegreesMinutesSeconds() {
        val parsed = success("""45°43'10.6", -117°16'02.4"""")
        assertEquals(latitude, parsed.latitude, 1e-5)
        assertEquals(longitude, parsed.longitude, 1e-5)
    }

    @Test
    fun everyFormatAgreesOnTheSamePosition() {
        val inputs = listOf(
            "45.719620 117.267328",
            "45.719620, -117.267328",
            "45.719620 N, 117.267328 W",
            "45 43.177 117 16.040",
            "45°43.177' N, 117°16.040' W",
            "45 43 10.6 117 16 02.4",
            """45°43'10.6" N, 117°16'02.4" W"""
        )
        for (input in inputs) {
            val parsed = success(input)
            assertEquals("$input latitude", latitude, parsed.latitude, 1e-4)
            assertEquals("$input longitude", longitude, parsed.longitude, 1e-4)
        }
    }

    @Test
    fun southAndEastAreHonoured() {
        val parsed = success("33 51.9 S 151 12.6 E")
        assertTrue("expected a southern latitude", parsed.latitude < 0)
        assertTrue("expected an eastern longitude", parsed.longitude > 0)
    }

    @Test
    fun partialEntryIsNotAnError() {
        // Halfway through someone reading it out. Odd counts cannot be any
        // format, so they read as still-typing rather than as a mistake.
        for (input in listOf("45", "45 43 10.6", "45 43 10.6 117 16")) {
            val result = CoordinateParser.parse(input)
            assertTrue(
                "$input should read as incomplete, not wrong",
                result is CoordinateParseResult.Incomplete
            )
        }
    }

    @Test
    fun twoNumbersIsAlwaysDecimalDegrees() {
        // "45 43" is both a valid decimal-degrees pair and the halfway point
        // of typing degrees and minutes. It has to resolve as the former --
        // refusing it would break the shortest format there is -- so the
        // caller shows the running interpretation and it corrects itself as
        // the remaining numbers arrive.
        val parsed = success("45 43")
        assertEquals(45.0, parsed.latitude, 1e-9)
        assertEquals(-43.0, parsed.longitude, 1e-9)
        assertEquals(CoordinateInputFormat.DECIMAL_DEGREES, parsed.format)

        val completed = success("45 43 10.6 117 16 02.4")
        assertEquals(CoordinateInputFormat.DEGREES_MINUTES_SECONDS, completed.format)
    }

    @Test
    fun emptyInputAsksForOne() {
        assertTrue(CoordinateParser.parse("") is CoordinateParseResult.Incomplete)
        assertTrue(CoordinateParser.parse("   ") is CoordinateParseResult.Incomplete)
    }

    @Test
    fun outOfRangeValuesAreRejected() {
        assertTrue(
            CoordinateParser.parse("95.0 117.0") is CoordinateParseResult.Invalid
        )
        assertTrue(
            CoordinateParser.parse("45.0 190.0") is CoordinateParseResult.Invalid
        )
        // Minutes and seconds are sixtieths, not hundredths.
        assertTrue(
            CoordinateParser.parse("45 75.0 117 16.0") is CoordinateParseResult.Invalid
        )
        assertTrue(
            CoordinateParser.parse("45 43 75.0 117 16 02.4") is CoordinateParseResult.Invalid
        )
    }

    @Test
    fun strayLettersAreRejectedRatherThanIgnored() {
        // X now means a missed digit, so it is no longer stray. Anything else is.
        assertTrue(CoordinateParser.parse("45.7 q 117.2") is CoordinateParseResult.Invalid)
        assertTrue(CoordinateParser.parse("45.7 abc 117.2") is CoordinateParseResult.Invalid)
    }

    @Test
    fun tooManyNumbersIsRejected() {
        assertTrue(
            CoordinateParser.parse("45 43 10 6 117 16 2 4") is CoordinateParseResult.Invalid
        )
    }

    @Test
    fun extraSpacingDoesNotMatter() {
        val parsed = success("  45   43.177    117   16.040  ")
        assertEquals(latitude, parsed.latitude, 1e-5)
        assertEquals(longitude, parsed.longitude, 1e-5)
    }

    @Test
    fun theParsedResultRoundTripsThroughTheFormatter() {
        val parsed = success("45 43.177 117 16.040")
        val formatted = CoordinateFormatter.format(
            parsed.latitude, parsed.longitude, CoordinateFormat.DDM
        )
        assertTrue("formatted as $formatted", formatted.contains("45°"))
        assertTrue("formatted as $formatted", formatted.startsWith("N"))
        assertTrue("formatted as $formatted", formatted.contains("W"))
    }
}

/** Copying a position off a radio that was not fully caught. */
class CoordinateSearchTest {

    private fun success(input: String): ParsedCoordinate {
        val result = CoordinateParser.parse(input)
        assertTrue("$input did not parse: $result", result is CoordinateParseResult.Success)
        return (result as CoordinateParseResult.Success).coordinate
    }

    @Test
    fun aDroppedDecimalIsPutBackInMinutes() {
        // "forty three one seven seven" with no decimal heard.
        val parsed = success("45 43177 117 16040")
        assertEquals(45.719620, parsed.latitude, 1e-5)
        assertEquals(-117.267328, parsed.longitude, 1e-5)
        assertTrue("should report the decimal was inferred", parsed.inferredDecimal)
        assertEquals(SearchShape.POINT, parsed.shape)
    }

    @Test
    fun aDroppedDecimalIsPutBackInDecimalDegrees() {
        val parsed = success("45719620 117267328")
        assertEquals(45.719620, parsed.latitude, 1e-5)
        assertEquals(-117.267328, parsed.longitude, 1e-5)
    }

    @Test
    fun anExactCoordinateIsAPoint() {
        val parsed = success("45 43.177 117 16.040")
        assertEquals(SearchShape.POINT, parsed.shape)
        assertEquals(parsed.southLatitude, parsed.northLatitude, 1e-9)
        assertTrue(!parsed.inferredDecimal)
    }

    @Test
    fun oneMissedDigitInLatitudeGivesALine() {
        // The last digit of the latitude minutes was not caught.
        val parsed = success("45 43.17X 117 16.040")
        assertEquals(SearchShape.LINE, parsed.shape)
        assertTrue(parsed.northLatitude > parsed.southLatitude)
        assertEquals(parsed.westLongitude, parsed.eastLongitude, 1e-9)
        // Still bracketed tightly: a tenth of a minute is about 185 m.
        assertTrue(parsed.northLatitude - parsed.southLatitude < 0.01)
    }

    @Test
    fun missedDigitsOnBothAxesGiveAnArea() {
        val parsed = success("45 43.1X7 117 16.0X0")
        assertEquals(SearchShape.AREA, parsed.shape)
        assertTrue(parsed.northLatitude > parsed.southLatitude)
        assertTrue(parsed.eastLongitude > parsed.westLongitude)
    }

    @Test
    fun aWhollyMissedSlotSpansItsRange() {
        // The minutes were not caught at all.
        val parsed = success("45 X 117 16.040")
        assertEquals(SearchShape.LINE, parsed.shape)
        // A whole degree of latitude, since any minute value is possible.
        assertEquals(1.0, parsed.northLatitude - parsed.southLatitude, 0.01)
    }

    @Test
    fun theSearchNarrowsAsDigitsArrive() {
        val vague = success("45 4X.XXX 117 16.040")
        val better = success("45 43.XXX 117 16.040")
        val exact = success("45 43.177 117 16.040")

        val vagueSpan = vague.northLatitude - vague.southLatitude
        val betterSpan = better.northLatitude - better.southLatitude
        val exactSpan = exact.northLatitude - exact.southLatitude

        assertTrue("expected narrowing", vagueSpan > betterSpan)
        assertTrue("expected narrowing", betterSpan > exactSpan)
        assertEquals(0.0, exactSpan, 1e-9)
    }

    @Test
    fun theTrueValueLiesInsideTheSearchedRange() {
        val parsed = success("45 43.1X7 117 16.0X0")
        assertTrue(45.719620 in parsed.southLatitude..parsed.northLatitude)
        assertTrue(-117.267328 in parsed.westLongitude..parsed.eastLongitude)
    }

    @Test
    fun wildcardsStillRespectHemisphere() {
        val parsed = success("45 43.1X7 117 16.040")
        assertTrue("latitude should be north", parsed.latitude > 0)
        assertTrue("longitude should be west", parsed.longitude < 0)
        assertTrue(parsed.eastLongitude <= 0)
    }

    @Test
    fun rubbishIsStillRejected() {
        assertTrue(CoordinateParser.parse("45 43 zz 117") is CoordinateParseResult.Invalid)
    }
}

/** Degrees and minutes typed as one number, because a space was awkward. */
class ConcatenatedCoordinateTest {

    private fun success(input: String): ParsedCoordinate {
        val result = CoordinateParser.parse(input)
        assertTrue("$input did not parse: $result", result is CoordinateParseResult.Success)
        return (result as CoordinateParseResult.Success).coordinate
    }

    @Test
    fun degreesAndMinutesRunTogether() {
        // Exactly what gets typed when the keypad makes a space hard to reach.
        val parsed = success("4159.713,10144.1135")
        assertEquals(41.995217, parsed.latitude, 1e-5)
        assertEquals(-101.735225, parsed.longitude, 1e-5)
    }

    @Test
    fun itAgreesWithTheSpacedForm() {
        val together = success("4159.713 10144.1135")
        val spaced = success("41 59.713 101 44.1135")
        assertEquals(spaced.latitude, together.latitude, 1e-9)
        assertEquals(spaced.longitude, together.longitude, 1e-9)
    }

    @Test
    fun degreesMinutesSecondsRunTogether() {
        val parsed = success("454310.6 1171602.4")
        assertEquals(45.719611, parsed.latitude, 1e-4)
        assertEquals(-117.267333, parsed.longitude, 1e-4)
    }

    @Test
    fun aPlainInRangeValueIsStillDegrees() {
        // 45.7 is a perfectly good latitude and must not be split.
        val parsed = success("45.719620 117.267328")
        assertEquals(45.719620, parsed.latitude, 1e-6)
        assertEquals(-117.267328, parsed.longitude, 1e-6)
    }

    @Test
    fun impossibleMinutesAreStillRejected() {
        // 4175.0 would be 41 degrees 75 minutes, which is not a position.
        val result = CoordinateParser.parse("4175.0 10144.0")
        assertTrue(result is CoordinateParseResult.Invalid)
    }
}
