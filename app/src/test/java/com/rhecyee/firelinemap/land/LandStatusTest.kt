package com.rhecyee.firelinemap.land

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the free land services.
 *
 * Every response below was taken from the live service for the ground it
 * describes, rather than invented. The shapes these return are the only thing
 * standing between a crew and a wrong answer about whose ground they are on,
 * and a guessed shape parses a guessed answer.
 */
class LandStatusTest {

    /** Burnt Creek, in the Wallowas. */
    private val whitman = """
        {"features":[{"attributes":{"Own_Name":"USFS","Mang_Name":"USFS",
        "Mang_Type":"FED","Loc_Own":"USDA FOREST SERVICE",
        "Unit_Nm":"Whitman National Forest","Des_Tp":"NF"}}]}
    """.trimIndent()

    /** Glacier, which is a park rather than a forest. */
    private val glacier = """
        {"features":[{"attributes":{"Own_Name":"NPS","Mang_Name":"NPS",
        "Mang_Type":"FED","Loc_Own":"NPS","Unit_Nm":"Glacier National Park",
        "Des_Tp":"NP"}}]}
    """.trimIndent()

    /** A BLM field office, which is the unit name a dispatcher would use. */
    private val newcastle = """
        {"features":[{"attributes":{"Own_Name":"BLM","Mang_Name":"BLM",
        "Mang_Type":"FED","Loc_Own":"BLM","Unit_Nm":"Newcastle Field Office",
        "Des_Tp":"PUB"}}]}
    """.trimIndent()

    /** Oregon state forest land, where the owner and manager differ. */
    private val stateForest = """
        {"features":[{"attributes":{"Own_Name":"SDNR","Mang_Name":"SLB",
        "Mang_Type":"STAT","Loc_Own":"Unknown",
        "Unit_Nm":"Forest Development Fund (Board Of Forestry)","Des_Tp":"SRMA"}}]}
    """.trimIndent()

    /** What the service returns over private ground: nothing. */
    private val nothing = """{"features":[]}"""

    private val unionCounty = """
        {"features":[{"attributes":{"BASENAME":"Union","STATE":"41","COUNTY":"061"}}]}
    """.trimIndent()

    private val oregon = """
        {"features":[{"attributes":{"BASENAME":"Oregon","STUSAB":"OR"}}]}
    """.trimIndent()

    @Test
    fun theUnitIsReadByTheNamePeopleUseForIt() {
        val unit = LandStatusParser.parseProtectedUnit(whitman)
        assertNotNull(unit)
        assertEquals("Whitman National Forest", unit!!.name)
        assertEquals(LandAgency.USFS, unit.agency)
        assertEquals("National Forest", unit.designationLabel())
        assertTrue(unit.isFederal)
        assertTrue(!unit.isState)
    }

    @Test
    fun aParkAndAFieldOfficeAreBothRecognised() {
        val park = LandStatusParser.parseProtectedUnit(glacier)!!
        assertEquals("Glacier National Park", park.name)
        assertEquals(LandAgency.NPS, park.agency)
        assertEquals("National Park", park.designationLabel())

        val office = LandStatusParser.parseProtectedUnit(newcastle)!!
        assertEquals("Newcastle Field Office", office.name)
        assertEquals(LandAgency.BLM, office.agency)
        assertEquals("Public land", office.designationLabel())
    }

    @Test
    fun stateGroundIsNotMistakenForFederal() {
        val unit = LandStatusParser.parseProtectedUnit(stateForest)!!
        assertEquals("Forest Development Fund (Board Of Forestry)", unit.name)
        assertTrue("state ground must not read as federal", !unit.isFederal)
        assertTrue(unit.isState)
        // "Unknown" is how these services write nothing, and it must not be
        // shown to anyone as though it were a name.
        assertNull(unit.localOwner)
    }

    @Test
    fun noRecordIsAnAnswerRatherThanAFailure() {
        // The protected areas layer is silent over private ground. Silence
        // there means private, not broken, and the two must not look alike.
        assertNull(LandStatusParser.parseProtectedUnit(nothing))
        assertNull(LandStatusParser.parseProtectedUnit("{}"))
    }

    @Test
    fun theCountyIsReadWithItsState() {
        val county = LandStatusParser.parseCounty(unionCounty, oregon)
        assertNotNull(county)
        assertEquals("Union", county!!.county)
        assertEquals("OR", county.stateCode)
        assertEquals("Oregon", county.stateName)
        assertEquals("41061", county.fips)
        assertEquals("Union County, OR", county.label)
    }

    @Test
    fun aCountyStillReadsWithoutItsState() {
        val county = LandStatusParser.parseCounty(unionCounty, null)!!
        assertEquals("Union County", county.label)
        assertEquals("41061", county.fips)
    }

    @Test
    fun aCountyAlreadyNamedCountyIsNotNamedTwice() {
        val already = """{"features":[{"attributes":{"BASENAME":"Union County"}}]}"""
        assertEquals("Union County", LandStatusParser.parseCounty(already, null)!!.label)
        // Two-word names are left alone rather than having County appended:
        // "Grand Forks County" is right, "Lewis and Clark County" is right,
        // and guessing wrongly on either is worse than saying less.
        val twoWord = """{"features":[{"attributes":{"BASENAME":"Grand Forks"}}]}"""
        assertEquals("Grand Forks", LandStatusParser.parseCounty(twoWord, null)!!.label)
    }

    /**
     * The three sources answer different questions, and the combination has to
     * put the most useful one first.
     */
    @Test
    fun theHeadlineIsTheNameSomebodyWouldSayOnTheRadio() {
        val full = LandStatus(
            owner = LandOwner(LandAgency.USFS, "USDA", "USFS", "OR"),
            unit = LandStatusParser.parseProtectedUnit(whitman),
            county = LandStatusParser.parseCounty(unionCounty, oregon)
        )
        assertEquals("Whitman National Forest", full.headline())
        assertEquals(LandAgency.USFS, full.agency())
        assertTrue(!full.isEmpty)
    }

    @Test
    fun theAgencyFallsBackToTheSurfaceLayerWhenThereIsNoUnit() {
        val surfaceOnly = LandStatus(
            owner = LandOwner(LandAgency.BLM, "DOI", null, "OR"),
            county = LandStatusParser.parseCounty(unionCounty, oregon)
        )
        assertEquals(LandAgency.BLM, surfaceOnly.agency())
        assertTrue(surfaceOnly.headline()!!.contains("Bureau of Land Management"))
    }

    @Test
    fun privateGroundStillGetsAnAnswerFromTheCounty() {
        // The case the protected areas layer cannot help with at all, and the
        // one where knowing who dispatches matters most.
        val private = LandStatus(
            owner = LandOwner(LandAgency.PRIVATE, null, null, "OR"),
            unit = null,
            county = LandStatusParser.parseCounty(unionCounty, oregon)
        )
        assertTrue(!private.isEmpty)
        assertEquals("Union County, OR", private.county!!.label)
        assertEquals(LandAgency.PRIVATE, private.agency())
    }

    @Test
    fun nothingAtAllIsEmpty() {
        assertTrue(LandStatus().isEmpty)
        assertNull(LandStatus().headline())
        assertNull(LandStatus().agency())
    }

    @Test
    fun everySourceIsKeylessAndOverHttps() {
        // The whole tool exists for people who cannot get onto agency
        // infrastructure. A source behind a key would defeat it.
        for (url in listOf(
            LandStatusParser.PROTECTED_ENDPOINT,
            LandStatusParser.COUNTY_ENDPOINT,
            LandStatusParser.STATE_ENDPOINT,
            LandOwnershipParser.ENDPOINT
        )) {
            assertTrue(url, url.startsWith("https://"))
            assertTrue(url, !url.contains("apikey") && !url.contains("token"))
        }
    }

    @Test
    fun theQueriesCarryThePositionTheyWereAskedAbout() {
        val url = LandStatusParser.protectedUnitUrl(45.20575, -117.6370)
        assertTrue(url, url.contains("-117.637"))
        assertTrue(url, url.contains("45.20575"))
        assertTrue("must not fetch geometry it will not draw", url.contains("returnGeometry=false"))
        assertTrue(url.contains("Unit_Nm"))
    }

    @Test
    fun attributionNamesWhoAnswered() {
        assertTrue(LandStatusParser.PROTECTED_ATTRIBUTION.contains("PAD-US"))
        assertTrue(LandStatusParser.COUNTY_ATTRIBUTION.contains("Census"))
    }

    @Test
    fun aTruncatedResponseIsRefusedRatherThanHalfRead() {
        assertNull(LandStatusParser.firstAttributes("""{"features":[{"attributes":{"Unit_Nm":"""))
        assertNull(LandStatusParser.parseProtectedUnit("not json at all"))
        assertNull(LandStatusParser.parseCounty("", null))
    }
}
