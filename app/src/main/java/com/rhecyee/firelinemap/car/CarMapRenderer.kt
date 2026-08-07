package com.rhecyee.firelinemap.car

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.rhecyee.firelinemap.data.MarkerEntity
import com.rhecyee.firelinemap.map.BasemapTileCache
import com.rhecyee.firelinemap.measure.DistanceUnit
import com.rhecyee.firelinemap.resources.ResourceSymbol

/** What the car display is being asked to show. */
data class CarMapState(
    val viewport: CarViewport,
    val position: CarPosition? = null,
    /** The track being laid down now. */
    val liveTrack: List<Pair<Double, Double>> = emptyList(),
    /** Tracks already recorded on this incident, with their colours. */
    val savedTracks: List<CarTrack> = emptyList(),
    val markers: List<MarkerEntity> = emptyList(),
    val recording: Boolean = false,
    val paused: Boolean = false,
    val incidentName: String? = null,
    /** Distance and moving time so far, already worded by the shared readout. */
    val distance: String? = null,
    val elapsed: String? = null,
    val following: Boolean = true
)

/** A recorded track, ready to draw. */
data class CarTrack(
    val points: List<Pair<Double, Double>>,
    val colourArgb: Int
)

/**
 * Draws the map on the head unit.
 *
 * Everything here is scaled for a screen looked at for under a second from
 * arm's length in a moving vehicle: thick lines, few labels, high contrast. The
 * phone's map is for reading; this one is for glancing.
 *
 * Nothing is interactive beyond the action strip. A car display that rewards
 * study is a car display that gets studied, and the person doing it is driving.
 */
class CarMapRenderer(private val tiles: BasemapTileCache) {

    private val tilePaint = Paint().apply { isFilterBitmap = true }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(canvas: Canvas, state: CarMapState) {
        val view = state.viewport
        canvas.drawColor(BACKGROUND)

        drawTerrain(canvas, view)
        state.savedTracks.forEach { drawTrack(canvas, view, it.points, it.colourArgb, SAVED_WIDTH) }
        drawTrack(canvas, view, state.liveTrack, LIVE, LIVE_WIDTH)
        drawMarkers(canvas, view, state.markers)
        state.position?.let { drawPosition(canvas, view, it) }
        drawReadout(canvas, state)
        drawScaleBar(canvas, view)
    }

    // ---------------------------------------------------------------- terrain

    private fun drawTerrain(canvas: Canvas, view: CarViewport) {
        val wanted = view.tiles()
        if (wanted.isEmpty()) return
        var drawn = 0
        for (tile in wanted) {
            val sample = tiles.sample(tile.zoom, tile.x, tile.y) ?: continue
            val source = Rect(
                sample.sourceLeft,
                sample.sourceTop,
                sample.sourceLeft + sample.sourceSize,
                sample.sourceTop + sample.sourceSize
            )
            val target = RectF(
                tile.left, tile.top, tile.left + tile.size, tile.top + tile.size
            )
            canvas.drawBitmap(sample.bitmap, source, target, tilePaint)
            drawn++
        }
        if (drawn == 0) {
            // Say it rather than showing an empty rectangle, which looks
            // exactly like the app having died.
            textPaint.color = DIM
            textPaint.textSize = 26f
            canvas.drawText(
                "No terrain here yet — it loads with a signal",
                view.widthPixels / 2f,
                view.heightPixels / 2f,
                textPaint
            )
        }
    }

    // ----------------------------------------------------------------- track

    private fun drawTrack(
        canvas: Canvas,
        view: CarViewport,
        points: List<Pair<Double, Double>>,
        colour: Int,
        width: Float
    ) {
        if (points.size < 2) return
        val path = Path()
        var started = false
        for ((latitude, longitude) in points) {
            val at = view.toScreen(latitude, longitude)
            if (!started) { path.moveTo(at.x, at.y); started = true } else path.lineTo(at.x, at.y)
        }

        // Drawn twice: a dark casing under the colour, so the line reads
        // against snow, bare rock and dark timber without changing colour.
        trackPaint.color = CASING
        trackPaint.strokeWidth = width + 6f
        canvas.drawPath(path, trackPaint)
        trackPaint.color = colour
        trackPaint.strokeWidth = width
        canvas.drawPath(path, trackPaint)
    }

    // --------------------------------------------------------------- markers

    private fun drawMarkers(canvas: Canvas, view: CarViewport, markers: List<MarkerEntity>) {
        textPaint.textSize = 22f
        for (marker in markers) {
            if (!view.isVisible(marker.latitude, marker.longitude)) continue
            val at = view.toScreen(marker.latitude, marker.longitude)
            val symbol = ResourceSymbol.byId(marker.symbol)

            fillPaint.color = CASING
            canvas.drawRoundRect(
                RectF(at.x - 34f, at.y - 21f, at.x + 34f, at.y + 21f), 9f, 9f, fillPaint
            )
            fillPaint.color = symbol.colorArgb
            canvas.drawRoundRect(
                RectF(at.x - 31f, at.y - 18f, at.x + 31f, at.y + 18f), 8f, 8f, fillPaint
            )
            textPaint.color = Color.WHITE
            canvas.drawText(symbol.glyph, at.x, at.y + 8f, textPaint)
        }
    }

    // -------------------------------------------------------------- position

    private fun drawPosition(canvas: Canvas, view: CarViewport, at: CarPosition) {
        val point = view.toScreen(at.latitude, at.longitude)
        fillPaint.color = Color.WHITE
        canvas.drawCircle(point.x, point.y, 20f, fillPaint)
        fillPaint.color = HERE
        canvas.drawCircle(point.x, point.y, 15f, fillPaint)
        fillPaint.color = Color.WHITE
        canvas.drawCircle(point.x, point.y, 5f, fillPaint)
    }

    // --------------------------------------------------------------- readout

    /**
     * The one strip of text on the display.
     *
     * Incident, whether it is recording, and how far -- which is what a driver
     * would otherwise pick the phone up to check. Everything else waits.
     */
    private fun drawReadout(canvas: Canvas, state: CarMapState) {
        val view = state.viewport
        val height = 62f
        panelPaint.color = PANEL
        canvas.drawRect(0f, 0f, view.widthPixels.toFloat(), height, panelPaint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 30f
        textPaint.color = Color.WHITE
        canvas.drawText(state.incidentName ?: "Fireline Map", 20f, 41f, textPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = when {
            state.paused -> PAUSED
            state.recording -> RECORDING
            else -> DIM
        }
        val label = when {
            state.paused -> "PAUSED"
            state.recording -> "RECORDING"
            else -> "NOT RECORDING"
        }
        val figures = listOfNotNull(
            label,
            state.distance.takeIf { state.recording || state.paused },
            state.elapsed.takeIf { state.recording || state.paused }
        ).joinToString("   ")
        canvas.drawText(figures, view.widthPixels - 20f, 41f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    /** How far across the screen is, because a map without a scale is a picture. */
    private fun drawScaleBar(canvas: Canvas, view: CarViewport) {
        val metresPerPixel = view.metresPerPixel()
        if (!metresPerPixel.isFinite() || metresPerPixel <= 0) return

        // A round number of feet or miles that fits in about a fifth of the
        // screen, so the bar is a length somebody can actually reckon with.
        val target = view.widthPixels / 5.0 * metresPerPixel
        val metres = ROUND_METRES.firstOrNull { it >= target } ?: ROUND_METRES.last()
        val pixels = (metres / metresPerPixel).toFloat()
        val baseline = view.heightPixels - 26f
        val left = 20f

        trackPaint.color = CASING
        trackPaint.strokeWidth = 8f
        canvas.drawLine(left, baseline, left + pixels, baseline, trackPaint)
        trackPaint.color = Color.WHITE
        trackPaint.strokeWidth = 4f
        canvas.drawLine(left, baseline, left + pixels, baseline, trackPaint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 22f
        textPaint.color = Color.WHITE
        canvas.drawText(DistanceUnit.readable(metres), left, baseline - 12f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    private companion object {
        const val BACKGROUND = 0xFF2B3A44.toInt()
        const val PANEL = 0xE60B171F.toInt()
        const val CASING = 0xB3000000.toInt()
        const val LIVE = 0xFFEF5350.toInt()
        const val HERE = 0xFF2196F3.toInt()
        const val RECORDING = 0xFFEF5350.toInt()
        const val PAUSED = 0xFFFFA000.toInt()
        const val DIM = 0xFF90A4AE.toInt()

        const val LIVE_WIDTH = 10f
        const val SAVED_WIDTH = 7f

        /** Round distances a person reckons in, in metres. */
        val ROUND_METRES = listOf(
            30.48, 76.2, 152.4, 304.8, 402.336, 804.672,
            1609.344, 3218.688, 8046.72, 16093.44, 40233.6
        )
    }
}
