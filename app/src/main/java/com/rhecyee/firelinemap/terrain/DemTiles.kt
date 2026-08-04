package com.rhecyee.firelinemap.terrain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.floor

/**
 * Bare-earth elevation, tiled and held on disk.
 *
 * The source is the AWS Open Data terrain tile set. Over the United States it
 * carries USGS 3DEP with SRTM and GMTED2010 behind it, all of which are public
 * domain: no key, no account, no agreement, and -- checked rather than assumed
 * -- no restriction on downloading and keeping it offline. Attribution is
 * requested, and [ATTRIBUTION] carries the wording the USGS asks for.
 *
 * This is what the contour layer is cut from. It is deliberately not the same
 * thing as the topographic basemap: that is a picture of contours somebody
 * else drew at a fixed interval, and it cannot be re-cut, cannot be labelled
 * to suit the zoom, and cannot answer what the elevation is at a point. This
 * can.
 */
class DemTileCache(context: Context) {

    private val root = File(context.filesDir, "dem").apply { mkdirs() }
    private val limiter = Semaphore(6)

    /**
     * Decoded tiles, most recently used last.
     *
     * Small on purpose. Panning re-reads the same handful of tiles over and
     * over and decoding a PNG each time is the slowest thing in the contour
     * path; holding a whole region of them would cost more memory than the
     * rest of the app uses.
     */
    private val decoded = object : LinkedHashMap<String, FloatArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>) =
            size > DECODED_TILES_HELD
    }

    private val failedAt = mutableMapOf<String, Long>()

    internal var now: () -> Long = { System.currentTimeMillis() }

    /** Whether a tile is already on the device. */
    fun isCached(zoom: Int, x: Int, y: Int): Boolean = file(zoom, x, y).length() > 0

    /**
     * Elevations for one tile in metres, or null if it is not here yet.
     *
     * Never fetches. Downloading is [download]'s job, which is called from
     * places that know whether the connection may be spent.
     */
    fun elevations(zoom: Int, x: Int, y: Int): FloatArray? {
        val key = key(zoom, x, y)
        synchronized(decoded) { decoded[key] }?.let { return it }

        val file = file(zoom, x, y)
        if (file.length() <= 0L) return null
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            ?: return null
        val values = decodeTerrarium(bitmap)
        bitmap.recycle()
        synchronized(decoded) { decoded[key] = values }
        return values
    }

    /** Fetches a tile if it is missing. Blocking; call from a worker. */
    suspend fun download(zoom: Int, x: Int, y: Int): Boolean {
        val target = file(zoom, x, y)
        if (target.length() > 0L) return true
        val key = key(zoom, x, y)
        synchronized(failedAt) {
            val failed = failedAt[key]
            if (failed != null && now() - failed < RETRY_AFTER_MILLIS) return false
        }

        val ok = limiter.withPermit { fetch(zoom, x, y, target) }
        synchronized(failedAt) {
            if (ok) failedAt.remove(key) else failedAt[key] = now()
        }
        return ok
    }

    private fun fetch(zoom: Int, x: Int, y: Int, target: File): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val url = URL("$ENDPOINT/$zoom/$x/$y.png")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "FirelineMap/0.3")
        }
        try {
            if (connection.responseCode !in 200..299) return false
            val temporary = File(target.parentFile, "${target.name}.part")
            connection.inputStream.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            if (temporary.length() > 0) {
                temporary.renameTo(target)
                true
            } else {
                temporary.delete()
                false
            }
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    private fun file(zoom: Int, x: Int, y: Int) = File(root, "$zoom/$x/$y.png")

    private fun key(zoom: Int, x: Int, y: Int) = "$zoom/$x/$y"

    fun cachedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clear() {
        synchronized(decoded) { decoded.clear() }
        synchronized(failedAt) { failedAt.clear() }
        root.deleteRecursively()
        root.mkdirs()
    }

    private fun decodeTerrarium(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val values = FloatArray(pixels.size)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            values[index] = TerrainMath.decodeTerrarium(
                (pixel shr 16) and 0xFF,
                (pixel shr 8) and 0xFF,
                pixel and 0xFF
            ).toFloat()
        }
        return values
    }

    companion object {
        const val ENDPOINT = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium"

        /**
         * The wording the USGS asks for, carried verbatim.
         *
         * Public domain data still gets credited. It costs a line on the key
         * and it is the reason this layer can exist at all.
         */
        const val ATTRIBUTION =
            "United States 3DEP (formerly NED) and global GMTED2010 and SRTM " +
                "terrain data courtesy of the U.S. Geological Survey."

        const val TILE_SIZE = 256

        /** The source stops here; asking beyond it returns nothing forever. */
        const val MAX_ZOOM = 15

        const val RETRY_AFTER_MILLIS = 60_000L

        private const val DECODED_TILES_HELD = 24
    }
}

/**
 * Builds one elevation grid across a view from whatever tiles are on disk.
 *
 * Sampling into a fixed-size grid rather than contouring each tile separately
 * is what keeps contours continuous across tile edges. Cut per tile they would
 * stop dead at every seam, and a contour with a gap in it every few hundred
 * metres is worse than none: it reads as a break in the ground.
 *
 * The output is deliberately coarser than the source. Contouring every DEM
 * pixel produces lines that follow the sampling noise rather than the terrain,
 * and costs an order of magnitude more work for a rougher-looking result.
 */
object ElevationGridAssembler {

    /** Longest side of the working grid. */
    const val MAX_SAMPLES = 192

    /** More tiles than a view should ever legitimately need. */
    const val MAX_TILES = 48

    /** Every tile covering a box at a zoom, in fetch order from the middle out. */
    fun tilesFor(
        north: Double,
        south: Double,
        west: Double,
        east: Double,
        zoom: Int
    ): List<Triple<Int, Int, Int>> {
        val scale = 1 shl zoom
        val minX = tileX(west, zoom)
        val maxX = tileX(east, zoom)
        val minY = tileY(north, zoom)
        val maxY = tileY(south, zoom)
        if (minX > maxX || minY > maxY) return emptyList()

        val centreX = (minX + maxX) / 2.0
        val centreY = (minY + maxY) / 2.0
        val tiles = mutableListOf<Triple<Int, Int, Int>>()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                if (x !in 0 until scale || y !in 0 until scale) continue
                tiles += Triple(zoom, x, y)
            }
        }
        // Nearest the middle of the view first: if a download is cut short,
        // what the operator is looking at is what arrived.
        return tiles.sortedBy { (_, x, y) ->
            val dx = x - centreX
            val dy = y - centreY
            dx * dx + dy * dy
        }
    }

    /**
     * Samples the cache into a grid, leaving NaN wherever nothing is held.
     *
     * Returns null when the box is degenerate or would need an unreasonable
     * number of tiles, which means the caller asked at the wrong zoom.
     */
    fun assemble(
        cache: DemTileCache,
        north: Double,
        south: Double,
        west: Double,
        east: Double,
        zoom: Int,
        maxSamples: Int = MAX_SAMPLES
    ): ElevationGrid? {
        if (north <= south || east <= west) return null
        val tiles = tilesFor(north, south, west, east, zoom)
        if (tiles.isEmpty() || tiles.size > MAX_TILES) return null

        // Square-ish samples: match the grid's aspect to the ground's.
        val northY = ElevationGrid.mercatorY(north)
        val southY = ElevationGrid.mercatorY(south)
        val spanX = east - west
        val spanY = Math.toDegrees(northY - southY)
        val columns: Int
        val rows: Int
        if (spanX >= spanY) {
            columns = maxSamples
            rows = (maxSamples * (spanY / spanX)).toInt().coerceIn(2, maxSamples)
        } else {
            rows = maxSamples
            columns = (maxSamples * (spanX / spanY)).toInt().coerceIn(2, maxSamples)
        }

        val scale = 1 shl zoom
        val worldPixels = DemTileCache.TILE_SIZE.toDouble() * scale
        val values = DoubleArray(columns * rows)
        // One tile's samples at a time, so each PNG is decoded once.
        val held = HashMap<Long, FloatArray?>()
        fun tile(tx: Int, ty: Int): FloatArray? {
            val key = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
            return held.getOrPut(key) { cache.elevations(zoom, tx, ty) }
        }

        for (row in 0 until rows) {
            val y = northY + (southY - northY) * (row / (rows - 1).toDouble())
            val normalisedY = 0.5 - y / (2.0 * PI)
            val globalY = (normalisedY * worldPixels)
                .coerceIn(0.0, worldPixels - 1e-6)
            val tileYIndex = floor(globalY / DemTileCache.TILE_SIZE).toInt()
            val pixelY = (globalY - tileYIndex * DemTileCache.TILE_SIZE).toInt()
                .coerceIn(0, DemTileCache.TILE_SIZE - 1)

            for (column in 0 until columns) {
                val longitude = west + (east - west) * (column / (columns - 1).toDouble())
                val normalisedX = (longitude + 180.0) / 360.0
                val globalX = (normalisedX * worldPixels)
                    .coerceIn(0.0, worldPixels - 1e-6)
                val tileXIndex = floor(globalX / DemTileCache.TILE_SIZE).toInt()
                val pixelX = (globalX - tileXIndex * DemTileCache.TILE_SIZE).toInt()
                    .coerceIn(0, DemTileCache.TILE_SIZE - 1)

                val samples = tile(tileXIndex, tileYIndex)
                values[row * columns + column] = if (samples == null) {
                    Double.NaN
                } else {
                    val value = samples[pixelY * DemTileCache.TILE_SIZE + pixelX].toDouble()
                    // The encoding's floor stands for ocean and for nothing at
                    // all; either way it is not ground worth contouring.
                    if (value < -1000.0) Double.NaN else value
                }
            }
        }

        return ElevationGrid(columns, rows, values, north, south, west, east)
    }

    fun tileX(longitude: Double, zoom: Int): Int {
        val scale = 1 shl zoom
        return ((longitude + 180.0) / 360.0 * scale).toInt().coerceIn(0, scale - 1)
    }

    fun tileY(latitude: Double, zoom: Int): Int {
        val scale = 1 shl zoom
        val y = ElevationGrid.mercatorY(latitude)
        val normalised = 0.5 - y / (2.0 * PI)
        return (normalised * scale).toInt().coerceIn(0, scale - 1)
    }
}
