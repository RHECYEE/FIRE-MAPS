package com.rhecyee.firelinemap.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Background terrain tiles, fetched once and then held on disk.
 *
 * The source is the USGS National Map, which is public domain federal data
 * and needs no key or account. That matters here: the whole point of this
 * tool is that it works for people who cannot get onto agency infrastructure,
 * so a basemap behind a licensed service would defeat it. It is also topo
 * rather than street cartography, which is the right thing to be looking at
 * off the end of a fireline map.
 *
 * Tiles are only ever fetched for ground the operator has actually looked at,
 * and once cached they render offline.
 */
/** A tile, or a crop of a coarser tile standing in for one. */
data class TileSample(
    val bitmap: Bitmap,
    val sourceLeft: Int,
    val sourceTop: Int,
    val sourceSize: Int
)

class BasemapTileCache(context: Context) {

    private val root = File(context.filesDir, "basemap").apply { mkdirs() }
    /**
     * Tile work must not be able to take the app down.
     *
     * Without a handler, anything thrown in one of these coroutines reaches
     * the thread's uncaught handler and the process is killed. Decoding
     * bitmaps off the network is the likeliest place in this app to run out
     * of memory, and losing the map because one tile failed is not a trade
     * worth making.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> }
    )

    /** Decoded tiles, keyed z/x/y. Backed by snapshot state so arrivals redraw. */
    val tiles: SnapshotStateMap<String, Bitmap> = mutableStateMapOf()

    /**
     * Which tiles were looked at, oldest first.
     *
     * This map used to grow without limit. Every tile ever drawn stayed
     * decoded in memory at a quarter of a megabyte each, and zooming is
     * precisely the thing that touches tiles at many levels at once: a few
     * passes in and out over one district is several hundred tiles, which is
     * more heap than the rendered sheet and everything else put together. The
     * app ran out of memory and was killed, which looked like zooming
     * crashing it. The tiles are still on disk; only the decoded copies are
     * dropped, and they decode again in a millisecond.
     */
    private val recent = object : LinkedHashMap<String, Unit>(16, 0.75f, true) {}

    private val inFlight = mutableSetOf<String>()

    /**
     * When a tile last failed to arrive.
     *
     * Without this a tile the service will not serve -- past the top of its
     * pyramid, or outside its extent -- is asked for again on the very next
     * frame, forever. Every draw restarts the request, every failure triggers
     * another draw, and the map churns through requests at frame rate.
     */
    private val failedAt = mutableMapOf<String, Long>()

    private val limiter = Semaphore(6)

    /**
     * A tile, or the matching piece of a coarser one that is already held.
     *
     * A missing tile is drawn from its nearest cached ancestor, blurred up,
     * rather than left as a hole. Fetches lag badly in a moving vehicle, and a
     * checkerboard of gaps is worse than soft terrain: it reads as the map
     * being broken rather than as detail still arriving.
     *
     * [fetch] is false while a finger is down. Mid-pinch the view sweeps
     * through several zoom levels in under a second, and fetching at each one
     * puts hundreds of requests in flight for ground that is already off
     * screen by the time they land -- which the operator sees as the map
     * swapping under them. Whatever is cached is drawn instead, and the level
     * that was actually settled on is fetched once the hand comes off.
     */
    fun sample(zoom: Int, x: Int, y: Int, fetch: Boolean = true): TileSample? {
        val exact = if (fetch) tile(zoom, x, y) else held(key(zoom, x, y))
        if (exact != null) return TileSample(exact, 0, 0, exact.width)

        var depth = 1
        while (depth <= ANCESTOR_DEPTH && zoom - depth >= 0) {
            val ancestorZoom = zoom - depth
            val ancestorX = x shr depth
            val ancestorY = y shr depth
            val ancestor = held(key(ancestorZoom, ancestorX, ancestorY))
            if (ancestor != null) {
                val span = 1 shl depth
                val size = ancestor.width / span
                if (size > 0) {
                    return TileSample(
                        bitmap = ancestor,
                        sourceLeft = (x - (ancestorX shl depth)) * size,
                        sourceTop = (y - (ancestorY shl depth)) * size,
                        sourceSize = size
                    )
                }
            }
            // Ask for it as well, so the fallback layer keeps existing.
            if (fetch && depth == ANCESTOR_DEPTH) tile(ancestorZoom, ancestorX, ancestorY)
            depth++
        }
        return null
    }

    /** Returns the tile if it is ready, otherwise starts fetching it. */
    fun tile(zoom: Int, x: Int, y: Int): Bitmap? {
        val key = key(zoom, x, y)
        held(key)?.let { return it }

        synchronized(inFlight) {
            // A pinch asks for every missing tile on every frame, across
            // several levels. Unbounded, that is hundreds of requests queued
            // behind a six-deep limiter, most of them for ground that is off
            // screen before they land -- and the ones that time out take the
            // ground the operator is actually looking at down with them.
            if (inFlight.size >= MAX_IN_FLIGHT) return null
            if (!inFlight.add(key)) return null
            val retryAt = failedAt[key]
            if (retryAt != null && now() < retryAt) {
                inFlight.remove(key)
                return null
            }
        }

        scope.launch {
            var retryAfter = 0L
            try {
                val file = File(root, "$zoom/$x/$y.png")
                var outcome = Fetch.OK
                val bitmap = decode(file) ?: run {
                    outcome = limiter.withPermit { download(zoom, x, y, file) }
                    decode(file)
                }
                if (bitmap != null) put(key, bitmap)
                retryAfter = when {
                    bitmap != null -> 0L
                    outcome == Fetch.MISSING -> MISSING_BACKOFF_MILLIS
                    else -> TRANSIENT_BACKOFF_MILLIS
                }
            } finally {
                // In a finally because a key left behind here is never asked
                // for again: the request is treated as still in flight for the
                // life of the process, and that patch of map stays empty
                // forever. It looks exactly like the terrain not loading,
                // which is what it is.
                synchronized(inFlight) {
                    inFlight.remove(key)
                    if (retryAfter > 0) failedAt[key] = now() + retryAfter
                    else failedAt.remove(key)
                }
            }
        }
        return null
    }

    /**
     * Puts a tile on disk without decoding it.
     *
     * The radius preload walks thousands of tiles across several levels. Sent
     * through [tile] they would each be decoded and inserted into the memory
     * cache, evicting everything the operator is actually looking at -- the
     * map goes grey while ground nobody is looking at streams past behind it.
     * The preload only ever wanted the files.
     */
    suspend fun prefetch(zoom: Int, x: Int, y: Int): Boolean {
        val file = File(root, "$zoom/$x/$y.png")
        if (file.length() > 0L) return true
        val key = key(zoom, x, y)
        synchronized(inFlight) {
            val retryAt = failedAt[key]
            if (retryAt != null && now() < retryAt) return false
        }
        val outcome = limiter.withPermit { download(zoom, x, y, file) }
        val ok = outcome == Fetch.OK && file.length() > 0L
        synchronized(inFlight) {
            when {
                ok -> failedAt.remove(key)
                outcome == Fetch.MISSING -> failedAt[key] = now() + MISSING_BACKOFF_MILLIS
                else -> failedAt[key] = now() + TRANSIENT_BACKOFF_MILLIS
            }
        }
        return ok
    }

    /** Overridable so the back-off can be tested without waiting a minute. */
    internal var now: () -> Long = { System.currentTimeMillis() }

    /** A held tile, marked as used so it survives the next eviction. */
    private fun held(key: String): Bitmap? {
        val bitmap = tiles[key] ?: return null
        synchronized(recent) { recent[key] = Unit }
        return bitmap
    }

    private fun put(key: String, bitmap: Bitmap) {
        tiles[key] = bitmap
        val evicted = synchronized(recent) {
            recent[key] = Unit
            val over = recent.size - TILES_HELD
            if (over <= 0) {
                emptyList()
            } else {
                // The eldest by last use, which during a zoom is the level
                // that was left behind rather than the one being looked at.
                val going = recent.keys.take(over).toList()
                going.forEach { recent.remove(it) }
                going
            }
        }
        // Dropped rather than recycled: a draw in flight may still be holding
        // one, and recycling underneath it would take the app down for the
        // sake of freeing memory a moment sooner.
        evicted.forEach { tiles.remove(it) }
    }

    private fun decode(file: File): Bitmap? {
        if (!file.exists() || file.length() == 0L) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    /** What came of asking for a tile. */
    private enum class Fetch { OK, MISSING, TRANSIENT }

    private fun download(zoom: Int, x: Int, y: Int, target: File): Fetch {
        var outcome = Fetch.TRANSIENT
        runCatching {
            target.parentFile?.mkdirs()
            // The National Map's REST tiles are ordered z/y/x, not z/x/y.
            val url = URL("$ENDPOINT/$zoom/$y/$x")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "FirelineMap/0.3")
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    // A refusal is about the tile and will not change; anything
                    // else is about the moment and will.
                    outcome = if (code in 400..499) Fetch.MISSING else Fetch.TRANSIENT
                    return@runCatching
                }
                val temporary = File(target.parentFile, "${target.name}.part")
                connection.inputStream.use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                outcome = if (temporary.length() > 0 && temporary.renameTo(target)) {
                    Fetch.OK
                } else {
                    temporary.delete()
                    Fetch.TRANSIENT
                }
            } finally {
                connection.disconnect()
            }
        }
        return outcome
    }


    private fun key(zoom: Int, x: Int, y: Int) = "$zoom/$x/$y"

    /**
     * What the tile layer is doing, in one line.
     *
     * Put here because "the map went grey" has cost several rounds of
     * guessing. Held is what can be drawn now; in flight is what is being
     * waited on; waiting is tiles under a back-off, which is the state that
     * looks like a broken map and is not.
     */
    fun diagnostics(): String {
        val (flight, waiting) = synchronized(inFlight) {
            inFlight.size to failedAt.count { now() < it.value }
        }
        return "${tiles.size} held · $flight fetching · $waiting waiting" +
            (lastLevel?.let { " · level $it" } ?: "") +
            (lastFailure?.let { " · DRAW FAILED: $it" } ?: "")
    }

    /** The tile level last drawn at, for the same diagnostic. */
    @Volatile
    var lastLevel: Int? = null

    /** Set when a terrain draw threw, so a silent failure is not silent. */
    @Volatile
    var lastFailure: String? = null

    fun cachedBytes(): Long =
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clear() {
        tiles.clear()
        synchronized(recent) { recent.clear() }
        synchronized(inFlight) { failedAt.clear() }
        root.deleteRecursively()
        root.mkdirs()
    }

    companion object {
        const val ENDPOINT =
            "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile"

        const val ATTRIBUTION = "USGS The National Map"

        const val TILE_SIZE = 256

        /**
         * How many decoded tiles to keep in memory.
         *
         * A quarter of a megabyte each, so this is about forty megabytes --
         * comfortably more than any one screen needs, which is a few dozen,
         * and far below what an unbounded cache reached after a few minutes
         * of zooming.
         */
        const val TILES_HELD = 160

        /** How many zoom levels to climb looking for something to draw. */
        const val ANCESTOR_DEPTH = 4

        /**
         * How long to leave a tile alone after the service refused it.
         *
         * A refusal is about the tile: it is past the top of the pyramid or
         * outside the extent, and asking again in a second changes nothing.
         */
        const val MISSING_BACKOFF_MILLIS = 60_000L

        /**
         * How long to wait after a tile failed to arrive.
         *
         * Short, because this is about the moment rather than the tile. A
         * timeout on a ridge with one bar is the normal case out here, and
         * treating it like a refusal left whole screens blank for a minute at
         * a time -- which is exactly what "the map went grey and stayed grey"
         * was.
         */
        const val TRANSIENT_BACKOFF_MILLIS = 3_000L

        /**
         * How many tile requests may be outstanding at once.
         *
         * Deep enough to keep the six workers fed, shallow enough that a
         * request started now is answered in seconds rather than after a
         * gesture's worth of stale ones ahead of it.
         */
        const val MAX_IN_FLIGHT = 24

        /**
         * How far past a level boundary the view has to travel before the
         * tile level follows it.
         *
         * A quarter of a level. Small enough that the terrain is never more
         * than slightly coarse or slightly fine for what is on screen, large
         * enough that a hand resting on a boundary cannot make it flip.
         */
        const val ZOOM_HYSTERESIS = 0.25

        /** Ground resolution of one tile pixel, in metres. */
        fun metersPerPixel(latitude: Double, zoom: Int): Double =
            156_543.03392 * cos(Math.toRadians(latitude)) / (1 shl zoom)

        /** The zoom whose pixels most nearly match [targetMetersPerPixel]. */
        fun zoomFor(latitude: Double, targetMetersPerPixel: Double, max: Int = 16): Int {
            if (targetMetersPerPixel <= 0) return max
            for (zoom in 0..max) {
                if (metersPerPixel(latitude, zoom) <= targetMetersPerPixel) return zoom
            }
            return max
        }

        /**
         * The zoom the view sits at, unrounded.
         *
         * [zoomFor] is the ceiling of this. Keeping the fraction is what lets
         * the choice below know how near a boundary the view is.
         */
        fun fractionalZoom(latitude: Double, targetMetersPerPixel: Double): Double {
            if (targetMetersPerPixel <= 0) return Double.MAX_VALUE
            val widest = 156_543.03392 * cos(Math.toRadians(latitude))
            if (widest <= 0) return 0.0
            return ln(widest / targetMetersPerPixel) / ln(2.0)
        }

        /**
         * The tile level to draw, holding the one already in use until the
         * view has clearly left it.
         *
         * Choosing purely by resolution flips level the instant a pinch
         * crosses a boundary, and a pinch does not cross a boundary once --
         * fingers wobble, and it crosses back and forth several times a
         * second. Each crossing swaps in a whole screen of tiles at a
         * different resolution, so the terrain appears to flicker between two
         * different maps. Holding the previous level through a margin stops
         * that without ever leaving the level more than a quarter step off.
         */
        fun zoomForStable(
            latitude: Double,
            targetMetersPerPixel: Double,
            previous: Int?,
            max: Int = 16
        ): Int {
            val ideal = zoomFor(latitude, targetMetersPerPixel, max)
            if (previous == null || previous == ideal) return ideal
            val exact = fractionalZoom(latitude, targetMetersPerPixel)
            return when {
                // Detail is wanted: the view has passed the level it is on.
                exact > previous + ZOOM_HYSTERESIS -> ideal
                // Detail is wasted: the view has dropped a whole level below.
                exact < previous - 1 - ZOOM_HYSTERESIS -> ideal
                else -> previous
            }
        }

        fun tileX(longitude: Double, zoom: Int): Int {
            val scale = 1 shl zoom
            return ((longitude + 180.0) / 360.0 * scale).toInt().coerceIn(0, scale - 1)
        }

        fun tileY(latitude: Double, zoom: Int): Int {
            val scale = 1 shl zoom
            val clamped = latitude.coerceIn(-85.05112878, 85.05112878)
            val radians = Math.toRadians(clamped)
            val value = (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0
            return (value * scale).toInt().coerceIn(0, scale - 1)
        }

        /** West longitude of a tile column. */
        fun tileWest(x: Int, zoom: Int): Double = x.toDouble() / (1 shl zoom) * 360.0 - 180.0

        /** North latitude of a tile row. */
        fun tileNorth(y: Int, zoom: Int): Double {
            val n = PI - 2.0 * PI * y / (1 shl zoom)
            return Math.toDegrees(atan(sinh(n)))
        }
    }
}
