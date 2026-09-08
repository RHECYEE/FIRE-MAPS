package com.rhecyee.firelinemap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which incident the app is working on.
 *
 * The reported fault was that closing the app deleted everything and put the
 * name back to Burnt Creek. Nothing was deleted. The seed asked a Room flow
 * whether any incident existed before that flow had emitted, read the initial
 * empty list, and inserted a fresh active incident on every launch. Markers,
 * tracks and reports are filed under an incident id, so they stayed under the
 * previous one and the screen showed an empty new incident wearing the seed
 * name. Losing the work and losing the name were one event seen twice.
 */
class IncidentBootstrapTest {

    private var clock = 1_000L

    private fun incident(
        name: String = SEED_INCIDENT_NAME,
        id: String = "i${clock}",
        createdAt: Long = clock++,
    ) = IncidentEntity(
        id = id, name = name, year = 2026, createdAt = createdAt, isActive = false
    )

    private fun usage(vararg pairs: Pair<IncidentEntity, Int>) =
        pairs.map { IncidentUsage(it.first, it.second) }

    @Test
    fun `an empty database is seeded`() {
        val choice = chooseIncident(emptyList())
        assertTrue(choice.seedRequired)
        assertEquals(null, choice.activeId)
        assertTrue(choice.discardable.isEmpty())
    }

    @Test
    fun `an existing incident is never re-seeded`() {
        val only = incident(name = "Magote Ridge")
        val choice = chooseIncident(usage(only to 0))
        assertFalse(choice.seedRequired)
        assertEquals(only.id, choice.activeId)
    }

    @Test
    fun `the incident holding the work wins over a newer empty seed`() {
        // Exactly the reported situation: a renamed incident with everything in
        // it, buried under the empty seeds each launch left on top.
        val real = incident(name = "Magote Ridge")
        val seededLater = incident()
        val seededLast = incident()

        val choice = chooseIncident(
            usage(real to 12, seededLater to 0, seededLast to 0)
        )

        assertEquals(real.id, choice.activeId)
        assertEquals(setOf(seededLater.id, seededLast.id), choice.discardable.toSet())
    }

    @Test
    fun `the newest incident holding work wins over an older one`() {
        val older = incident(name = "Last week")
        val newer = incident(name = "Today")
        val choice = chooseIncident(usage(older to 5, newer to 3))
        assertEquals(newer.id, choice.activeId)
        // Neither is litter: both hold work, so neither is discarded.
        assertTrue(choice.discardable.isEmpty())
    }

    @Test
    fun `an empty incident somebody named is kept`() {
        // Naming it is the act that makes it theirs. It may be the incident
        // they are about to work, and it holds nothing precisely because they
        // have not started yet.
        val named = incident(name = "Tomorrow's assignment")
        val seed = incident()
        val choice = chooseIncident(usage(seed to 4, named to 0))
        assertEquals(seed.id, choice.activeId)
        assertTrue("a named incident is not litter", choice.discardable.isEmpty())
    }

    @Test
    fun `with nothing filed anywhere the newest is kept and the seeds go`() {
        val first = incident()
        val second = incident()
        val third = incident()
        val choice = chooseIncident(usage(first to 0, second to 0, third to 0))
        assertEquals(third.id, choice.activeId)
        assertEquals(setOf(first.id, second.id), choice.discardable.toSet())
    }

    @Test
    fun `the chosen incident is never in the discard list`() {
        val kept = incident(name = "Magote Ridge")
        val choice = chooseIncident(
            usage(kept to 9, incident() to 0, incident() to 0, incident() to 0)
        )
        assertFalse(choice.activeId in choice.discardable)
    }

    @Test
    fun `a seeded incident is active and carries the seed name`() {
        val seeded = seedIncident(now = 42L)
        assertTrue(seeded.isActive)
        assertEquals(SEED_INCIDENT_NAME, seeded.name)
        assertEquals(42L, seeded.createdAt)
        assertTrue(seeded.id.isNotBlank())
    }

    @Test
    fun `two seeds are different incidents`() {
        assertFalse(seedIncident().id == seedIncident().id)
    }
}
