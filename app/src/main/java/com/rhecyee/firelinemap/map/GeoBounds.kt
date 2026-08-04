package com.rhecyee.firelinemap.map

/**
 * A geographic bounding box in WGS84 degrees.
 *
 * Used for both the coverage area of an imported [com.rhecyee.firelinemap.data.MapDocumentEntity]
 * and the extent of a preloaded terrain region.
 *
 * Antimeridian-straddling boxes are not supported. No incident map product
 * crosses 180 degrees, and pretending to handle it would add branches that
 * could never be exercised in the field or in test.
 */
data class GeoBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double
) {
    init {
        require(south <= north) { "south ($south) must not exceed north ($north)" }
        require(west <= east) { "west ($west) must not exceed east ($east)" }
    }

    fun contains(latitude: Double, longitude: Double): Boolean =
        latitude in south..north && longitude in west..east

    /** The point inside these bounds nearest to the given position. */
    fun nearestPointTo(latitude: Double, longitude: Double): Pair<Double, Double> =
        latitude.coerceIn(south, north) to longitude.coerceIn(west, east)

    /**
     * Serialized as a fixed-shape JSON object.
     *
     * Hand-rolled rather than routed through `org.json` so that coverage logic
     * stays testable on the plain JVM. `org.json` is only a stub in the unit
     * test classpath, and coverage state is exactly the kind of safety-relevant
     * logic that needs to run in fast tests rather than on a device.
     */
    fun toJson(): String =
        """{"south":$south,"west":$west,"north":$north,"east":$east}"""

    companion object {
        private val KEYS = listOf("south", "west", "north", "east")

        /** Returns null for absent or malformed input; callers treat that as "extent unknown". */
        fun fromJson(json: String?): GeoBounds? {
            if (json.isNullOrBlank()) return null
            val values = mutableMapOf<String, Double>()
            for (key in KEYS) {
                values[key] = readNumber(json, key) ?: return null
            }
            val south = values.getValue("south")
            val west = values.getValue("west")
            val north = values.getValue("north")
            val east = values.getValue("east")
            if (south > north || west > east) return null
            if (south < -90.0 || north > 90.0 || west < -180.0 || east > 180.0) return null
            return GeoBounds(south, west, north, east)
        }

        private fun readNumber(json: String, key: String): Double? {
            val marker = "\"$key\""
            val keyIndex = json.indexOf(marker)
            if (keyIndex < 0) return null
            var index = keyIndex + marker.length
            while (index < json.length && json[index].isWhitespace()) index++
            if (index >= json.length || json[index] != ':') return null
            index++
            while (index < json.length && json[index].isWhitespace()) index++
            val start = index
            while (index < json.length && (json[index].isDigit() || json[index] in "+-.eE")) index++
            return json.substring(start, index).toDoubleOrNull()
        }
    }
}
