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

        val url = ElevationQuery.url(latitude, longitude)
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
        const val ENDPOINT = ElevationQuery.ENDPOINT

        /** Delegated so the phone and the browser read a reply the same way. */
        internal fun parseValue(body: String): Double? = ElevationQuery.parseValue(body)
    }
}
