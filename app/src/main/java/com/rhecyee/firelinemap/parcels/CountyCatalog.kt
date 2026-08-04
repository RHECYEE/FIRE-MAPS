package com.rhecyee.firelinemap.parcels

import android.content.Context

/** One county, as the parcel provider organises them. */
data class CountyRecord(
    val fips: String,
    val stateCode: String,
    val countyName: String
) {
    val stateName: String get() = STATE_NAMES[stateCode] ?: stateCode

    /** "Wallowa County, OR". */
    val label: String get() = "$countyName, $stateCode"

    val searchName: String get() = "$countyName $stateCode $stateName $fips".lowercase()

    companion object {
        val STATE_NAMES = mapOf(
            "AL" to "Alabama", "AK" to "Alaska", "AZ" to "Arizona", "AR" to "Arkansas",
            "CA" to "California", "CO" to "Colorado", "CT" to "Connecticut",
            "DE" to "Delaware", "DC" to "District of Columbia", "FL" to "Florida",
            "GA" to "Georgia", "HI" to "Hawaii", "ID" to "Idaho", "IL" to "Illinois",
            "IN" to "Indiana", "IA" to "Iowa", "KS" to "Kansas", "KY" to "Kentucky",
            "LA" to "Louisiana", "ME" to "Maine", "MD" to "Maryland",
            "MA" to "Massachusetts", "MI" to "Michigan", "MN" to "Minnesota",
            "MS" to "Mississippi", "MO" to "Missouri", "MT" to "Montana",
            "NE" to "Nebraska", "NV" to "Nevada", "NH" to "New Hampshire",
            "NJ" to "New Jersey", "NM" to "New Mexico", "NY" to "New York",
            "NC" to "North Carolina", "ND" to "North Dakota", "OH" to "Ohio",
            "OK" to "Oklahoma", "OR" to "Oregon", "PA" to "Pennsylvania",
            "RI" to "Rhode Island", "SC" to "South Carolina", "SD" to "South Dakota",
            "TN" to "Tennessee", "TX" to "Texas", "UT" to "Utah", "VT" to "Vermont",
            "VA" to "Virginia", "WA" to "Washington", "WV" to "West Virginia",
            "WI" to "Wisconsin", "WY" to "Wyoming", "PR" to "Puerto Rico",
            "AS" to "American Samoa", "GU" to "Guam", "MP" to "Northern Mariana Islands",
            "VI" to "US Virgin Islands"
        )
    }
}

/**
 * The county list, carried in the app.
 *
 * Bundled rather than fetched: choosing which county to download is the step
 * before there is any parcel data, and it happens wherever the incident is.
 * Needing a connection to look up a county name would defeat the point. The
 * list is the Census national county file, which is public domain and about
 * 76 KB.
 */
class CountyCatalog(private val context: Context) {

    private val counties: List<CountyRecord> by lazy { load() }

    val size: Int get() = counties.size

    fun all(): List<CountyRecord> = counties

    /**
     * Finds counties by name, state, or FIPS.
     *
     * Typing "wallowa", "OR", "Oregon" or "41063" all reach Wallowa County.
     */
    fun search(query: String, limit: Int = 40): List<CountyRecord> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val exactFips = counties.filter { it.fips == needle }
        if (exactFips.isNotEmpty()) return exactFips

        return counties.asSequence()
            .filter { it.searchName.contains(needle) }
            // Counties whose own name starts with the query come first.
            .sortedBy { record ->
                when {
                    record.countyName.lowercase().startsWith(needle) -> 0
                    record.countyName.lowercase().contains(needle) -> 1
                    else -> 2
                }
            }
            .take(limit)
            .toList()
    }

    fun byFips(fips: String): CountyRecord? = counties.firstOrNull { it.fips == fips }

    private fun load(): List<CountyRecord> = runCatching {
        context.assets.open(ASSET).bufferedReader().useLines { lines ->
            lines.mapNotNull { parse(it) }.toList()
        }
    }.getOrDefault(emptyList())

    companion object {
        const val ASSET = "counties.txt"

        internal fun parse(line: String): CountyRecord? {
            if (line.isBlank() || line.startsWith("#")) return null
            val parts = line.split('|')
            if (parts.size < 3) return null
            val fips = parts[0].trim()
            if (fips.length != 5 || !fips.all { it.isDigit() }) return null
            return CountyRecord(fips, parts[1].trim(), parts[2].trim())
        }
    }
}
