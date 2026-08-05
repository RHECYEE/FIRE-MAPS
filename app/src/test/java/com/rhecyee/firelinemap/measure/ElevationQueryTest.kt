package com.rhecyee.firelinemap.measure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a ground elevation is asked for and read, on both the phone and the page.
 *
 * Shared rather than written twice because the failure is silent: a reply the
 * two read differently produces two grades for one hillside, and a grade is
 * the kind of number a crew boss acts on.
 */
class ElevationQueryTest {

    @Test
    fun theRequestNamesThePositionAndTheUnits() {
        val url = ElevationQuery.url(45.719620, -117.267328)
        assertTrue(url, url.startsWith(ElevationQuery.ENDPOINT))
        assertTrue(url, url.contains("x=-117.267328"))
        assertTrue(url, url.contains("y=45.71962"))
        assertTrue(url, url.contains("units=Meters"))
        assertTrue(url, url.contains("wkid=4326"))
    }

    /** The service has returned the figure both ways depending on the day. */
    @Test
    fun aQuotedValueAndABareValueReadTheSame() {
        assertEquals(1417.5, ElevationQuery.parseValue("""{"value":"1417.5"}""")!!, 1e-9)
        assertEquals(1417.5, ElevationQuery.parseValue("""{"value":1417.5}""")!!, 1e-9)
        assertEquals(
            1417.5,
            ElevationQuery.parseValue("""{'value': "1417.5", 'units':'Meters'}""")!!,
            1e-9
        )
    }

    @Test
    fun negativeGroundIsRealAndIsKept() {
        // Death Valley is below sea level and is not a missing answer.
        assertEquals(-86.0, ElevationQuery.parseValue("""{"value":"-86.0"}""")!!, 1e-9)
    }

    /**
     * The one that matters.
     *
     * Where the service has no coverage it answers with a sentinel. Read as a
     * number that is an elevation a thousand kilometres underground, and it
     * would carry straight through into a slope of several thousand percent
     * without anything on screen looking wrong.
     */
    @Test
    fun theNoCoverageSentinelIsNotAnElevation() {
        assertNull(ElevationQuery.parseValue("""{"value":"-1000000"}"""))
        assertNull(ElevationQuery.parseValue("""{"value":-1000000.0}"""))
        assertNull(ElevationQuery.parseValue("""{"value":"-999999999"}"""))
    }

    @Test
    fun anAnswerWithNoValueIsNoAnswer() {
        assertNull(ElevationQuery.parseValue("""{"error":"out of range"}"""))
        assertNull(ElevationQuery.parseValue(""))
        assertNull(ElevationQuery.parseValue("<html>503</html>"))
    }

    /** The service and the shared rules cannot drift apart. */
    @Test
    fun theServiceReadsRepliesThroughTheSharedRule() {
        assertEquals(ElevationQuery.ENDPOINT, ElevationService.ENDPOINT)
        assertEquals(
            ElevationQuery.parseValue("""{"value":"12.5"}"""),
            ElevationService.parseValue("""{"value":"12.5"}""")
        )
        assertNull(ElevationService.parseValue("""{"value":"-1000000"}"""))
    }
}
