package com.rhecyee.firelinemap.terrain

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.rhecyee.firelinemap.map.ElevationTiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round

/** A patch of ground to trace contours over, and the interval to trace at. */
data class ContourWindow(
    val north: Double,
    val south: Double,
    val west: Double,
    val east: Double,
    val intervalFeet: Int,
)

/**
 * Turns what is on screen into a stable request for contours.
 *
 * Tracing takes tens of milliseconds, which is fine in the background and
 * fatal on every frame of a pan. If the request tracked the viewport exactly,
 * each finger movement would cancel the trace in flight and start another, and
 * the lines would never appear at all while the map was moving.
 *
 * So the window is deliberately coarse: grown past the edges of the screen,
 * its size rounded to fixed steps, and its centre snapped to a grid of that
 * size. Panning and pinching then leave the request unchanged until the view
 * has genuinely moved somewhere else, and the previous lines stay on the map
 * in the meantime.
 */
object ContourWindows {

    /**
     * How far past the screen to trace, as a fraction of what is visible.
     *
     * Enough that a small pan reveals contours already drawn rather than a
     * blank margin waiting on a trace.
     */
    const val MARGIN = 0.35

    /** Rounding steps a span may take, per doubling. Two is every root two. */
    private const val STEPS_PER_DOUBLING = 2.0

    /** How finely the centre is snapped, as a fraction of the span. */
    private const val CENTRE_STEPS = 4.0

    fun of(
        north: Double,
        south: Double,
        west: Double,
        east: Double,
        intervalFeet: Int,
    ): ContourWindow? {
        if (!north.isFinite() || !south.isFinite() ||
            !west.isFinite() || !east.isFinite()
        ) return null
        if (north <= south || east <= west) return null
        if (intervalFeet <= 0) return null

        val latitudeSpan = quantiseSpan((north - south) * (1 + 2 * MARGIN))
        val longitudeSpan = quantiseSpan((east - west) * (1 + 2 * MARGIN))
        if (latitudeSpan <= 0.0 || longitudeSpan <= 0.0) return null

        val latitude = snap((north + south) / 2, latitudeSpan / CENTRE_STEPS)
        val longitude = snap((west + east) / 2, longitudeSpan / CENTRE_STEPS)

        return ContourWindow(
            north = (latitude + latitudeSpan / 2).coerceAtMost(MAX_LATITUDE),
            south = (latitude - latitudeSpan / 2).coerceAtLeast(-MAX_LATITUDE),
            west = longitude - longitudeSpan / 2,
            east = longitude + longitudeSpan / 2,
            intervalFeet = intervalFeet
        )
    }

    /** Rounds a span up to the next fixed step, so a pinch does not jitter it. */
    private fun quantiseSpan(span: Double): Double {
        if (span <= 0.0 || !span.isFinite()) return 0.0
        val steps = ceil(ln(span) / ln(2.0) * STEPS_PER_DOUBLING)
        return 2.0.pow(steps / STEPS_PER_DOUBLING)
    }

    private fun snap(value: Double, step: Double): Double =
        if (step <= 0.0) value else round(value / step) * step

    private const val MAX_LATITUDE = 85.0
}

/**
 * Contours for what is on screen, traced off the main thread.
 *
 * Returns the previous set while a new one is being worked out, so the lines
 * do not blink out every time the map moves.
 */
@Composable
fun rememberContours(
    tiles: ElevationTiles?,
    enabled: Boolean,
    window: ContourWindow?,
): List<GeoContour> {
    val version = tiles?.version?.intValue ?: 0
    val state: State<List<GeoContour>> = produceState(
        initialValue = emptyList(),
        tiles, enabled, window, version
    ) {
        if (tiles == null || !enabled || window == null) {
            value = emptyList()
            return@produceState
        }
        // Tiles land one at a time; without this every arrival during the
        // initial fetch would start and abandon a trace.
        delay(SETTLE_MILLIS)
        val traced = withContext(Dispatchers.Default) {
            val grid = tiles.grid(
                north = window.north,
                south = window.south,
                west = window.west,
                east = window.east,
                samples = SAMPLES
            ) ?: return@withContext null
            ContourField.build(grid, window.intervalFeet)
        }
        if (traced != null) value = traced
    }
    val contours by state
    return contours
}

/**
 * Samples across the traced window.
 *
 * Two hundred and fifty six is a compromise, and worth naming as one: it is
 * finer than any screen needs at the zooms contours are read at, and coarse
 * enough that a trace finishes inside a frame or two rather than a second.
 */
private const val SAMPLES = 256

private const val SETTLE_MILLIS = 250L
