package com.rhecyee.firelinemap.satellite

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round

/** The ground a fetch covered, quantised so panning does not refetch. */
data class DetectionWindow(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double
) {
    companion object {
        /** How far past the screen to ask, as a fraction of what is visible. */
        private const val MARGIN = 0.4

        /**
         * Rounds the request off the exact viewport.
         *
         * The same reasoning as the contour window: a request that tracked the
         * screen exactly would be cancelled and reissued on every finger
         * movement, and FIRMS is a rate-limited public service rather than a
         * tile server. Panning around a fire should cost one request, not
         * forty.
         */
        fun of(south: Double, west: Double, north: Double, east: Double): DetectionWindow? {
            if (!south.isFinite() || !west.isFinite() ||
                !north.isFinite() || !east.isFinite()
            ) return null
            if (north <= south || east <= west) return null

            val latitudeSpan = quantise((north - south) * (1 + 2 * MARGIN))
            val longitudeSpan = quantise((east - west) * (1 + 2 * MARGIN))
            if (latitudeSpan <= 0.0 || longitudeSpan <= 0.0) return null

            val latitude = snap((north + south) / 2, latitudeSpan / 4.0)
            val longitude = snap((west + east) / 2, longitudeSpan / 4.0)
            return DetectionWindow(
                south = (latitude - latitudeSpan / 2).coerceAtLeast(-85.0),
                west = longitude - longitudeSpan / 2,
                north = (latitude + latitudeSpan / 2).coerceAtMost(85.0),
                east = longitude + longitudeSpan / 2
            )
        }

        private fun quantise(span: Double): Double {
            if (span <= 0.0 || !span.isFinite()) return 0.0
            return 2.0.pow(ceil(ln(span) / ln(2.0)))
        }

        private fun snap(value: Double, step: Double): Double =
            if (step <= 0.0) value else round(value / step) * step
    }
}

/**
 * Detections for the ground on screen, and what happened trying to get them.
 *
 * Shared by the phone canvas and the car screen. The failure is held next to
 * the data on purpose: an empty list because nothing is burning and an empty
 * list because the key was rejected look identical on a map, and those are
 * the two things an operator most needs told apart.
 */
class FirmsFeed(private val client: FirmsClient = FirmsClient()) {

    var detections: List<FireDetection> by mutableStateOf(emptyList())
        private set

    /** Null when the last attempt worked, or when none has been made. */
    var error: String? by mutableStateOf(null)
        private set

    var keyRejected: Boolean by mutableStateOf(false)
        private set

    var fetchedAt: Long? by mutableStateOf(null)
        private set

    var loading: Boolean by mutableStateOf(false)
        private set

    private var lastWindow: DetectionWindow? = null
    private var lastSources: Set<SatelliteSource> = emptySet()
    private var lastKey: String = ""
    private var retryAfter: Long = 0L

    /**
     * Whether asking again right now would actually send anything.
     *
     * Public because the car screen redraws on a fixed loop several times a
     * second and would otherwise launch a coroutine per frame to be told no.
     * The rule lives here rather than at each call site so the phone and the
     * car cannot end up with different ideas of how hard to try.
     */
    fun wouldFetch(
        mapKey: String,
        sources: Set<SatelliteSource>,
        window: DetectionWindow,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (mapKey.isBlank() || sources.isEmpty()) return false
        val sameQuestion = window == lastWindow &&
            sources == lastSources && mapKey == lastKey
        if (!sameQuestion) return true
        if (error == null) {
            // Standing still is the case this exists for. Parked at a
            // staging area the window never changes, so without an age
            // check the map would hold the detections it loaded on arrival
            // for the whole shift -- and URT means the same pass gets
            // revised within minutes of the bird going over.
            val loaded = fetchedAt ?: return true
            return now - loaded >= STALE_MILLIS
        }
        // A key FIRMS rejected does not become a good key by being asked
        // again. It becomes a good key by being corrected, which changes the
        // question and lands above.
        if (keyRejected) return false
        // Everything else -- a timeout, a rate limit, a 500 -- is worth
        // another go, but not four times a second at a public service that
        // meters by key.
        return now >= retryAfter
    }

    /**
     * Fetches, unless the same question was already answered.
     *
     * One request per satellite, merged. They are separate products and FIRMS
     * has no combined endpoint; asking for them together is not on offer.
     */
    suspend fun refresh(
        mapKey: String,
        sources: Set<SatelliteSource>,
        window: DetectionWindow,
        force: Boolean = false
    ) {
        if (sources.isEmpty()) {
            detections = emptyList()
            error = null
            return
        }
        if (!force && !wouldFetch(mapKey, sources, window)) return

        lastWindow = window
        lastSources = sources
        lastKey = mapKey
        loading = true

        val gathered = mutableListOf<FireDetection>()
        var failure: FirmsResult.Failed? = null
        try {
            withContext(Dispatchers.IO) {
                for (source in sources) {
                    when (val result = client.area(
                        mapKey = mapKey,
                        source = source,
                        south = window.south,
                        west = window.west,
                        north = window.north,
                        east = window.east
                    )) {
                        is FirmsResult.Detections -> gathered += result.detections
                        // Held rather than thrown: one satellite being
                        // unavailable should not take the other two off the
                        // map.
                        is FirmsResult.Failed -> if (failure == null) failure = result
                    }
                }
            }
        } finally {
            // In a finally because a pan cancels this job mid-flight, and a
            // spinner left running forever is how a map comes to look broken.
            loading = false
        }

        val failed = failure
        // Whatever came back stays: two satellites answering and a third
        // timing out is still two satellites' worth of map.
        if (gathered.isNotEmpty() || failed == null) {
            detections = gathered
            fetchedAt = System.currentTimeMillis()
        }
        error = failed?.reason
        keyRejected = failed?.keyRejected == true
        retryAfter = if (failed == null) 0L else System.currentTimeMillis() + RETRY_MILLIS
    }

    /** Drops everything, for when the layer is switched off or the key changes. */
    fun clear() {
        detections = emptyList()
        error = null
        keyRejected = false
        fetchedAt = null
        lastWindow = null
        lastSources = emptySet()
        lastKey = ""
        retryAfter = 0L
    }

    /**
     * What the map says about itself.
     *
     * Never "live". The fastest of these is under a minute behind a satellite
     * pass and the slowest is hours, and once the connection goes it is as old
     * as whenever it last loaded.
     */
    fun caption(minimum: DetectionConfidence): String {
        error?.let { return it }
        val shown = visible(minimum)
        val since = fetchedAt?.let {
            val minutes = (System.currentTimeMillis() - it).coerceAtLeast(0L) / 60_000L
            when {
                minutes < 1 -> "just now"
                minutes == 1L -> "1 min ago"
                minutes < 90 -> "$minutes min ago"
                else -> "${minutes / 60} h ago"
            }
        } ?: "not yet fetched"
        val newest = shown.maxByOrNull { it.date + (it.timeUtc ?: "") }
        return buildString {
            append("${shown.size} detection${if (shown.size == 1) "" else "s"}")
            newest?.let { append(" · newest ").append(it.date).append(' ')
                .append(it.clock ?: "") }
            append(" · loaded ").append(since)
        }.trim()
    }

    /** Those at or above [minimum] confidence. */
    fun visible(minimum: DetectionConfidence): List<FireDetection> {
        if (minimum == DetectionConfidence.LOW) return detections
        return detections.filter { passes(it.confidence, minimum) }
    }

    companion object {
        /**
         * Whether a detection clears a confidence floor.
         *
         * An unstated confidence never clears a floor above the bottom. The
         * alternative is letting a detection whose grading did not come
         * through pass as though it had been graded, which is the wrong way
         * round to guess.
         */
        /**
         * How long a failed fetch is left alone.
         *
         * A minute. Long enough that a rate limit has a chance to clear and
         * that a dead connection is not retried on every frame; short enough
         * that coming back into signal puts the fire back on the map before
         * anybody gives up on it.
         */
        const val RETRY_MILLIS = 60_000L

        /**
         * How long an answer stands before it is worth asking again.
         *
         * Ten minutes. A bird passes twice a day, so this is not about
         * catching new passes -- it is about the minutes-old detections
         * being replaced by their processed versions, and about a map that
         * has not moved not quietly becoming a map of this morning.
         */
        const val STALE_MILLIS = 10 * 60_000L

        fun passes(actual: DetectionConfidence, minimum: DetectionConfidence): Boolean {
            if (minimum == DetectionConfidence.LOW) return true
            if (actual == DetectionConfidence.UNKNOWN) return false
            return actual.ordinal >= minimum.ordinal
        }
    }
}

/**
 * Keeps [feed] pointed at whatever ground is on screen.
 *
 * Written as an effect rather than a producer because the feed holds the
 * answer itself: the points already fetched stay on the map while the next
 * request is in flight, which is what stops a fire blinking out of existence
 * every time somebody pans. Returns nothing for the same reason -- read the
 * feed.
 */
@Composable
fun ObserveDetections(
    feed: FirmsFeed,
    mapKey: String,
    sources: Set<SatelliteSource>,
    window: DetectionWindow?,
    enabled: Boolean
) {
    LaunchedEffect(feed, mapKey, sources, window, enabled) {
        if (!enabled || mapKey.isBlank() || sources.isEmpty() || window == null) {
            if (!enabled || mapKey.isBlank()) feed.clear()
            return@LaunchedEffect
        }
        // A pan that crosses a window boundary mid-gesture would otherwise
        // fire a request the next frame cancels. The window is already
        // quantised; this covers the crossing itself.
        kotlinx.coroutines.delay(SETTLE_MILLIS)
        feed.refresh(mapKey = mapKey, sources = sources, window = window)
    }
}

/** Long enough to sit out a pan, short enough not to feel stuck. */
private const val SETTLE_MILLIS = 400L
