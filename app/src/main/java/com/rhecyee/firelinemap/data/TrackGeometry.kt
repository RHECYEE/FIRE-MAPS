package com.rhecyee.firelinemap.data

/**
 * Reading a track's stored line back out.
 *
 * Lived in the phone screen until the car needed it too. A track drawn on one
 * display and absent from the other is the same recording described twice, and
 * the difference was only ever which file the parser happened to sit in.
 */
fun parseLineString(geoJson: String): List<Pair<Double, Double>> {
    val open = geoJson.indexOf("[[")
    if (open < 0) return emptyList()
    val close = geoJson.lastIndexOf("]]")
    if (close <= open) return emptyList()
    return Regex("""\[\s*(-?[0-9.eE+-]+)\s*,\s*(-?[0-9.eE+-]+)\s*\]""")
        .findAll(geoJson.substring(open, close + 2))
        .mapNotNull { match ->
            // GeoJSON is longitude first.
            val longitude = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
            val latitude = match.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            latitude to longitude
        }
        .toList()
}
