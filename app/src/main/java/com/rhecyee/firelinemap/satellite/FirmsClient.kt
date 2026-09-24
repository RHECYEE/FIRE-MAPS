package com.rhecyee.firelinemap.satellite

import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** What came back from an area request. */
sealed interface FirmsResult {
    data class Detections(val detections: List<FireDetection>) : FirmsResult

    /**
     * Carried as a message rather than swallowed.
     *
     * A detection layer that silently shows nothing is indistinguishable from
     * a quiet day, and those are the two things an operator most needs told
     * apart.
     */
    data class Failed(val reason: String, val keyRejected: Boolean = false) : FirmsResult
}

/**
 * Fetches detections from NASA FIRMS.
 *
 * Needs a MAP_KEY, which is free and takes an email, and that is the reason
 * this is not the only source the app has: everything else here works for
 * somebody who cannot get onto agency infrastructure, and this one asks them
 * to sign up. It earns the exception by being the only source that carries a
 * confidence per detection and a processing level per detection, which is
 * what makes "show me only the ones it was sure about" a real control rather
 * than a picture somebody already decided the contents of.
 *
 * Everything here blocks; callers run it off the main thread.
 */
class FirmsClient(private val openConnection: (String) -> HttpURLConnection = ::defaultOpen) {

    /**
     * One area request.
     *
     * [dayRange] counts back from today and FIRMS caps it at ten. One day is
     * the operational answer and the default: a week of detections over a
     * going fire is a solid red smear that says nothing about where the edge
     * is now.
     */
    fun area(
        mapKey: String,
        source: SatelliteSource,
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        dayRange: Int = 1
    ): FirmsResult {
        if (mapKey.isBlank()) {
            return FirmsResult.Failed("No FIRMS key set.", keyRejected = true)
        }
        if (north <= south || east <= west) {
            return FirmsResult.Failed("Nothing on screen to ask about.")
        }

        val box = String.format(
            Locale.US, "%.4f,%.4f,%.4f,%.4f",
            west.coerceIn(-180.0, 180.0),
            south.coerceIn(-90.0, 90.0),
            east.coerceIn(-180.0, 180.0),
            north.coerceIn(-90.0, 90.0)
        )
        val url = "$ENDPOINT/${mapKey.trim()}/${source.firmsId}/$box/${dayRange.coerceIn(1, 10)}"

        val connection = runCatching { openConnection(url) }
            .getOrElse { return FirmsResult.Failed("Could not reach FIRMS.") }

        return try {
            val status = connection.responseCode
            val body = runCatching {
                if (status in 200..299) {
                    connection.inputStream.use { it.readBytes() }
                } else {
                    connection.errorStream?.use { it.readBytes() } ?: ByteArray(0)
                }
            }.getOrDefault(ByteArray(0)).toString(Charsets.UTF_8)

            // The service answers a bad request with 400 and a one-line body
            // rather than a status that says which thing was wrong, so the
            // body is what has to be read. Both of these were checked against
            // the live service.
            val trimmed = body.trim()
            when {
                trimmed.startsWith("Invalid MAP_KEY", ignoreCase = true) ->
                    FirmsResult.Failed(
                        "FIRMS rejected that key. Check it in Settings.",
                        keyRejected = true
                    )
                trimmed.startsWith("Invalid source", ignoreCase = true) ->
                    FirmsResult.Failed("FIRMS does not know that product.")
                status == 429 ->
                    FirmsResult.Failed("FIRMS is rate limiting; try again shortly.")
                status !in 200..299 ->
                    FirmsResult.Failed("FIRMS returned $status.")
                else -> FirmsResult.Detections(FirmsCsv.parse(body, source))
            }
        } catch (error: Exception) {
            FirmsResult.Failed(error.message ?: "FIRMS request failed.")
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    companion object {
        const val ENDPOINT = "https://firms.modaps.eosdis.nasa.gov/api/area/csv"

        /** Where a key comes from, shown next to the field that wants one. */
        const val KEY_SIGNUP = "firms.modaps.eosdis.nasa.gov/api/map_key"

        const val ATTRIBUTION = "NASA FIRMS"

        private fun defaultOpen(url: String): HttpURLConnection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "FirelineMap/0.7")
            }
    }
}
