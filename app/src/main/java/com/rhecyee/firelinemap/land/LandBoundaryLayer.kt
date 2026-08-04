package com.rhecyee.firelinemap.land

import com.rhecyee.firelinemap.map.MapProjection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/** One outline, already in the canvas's unit square. */
class ProjectedBoundary(
    val name: String,
    val agency: LandAgency,
    val xs: FloatArray,
    val ys: FloatArray,
    /** Where each ring starts in [xs]; the last entry is the end. */
    val ringStarts: IntArray,
    val labelX: Float,
    val labelY: Float,
    val hasLabel: Boolean
)

/**
 * Outlines ready to draw.
 *
 * A plain class, not a data class, for the same reason as the contours: Compose
 * compares state on every recomposition, and structurally comparing thousands
 * of boundary points sixty times a second is enough on its own to stop the
 * interface answering.
 */
class BoundaryRender(val boundaries: List<ProjectedBoundary>) {
    val isEmpty: Boolean get() = boundaries.isEmpty()

    companion object {
        val NONE = BoundaryRender(emptyList())
    }
}

/**
 * Draws where administered ground begins and ends.
 *
 * Tapping already said whose ground a point is on. That answers a question
 * somebody knew to ask; an outline answers the one they did not -- that the
 * line they are cutting crosses onto another agency's ground in four hundred
 * metres, which changes who is notified, whose resources respond and whether
 * structure protection applies.
 *
 * Fetched and projected off the main thread, once per settled view, exactly
 * like the contours. Drawing then only walks arrays.
 */
class LandBoundaryLayer {

    private val _boundaries = MutableStateFlow(BoundaryRender.NONE)
    val boundaries: StateFlow<BoundaryRender> = _boundaries.asStateFlow()

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, error ->
                if (error !is CancellationException) _boundaries.value = BoundaryRender.NONE
            }
    )

    private var job: Job? = null
    private var lastRequest: Request? = null

    private data class Request(
        val north: Double,
        val south: Double,
        val west: Double,
        val east: Double,
        val projection: MapProjection
    ) {
        fun covers(other: Request): Boolean {
            if (other.projection !== projection) return false
            val slackLatitude = (north - south) * 0.15
            val slackLongitude = (east - west) * 0.15
            return other.north <= north + slackLatitude &&
                other.south >= south - slackLatitude &&
                other.west >= west - slackLongitude &&
                other.east <= east + slackLongitude
        }
    }

    fun request(
        north: Double,
        south: Double,
        west: Double,
        east: Double,
        projection: MapProjection
    ) {
        if (north <= south || east <= west) return
        // Past a certain width every unit in three states intersects the view
        // and none of them mean anything at that size.
        if (north - south > MAX_SPAN_DEGREES || east - west > MAX_SPAN_DEGREES) {
            _boundaries.value = BoundaryRender.NONE
            lastRequest = null
            return
        }

        val request = Request(north, south, west, east, projection)
        if (lastRequest?.covers(request) == true) return
        lastRequest = request

        job?.cancel()
        job = scope.launch {
            val span = maxOf(north - south, east - west)
            val url = LandBoundaryParser.boundariesUrl(
                north, south, west, east,
                generaliseDegrees = LandBoundaryParser.generalisationFor(span)
            )
            val body = fetch(url)
            if (body == null) {
                // Not cached as an answer: no signal now is not no boundary.
                lastRequest = null
                return@launch
            }
            ensureActive()

            val parsed = LandBoundaryParser.parse(body)
            ensureActive()
            _boundaries.value = BoundaryRender(parsed.mapNotNull { project(it, projection) })
        }
    }

    fun clear() {
        job?.cancel()
        lastRequest = null
        _boundaries.value = BoundaryRender.NONE
    }

    private fun project(
        boundary: LandBoundary,
        projection: MapProjection
    ): ProjectedBoundary? {
        val xs = ArrayList<Float>(boundary.pointCount)
        val ys = ArrayList<Float>(boundary.pointCount)
        val starts = ArrayList<Int>(boundary.rings.size + 1)

        for (ring in boundary.rings) {
            val begin = xs.size
            for ((longitude, latitude) in ring) {
                val unit = projection.toUnit(latitude, longitude) ?: continue
                xs += unit.first
                ys += unit.second
            }
            if (xs.size - begin >= 3) starts += begin else {
                while (xs.size > begin) { xs.removeAt(xs.size - 1); ys.removeAt(ys.size - 1) }
            }
        }
        if (starts.isEmpty()) return null
        starts += xs.size

        // Labelled at the middle of the largest ring, which for these is the
        // body of the unit rather than an outlying parcel of it.
        var largest = 0
        var largestSize = 0
        for (index in 0 until starts.size - 1) {
            val size = starts[index + 1] - starts[index]
            if (size > largestSize) { largestSize = size; largest = index }
        }
        val middle = starts[largest] + largestSize / 2

        return ProjectedBoundary(
            name = boundary.name,
            agency = boundary.agency,
            xs = xs.toFloatArray(),
            ys = ys.toFloatArray(),
            ringStarts = starts.toIntArray(),
            labelX = xs[middle],
            labelY = ys[middle],
            hasLabel = largestSize >= 6
        )
    }

    private fun fetch(url: String): String? {
        val connection = runCatching {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 25_000
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

    companion object {
        /**
         * Widest view worth outlining, in degrees.
         *
         * About seventy miles. Past that the whole screen is two or three
         * units and the boundaries are the edge of the map rather than
         * anything to navigate by.
         */
        const val MAX_SPAN_DEGREES = 1.0
    }
}
