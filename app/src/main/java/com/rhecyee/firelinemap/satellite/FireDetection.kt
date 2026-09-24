package com.rhecyee.firelinemap.satellite

import java.util.Locale

/**
 * How sure the algorithm was that a pixel was fire.
 *
 * The two instrument families say this differently and neither is a
 * probability. VIIRS grades each detection low, nominal or high. MODIS gives
 * a number out of a hundred. Both are the detection algorithm's own opinion
 * about its own output, arrived at without anybody looking, and a high
 * confidence gas flare is still a gas flare.
 */
enum class DetectionConfidence(val label: String) {
    LOW("Low"),
    NOMINAL("Nominal"),
    HIGH("High"),

    /** The column was absent or unreadable. Shown, never silently upgraded. */
    UNKNOWN("Unstated");

    companion object {
        /**
         * Reads either encoding.
         *
         * MODIS thresholds follow FIRMS' own banding of its 0-100 scale, so a
         * filter set to "nominal and above" means the same thing whichever
         * satellite a detection came off.
         */
        fun parse(raw: String?): DetectionConfidence {
            val value = raw?.trim()?.lowercase(Locale.US).orEmpty()
            if (value.isEmpty()) return UNKNOWN
            when (value) {
                "l", "low" -> return LOW
                "n", "nominal" -> return NOMINAL
                "h", "high" -> return HIGH
            }
            val percent = value.toIntOrNull() ?: return UNKNOWN
            return when {
                percent < 30 -> LOW
                percent < 80 -> NOMINAL
                else -> HIGH
            }
        }
    }
}

/**
 * How far through processing a detection has got.
 *
 * This is the distinction that matters operationally and it is carried in the
 * version string rather than a column of its own. FIRMS publishes a detection
 * within a minute of the satellite passing over much of the US and Canada,
 * and then replaces it hours later with the processed version -- so the fast
 * one and the checked one are the same observation at different ages, not two
 * different feeds.
 *
 * Nothing here has been looked at by a person. Standard processing is
 * reprocessed and better characterised, not human-reviewed, and it arrives
 * months to years later.
 */
enum class ProcessingLevel(val label: String, val note: String) {
    ULTRA_REAL_TIME("URT", "under a minute old, unprocessed"),
    REAL_TIME("RT", "within the hour, unprocessed"),
    NEAR_REAL_TIME("NRT", "processed, a few hours behind"),
    STANDARD("Standard", "reprocessed, months behind"),
    UNKNOWN("—", "processing level unstated");

    companion object {
        fun parse(version: String?): ProcessingLevel {
            val value = version?.trim()?.uppercase(Locale.US).orEmpty()
            return when {
                value.isEmpty() -> UNKNOWN
                value.contains("URT") -> ULTRA_REAL_TIME
                value.contains("RT") && !value.contains("NRT") -> REAL_TIME
                value.contains("NRT") -> NEAR_REAL_TIME
                else -> STANDARD
            }
        }
    }
}

/** One pixel a satellite thought was hot. */
data class FireDetection(
    val latitude: Double,
    val longitude: Double,
    /** UTC, as FIRMS dates it: yyyy-MM-dd. */
    val date: String,
    /** UTC, as FIRMS times it: HHmm. Null when unreadable. */
    val timeUtc: String?,
    val confidence: DetectionConfidence,
    val level: ProcessingLevel,
    /** Fire radiative power in megawatts, where the product carries it. */
    val powerMegawatts: Double?,
    val satellite: String?,
    val daytime: Boolean?,
    /**
     * Which product this came back from.
     *
     * Carried so the footprint can be drawn at its real ground size: a MODIS
     * detection covers a kilometre and a VIIRS one 375 metres, and drawing
     * them the same size would overstate one and understate the other. The
     * CSV's own satellite column is a bird name, not a resolution.
     */
    val source: SatelliteSource? = null
) {
    /** "14:32Z", or null when the pass time did not come through. */
    val clock: String?
        get() {
            val raw = timeUtc?.padStart(4, '0') ?: return null
            if (raw.length != 4) return null
            return "${raw.substring(0, 2)}:${raw.substring(2)}Z"
        }

    /** "2026-09-23 14:32Z · High · URT", for a readout. */
    fun summary(): String = buildString {
        append(date)
        clock?.let { append(' ').append(it) }
        append(" · ").append(confidence.label)
        append(" · ").append(level.label)
        powerMegawatts?.let { append(" · ").append("%.0f MW".format(it)) }
    }
}

/**
 * Reads a FIRMS area CSV.
 *
 * Columns are looked up by the name in the header rather than by position,
 * which is not fussiness: MODIS and VIIRS return different schemas for the
 * same request shape -- brightness against bright_ti4, a confidence out of a
 * hundred against a letter -- and LANDSAT a third. Reading by position would
 * mean a table per instrument, each of which would be wrong the first time
 * FIRMS added a field.
 */
object FirmsCsv {

    fun parse(body: String, source: SatelliteSource? = null): List<FireDetection> {
        val lines = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.size < 2) return emptyList()

        val header = split(lines.first()).map { it.trim().lowercase(Locale.US) }
        fun columnOf(vararg names: String): Int =
            names.firstNotNullOfOrNull { name ->
                header.indexOf(name).takeIf { it >= 0 }
            } ?: -1

        val latitude = columnOf("latitude")
        val longitude = columnOf("longitude")
        if (latitude < 0 || longitude < 0) return emptyList()

        val date = columnOf("acq_date")
        val time = columnOf("acq_time")
        val confidence = columnOf("confidence")
        val version = columnOf("version")
        val power = columnOf("frp")
        val satellite = columnOf("satellite")
        val dayNight = columnOf("daynight")

        return lines.drop(1).mapNotNull { line ->
            val cells = split(line)
            fun cell(index: Int): String? =
                cells.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }

            val lat = cell(latitude)?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = cell(longitude)?.toDoubleOrNull() ?: return@mapNotNull null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null

            FireDetection(
                latitude = lat,
                longitude = lon,
                date = cell(date).orEmpty(),
                timeUtc = cell(time),
                confidence = DetectionConfidence.parse(cell(confidence)),
                level = ProcessingLevel.parse(cell(version)),
                powerMegawatts = cell(power)?.toDoubleOrNull(),
                satellite = cell(satellite),
                daytime = cell(dayNight)?.let { it.equals("D", ignoreCase = true) },
                source = source
            )
        }
    }

    /** Splits a row, honouring quoted cells. FIRMS does not use them, yet. */
    private fun split(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        for (character in line) {
            when {
                character == '"' -> quoted = !quoted
                character == ',' && !quoted -> {
                    cells += current.toString()
                    current.setLength(0)
                }
                else -> current.append(character)
            }
        }
        cells += current.toString()
        return cells
    }
}
