package com.rhecyee.firelinemap.incident

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * What changing incident does, and what it must not do.
 *
 * The two halves fail in opposite directions and both are expensive. Carrying
 * incident data across a switch puts last week's division breaks on this week's
 * fire. Throwing away shared ground makes the operator re-download a district
 * over whatever signal is left at the end of a road, which is how a phone
 * becomes useless at the moment it is needed.
 */
class IncidentScopeTest {

    private fun at(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance(Locale.US).apply {
            clear()
            set(year, month, day, 9, 0, 0)
        }.timeInMillis

    @Test
    fun everythingRecordedOnAFireIsLeftBehindWithIt() {
        val cleared = IncidentData.cleared
        assertTrue(IncidentData.MARKERS in cleared)
        assertTrue(IncidentData.TRACKS in cleared)
        assertTrue(IncidentData.MEDICAL in cleared)
        assertTrue(IncidentData.MEASUREMENTS in cleared)
        // The one that had actually been getting through. Sheets used to share
        // a single folder, so every product map ever imported turned up on
        // every incident afterwards with only a filename to tell them apart.
        assertTrue("sheets must not follow the operator", IncidentData.SHEETS in cleared)
    }

    @Test
    fun groundIsNotIncidentDataAndIsNeverThrownAway() {
        val kept = IncidentData.kept
        assertTrue("ridges do not move", IncidentData.TERRAIN in kept)
        assertTrue(IncidentData.BASEMAP in kept)
        assertTrue(IncidentData.REGIONS in kept)
        // Nor is who you are, or how you have set the app up to work.
        assertTrue(IncidentData.PROFILE in kept)
        assertTrue(IncidentData.SETTINGS in kept)
    }

    @Test
    fun everyKindIsDecidedOneWayOrTheOther() {
        assertEquals(
            IncidentData.entries.size,
            IncidentData.cleared.size + IncidentData.kept.size
        )
        // And every one can say why, because this is the list the operator is
        // shown before they commit to a switch.
        IncidentData.entries.forEach {
            assertTrue(it.name, it.label.isNotBlank())
            assertTrue(it.name, it.reason.isNotBlank())
        }
    }

    @Test
    fun aPlaceholderIsDatedAndObviouslyNotADispatchName() {
        val name = IncidentNaming.placeholder(at(2026, Calendar.AUGUST, 5))
        assertEquals("New incident — Aug 5", name)
        // Two unnamed ones on different days must still be tellable apart.
        assertTrue(
            name != IncidentNaming.placeholder(at(2026, Calendar.AUGUST, 6))
        )
    }

    @Test
    fun aTypedNameIsTidiedRatherThanRefused() {
        assertEquals("Burnt Creek 2026", IncidentNaming.clean("  Burnt Creek 2026  "))
        // Gloves and a phone keyboard produce doubled spaces constantly.
        assertEquals("Burnt Creek", IncidentNaming.clean("Burnt   Creek"))
        assertEquals("Burnt Creek", IncidentNaming.clean("Burnt\tCreek"))
    }

    @Test
    fun aBlankNameFallsBackRatherThanLeavingAnUnnamedIncident() {
        val now = at(2026, Calendar.AUGUST, 5)
        assertEquals(IncidentNaming.placeholder(now), IncidentNaming.clean("", now))
        assertEquals(IncidentNaming.placeholder(now), IncidentNaming.clean("   ", now))
    }

    @Test
    fun aNameTooLongForATitleBarIsCutRatherThanWrapped() {
        val long = "A".repeat(200)
        assertEquals(IncidentNaming.MAX_LENGTH, IncidentNaming.clean(long).length)
    }

    @Test
    fun theYearComesOutOfTheNameWhenSomebodyTypedItThere() {
        val now = at(2026, Calendar.AUGUST, 5)
        assertEquals(2026, IncidentNaming.yearOf("Burnt Creek 2026", now))
        assertEquals(2024, IncidentNaming.yearOf("Dixie 2024", now))
        // Next season's assignment, typed early, is plausible.
        assertEquals(2027, IncidentNaming.yearOf("Burnt Creek 2027", now))
    }

    @Test
    fun aNumberThatIsNotAYearDoesNotBecomeOne() {
        val now = at(2026, Calendar.AUGUST, 5)
        // An incident number, a road, a helispot -- all far commoner in a name
        // than a year, and none of them are one.
        assertEquals(2026, IncidentNaming.yearOf("OR-WWF-000432", now))
        assertEquals(2026, IncidentNaming.yearOf("Div Z Road 5200", now))
        assertEquals(2026, IncidentNaming.yearOf("Burnt Creek", now))
        // Nor is a year that has not happened yet.
        assertEquals(2026, IncidentNaming.yearOf("Burnt Creek 2099", now))
    }

    @Test
    fun aRepeatedNameIsFlaggedButNotForbidden() {
        val existing = listOf("Burnt Creek 2026", "Dixie")
        assertTrue(IncidentNaming.clashes("Burnt Creek 2026", existing))
        // Case and stray spaces are how the duplicate actually gets typed.
        assertTrue(IncidentNaming.clashes("burnt creek 2026", existing))
        assertTrue(IncidentNaming.clashes("  Dixie ", existing))
        assertFalse(IncidentNaming.clashes("Burnt Creek 2027", existing))
        assertFalse(IncidentNaming.clashes("Anything", emptyList()))
    }
}
