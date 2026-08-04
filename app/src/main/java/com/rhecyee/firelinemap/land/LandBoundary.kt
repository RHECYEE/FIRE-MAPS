package com.rhecyee.firelinemap.land

/**
 * One administered unit's outline.
 *
 * Rings are longitude to latitude, in the order the service gives them, which
 * for these is outer ring first. Nothing here distinguishes holes from
 * islands: outlines are stroked rather than filled, so the difference does not
 * change what is drawn, and guessing wrongly about ring winding would.
 */
data class LandBoundary(
    val name: String,
    val agency: LandAgency,
    val managerType: String?,
    val rings: List<List<Pair<Double, Double>>>
) {
    val pointCount: Int get() = rings.sumOf { it.size }
}

/**
 * Reads unit outlines out of an ArcGIS query response.
 *
 * A hand-written scan rather than a JSON parse, for the same reason as the
 * rest of this package: `org.json` is only a stub on the unit test classpath,
 * and what is wanted is one known shape rather than arbitrary JSON. The shape
 * was taken from a live reply covering the Wallowas.
 */
object LandBoundaryParser {

    /**
     * A ceiling on points taken from one response.
     *
     * A single national forest comes back with a few thousand even after the
     * service has generalised it, and a wide view can ask for several at once.
     * Past this the outlines are finer than the screen can show and are only
     * costing frames.
     */
    const val MAX_POINTS = 60_000

    fun parse(json: String, maxPoints: Int = MAX_POINTS): List<LandBoundary> {
        val out = mutableListOf<LandBoundary>()
        var budget = maxPoints
        var cursor = 0

        while (budget > 0) {
            val featureAt = json.indexOf("\"attributes\"", cursor)
            if (featureAt < 0) break
            val attributes = LandStatusParser.firstAttributes(json.substring(featureAt))
                ?: break
            val name = LandStatusParser.field(attributes, "Unit_Nm")
                ?.takeIf { it.isNotBlank() && !it.equals("null", true) }
            val manager = LandStatusParser.field(attributes, "Mang_Name")
            val managerType = LandStatusParser.field(attributes, "Mang_Type")

            val ringsAt = json.indexOf("\"rings\"", featureAt)
            if (ringsAt < 0) break
            // Stop before the next feature, so a record with no geometry does
            // not quietly borrow the following one's outline.
            val nextFeature = json.indexOf("\"attributes\"", featureAt + 1)
            if (nextFeature in 1 until ringsAt) {
                cursor = nextFeature
                continue
            }

            val open = json.indexOf('[', ringsAt)
            val close = matchingBracket(json, open)
            if (open < 0 || close < 0) break

            val rings = readRings(json, open, close, budget)
            cursor = close + 1
            if (name == null || rings.isEmpty()) continue

            budget -= rings.sumOf { it.size }
            out += LandBoundary(
                name = name,
                agency = LandAgency.fromCode(manager),
                managerType = managerType,
                rings = rings
            )
        }
        return out
    }

    /** Every ring inside the rings array, as longitude/latitude pairs. */
    private fun readRings(
        json: String,
        open: Int,
        close: Int,
        budget: Int
    ): List<List<Pair<Double, Double>>> {
        val rings = mutableListOf<List<Pair<Double, Double>>>()
        var remaining = budget
        var index = open + 1
        while (index < close && remaining > 0) {
            if (json[index] != '[') { index++; continue }
            val ringEnd = matchingBracket(json, index)
            if (ringEnd < 0 || ringEnd > close) break
            val ring = readPoints(json, index, ringEnd, remaining)
            // Three points is the least that encloses anything; anything
            // shorter is a fragment of a boundary rather than one.
            if (ring.size >= 3) {
                rings += ring
                remaining -= ring.size
            }
            index = ringEnd + 1
        }
        return rings
    }

    private fun readPoints(
        json: String,
        open: Int,
        close: Int,
        budget: Int
    ): List<Pair<Double, Double>> {
        val points = ArrayList<Pair<Double, Double>>()
        var index = open + 1
        while (index < close && points.size < budget) {
            if (json[index] != '[') { index++; continue }
            val end = json.indexOf(']', index)
            if (end < 0 || end > close) break
            val body = json.substring(index + 1, end)
            val comma = body.indexOf(',')
            if (comma > 0) {
                val longitude = body.substring(0, comma).trim().toDoubleOrNull()
                val latitude = body.substring(comma + 1).trim()
                    .substringBefore(',').toDoubleOrNull()
                if (longitude != null && latitude != null &&
                    longitude.isFinite() && latitude.isFinite()
                ) {
                    points += longitude to latitude
                }
            }
            index = end + 1
        }
        return points
    }

    /** Index of the bracket closing the one at [open], or -1. */
    internal fun matchingBracket(json: String, open: Int): Int {
        if (open < 0 || open >= json.length || json[open] != '[') return -1
        var depth = 0
        for (index in open until json.length) {
            when (json[index]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    /**
     * The query for every unit crossing a view.
     *
     * [generaliseDegrees] is handed to the service so it does the thinning
     * rather than the phone: a national forest boundary at full precision is
     * hundreds of thousands of points, and none of that detail survives being
     * drawn a few hundred pixels wide.
     */
    fun boundariesUrl(
        north: Double,
        south: Double,
        west: Double,
        east: Double,
        generaliseDegrees: Double,
        limit: Int = 30
    ): String {
        val envelope = "%7B%22xmin%22%3A$west%2C%22ymin%22%3A$south" +
            "%2C%22xmax%22%3A$east%2C%22ymax%22%3A$north" +
            "%2C%22spatialReference%22%3A%7B%22wkid%22%3A4326%7D%7D"
        return "${LandStatusParser.PROTECTED_ENDPOINT}?geometry=$envelope" +
            "&geometryType=esriGeometryEnvelope&inSR=4326&outSR=4326" +
            "&spatialRel=esriSpatialRelIntersects" +
            "&outFields=Unit_Nm%2CMang_Name%2CMang_Type" +
            "&returnGeometry=true&geometryPrecision=5" +
            "&maxAllowableOffset=$generaliseDegrees" +
            "&resultRecordCount=$limit&f=json"
    }

    /**
     * How coarsely to ask for outlines, given how much ground is on screen.
     *
     * Roughly one vertex per few screen pixels. Finer is invisible and costs
     * both the connection and the frame; coarser starts cutting corners off
     * boundaries that people navigate by.
     */
    fun generalisationFor(spanDegrees: Double): Double =
        (spanDegrees / 500.0).coerceIn(0.00005, 0.02)
}
