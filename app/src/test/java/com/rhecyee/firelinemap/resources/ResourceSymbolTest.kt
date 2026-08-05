package com.rhecyee.firelinemap.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The palette a crew picks from with gloves on.
 *
 * Every entry has to be distinguishable at a glance, and no entry may appear
 * twice: the list showed a medic and a dozer each twice, because the leading
 * few were prepended to a list that already contained them.
 */
class ResourceSymbolTest {

    @Test
    fun nothingIsOfferedTwice() {
        assertEquals(ResourceSymbol.RESOURCES.distinct().size, ResourceSymbol.RESOURCES.size)
        assertEquals(ResourceSymbol.POINTS.distinct().size, ResourceSymbol.POINTS.size)
    }

    @Test
    fun everySymbolIsOfferedSomewhere() {
        for (symbol in ResourceSymbol.entries) {
            assertTrue("${symbol.id} is unreachable", symbol in ResourceSymbol.RESOURCES)
        }
    }

    @Test
    fun noTwoSymbolsCarryTheSameLettersOrTheSameName() {
        // Two pins reading MED are two pins nobody can tell apart on a map.
        val glyphs = ResourceSymbol.entries.map { it.glyph }
        assertEquals("duplicate glyphs: $glyphs", glyphs.distinct().size, glyphs.size)
        val labels = ResourceSymbol.entries.map { it.label.lowercase() }
        assertEquals("duplicate labels: $labels", labels.distinct().size, labels.size)
    }

    @Test
    fun identifiersAreUniqueAndStable() {
        val ids = ResourceSymbol.entries.map { it.id }
        assertEquals(ids.distinct().size, ids.size)
        for (symbol in ResourceSymbol.entries) {
            assertEquals(symbol, ResourceSymbol.byId(symbol.id))
        }
    }

    /**
     * A pin dropped last season keeps its meaning.
     *
     * Identifiers are written into the database. Retiring one without a
     * forwarding address turns a dozer somebody placed into a generic dot.
     */
    @Test
    fun aRetiredSymbolStillResolvesToWhatItBecame() {
        assertEquals(ResourceSymbol.DOZER, ResourceSymbol.byId("dozer_crew"))
        assertEquals(ResourceSymbol.OTHER, ResourceSymbol.byId("nothing_like_this"))
        assertEquals(ResourceSymbol.OTHER, ResourceSymbol.byId(null))
    }

    @Test
    fun theOnesPlacedMostComeFirst() {
        assertEquals(ResourceSymbol.HAND_CREW, ResourceSymbol.RESOURCES.first())
        assertTrue(ResourceSymbol.RESOURCES.take(6).contains(ResourceSymbol.DOZER))
        assertTrue(ResourceSymbol.RESOURCES.take(6).contains(ResourceSymbol.HAZARD))
    }
}
