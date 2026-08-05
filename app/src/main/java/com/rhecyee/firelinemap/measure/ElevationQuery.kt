package com.rhecyee.firelinemap.measure

/**
 * How ground elevation is asked for, and how the answer is read.
 *
 * Split out from the service that does the fetching so the browser can use the
 * same rules: it builds its own request with its own networking, but the URL
 * it asks for and the way it reads the reply come from here. A slope is the
 * kind of number a crew boss acts on, and two apps disagreeing about one --
 * because one of them read a sentinel as a real elevation -- is not a
 * difference anybody would notice until it mattered.
 *
 * Backed by the USGS Elevation Point Query Service, which is public domain and
 * needs no key. Anything behind an account would not be usable by the people
 * this is built for.
 */
object ElevationQuery {

    const val ENDPOINT = "https://epqs.nationalmap.gov/v1/json"

    fun url(latitude: Double, longitude: Double): String =
        "$ENDPOINT?x=$longitude&y=$latitude&units=Meters&wkid=4326&includeDate=false"

    /**
     * The elevation in a reply, or null where there is none to be had.
     *
     * The service has returned the figure as both a bare number and a quoted
     * string depending on the day, so both are accepted.
     */
    fun parseValue(body: String): Double? {
        val match = Regex("""["']?value["']?\s*:\s*"?(-?[0-9]+(?:\.[0-9]+)?)"?""")
            .find(body) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        // The sentinel the service reports where it has no coverage. Read as a
        // number it is an elevation a thousand kilometres underground, which
        // would carry straight through into a slope.
        if (value <= -1_000_000) return null
        return value
    }
}
