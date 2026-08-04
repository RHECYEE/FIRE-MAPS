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
        assertTrue(CoordinateParser.parse("45.7 x 117.2") is CoordinateParseResult.Invalid)
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
