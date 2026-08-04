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
class BasemapTileCache(context: Context) {

    private val root = File(context.filesDir, "basemap").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Decoded tiles, keyed z/x/y. Backed by snapshot state so arrivals redraw. */
    val tiles: SnapshotStateMap<String, Bitmap> = mutableStateMapOf()

    private val inFlight = mutableSetOf<String>()
    private val limiter = Semaphore(4)

    /** Returns the tile if it is ready, otherwise starts fetching it. */
    fun tile(zoom: Int, x: Int, y: Int): Bitmap? {
        val key = key(zoom, x, y)
        tiles[key]?.let { return it }

        synchronized(inFlight) {
            if (!inFlight.add(key)) return null
        }

        scope.launch {
            val file = File(root, "$zoom/$x/$y.png")
            val bitmap = decode(file) ?: run {
                limiter.withPermit { download(zoom, x, y, file) }
                decode(file)
            }
            if (bitmap != null) tiles[key] = bitmap
            synchronized(inFlight) { inFlight.remove(key) }
        }
        return null
    }

    private fun decode(file: File): Bitmap? {
        if (!file.exists() || file.length() == 0L) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    private fun download(zoom: Int, x: Int, y: Int, target: File) {
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

    private fun key(zoom: Int, x: Int, y: Int) = "$zoom/$x/$y"

    fun cachedBytes(): Long =
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clear() {
        tiles.clear()
        root.deleteRecursively()
        root.mkdirs()
    }

    companion object {
        const val ENDPOINT =
            "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile"

        const val ATTRIBUTION = "USGS The National Map"

        const val TILE_SIZE = 256

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
