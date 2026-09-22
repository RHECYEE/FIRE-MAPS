package com.rhecyee.firelinemap.annotations

import java.util.Locale

/** What put a shape on the map, which is also how it gets drawn. */
enum class AnnotationKind {
    /** A perimeter as it stood when somebody committed it. */
    FIRELINE_PERIMETER,

    /** An open run: a leg measured out, usually to be driven or walked. */
    MEASURE_LINE,

    /** An enclosed measurement. */
    MEASURE_AREA;

    /** Whether the run closes back on itself. */
    val isClosed: Boolean get() = this != MEASURE_LINE

    companion object {
        fun from(name: String?): AnnotationKind? =
            entries.firstOrNull { it.name == name }
    }
}

/**
 * A shape a tool left on the map.
 *
 * [rings] is one run for an open line and one entry per ring for anything
 * enclosed, so a perimeter with unburnt ground inside it keeps its holes.
 * Positions are latitude to longitude, matching everything else the canvas
 * is handed; only the stored form is longitude-first, because that is what
 * GeoJSON requires.
 */
data class MapAnnotation(
    val id: String,
    val kind: AnnotationKind,
    val label: String,
    val rings: List<List<Pair<Double, Double>>>,
    val createdAt: Long,
    val note: String? = null
) {
    val isEmpty: Boolean get() = rings.all { it.size < 2 }

    /** A position to hang the label off: the middle of the longest run. */
    fun labelAnchor(): Pair<Double, Double>? {
        val longest = rings.maxByOrNull { it.size } ?: return null
        if (longest.isEmpty()) return null
        return longest[longest.size / 2]
    }
}

/**
 * Reads and writes annotation geometry.
 *
 * Hand-rolled for the same reason the track and observation geometry is:
 * the shapes are fixed, this app is the only thing that writes them, and
 * org.json is a stub on the unit test classpath -- so a parser that runs in
 * a unit test has to be one of ours.
 */
object AnnotationGeometry {

    fun encode(kind: AnnotationKind, rings: List<List<Pair<Double, Double>>>): String {
        val usable = rings.filter { it.size >= 2 }
        return if (!kind.isClosed) {
            val run = usable.firstOrNull().orEmpty()
            """{"type":"LineString","coordinates":${points(run)}}"""
        } else {
            val body = usable.joinToString(",") { points(closed(it)) }
            """{"type":"Polygon","coordinates":[$body]}"""
        }
    }

    fun decode(geoJson: String): List<List<Pair<Double, Double>>> {
        val open = geoJson.indexOf("\"coordinates\"")
        if (open < 0) return emptyList()
        val bracket = geoJson.indexOf('[', open)
        if (bracket < 0) return emptyList()
        val coordinates = bracketed(geoJson, bracket) ?: return emptyList()
        val polygon = geoJson.contains("\"Polygon\"")

        return if (polygon) {
            elementsOf(coordinates).map { ring -> pairsOf(ring) }.filter { it.size >= 2 }
        } else {
            listOf(pairsOf(coordinates)).filter { it.size >= 2 }
        }
    }

    /** A ring is stored closed, as GeoJSON requires, and read back open. */
    private fun closed(ring: List<Pair<Double, Double>>): List<Pair<Double, Double>> =
        if (ring.size >= 2 && ring.first() != ring.last()) ring + ring.first() else ring

    private fun points(run: List<Pair<Double, Double>>): String =
        run.joinToString(",", prefix = "[", postfix = "]") { (latitude, longitude) ->
            String.format(Locale.US, "[%.7f,%.7f]", longitude, latitude)
        }

    private fun pairsOf(array: String): List<Pair<Double, Double>> =
        elementsOf(array).mapNotNull { element ->
            val numbers = element.trim().removePrefix("[").removeSuffix("]").split(',')
            if (numbers.size < 2) return@mapNotNull null
            val longitude = numbers[0].trim().toDoubleOrNull() ?: return@mapNotNull null
            val latitude = numbers[1].trim().toDoubleOrNull() ?: return@mapNotNull null
            latitude to longitude
        }

    /**
     * The top-level elements of a bracketed array, still bracketed.
     *
     * A depth scan rather than a split, because every element of interest is
     * itself an array and splitting on commas would cut them all in half.
     */
    private fun elementsOf(array: String): List<String> {
        val inner = array.trim().removePrefix("[").removeSuffix("]")
        val elements = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (index in inner.indices) {
            when (inner[index]) {
                '[' -> depth++
                ']' -> depth--
                ',' -> if (depth == 0) {
                    elements += inner.substring(start, index)
                    start = index + 1
                }
            }
        }
        if (start < inner.length) elements += inner.substring(start)
        return elements.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The substring from an opening bracket to its match, inclusive. */
    private fun bracketed(text: String, open: Int): String? {
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return text.substring(open, index + 1)
                }
            }
        }
        return null
    }
}
