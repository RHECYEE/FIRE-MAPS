package com.rhecyee.firelinemap.terrain

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.rhecyee.firelinemap.map.ElevationTiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A shaded raster ready to draw, and the ground it covers. */
class ReliefRaster(
    val bitmap: Bitmap,
    val north: Double,
    val south: Double,
    val west: Double,
    val east: Double
)

/**
 * The window to shade, quantised the same way a contour trace is.
 *
 * Shading has no interval of its own, so it asks for one and throws it away.
 * What it wants from [ContourWindows] is the quantising: a window that stops
 * moving while the map is being panned, so the raster in hand stays usable
 * instead of being cancelled and restarted on every finger movement. That
 * logic is worth having once rather than twice, and a fixed interval here
 * keeps the shading window from churning every time somebody changes the
 * contour interval.
 */
fun shadingWindow(
    north: Double,
    south: Double,
    west: Double,
    east: Double
): ContourWindow? = ContourWindows.of(north, south, west, east, intervalFeet = 1)

/**
 * Shading for what is on screen, computed off the main thread.
 *
 * Keeps the previous raster up while a new one is being worked out. Terrain
 * shading blinking off and back on during a pan is worse than shading that is
 * a moment behind the view: the ground it describes has not moved either way,
 * and the stale raster is still drawn against its own coordinates, so it is
 * in the right place throughout.
 */
@Composable
fun rememberShadedRelief(
    tiles: ElevationTiles?,
    options: ShadingOptions?,
    window: ContourWindow?
): ReliefRaster? {
    val version = tiles?.version?.intValue ?: 0
    val state = produceState<ReliefRaster?>(
        initialValue = null,
        tiles, options, window, version
    ) {
        val wanted = options
        if (tiles == null || wanted == null || window == null ||
            (!wanted.hillshade && !wanted.slopeClasses)
        ) {
            value = null
            return@produceState
        }
        // Elevation lands a tile at a time, and without this every arrival
        // during the first fetch would start and abandon a pass.
        delay(SETTLE_MILLIS)
        val raster = withContext(Dispatchers.Default) {
            val grid = tiles.grid(
                north = window.north,
                south = window.south,
                west = window.west,
                east = window.east,
                samples = TerrainShading.SAMPLES
            ) ?: return@withContext null
            val shaded = TerrainShading.render(grid, wanted) ?: return@withContext null
            val bitmap = Bitmap.createBitmap(
                shaded.width, shaded.height, Bitmap.Config.ARGB_8888
            )
            bitmap.setPixels(
                shaded.pixels, 0, shaded.width, 0, 0, shaded.width, shaded.height
            )
            ReliefRaster(bitmap, shaded.north, shaded.south, shaded.west, shaded.east)
        }
        if (raster != null) value = raster
    }
    val relief by state
    return relief
}

private const val SETTLE_MILLIS = 250L
