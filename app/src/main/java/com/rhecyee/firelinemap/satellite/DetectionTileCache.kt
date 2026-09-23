package com.rhecyee.firelinemap.satellite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Satellite thermal anomalies, fetched as tiles and held on disk.
 *
 * Deliberately a separate cache from the terrain, because the two age in
 * opposite ways. A ridge is a ridge and a terrain tile is good forever, so
 * that cache only ever grows. A detection is a statement about one moment,
 * and yesterday's is not a worse version of today's -- it is a different
 * claim. So these are filed by the pass they came from, and passes that are
 * no longer being shown are deleted rather than kept around getting older.
 *
 * Nothing here says "live". The nearest this data gets to now is a few hours
 * behind, and after the connection goes it is as old as whenever it last
 * loaded. [age] is what the screen puts in front of the operator.
 */
class DetectionTileCache(context: Context) {

    private val root = File(context.filesDir, "detections").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Decoded tiles, keyed source/date/z/x/y. Snapshot-backed so arrivals redraw. */
    val tiles: SnapshotStateMap<String, Bitmap> = mutableStateMapOf()

    private val inFlight = mutableSetOf<String>()
    private val limiter = Semaphore(4)

    /** When a tile was last actually retrieved, for the age caption. */
    private val fetchedAt = mutableStateOf<Long?>(null)

    /**
     * The pass being asked for.
     *
     * UTC, because that is how the satellite dates its own overpasses, and
     * converting to local time here would put an operator working a night
     * shift on the wrong side of the date line from their own data.
     */
    var date: String = today()
        private set

    fun age(): DetectionAge = DetectionAge(date, fetchedAt.value)

    /**
     * Moves to the most recent pass, dropping anything older off the disk.
     *
     * Called when the layer is switched on and when the day rolls over. The
     * purge is the point: without it the cache would quietly accumulate every
     * day the app was ever opened, and a stale tile could be drawn against a
     * date it did not belong to.
     */
    fun refresh(now: Long = System.currentTimeMillis()) {
        val wanted = today(now)
        if (wanted == date) return
        date = wanted
        tiles.clear()
        fetchedAt.value = null
        scope.launch { purgeOtherThan(wanted) }
    }

    /**
     * A tile, or the matching piece of a coarser one already held.
     *
     * Same fallback as the terrain, and for a stronger reason: detections are
     * fetched only to [MAX_FETCH_ZOOM], because a 375 metre footprint drawn
     * from a tile any deeper than that is inventing precision the sensor does
     * not have. Zoomed past it, the coarser tile is what there is.
     */
    fun sample(
        source: SatelliteSource,
        zoom: Int,
        x: Int,
        y: Int
    ): com.rhecyee.firelinemap.map.TileSample? {
        val capped = zoom.coerceAtMost(MAX_FETCH_ZOOM)
        val shift = zoom - capped
        val cappedX = x shr shift
        val cappedY = y shr shift

        tile(source, capped, cappedX, cappedY)?.let {
            return com.rhecyee.firelinemap.map.TileSample(it, 0, 0, it.width)
        }

        var depth = 1
        while (depth <= ANCESTOR_DEPTH && capped - depth >= 0) {
            val ancestorZoom = capped - depth
            val ancestorX = cappedX shr depth
            val ancestorY = cappedY shr depth
            val ancestor = tiles[key(source, ancestorZoom, ancestorX, ancestorY)]
            if (ancestor != null) {
                val span = 1 shl depth
                val size = ancestor.width / span
                if (size > 0) {
                    return com.rhecyee.firelinemap.map.TileSample(
                        bitmap = ancestor,
                        sourceLeft = (cappedX - (ancestorX shl depth)) * size,
                        sourceTop = (cappedY - (ancestorY shl depth)) * size,
                        sourceSize = size
                    )
                }
            }
            if (depth == ANCESTOR_DEPTH) tile(source, ancestorZoom, ancestorX, ancestorY)
            depth++
        }
        return null
    }

    /** Returns the tile if it is ready, otherwise starts fetching it. */
    fun tile(source: SatelliteSource, zoom: Int, x: Int, y: Int): Bitmap? {
        val key = key(source, zoom, x, y)
        tiles[key]?.let { return it }

        synchronized(inFlight) {
            if (!inFlight.add(key)) return null
        }

        val wanted = date
        scope.launch {
            val file = File(root, "${source.name}/$wanted/$zoom/$x/$y.png")
            val bitmap = decode(file) ?: run {
                limiter.withPermit { download(source, wanted, zoom, x, y, file) }
                decode(file)
            }
            if (bitmap != null && wanted == date) {
                tiles[key] = bitmap
                if (fetchedAt.value == null) fetchedAt.value = System.currentTimeMillis()
            }
            synchronized(inFlight) { inFlight.remove(key) }
        }
        return null
    }

    private fun decode(file: File): Bitmap? {
        if (!file.exists() || file.length() == 0L) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    private fun download(
        source: SatelliteSource,
        date: String,
        zoom: Int,
        x: Int,
        y: Int,
        target: File
    ) {
        runCatching {
            target.parentFile?.mkdirs()
            val url = URL(DetectionTileRequest.url(source, date, zoom, x, y))
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "FirelineMap/0.7")
            }
            try {
                if (connection.responseCode !in 200..299) return
                // An empty pass is a real answer and worth caching: most tiles
                // over most ground have nothing burning on them, and asking
                // again every redraw would be the bulk of the traffic.
                val temporary = File(target.parentFile, "${target.name}.part")
                connection.inputStream.use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                if (temporary.length() > 0) temporary.renameTo(target) else temporary.delete()
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun purgeOtherThan(keep: String) {
        runCatching {
            root.listFiles()?.forEach { bySource ->
                bySource.listFiles()?.forEach { byDate ->
                    if (byDate.name != keep) byDate.deleteRecursively()
                }
            }
        }
    }

    private fun key(source: SatelliteSource, zoom: Int, x: Int, y: Int) =
        "${source.name}/$date/$zoom/$x/$y"

    fun cachedBytes(): Long =
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clear() {
        tiles.clear()
        fetchedAt.value = null
        root.deleteRecursively()
        root.mkdirs()
    }

    companion object {
        /**
         * Deepest zoom a detection tile is fetched at.
         *
         * One pixel of a tile at this zoom is roughly forty metres of ground
         * at these latitudes, so a 375 metre VIIRS footprint is about ten
         * pixels across: as much precision as the sensor has, and past which
         * the map would only be drawing bigger dots.
         */
        const val MAX_FETCH_ZOOM = 12

        const val ANCESTOR_DEPTH = 4

        const val ATTRIBUTION = "NASA FIRMS / GIBS"

        /** The UTC day, which is how the satellite dates its own passes. */
        fun today(now: Long = System.currentTimeMillis()): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(now))
    }
}
