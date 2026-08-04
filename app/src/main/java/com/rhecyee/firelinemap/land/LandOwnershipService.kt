package com.rhecyee.firelinemap.land

import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks the free services who holds and who administers a patch of ground.
 *
 * Three sources, because none of them alone answers the question a crew is
 * asking. The BLM surface layer names the administering agency but not the
 * unit. PAD-US names the unit -- Whitman National Forest, Newcastle Field
 * Office -- but is silent on private ground. The census names the county
 * everywhere, which is who dispatches and who to ring for mutual aid.
 *
 * Answers are cached to a four-decimal grid, about ten metres, because the
 * same ground gets asked about repeatedly as a map is panned and these are
 * free rather than unlimited.
 *
 * Needs a connection. There is no offline answer yet; a downloaded ownership
 * package would be the fix, and the layer is built so one can drop in.
 */
class LandOwnershipService {

    private val cache = HashMap<String, LandStatus>()

    /** Blocking. Callers run it off the main thread. */
    fun statusAt(latitude: Double, longitude: Double): LandStatus {
        val key = "%.4f,%.4f".format(latitude, longitude)
        cache[key]?.let { return it }

        val owner = fetch(LandOwnershipParser.identifyUrl(latitude, longitude))
            ?.let { LandOwnershipParser.parseIdentify(it) }
        val unit = fetch(LandStatusParser.protectedUnitUrl(latitude, longitude))
            ?.let { LandStatusParser.parseProtectedUnit(it) }
        val countyJson = fetch(LandStatusParser.countyUrl(latitude, longitude))
        // Only worth a second call once the first has found a county.
        val stateJson = countyJson?.let { fetch(LandStatusParser.stateUrl(latitude, longitude)) }
        val county = countyJson?.let { LandStatusParser.parseCounty(it, stateJson) }

        val status = LandStatus(owner = owner, unit = unit, county = county)
        // A wholly empty answer is not cached: it usually means no signal
        // rather than no ground, and the next attempt may be on a hill.
        if (!status.isEmpty) cache[key] = status
        return status
    }

    /** Kept for callers that only want the administering agency. */
    fun ownerAt(latitude: Double, longitude: Double): LandOwner? = statusAt(latitude, longitude).owner

    private fun fetch(url: String): String? {
        val connection = runCatching {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "FirelineMap/0.20")
            }
        }.getOrNull() ?: return null

        return try {
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        } catch (error: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
