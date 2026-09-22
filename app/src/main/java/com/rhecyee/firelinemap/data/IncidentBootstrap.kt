package com.rhecyee.firelinemap.data

import java.util.UUID

/**
 * Deciding which incident the app is working on.
 *
 * This used to live in the phone screen's first composition, and asked the
 * question of a Room flow that had not emitted yet:
 *
 *     val incidents by dao.observeIncidents().collectAsState(initial = emptyList())
 *     LaunchedEffect(Unit) { if (incidents.isEmpty()) { seed a new incident } }
 *
 * On every launch that read the initial empty list rather than the database, so
 * every launch inserted another active "Burnt Creek 2026". Nothing was ever
 * deleted -- markers, tracks and reports are filed under an incident id, and
 * the id they were filed under was no longer the active one. The name reverting
 * and the work vanishing were the same event seen twice.
 *
 * It also left the car with nothing to file against. The seed was in a Compose
 * effect, and Android Auto can start the service with the phone screen never
 * having run, so recording from the car found no active incident at all.
 *
 * So bootstrapping happens here, off an authoritative read, for both surfaces.
 */

/** The name given to an incident nobody has named yet. */
const val SEED_INCIDENT_NAME = "Burnt Creek 2026"

/** An incident and how much work is filed under it. */
data class IncidentUsage(val incident: IncidentEntity, val contentCount: Int)

/** What bootstrapping decided: which incident to work on, and what to tidy away. */
data class IncidentChoice(
    val activeId: String?,
    val discardable: List<String> = emptyList(),
    val seedRequired: Boolean = false,
)

/**
 * Picks the incident to work on, and the abandoned seeds to remove.
 *
 * The rules, in order:
 *
 * 1. Nothing at all: seed one.
 * 2. Something with work filed under it: the most recent of those wins, because
 *    it is the one somebody has actually been using.
 * 3. Nothing has any work: keep the most recent, which is the one already on
 *    screen, and let the rest go.
 *
 * Only unnamed empties are ever discarded. An incident somebody renamed is
 * theirs whether or not anything is filed under it yet, and an empty incident
 * that was deliberately created and named is not litter.
 */
fun chooseIncident(usage: List<IncidentUsage>): IncidentChoice {
    if (usage.isEmpty()) return IncidentChoice(activeId = null, seedRequired = true)

    val byNewest = usage.sortedByDescending { it.incident.createdAt }
    val used = byNewest.filter { it.contentCount > 0 }
    val keep = (used.firstOrNull() ?: byNewest.first()).incident

    val discardable = byNewest
        .filter { it.incident.id != keep.id }
        .filter { it.contentCount == 0 && it.incident.name == SEED_INCIDENT_NAME }
        .map { it.incident.id }

    return IncidentChoice(activeId = keep.id, discardable = discardable)
}

fun seedIncident(now: Long = System.currentTimeMillis()): IncidentEntity = IncidentEntity(
    id = UUID.randomUUID().toString(),
    name = SEED_INCIDENT_NAME,
    year = 2026,
    createdAt = now,
    isActive = true,
)

/**
 * Settles the active incident against the database and returns its id.
 *
 * Safe to call from anywhere, more than once, and from either surface: the
 * phone screen on first composition and the car session as it opens both go
 * through here, so whichever runs first leaves the other with an incident to
 * file against.
 */
suspend fun ensureActiveIncident(dao: FirelineDao): String {
    val existing = dao.allIncidents()
    val choice = chooseIncident(
        existing.map { IncidentUsage(it, dao.incidentContentCount(it.id)) }
    )

    if (choice.seedRequired || choice.activeId == null) {
        val seeded = seedIncident()
        dao.upsertIncident(seeded)
        dao.setActiveIncident(seeded.id)
        return seeded.id
    }

    // Every launch before this fix left one of these behind. They hold nothing,
    // and leaving them makes the incident list unreadable.
    choice.discardable.forEach { dao.deleteIncident(it) }
    dao.setActiveIncident(choice.activeId)
    return choice.activeId
}
