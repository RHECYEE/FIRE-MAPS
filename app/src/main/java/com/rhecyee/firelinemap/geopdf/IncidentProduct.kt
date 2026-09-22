package com.rhecyee.firelinemap.geopdf

import java.util.Locale

/**
 * A published incident map product, read out of its filename.
 *
 * Incident products are published to a dated folder with names like
 *
 *     ops_arch_e_land_20260910_1956_Timber_CALPF002271_0911day_DIV B-X.pdf
 *
 * which carries everything needed to tell one sheet from another -- and puts
 * all of it after the sixty characters that are identical across the folder.
 * A list showing the first part of the name shows a dozen rows reading
 * `ops_arch_e_land_20260910_1956_Timber_CAL`, and the division letter, which
 * is the only thing anybody is choosing between, is the part that got cut.
 *
 * So the name is taken apart and put back together the other way round: what
 * it is and which piece of the fire, first.
 */
data class IncidentProduct(
    /** "Operations", "Air ops", or the raw token when it is not a known kind. */
    val kind: String?,
    /** "DIV B-X", "Branch 20", or null on a sheet covering the whole fire. */
    val unit: String?,
    val incident: String?,
    /** The published operational period, such as "0911day". */
    val period: String?,
    /** Publication time as yyyyMMdd and HHmm, kept as written. */
    val publishedDate: String?,
    val publishedTime: String?,
    /** "arch E", "11x17", or null. */
    val sheetSize: String?,
    /** The original filename, always. */
    val fileName: String,
) {
    /** What the row leads with: the thing being chosen between. */
    val title: String
        get() {
            val what = kind ?: fileName.substringBeforeLast('.').take(TITLE_LIMIT)
            return if (unit != null) "$what — $unit" else what
        }

    /**
     * The line underneath: which operational period, and when it was posted.
     *
     * Two sheets for the same division from consecutive periods are otherwise
     * indistinguishable, and working yesterday's division map is a real way to
     * end up somewhere nobody expects you.
     */
    val detail: String
        get() = listOfNotNull(
            period?.let { describePeriod(it) },
            describePublished(),
            sheetSize
        ).joinToString(" · ").ifEmpty { fileName }

    /** Sorts newest period first, then by kind, then by unit. */
    val sortKey: String
        get() = buildString {
            append(period?.let { invert(it) } ?: "zzzz")
            append('|')
            append(kind ?: "zzzz")
            append('|')
            append(unit ?: "")
        }

    private fun describePublished(): String? {
        val date = publishedDate ?: return null
        if (date.length != 8) return null
        val month = date.substring(4, 6)
        val day = date.substring(6, 8)
        val time = publishedTime?.let { if (it.length == 4) "${it.take(2)}:${it.drop(2)}" else it }
        return listOfNotNull("$month/$day", time).joinToString(" ")
    }

    companion object {
        private const val TITLE_LIMIT = 44

        /**
         * The product kinds published for an incident.
         *
         * Longest first, because `airops_overview` has to win over `airops`.
         */
        private val KINDS = listOf(
            "airops_overview" to "Air ops overview",
            "airops" to "Air ops",
            "brief" to "Briefing",
            "evac" to "Evacuation",
            "fhist" to "Fire history",
            "ops" to "Operations",
            "own" to "Ownership",
            "pilot" to "Pilot",
            "pio" to "Public information",
            "prog" to "Progression",
            "trans" to "Transportation",
            "ir" to "Infrared",
            "struct" to "Structures",
            "repop" to "Repopulation",
        )

        private val SIZES = listOf(
            "arch_e_land" to "arch E",
            "arch_e_port" to "arch E portrait",
            "arch_d_land" to "arch D",
            "11x17_land" to "11x17",
            "11x17_port" to "11x17 portrait",
            "8x11_port" to "letter",
        )

        /** Anchored on the timestamp, which is the one fixed landmark. */
        private val STAMP = Regex("""_(\d{8})_(\d{4})_""")

        /** "0911day" or "0911night", wherever it falls in the tail. */
        private val PERIOD = Regex("""(\d{4})(day|night)""", RegexOption.IGNORE_CASE)

        fun parse(fileName: String): IncidentProduct {
            val stem = fileName.substringBeforeLast('.')
            val stamp = STAMP.find(stem)
                ?: return IncidentProduct(
                    kind = null, unit = null, incident = null, period = null,
                    publishedDate = null, publishedTime = null, sheetSize = null,
                    fileName = fileName
                )

            val head = stem.substring(0, stamp.range.first)
            val tail = stem.substring(stamp.range.last + 1)

            var remainder = head.lowercase(Locale.US)
            val size = SIZES.firstOrNull { remainder.endsWith("_${it.first}") }
            if (size != null) remainder = remainder.removeSuffix("_${size.first}")
            val kind = KINDS.firstOrNull { remainder == it.first || remainder.startsWith("${it.first}_") }

            val periodMatch = PERIOD.find(tail)
            val period = periodMatch?.value
            // Everything after the period is the piece of the fire this sheet
            // covers: a division, a branch, or a name somebody typed.
            val unit = periodMatch
                ?.let { tail.substring(it.range.last + 1) }
                ?.trim('_', ' ', '-')
                ?.takeIf { it.isNotEmpty() }
            // Before it sits the incident and its number.
            val incident = periodMatch
                ?.let { tail.substring(0, it.range.first) }
                ?.trim('_')
                ?.split('_')
                ?.dropLast(1)
                ?.joinToString(" ")
                ?.takeIf { it.isNotEmpty() }

            return IncidentProduct(
                kind = kind?.second ?: remainder.takeIf { it.isNotEmpty() }
                    ?.replace('_', ' ')
                    ?.replaceFirstChar { it.uppercase() },
                unit = unit,
                incident = incident,
                period = period,
                publishedDate = stamp.groupValues[1],
                publishedTime = stamp.groupValues[2],
                sheetSize = size?.second,
                fileName = fileName
            )
        }

        /** "0911day" as "09/11 day". */
        fun describePeriod(period: String): String {
            val match = PERIOD.matchEntire(period) ?: return period
            val digits = match.groupValues[1]
            return "${digits.take(2)}/${digits.drop(2)} ${match.groupValues[2].lowercase(Locale.US)}"
        }

        /** Flips digits so a plain ascending sort puts the newest period first. */
        private fun invert(period: String): String =
            period.map { character ->
                if (character.isDigit()) ('9' - (character - '0')) else character
            }.joinToString("")
    }
}
