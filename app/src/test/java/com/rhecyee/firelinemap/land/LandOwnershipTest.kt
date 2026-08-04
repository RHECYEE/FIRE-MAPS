package com.rhecyee.firelinemap.land

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinned to what the service actually returned.
 *
 * Both payloads below are the shapes that came back from the live Surface
 * Management Agency service during development: national forest at Burnt
 * Creek, and private ground out on the plains.
 */
class LandOwnershipTest {

    private val nationalForest = """
        {"results":[{"layerId":0,"layerName":"Surface Management Agency","value":"468",
        "attributes":{"OBJECTID":"468","SMA_ID":"915","HOLD_ID":"Null",
        "ADMIN_DEPT_CODE":"USDA","ADMIN_AGENCY_CODE":"USFS","ADMIN_UNIT_NAME":"Null",
        "ADMIN_UNIT_TYPE":"Null","ADMIN_ST":"OR","FAU_ID":"Null"}}]}
    """.trimIndent()

    private val privateGround = """
        {"results":[{"layerId":0,"layerName":"Surface Management Agency","value":"12",
        "attributes":{"ADMIN_DEPT_CODE":"PVT","ADMIN_AGENCY_CODE":"PVT",
        "ADMIN_UNIT_NAME":"Null","ADMIN_ST":"WY"}}]}
    """.trimIndent()

    @Test
    fun readsNationalForest() {
        val owner = LandOwnershipParser.parseIdentify(nationalForest)!!
        assertEquals(LandAgency.USFS, owner.agency)
        assertEquals("USDA", owner.departmentCode)
        assertEquals("OR", owner.stateCode)
        assertTrue(owner.agency.isFederal)
        assertFalse(owner.isPrivate)
        assertEquals("US Forest Service", owner.summary())
    }

    @Test
    fun readsPrivateGround() {
        val owner = LandOwnershipParser.parseIdentify(privateGround)!!
        assertEquals(LandAgency.PRIVATE, owner.agency)
        assertTrue(owner.isPrivate)
        assertFalse(owner.agency.isFederal)
    }

    @Test
    fun nullPlaceholdersDoNotBecomeText() {
        // The service writes the string "Null" rather than a JSON null.
        val owner = LandOwnershipParser.parseIdentify(nationalForest)!!
        assertNull(owner.unitName)
        assertTrue("summary should not carry a placeholder", !owner.summary().contains("Null"))
    }

    @Test
    fun everyAnswerIsMarkedApproximate() {
        // The national layer is generalised for display: a query on the plains
        // came back carrying a Wyoming state code during testing, so nothing
        // from it should be presented as a land status record.
        assertTrue(LandOwnershipParser.parseIdentify(nationalForest)!!.approximate)
        assertTrue(LandOwnershipParser.parseIdentify(privateGround)!!.approximate)
    }

    @Test
    fun anEmptyResponseYieldsNothing() {
        assertNull(LandOwnershipParser.parseIdentify("""{"results":[]}"""))
        assertNull(LandOwnershipParser.parseIdentify(""))
        assertNull(LandOwnershipParser.parseIdentify("not json"))
    }

    @Test
    fun theDepartmentStandsInWhenTheAgencyIsBlank() {
        val json = """{"results":[{"attributes":{"ADMIN_DEPT_CODE":"PVT",
            "ADMIN_AGENCY_CODE":"Null","ADMIN_ST":"MT"}}]}"""
        val owner = LandOwnershipParser.parseIdentify(json)!!
        assertEquals(LandAgency.PRIVATE, owner.agency)
    }

    @Test
    fun theAgenciesThatMatterOnAFireAreRecognised() {
        assertEquals(LandAgency.USFS, LandAgency.fromCode("USFS"))
        assertEquals(LandAgency.BLM, LandAgency.fromCode("BLM"))
        assertEquals(LandAgency.NPS, LandAgency.fromCode("NPS"))
        assertEquals(LandAgency.FWS, LandAgency.fromCode("FWS"))
        assertEquals(LandAgency.BIA, LandAgency.fromCode("BIA"))
        assertEquals(LandAgency.STATE, LandAgency.fromCode("STATE"))
        assertEquals(LandAgency.PRIVATE, LandAgency.fromCode("PVT"))
    }

    @Test
    fun anUnrecognisedCodeIsOtherAndAnAbsentOneIsUnknown() {
        assertEquals(LandAgency.OTHER, LandAgency.fromCode("ZZZ"))
        assertEquals(LandAgency.UNKNOWN, LandAgency.fromCode(null))
        assertEquals(LandAgency.UNKNOWN, LandAgency.fromCode(""))
        assertEquals(LandAgency.UNKNOWN, LandAgency.fromCode("Null"))
    }

    @Test
    fun federalIsDistinguishedFromStateAndPrivate() {
        assertTrue(LandAgency.USFS.isFederal)
        assertTrue(LandAgency.BLM.isFederal)
        assertFalse(LandAgency.STATE.isFederal)
        assertFalse(LandAgency.PRIVATE.isFederal)
        assertFalse(LandAgency.UNKNOWN.isFederal)
    }

    @Test
    fun theQueryUrlCarriesThePositionItWasAskedAbout() {
        val url = LandOwnershipParser.identifyUrl(45.7196, -117.2673)
        assertTrue(url.startsWith(LandOwnershipParser.ENDPOINT))
        assertTrue(url.contains("45.7196"))
        assertTrue(url.contains("-117.2673"))
        assertTrue(url.contains("f=json"))
        assertTrue(
            "geometry must not be returned; only the attributes are wanted",
            url.contains("returnGeometry=false")
        )
    }
}
