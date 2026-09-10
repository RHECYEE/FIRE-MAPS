package com.rhecyee.firelinemap.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.MutableIntState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos

/**
 * Ground elevations, fetched as tiles and held on disk.
 *
 * Separate from the basemap on purpose. The basemap is a picture of a map:
 * its contour lines are pixels, so they cannot be turned off, re-intervalled,
 * or laid over an incident sheet. This is the elevation itself, and contours
 * drawn from it are the operator's to set.
 *
 * The source is the public Terrain Tiles archive, which carries 3DEP over the
 * United States and needs no key or account -- the same reason the basemap
 * comes from the National Map. Coverage stops at zoom fifteen, which at these
 * latitudes is a sample every four metres or so: far finer than any contour
 * interval anyone would ask for.
 */
class ElevationTiles(context: Context) {

    private val root = File(context.filesDir, "elevation").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Bumped whenever a tile lands.
     *
     * Contours are traced off the main thread against whatever is cached, so
     * something has to say "there is more ground now, trace it again". A
     * counter is enough and costs nothing to read every frame.
     */
    val version: MutableIntState = mutableIntStateOf(0)

    private val decoded = LinkedHashMap<String, FloatArray>(16, 0.75f, true)
    private val inFlight = mutableSetOf<String>()
    private val limiter = Semaphore(4)

    /**
     * Elevations across a window, in metres, on a regular latitude/longitude
     * grid. Samples over ground not yet fetched come back as NaN, and the
     * missing tiles are requested.
     *
     * Null when the window is nonsense or so wide that covering it would mean
     * fetching half a state.
     */
    fun grid(
        north: Double,
        south: Double,
        west: Double,
        east: Double,
        samples: Int,
    ): ElevationGrid? {
        if (north <= south || east <= west || samples < 2) return null

        val centreLatitude = (north + south) / 2.0
        val spanMeters = MapCoverage.distanceMeters(centreLatitude, west, centreLatitude, east)
        if (spanMeters <= 0.0) return null
        val zoom = zoomFor(centreLatitude, spanMeters / samples)

        // Guard as the basemap does: a runaway transform must not turn into a
        // thousand downloads.
        val minX = BasemapTileCache.tileX(west, zoom)
        val maxX = BasemapTileCache.tileX(east, zoom)
        val minY = BasemapTileCache.tileY(north, zoom)
        val maxY = BasemapTileCache.tileY(south, zoom)
        if ((maxX - minX + 1).toLong() * (maxY - minY + 1).toLong() > MAX_TILES) return null

        // Square samples on the ground rather than in degrees, so a contour is
        // traced at the same fidelity across as it is down.
        val heightMeters = MapCoverage.distanceMeters(north, west, south, west)
        val rows = if (spanMeters <= 0.0) samples else
            ((samples * heightMeters / spanMeters).toInt()).coerceIn(2, MAX_SAMPLES)
        val columns = samples.coerceIn(2, MAX_SAMPLES)

        val values = FloatArray(columns * rows)
        var known = 0
        for (row in 0 until rows) {
            val latitude = north - (north - south) * row / (rows - 1.0)
            for (column in 0 until columns) {
                val longitude = west + (east - west) * column / (columns - 1.0)
                val value = elevationAt(latitude, longitude, zoom)
                values[row * columns + column] = value
                if (!value.isNaN()) known++
            }
        }
        if (known == 0) return null

        return ElevationGrid(
            values = values,
            width = columns,
            height = rows,
            north = north,
            south = south,
            west = west,
            east = east,
            zoom = zoom,
            coverage = known.toFloat() / values.size
        )
    }

    /** One sample, or NaN with a fetch started. */
    private fun elevationAt(latitude: Double, longitude: Double, zoom: Int): Float {
        val scale = 1 shl zoom
        val x = TileMath.worldX(longitude, zoom)
        val y = TileMath.worldY(latitude, zoom)
        val tileX = x.toInt().coerceIn(0, scale - 1)
        val tileY = y.toInt().coerceIn(0, scale - 1)

        val tile = tile(zoom, tileX, tileY) ?: return Float.NaN
        val pixelX = (((x - tileX) * TILE_SIZE).toInt()).coerceIn(0, TILE_SIZE - 1)
        val pixelY = (((y - tileY) * TILE_SIZE).toInt()).coerceIn(0, TILE_SIZE - 1)
        return tile[pixelY * TILE_SIZE + pixelX]
    }

    /** A decoded tile if it is held, otherwise null with a fetch started. */
    private fun tile(zoom: Int, x: Int, y: Int): FloatArray? {
        val key = "$zoom/$x/$y"
        synchronized(decoded) { decoded[key] }?.let { return it }

        synchronized(inFlight) {
            if (!inFlight.add(key)) return null
        }

        scope.launch {
            val file = File(root, "$zoom/$x/$y.png")
            var samples = decode(file)
            if (samples == null) {
                limiter.withPermit { download(zoom, x, y, file) }
                samples = decode(file)
            }
            if (samples != null) {
                synchronized(decoded) {
                    decoded[key] = samples
                    while (decoded.size > MEMORY_TILES) {
                        val oldest = decoded.keys.firstOrNull() ?: break
                        decoded.remove(oldest)
                    }
                }
                version.intValue++
            }
            synchronized(inFlight) { inFlight.remove(key) }
        }
        return null
    }

    private fun decode(file: File): FloatArray? {
        if (!file.exists() || file.length() == 0L) return null
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            ?: return null
        return try {
            if (bitmap.width != TILE_SIZE || bitmap.height != TILE_SIZE) return null
            val pixels = IntArray(TILE_SIZE * TILE_SIZE)
            bitmap.getPixels(pixels, 0, TILE_SIZE, 0, 0, TILE_SIZE, TILE_SIZE)
            FloatArray(pixels.size) { decodeTerrarium(pixels[it]).toFloat() }
        } finally {
            bitmap.recycle()
        }
    }

    private fun download(zoom: Int, x: Int, y: Int, target: File) {
        runCatching {
            target.parentFile?.mkdirs()
            val url = URL("$ENDPOINT/$zoom/$x/$y.png")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "FirelineMap/0.3")
            }
            try {
                if (connection.responseCode !in 200..299) return
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

    fun cachedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clear() {
        synchronized(decoded) { decoded.clear() }
        root.deleteRecursively()
        root.mkdirs()
        version.intValue++
    }

    companion object {
        const val ENDPOINT = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium"

        const val ATTRIBUTION = "Elevation: Terrain Tiles (USGS 3DEP, SRTM)"

        const val TILE_SIZE = 256

        /** The archive serves no deeper than this and 404s beyond it. */
        const val MAX_ZOOM = 15

        /** Below this a tile covers so much ground that contours are noise. */
        const val MIN_ZOOM = 8

        private const val MAX_TILES = 64L
        private const val MEMORY_TILES = 48
        private const val MAX_SAMPLES = 512

        /**
         * Elevation packed into colour: two bytes of metres and one of
         * fraction, offset so the bottom of the Dead Sea still fits.
         */
        fun decodeTerrarium(red: Int, green: Int, blue: Int): Double =
            (red * 256.0 + green + blue / 256.0) - 32768.0

        fun decodeTerrarium(pixel: Int): Double = decodeTerrarium(
            (pixel shr 16) and 0xFF,
            (pixel shr 8) and 0xFF,
            pixel and 0xFF
        )

        fun metersPerPixel(latitude: Double, zoom: Int): Double =
            156_543.03392 * cos(Math.toRadians(latitude)) / (1 shl zoom)

        /** The deepest zoom whose samples are no coarser than asked for. */
        fun zoomFor(latitude: Double, targetMetersPerSample: Double): Int {
            if (targetMetersPerSample <= 0) return MAX_ZOOM
            for (zoom in MIN_ZOOM..MAX_ZOOM) {
                if (metersPerPixel(latitude, zoom) <= targetMetersPerSample) return zoom
            }
            return MAX_ZOOM
        }
    }
}

/**
 * A window of elevations in metres, regular in latitude and longitude.
 *
 * Row zero is the north edge, as tiles are ordered, and the last row is the
 * south. Holes are NaN.
 */
data class ElevationGrid(
    val values: FloatArray,
    val width: Int,
    val height: Int,
    val north: Double,
    val south: Double,
    val west: Double,
    val east: Double,
    val zoom: Int,
    /** How much of the window is actually known, nought to one. */
    val coverage: Float,
) {
    fun latitudeAt(row: Double): Double = north - (north - south) * row / (height - 1.0)

    fun longitudeAt(column: Double): Double = west + (east - west) * column / (width - 1.0)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ElevationGrid) return false
        return width == other.width && height == other.height &&
            north == other.north && south == other.south &&
            west == other.west && east == other.east &&
            zoom == other.zoom && values.contentEquals(other.values)
    }

    override fun hashCode(): Int {
        var result = values.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + north.hashCode()
        result = 31 * result + south.hashCode()
        result = 31 * result + west.hashCode()
        result = 31 * result + east.hashCode()
        result = 31 * result + zoom
        return result
    }
}
