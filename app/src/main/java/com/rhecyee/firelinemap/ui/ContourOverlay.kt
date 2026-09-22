package com.rhecyee.firelinemap.ui

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.rhecyee.firelinemap.geopdf.MapFrame
import com.rhecyee.firelinemap.terrain.GeoContour
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Where an elevation figure goes, in the same normalised space as the paths. */
data class ContourLabel(
    val x: Float,
    val y: Float,
    /** Degrees, so the figure lies along its line rather than across it. */
    val angle: Float,
    val text: String,
)

/**
 * Contours flattened onto the open sheet, ready to draw.
 *
 * Held in normalised page space -- nought to one across the sheet and down it
 * -- rather than in latitude and longitude, because turning a position on the
 * ground into a position on a georeferenced page is an inverse projection, and
 * there are tens of thousands of vertices in a screenful of contours. Done
 * every frame that is a stall; done once when the trace lands, panning and
 * zooming are left as an affine transform the graphics layer does for free.
 */
class ContourOverlay(
    val regular: Path,
    val index: Path,
    val labels: List<ContourLabel>,
) {
    val isEmpty: Boolean get() = regular.isEmpty && index.isEmpty
}

/**
 * Projects traced contours onto a sheet.
 *
 * Vertices that fall outside the page's projection are dropped and the stroke
 * is broken there, rather than joined across the gap: a sheet's georeference
 * runs out somewhere, and a line drawn straight across that gap would be
 * pure invention.
 */
fun buildContourOverlay(
    contours: List<GeoContour>,
    frame: MapFrame,
    pageWidthPoints: Int,
    pageHeightPoints: Int,
): ContourOverlay? {
    if (contours.isEmpty() || pageWidthPoints <= 0 || pageHeightPoints <= 0) return null

    val regular = Path()
    val index = Path()
    val labels = ArrayList<ContourLabel>()

    for (contour in contours) {
        val target = if (contour.index) index else regular
        var started = false
        var previousX = 0f
        var previousY = 0f
        var firstX = 0f
        var firstY = 0f
        var drawn = 0
        var length = 0f

        for (vertex in contour.points) {
            val page = frame.geoToPage(vertex.latitude, vertex.longitude)
            if (page == null) {
                started = false
                continue
            }
            val x = (page.first / pageWidthPoints).toFloat()
            val y = (1.0 - page.second / pageHeightPoints).toFloat()
            if (!x.isFinite() || !y.isFinite()) {
                started = false
                continue
            }
            if (!started) {
                target.moveTo(x, y)
                started = true
                firstX = x
                firstY = y
            } else {
                target.lineTo(x, y)
                length += hypot(x - previousX, y - previousY)
            }
            previousX = x
            previousY = y
            drawn++
        }
        if (contour.closed && started && drawn == contour.points.size) {
            target.lineTo(firstX, firstY)
            length += hypot(firstX - previousX, firstY - previousY)
        }

        if (contour.index && drawn >= MIN_LABEL_VERTICES && length >= MIN_LABEL_LENGTH) {
            labelFor(contour, frame, pageWidthPoints, pageHeightPoints)?.let(labels::add)
        }
    }

    val overlay = ContourOverlay(regular, index, labels)
    return if (overlay.isEmpty) null else overlay
}

/** An elevation figure laid along the middle of a line. */
private fun labelFor(
    contour: GeoContour,
    frame: MapFrame,
    pageWidthPoints: Int,
    pageHeightPoints: Int,
): ContourLabel? {
    val middle = contour.points.size / 2
    val before = contour.points.getOrNull(middle - 1) ?: return null
    val at = contour.points.getOrNull(middle) ?: return null
    val after = contour.points.getOrNull(middle + 1) ?: return null

    fun project(latitude: Double, longitude: Double): Pair<Float, Float>? {
        val page = frame.geoToPage(latitude, longitude) ?: return null
        val x = (page.first / pageWidthPoints).toFloat()
        val y = (1.0 - page.second / pageHeightPoints).toFloat()
        return if (x.isFinite() && y.isFinite()) x to y else null
    }

    val start = project(before.latitude, before.longitude) ?: return null
    val centre = project(at.latitude, at.longitude) ?: return null
    val end = project(after.latitude, after.longitude) ?: return null

    var angle = Math.toDegrees(
        atan2((end.second - start.second).toDouble(), (end.first - start.first).toDouble())
    ).toFloat()
    // Figures are read left to right; upside down is not a style choice.
    if (angle > 90f) angle -= 180f
    if (angle < -90f) angle += 180f

    return ContourLabel(
        x = centre.first,
        y = centre.second,
        angle = angle,
        text = "%,d".format(contour.elevationFeet.roundToInt())
    )
}

/**
 * Draws an overlay over whatever the sheet is currently showing.
 *
 * Two strokes rather than one: index contours -- every fifth line -- carry the
 * elevation and are drawn heavier, which is what lets a reader count off the
 * others between them instead of tracing each one back to a figure.
 *
 * A light halo goes under both. Over a topo sheet the contours are being drawn
 * on top of a picture that already has brown lines in it, and over an aerial
 * they would otherwise disappear into the ground entirely.
 */
fun DrawScope.drawContours(
    overlay: ContourOverlay,
    originX: Float,
    originY: Float,
    drawWidth: Float,
    drawHeight: Float,
    labelsVisible: Boolean,
) {
    if (drawWidth <= 0f || drawHeight <= 0f) return
    val canvas = drawContext.canvas.nativeCanvas

    val matrix = Matrix()
    matrix.setScale(drawWidth, drawHeight)
    matrix.postTranslate(originX, originY)

    val scratch = Path()

    fun stroke(source: Path, width: Float) {
        if (source.isEmpty) return
        source.transform(matrix, scratch)
        HALO_PAINT.strokeWidth = width + HALO_GROWTH
        canvas.drawPath(scratch, HALO_PAINT)
        LINE_PAINT.strokeWidth = width
        canvas.drawPath(scratch, LINE_PAINT)
    }

    stroke(overlay.regular, REGULAR_WIDTH)
    stroke(overlay.index, INDEX_WIDTH)

    if (!labelsVisible) return
    for (label in overlay.labels) {
        val x = originX + label.x * drawWidth
        val y = originY + label.y * drawHeight
        if (x < -LABEL_MARGIN || y < -LABEL_MARGIN ||
            x > size.width + LABEL_MARGIN || y > size.height + LABEL_MARGIN
        ) continue
        canvas.save()
        canvas.rotate(label.angle, x, y)
        // The line is broken under the figure the way a printed sheet does it,
        // rather than the figure sitting on top of its own contour.
        canvas.drawText(label.text, x, y + LABEL_LIFT, LABEL_HALO_PAINT)
        canvas.drawText(label.text, x, y + LABEL_LIFT, LABEL_PAINT)
        canvas.restore()
    }
}

/**
 * Whether elevation figures are worth drawing.
 *
 * Judged on how much ground is on screen rather than on how far the sheet has
 * been zoomed, because those are not the same thing: a quarter-scale sheet and
 * the plain terrain view at the same pinch show wildly different amounts of
 * country, and a rule written against the pinch gets one of them wrong.
 */
fun contourLabelsVisible(visibleMeters: Double): Boolean =
    visibleMeters > 0 && visibleMeters <= LABEL_MAX_SPAN_METERS

private const val MIN_LABEL_VERTICES = 12

/** As a fraction of the page, so a label never lands on a two-cell stub. */
private const val MIN_LABEL_LENGTH = 0.04f

private const val REGULAR_WIDTH = 1.6f
private const val INDEX_WIDTH = 3.2f
private const val HALO_GROWTH = 2.4f
private const val LABEL_MARGIN = 80f
private const val LABEL_LIFT = 4f
/** About five miles across, which is where a 40 foot band stops crowding. */
private const val LABEL_MAX_SPAN_METERS = 8_000.0

/** A burnt sienna, which is the colour a quad sheet prints contours in. */
private val LINE_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = android.graphics.Color.argb(215, 150, 88, 44)
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}

private val HALO_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = android.graphics.Color.argb(120, 255, 250, 235)
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}

private val LABEL_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.argb(255, 120, 66, 30)
    textAlign = Paint.Align.CENTER
    textSize = 26f
    isFakeBoldText = true
}

private val LABEL_HALO_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 5f
    color = android.graphics.Color.argb(220, 255, 250, 235)
    textAlign = Paint.Align.CENTER
    textSize = 26f
    isFakeBoldText = true
}
