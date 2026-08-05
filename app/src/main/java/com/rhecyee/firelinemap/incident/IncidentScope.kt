package com.rhecyee.firelinemap.incident

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * One kind of thing the app holds, and whether it belongs to an incident.
 *
 * Written down rather than left implicit because the two halves have opposite
 * consequences when they are got wrong. Carrying incident data across a switch
 * puts last week's division breaks on this week's fire, which is worse than
 * having no map at all. Throwing away shared ground makes the operator
 * re-download a district over whatever signal is left at the end of a road,
 * which is how a phone becomes useless at the moment it is needed.
 */
enum class IncidentData(
    val label: String,
    /** True when a switch must leave it behind. */
    val perIncident: Boolean,
    val reason: String
) {
    MARKERS("Pins", true, "Where this fire's resources, hazards and drop points are"),
    TRACKS("Tracks", true, "Where this fire's crews actually went"),
    MEDICAL("Medical reports", true, "Patient records belong to the incident they were taken on"),
    MEASUREMENTS("Measurements", true, "Distances and areas were taken off this fire's ground"),
    SHEETS("Map sheets", true, "IAP and ops products are printed per incident and per shift"),

    TERRAIN("Terrain and contours", false, "Ridges do not move between fires"),
    BASEMAP("Downloaded basemap", false, "The same district serves every incident in it"),
    REGIONS("Offline areas", false, "Downloaded once, over whatever signal there was"),
    PROFILE("Your name and qualification", false, "You are the same person on the next fire"),
    SETTINGS("Settings", false, "Units, intervals and layer choices are how you work");

    companion object {
        val cleared: List<IncidentData> get() = entries.filter { it.perIncident }
        val kept: List<IncidentData> get() = entries.filter { !it.perIncident }
    }
}

/**
 * Naming an incident.
 *
 * A fire has a name before it has anything else, and it is the name that goes
 * over the radio -- so the operator types it, rather than the app inventing one
 * and making them live with it. What the app supplies is a placeholder good
 * enough to start working under before anybody has stopped to type.
 */
object IncidentNaming {

    /** Longest name worth holding. Past this it stops fitting a title bar. */
    const val MAX_LENGTH = 60

    /**
     * What a brand new incident is called until it is named.
     *
     * Dated, so two unnamed ones are still tellable apart, and obviously a
     * placeholder so nobody mistakes it for a dispatch name.
     */
    fun placeholder(nowMillis: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("MMM d", Locale.US).format(java.util.Date(nowMillis))
        return "New incident — $stamp"
    }

    /** Trims and caps what was typed. Blank falls back to the placeholder. */
    fun clean(typed: String, nowMillis: Long = System.currentTimeMillis()): String {
        val trimmed = typed.trim().replace(WHITESPACE, " ")
        if (trimmed.isEmpty()) return placeholder(nowMillis)
        return trimmed.take(MAX_LENGTH)
    }

    /**
     * The year an incident belongs to.
     *
     * Taken from the name when one was typed into it, because that is how
     * incidents are actually written -- "Burnt Creek 2026" -- and a name
     * carrying a year that disagrees with the record is the kind of thing
     * nobody notices until an export is wrong. Anything not plausibly a fire
     * season is ignored rather than trusted.
     */
    fun yearOf(name: String, nowMillis: Long = System.currentTimeMillis()): Int {
        val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val thisYear = calendar.get(Calendar.YEAR)
        val found = YEAR.findAll(name)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in 1900..thisYear + 1 }
            .lastOrNull()
        return found ?: thisYear
    }

    /**
     * Whether two incidents would be indistinguishable in a list.
     *
     * Duplicate names are allowed -- the same fire genuinely does come back
     * the following season -- but the operator is told, because the usual
     * cause is starting a second incident for one that is already there and
     * then wondering where the morning's pins went.
     */
    fun clashes(name: String, existing: List<String>): Boolean =
        existing.any { it.trim().equals(name.trim(), ignoreCase = true) }

    private val WHITESPACE = Regex("\\s+")
    private val YEAR = Regex("\\b(19|20)\\d{2}\\b")
}
