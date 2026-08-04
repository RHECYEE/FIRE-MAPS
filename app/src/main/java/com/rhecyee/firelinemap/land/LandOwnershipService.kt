package com.rhecyee.firelinemap.land

import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks the BLM who administers a patch of ground.
 *
 * Answers are cached to a five-decimal grid -- about a metre -- because the
 * same ground gets asked about repeatedly as a map is panned, and the service
 * is free rather than unlimited.
 *
 * Needs a connection. There is no offline answer yet; a downloaded ownership
 * package would be the fix, and the layer is built so one can drop in.
 */
class LandOwnershipService {

    private val cache = HashMap<String, LandOwner?>()

    /** Blocking. Callers run it off the main thread. */
    fun ownerAt(latitude: Double, longitude: Double): LandOwner? {
        val key = "%.4f,%.4f".format(latitude, longitude)
        if (cache.containsKey(key)) return cache[key]

        val connection = runCatching {
            (URL(LandOwnershipParser.identifyUrl(latitude, longitude))
                .openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "FirelineMap/0.14")
            }
        }.getOrNull() ?: return null

        val owner = try {
            if (connection.responseCode !in 200..299) null
            else LandOwnershipParser.parseIdentify(
                connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            )
        } catch (error: Exception) {
            null
        } finally {
            connection.disconnect()
        }

        // A failure is not cached: the next attempt may have signal.
        if (owner != null) cache[key] = owner
        return owner
    }
}
