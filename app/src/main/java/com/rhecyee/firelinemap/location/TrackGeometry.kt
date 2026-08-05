package com.rhecyee.firelinemap.location

/**
 * A track's shape, as stored.
 *
 * GeoJSON, so the column is readable by anything that reads GeoJSON, plus the
 * one thing GeoJSON has no place for: when each point was reached.
 *
 * The times go in a `coordTimes` member alongside the coordinates. The format
 * permits members it does not define and requires readers to ignore them, so
 * this stays valid GeoJSON everywhere while carrying what the app needs. The
 * name is the one Garmin and Strava already use for exactly this, which makes
 * it the likeliest thing another program will understand.
 *
 * Without the times a track is a line and nothing more -- no speed, and no way
 * to say how long a road takes, which is the question a division supervisor
 * asks at every briefing.
 *
 * Hand-written rather than run through a JSON library: the shape is fixed and
 * this has to compile for the web app as well as the phone, where there is no
 * library common to both.
 */
object TrackGeometry {

    fun write(points: List<Fix>): String {
        val out = StringBuilder(points.size * 48 + 64)
        out.append("{\"type\":\"LineString\",\"coordinates\":[")
        points.forEachIndexed { index, fix ->
            if (index > 0) out.append(',')
            out.append('[').append(fix.longitude).append(',').append(fix.latitude).append(']')
        }
        out.append(']')
        if (points.any { it.timeMillis > 0 }) {
            out.append(",\"coordTimes\":[")
            points.forEachIndexed { index, fix ->
                if (index > 0) out.append(',')
                out.append(fix.timeMillis)
            }
            out.append(']')
        }
        out.append('}')
        return out.toString()
    }

    /** For a trace that carries positions only, such as the line being drawn live. */
    fun writePositions(points: List<Pair<Double, Double>>): String =
        write(points.map { Fix(it.first, it.second, 0L) })

    /**
     * Reads a stored track back.
     *
     * Tolerant: a track written before times were kept, or one imported from
     * elsewhere, comes back with its shape intact and every time zero. Callers
     * treat a zero as "not known" rather than as 1970.
     */
    fun read(geoJson: String): List<Fix> {
        val coordinates = arrayBody(geoJson, "\"coordinates\"") ?: return emptyList()
        val positions = PAIR.findAll(coordinates).mapNotNull { match ->
            // GeoJSON is longitude first.
            val longitude = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
            val latitude = match.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            if (!latitude.isFinite() || !longitude.isFinite()) return@mapNotNull null
            latitude to longitude
        }.toList()
        if (positions.isEmpty()) return emptyList()

        val times = arrayBody(geoJson, "\"coordTimes\"")
            ?.let { body -> NUMBER.findAll(body).mapNotNull { it.value.toLongOrNull() }.toList() }
            ?: emptyList()

        return positions.mapIndexed { index, position ->
            Fix(
                latitude = position.first,
                longitude = position.second,
                // A times array that does not line up with the coordinates is
                // not trustworthy for the points it does cover either, so a
                // short one is used only as far as it goes.
                timeMillis = times.getOrElse(index) { 0L }
            )
        }
    }

    /** Positions only, for drawing. */
    fun readPositions(geoJson: String): List<Pair<Double, Double>> =
        read(geoJson).map { it.latitude to it.longitude }

    /**
     * The text between the brackets of a named array.
     *
     * Bracket-counted rather than matched with a pattern, because the
     * coordinates array contains arrays and the first closing bracket is the
     * end of the first point, not of the list.
     */
    private fun arrayBody(json: String, key: String): String? {
        val keyAt = json.indexOf(key)
        if (keyAt < 0) return null
        val open = json.indexOf('[', keyAt + key.length)
        if (open < 0) return null
        var depth = 0
        for (index in open until json.length) {
            when (json[index]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return json.substring(open + 1, index)
                }
            }
        }
        return null
    }

    private val PAIR = Regex("""\[\s*(-?[0-9.eE+-]+)\s*,\s*(-?[0-9.eE+-]+)[^\]]*\]""")
    private val NUMBER = Regex("""-?\d+""")
}
