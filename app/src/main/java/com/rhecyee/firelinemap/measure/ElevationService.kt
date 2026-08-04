package com.rhecyee.firelinemap.measure

import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks up ground elevation for a coordinate.
 *
 * Backed by the USGS Elevation Point Query Service, which is public domain and
 * needs no key -- the same reasoning as the terrain basemap: anything behind
 * an account would not be usable by the people this is built for.
 *
 * Requires connectivity. Slope is reported only when every point on a leg has
 * an elevation, so a failed lookup leaves the figure absent rather than
 * quietly wrong.
 */
class ElevationService {

    private val cache = HashMap<String, Double>()

    /** Blocking. Callers run it off the main thread. */
    fun elevationMeters(latitude: Double, longitude: Double): Double? {
        val key = "%.5f,%.5f".format(latitude, longitude)
        cache[key]?.let { return it }

        val url = "$ENDPOINT?x=$longitude&y=$latitude&units=Meters&wkid=4326&includeDate=false"
        val connection = runCatching {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "FirelineMap/0.4")
            }
        }.getOrNull() ?: return null

        return try {
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            parseValue(body)?.also { cache[key] = it }
        } catch (error: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val ENDPOINT = "https://epqs.nationalmap.gov/v1/json"

        /** The service has returned the value as both a number and a string. */
        internal fun parseValue(body: String): Double? {
            val match = Regex("""["']?value["']?\s*:\s*"?(-?[0-9]+(?:\.[0-9]+)?)"?""")
                .find(body) ?: return null
            val value = match.groupValues[1].toDoubleOrNull() ?: return null
            // The service reports this sentinel where it has no coverage.
            if (value <= -1_000_000) return null
            return value
        }
    }
}
