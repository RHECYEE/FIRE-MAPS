package com.rhecyee.firelinemap.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.rhecyee.firelinemap.map.BasemapTileCache
import com.rhecyee.firelinemap.data.MarkerEntity
import com.rhecyee.firelinemap.resources.ResourceSymbol
import com.rhecyee.firelinemap.measure.MeasureMode
import com.rhecyee.firelinemap.measure.MeasurePoint
import com.rhecyee.firelinemap.geopdf.DropPoint
import com.rhecyee.firelinemap.geopdf.ImportedMap
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Draws an imported map sheet with a position on it.
 *
 * The sheet is clipped to the canvas and the pan is bounded, so the page
 * cannot be dragged out from under the operator or spill over the controls.
 */
@Composable
fun MapCanvas(
    map: ImportedMap?,
    bitmap: Bitmap?,
    pageWidthPoints: Int,
    pageHeightPoints: Int,
    latitude: Double?,
    longitude: Double?,
    positionIsSimulated: Boolean = false,
    dropPoints: List<DropPoint> = emptyList(),
    basemap: BasemapTileCache? = null,
    measurePoints: List<MeasurePoint> = emptyList(),
    measureMode: MeasureMode = MeasureMode.DISTANCE,
    markers: List<MarkerEntity> = emptyList(),
    trackPoints: List<Pair<Double, Double>> = emptyList(),
    savedTracks: List<SavedTrack> = emptyList(),
    searchRegion: SearchRegion? = null,
    centreOn: Pair<Double, Double>? = null,
    onCentred: () -> Unit = {},
    onInteraction: () -> Unit = {},
    onTrackTap: ((SavedTrack) -> Unit)? = null,
    onMarkerTap: ((MarkerEntity) -> Unit)? = null,
    onMarkerMoved: ((MarkerEntity, Double, Double) -> Unit)? = null,
    onMapTap: ((latitude: Double, longitude: Double) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var scale by remember(map?.id) { mutableFloatStateOf(1f) }
    var offset by remember(map?.id) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var draggingMarkerId by remember { mutableStateOf<String?>(null) }
    var dragPoint by remember { mutableStateOf(Offset.Zero) }

    // Held in updated state so the gesture handler below can key on the map
    // alone. Putting these in the pointerInput keys restarts the gesture
    // coroutine on every recomposition, and with a GPS fix arriving every few
    // seconds that means taps are being dropped more often than not.
    val currentMarkers by rememberUpdatedState(markers)
    val currentOnMapTap by rememberUpdatedState(onMapTap)
    val currentOnMarkerTap by rememberUpdatedState(onMarkerTap)
    val currentOnMarkerMoved by rememberUpdatedState(onMarkerMoved)
    val currentSavedTracks by rememberUpdatedState(savedTracks)
    val currentOnTrackTap by rememberUpdatedState(onTrackTap)
    val currentOnInteraction by rememberUpdatedState(onInteraction)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF2F3A2D), RoundedCornerShape(14.dp))
            // Without this the page overflows the card and covers the controls.
            .clipToBounds()
            .onSizeChanged { viewport = it },
        contentAlignment = Alignment.Center
    ) {
        if (map == null || bitmap == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("NO MAP IMPORTED", color = Color.White, fontWeight = FontWeight.Black)
                Text(
                    "Import a GeoPDF to place your position on it",
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelMedium
                )
            }
            return@Box
        }

        val image = remember(bitmap) { bitmap.asImageBitmap() }

        // An external request to bring a position into view, used by the
        // search so a found region can be looked at without hunting for it.
        androidx.compose.runtime.LaunchedEffect(centreOn, viewport) {
            val target = centreOn ?: return@LaunchedEffect
            val frame = map.frame ?: return@LaunchedEffect
            if (viewport.width == 0 || pageWidthPoints <= 0 || pageHeightPoints <= 0) {
                return@LaunchedEffect
            }
            val page = frame.geoToPage(target.first, target.second)
            if (page != null) {
                val fitNow = minOf(
                    viewport.width.toFloat() / image.width,
                    viewport.height.toFloat() / image.height
                )
                val next = maxOf(scale, 6f)
                scale = next
                val drawWidth = image.width * fitNow * next
                val drawHeight = image.height * fitNow * next
                val fx = (page.first / pageWidthPoints).toFloat()
                val fy = 1f - (page.second / pageHeightPoints).toFloat()
                offset = Offset(drawWidth * (0.5f - fx), drawHeight * (0.5f - fy))
            }
            onCentred()
        }

        // Evaluated on every call rather than captured.
        //
        // The gesture handler below is a long-lived coroutine that only
        // restarts when the map changes, so anything it closes over is frozen
        // at the first composition -- when the viewport is still zero and this
        // would be 1. Drawing re-runs every recomposition and so looked
        // correct, while every tap was converted through the wrong scale: taps
        // landed in the wrong place and pins were never found under a finger.
        fun fitScale(): Float =
            if (viewport.width > 0 && viewport.height > 0) {
                minOf(
                    viewport.width.toFloat() / image.width,
                    viewport.height.toFloat() / image.height
                )
            } else {
                1f
            }

        // The sheet may be panned until its edge reaches the view, plus an
        // allowance for travelling off it. Being off the sheet is normal --
        // ICP and the drive in usually sit outside the neatline -- so the
        // operator has to be able to pan out there and see where they are.
        fun clamp(candidate: Offset, atScale: Float): Offset {
            val drawWidth = image.width * fitScale() * atScale
            val drawHeight = image.height * fitScale() * atScale
            val slackX = viewport.width * OFF_SHEET_PAN_ALLOWANCE
            val slackY = viewport.height * OFF_SHEET_PAN_ALLOWANCE
            val maxX = ((drawWidth - viewport.width) / 2f).coerceAtLeast(0f) + slackX
            val maxY = ((drawHeight - viewport.height) / 2f).coerceAtLeast(0f) + slackY
            return Offset(
                candidate.x.coerceIn(-maxX, maxX),
                candidate.y.coerceIn(-maxY, maxY)
            )
        }

        fun screenToPagePoints(point: Offset): Pair<Double, Double>? {
            if (pageWidthPoints <= 0 || pageHeightPoints <= 0) return null
            val drawWidth = image.width * fitScale() * scale
            val drawHeight = image.height * fitScale() * scale
            val originX = (viewport.width - drawWidth) / 2f + offset.x
            val originY = (viewport.height - drawHeight) / 2f + offset.y
            val fx = (point.x - originX) / drawWidth
            val fy = (point.y - originY) / drawHeight
            // Not clamped to the sheet. Taps land on the terrain fill beyond
            // the neatline all the time -- the drive in, ICP, a spot across the
            // road -- and refusing them there was making the tools look dead
            // whenever the sheet did not fill the view.
            // Bitmap y runs downward; PDF page space runs upward.
            return fx * pageWidthPoints.toDouble() to (1f - fy) * pageHeightPoints.toDouble()
        }

        fun markerScreenPosition(marker: MarkerEntity): Offset? {
            val frame = map.frame ?: return null
            if (pageWidthPoints <= 0 || pageHeightPoints <= 0) return null
            val page = frame.geoToPage(marker.latitude, marker.longitude) ?: return null
            val drawWidth = image.width * fitScale() * scale
            val drawHeight = image.height * fitScale() * scale
            val originX = (viewport.width - drawWidth) / 2f + offset.x
            val originY = (viewport.height - drawHeight) / 2f + offset.y
            return Offset(
                originX + (page.first / pageWidthPoints).toFloat() * drawWidth,
                originY + (1f - (page.second / pageHeightPoints).toFloat()) * drawHeight
            )
        }

        fun markerAt(point: Offset): MarkerEntity? = currentMarkers.lastOrNull { marker ->
            val position = markerScreenPosition(marker) ?: return@lastOrNull false
            // Generous target: this gets used with gloves on.
            (point - position).getDistance() <= 48f
        }

        /** The saved track a tap lands on, if any. */
        fun trackAt(point: Offset): SavedTrack? {
            val frame = map.frame ?: return null
            if (pageWidthPoints <= 0 || pageHeightPoints <= 0) return null
            val drawWidth = image.width * fitScale() * scale
            val drawHeight = image.height * fitScale() * scale
            val originX = (viewport.width - drawWidth) / 2f + offset.x
            val originY = (viewport.height - drawHeight) / 2f + offset.y

            for (saved in currentSavedTracks) {
                var previous: Offset? = null
                for ((latitude, longitude) in saved.points) {
                    val page = frame.geoToPage(latitude, longitude) ?: continue
                    val current = Offset(
                        originX + (page.first / pageWidthPoints).toFloat() * drawWidth,
                        originY + (1f - (page.second / pageHeightPoints).toFloat()) * drawHeight
                    )
                    val start = previous
                    if (start != null && distanceToSegment(point, start, current) <= 44f) {
                        return saved
                    }
                    previous = current
                }
            }
            return null
        }

        fun screenToGeoPoint(point: Offset): Pair<Double, Double>? {
            val page = screenToPagePoints(point) ?: return null
            val geo = map.frame?.pageToGeo(page.first, page.second) ?: return null
            return geo.latitude to geo.longitude
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // One handler for everything. Three competing pointerInput
                // blocks meant drag and transform each claimed the pointer
                // stream and taps frequently never arrived at all.
                .pointerInput(map.id, bitmap) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Any touch on the map counts as being in use.
                        currentOnInteraction()
                        val grabbed = if (currentOnMarkerMoved != null) {
                            markerAt(down.position)
                        } else {
                            null
                        }

                        if (grabbed != null) {
                            draggingMarkerId = grabbed.id
                            dragPoint = down.position
                            var moved = false
                            drag(down.id) { change ->
                                dragPoint += change.positionChange()
                                moved = true
                                change.consume()
                            }
                            draggingMarkerId = null
                            if (moved) {
                                screenToGeoPoint(dragPoint)?.let { (lat, lon) ->
                                    currentOnMarkerMoved?.invoke(grabbed, lat, lon)
                                }
                            } else {
                                // A press that never moved is a tap on the pin.
                                currentOnMarkerTap?.invoke(grabbed)
                            }
                            return@awaitEachGesture
                        }

                        val trackHit = if (currentOnTrackTap != null) {
                            trackAt(down.position)
                        } else {
                            null
                        }

                        var travelled = 0f
                        var pointers = 1
                        do {
                            val event = awaitPointerEvent()
                            pointers = maxOf(pointers, event.changes.count { it.pressed })
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            travelled += panChange.getDistance() + abs(1f - zoomChange) * 200f

                            if (travelled > viewConfiguration.touchSlop) {
                                val next = (scale * zoomChange).coerceIn(1f, 12f)
                                scale = next
                                offset = clamp(offset + panChange, next)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })

                        if (travelled <= viewConfiguration.touchSlop && pointers == 1) {
                            if (trackHit != null) {
                                currentOnTrackTap?.invoke(trackHit)
                            } else {
                                val geo = screenToGeoPoint(down.position)
                                if (geo != null) currentOnMapTap?.invoke(geo.first, geo.second)
                            }
                        }
                    }
                }
        ) {
            val drawWidth = image.width * fitScale() * scale
            val drawHeight = image.height * fitScale() * scale
            val originX = (size.width - drawWidth) / 2f + offset.x
            val originY = (size.height - drawHeight) / 2f + offset.y

            // Terrain first, so any ground the sheet does not cover is filled
            // rather than left blank.
            val frameForBase = map.frame
            if (basemap != null && frameForBase != null &&
                pageWidthPoints > 0 && pageHeightPoints > 0
            ) {
                drawBasemap(
                    basemap = basemap,
                    frame = frameForBase,
                    pageWidthPoints = pageWidthPoints,
                    pageHeightPoints = pageHeightPoints,
                    originX = originX,
                    originY = originY,
                    drawWidth = drawWidth,
                    drawHeight = drawHeight
                )
            }

            drawImage(
                image = image,
                dstOffset = IntOffset(originX.roundToInt(), originY.roundToInt()),
                dstSize = IntSize(drawWidth.roundToInt(), drawHeight.roundToInt())
            )

            val frame = map.frame
            if (frame != null && pageWidthPoints > 0 && pageHeightPoints > 0) {
                for (point in dropPoints) {
                    val dx = (point.pageX / pageWidthPoints).toFloat()
                    val dy = 1f - (point.pageY / pageHeightPoints).toFloat()
                    if (dx !in 0f..1f || dy !in 0f..1f) continue
                    drawDropPointMarker(
                        Offset(originX + dx * drawWidth, originY + dy * drawHeight)
                    )
                }
            }

            if (frame == null || latitude == null || longitude == null ||
                pageWidthPoints <= 0 || pageHeightPoints <= 0
            ) {
                return@Canvas
            }

            val page = frame.geoToPage(latitude, longitude) ?: return@Canvas
            val fx = (page.first / pageWidthPoints).toFloat()
            val fy = 1f - (page.second / pageHeightPoints).toFloat()
            val target = Offset(originX + fx * drawWidth, originY + fy * drawHeight)

            if (frame != null) {
                for (saved in savedTracks) {
                    drawTrack(
                        points = saved.points,
                        frame = frame,
                        pageWidthPoints = pageWidthPoints,
                        pageHeightPoints = pageHeightPoints,
                        originX = originX,
                        originY = originY,
                        drawWidth = drawWidth,
                        drawHeight = drawHeight,
                        colour = Color(0xFF9C27B0)
                    )
                }
            }

            if (trackPoints.size >= 2 && frame != null) {
                drawTrack(
                    points = trackPoints,
                    frame = frame,
                    pageWidthPoints = pageWidthPoints,
                    pageHeightPoints = pageHeightPoints,
                    originX = originX,
                    originY = originY,
                    drawWidth = drawWidth,
                    drawHeight = drawHeight
                )
            }

            if (measurePoints.size >= 1) {
                drawMeasurement(
                    points = measurePoints,
                    mode = measureMode,
                    frame = frame,
                    pageWidthPoints = pageWidthPoints,
                    pageHeightPoints = pageHeightPoints,
                    originX = originX,
                    originY = originY,
                    drawWidth = drawWidth,
                    drawHeight = drawHeight
                )
            }

            // Where the searched position could still be. A point when it is
            // known, a line or a box while digits are missing.
            if (searchRegion != null && frame != null &&
                pageWidthPoints > 0 && pageHeightPoints > 0
            ) {
                fun toScreen(latitude: Double, longitude: Double): Offset? {
                    val page = frame.geoToPage(latitude, longitude) ?: return null
                    return Offset(
                        originX + (page.first / pageWidthPoints).toFloat() * drawWidth,
                        originY + (1f - (page.second / pageHeightPoints).toFloat()) * drawHeight
                    )
                }

                val southWest = toScreen(searchRegion.south, searchRegion.west)
                val northEast = toScreen(searchRegion.north, searchRegion.east)
                val northWest = toScreen(searchRegion.north, searchRegion.west)
                val southEast = toScreen(searchRegion.south, searchRegion.east)

                if (southWest != null && northEast != null &&
                    northWest != null && southEast != null
                ) {
                    val accent = Color(0xFF40C4FF)
                    if (searchRegion.isPoint) {
                        drawCircle(Color.Black, radius = 22f, center = southWest, alpha = 0.5f)
                        drawCircle(accent, radius = 20f, center = southWest,
                            style = Stroke(width = 4f))
                        drawCircle(accent, radius = 6f, center = southWest)
                    } else {
                        val ring = Path().apply {
                            moveTo(southWest.x, southWest.y)
                            lineTo(northWest.x, northWest.y)
                            lineTo(northEast.x, northEast.y)
                            lineTo(southEast.x, southEast.y)
                            close()
                        }
                        drawPath(ring, accent, alpha = 0.22f)
                        drawPath(ring, Color.Black, alpha = 0.5f, style = Stroke(width = 7f))
                        drawPath(ring, accent, style = Stroke(width = 3.5f))
                    }
                }
            }

            for (marker in markers) {
                val position = if (marker.id == draggingMarkerId) {
                    dragPoint
                } else {
                    markerScreenPosition(marker)
                } ?: continue
                drawResourcePin(
                    center = position,
                    symbol = ResourceSymbol.byId(marker.symbol),
                    title = marker.title,
                    lifted = marker.id == draggingMarkerId
                )
            }

            // Drawn wherever it lands, not only inside the neatline. Off the
            // sheet there is terrain underneath now, and the whole question
            // being asked of the screen is "where am I".
            val onScreen = target.x >= -40f && target.x <= size.width + 40f &&
                target.y >= -40f && target.y <= size.height + 40f
            if (onScreen) {
                drawPositionDot(target, positionIsSimulated)
            } else {
                // Off the sheet: point at where the position actually is
                // rather than drawing nothing at all.
                drawOffSheetArrow(
                    target = target,
                    sheetCentre = Offset(originX + drawWidth / 2f, originY + drawHeight / 2f),
                    left = originX,
                    top = originY,
                    right = originX + drawWidth,
                    bottom = originY + drawHeight
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalIconButton(
                onClick = {
                    // Centre the sheet on the current position, zooming in
                    // enough that centring can actually take effect: at a
                    // fit-to-view scale the pan clamp pins the sheet in place.
                    val frame = map.frame
                    if (frame != null && latitude != null && longitude != null &&
                        pageWidthPoints > 0 && pageHeightPoints > 0
                    ) {
                        val page = frame.geoToPage(latitude, longitude)
                        if (page != null) {
                            val fx = (page.first / pageWidthPoints).toFloat()
                            val fy = 1f - (page.second / pageHeightPoints).toFloat()
                            val next = maxOf(scale, 4f)
                            scale = next
                            val drawWidth = image.width * fitScale() * next
                            val drawHeight = image.height * fitScale() * next
                            // Set directly rather than through the pan clamp:
                            // when the position is off the sheet the clamp
                            // would stop short of it, which is precisely the
                            // case this control exists for.
                            offset = Offset(
                                drawWidth * (0.5f - fx),
                                drawHeight * (0.5f - fy)
                            )
                        }
                    }
                },
                enabled = latitude != null && longitude != null
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Centre on my position")
            }
            FilledTonalIconButton(onClick = { scale = 1f; offset = Offset.Zero }) {
                Icon(Icons.Default.CenterFocusStrong, contentDescription = "Fit sheet to view")
            }
        }
    }
}

private const val OFF_SHEET_PAN_ALLOWANCE = 1.5f

/** Where a searched position could be: a point, a line, or a box. */
data class SearchRegion(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double
) {
    val isPoint: Boolean
        get() = north - south < 1e-9 && east - west < 1e-9
}

/** A completed track held against the incident. */
data class SavedTrack(
    val id: String,
    val name: String,
    val points: List<Pair<Double, Double>>,
    val distanceMeters: Double,
    val elapsedSeconds: Long
)

/** Shortest distance from a point to a line segment, in pixels. */
private fun distanceToSegment(point: Offset, start: Offset, end: Offset): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared <= 0.0001f) return (point - start).getDistance()
    val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared)
        .coerceIn(0f, 1f)
    return (point - Offset(start.x + t * dx, start.y + t * dy)).getDistance()
}

/** Draws the recorded travel line. */
private fun DrawScope.drawTrack(
    points: List<Pair<Double, Double>>,
    frame: com.rhecyee.firelinemap.geopdf.MapFrame,
    pageWidthPoints: Int,
    pageHeightPoints: Int,
    originX: Float,
    originY: Float,
    drawWidth: Float,
    drawHeight: Float,
    colour: Color = Color(0xFFE91E63)
) {
    if (pageWidthPoints <= 0 || pageHeightPoints <= 0) return
    val screen = points.mapNotNull { (latitude, longitude) ->
        val page = frame.geoToPage(latitude, longitude) ?: return@mapNotNull null
        Offset(
            originX + (page.first / pageWidthPoints).toFloat() * drawWidth,
            originY + (1f - (page.second / pageHeightPoints).toFloat()) * drawHeight
        )
    }
    if (screen.size < 2) return

    val path = Path().apply {
        moveTo(screen.first().x, screen.first().y)
        screen.drop(1).forEach { lineTo(it.x, it.y) }
    }
    // Cased so the line stays readable over both pale terrain and dark shading.
    drawPath(path, Color.Black, alpha = 0.55f, style = Stroke(width = 9f))
    drawPath(path, colour, style = Stroke(width = 4.5f))
    // Mark where travel began, so a long track reads directionally.
    drawCircle(Color.White, radius = 7f, center = screen.first())
    drawCircle(colour, radius = 4.5f, center = screen.first())
}

/** Draws the in-progress measurement over the sheet. */
private fun DrawScope.drawMeasurement(
    points: List<MeasurePoint>,
    mode: MeasureMode,
    frame: com.rhecyee.firelinemap.geopdf.MapFrame,
    pageWidthPoints: Int,
    pageHeightPoints: Int,
    originX: Float,
    originY: Float,
    drawWidth: Float,
    drawHeight: Float
) {
    if (pageWidthPoints <= 0 || pageHeightPoints <= 0) return
    val screen = points.mapNotNull { point ->
        val page = frame.geoToPage(point.latitude, point.longitude) ?: return@mapNotNull null
        Offset(
            originX + (page.first / pageWidthPoints).toFloat() * drawWidth,
            originY + (1f - (page.second / pageHeightPoints).toFloat()) * drawHeight
        )
    }
    if (screen.isEmpty()) return

    val accent = Color(0xFFFFC400)
    if (screen.size >= 2) {
        val path = Path().apply {
            moveTo(screen.first().x, screen.first().y)
            screen.drop(1).forEach { lineTo(it.x, it.y) }
            if (mode == MeasureMode.AREA && screen.size >= 3) close()
        }
        if (mode == MeasureMode.AREA && screen.size >= 3) {
            drawPath(path, accent, alpha = 0.20f)
        }
        // A dark casing under the line keeps it readable over pale terrain
        // and dark shading alike.
        drawPath(path, Color.Black, alpha = 0.55f, style = Stroke(width = 7f))
        drawPath(path, accent, style = Stroke(width = 3.5f))
    }
    screen.forEachIndexed { index, point ->
        drawCircle(Color.Black, radius = 7.5f, center = point, alpha = 0.6f)
        drawCircle(if (index == 0) Color.White else accent, radius = 5f, center = point)
    }
}

/**
 * Fills the canvas with terrain tiles positioned through the sheet's own
 * georeferencing.
 *
 * Tiles are placed by converting their geographic corners back through the
 * frame transform, so terrain and sheet share one coordinate system and stay
 * registered to each other when panned or zoomed.
 */
private fun DrawScope.drawBasemap(
    basemap: BasemapTileCache,
    frame: com.rhecyee.firelinemap.geopdf.MapFrame,
    pageWidthPoints: Int,
    pageHeightPoints: Int,
    originX: Float,
    originY: Float,
    drawWidth: Float,
    drawHeight: Float
) {
    fun screenToGeo(x: Float, y: Float): com.rhecyee.firelinemap.geopdf.GeoPoint? {
        val fx = (x - originX) / drawWidth
        val fy = (y - originY) / drawHeight
        return frame.pageToGeo(
            fx * pageWidthPoints.toDouble(),
            (1f - fy) * pageHeightPoints.toDouble()
        )
    }

    val topLeft = screenToGeo(0f, 0f) ?: return
    val bottomRight = screenToGeo(size.width, size.height) ?: return

    val north = maxOf(topLeft.latitude, bottomRight.latitude)
    val south = minOf(topLeft.latitude, bottomRight.latitude)
    val west = minOf(topLeft.longitude, bottomRight.longitude)
    val east = maxOf(topLeft.longitude, bottomRight.longitude)
    if (north <= south || east <= west) return

    val centreLatitude = (north + south) / 2.0
    // Match tile resolution to what is actually on screen.
    val spanMeters = com.rhecyee.firelinemap.map.MapCoverage.distanceMeters(
        centreLatitude, west, centreLatitude, east
    )
    if (spanMeters <= 0.0 || size.width <= 0f) return
    val zoom = BasemapTileCache.zoomFor(centreLatitude, spanMeters / size.width)
        .coerceIn(4, 15)

    val minX = BasemapTileCache.tileX(west, zoom)
    val maxX = BasemapTileCache.tileX(east, zoom)
    val minY = BasemapTileCache.tileY(north, zoom)
    val maxY = BasemapTileCache.tileY(south, zoom)

    // A viewport this wide means something is wrong with the transform;
    // fetching thousands of tiles would be worse than drawing nothing.
    if ((maxX - minX + 1).toLong() * (maxY - minY + 1).toLong() > 200) return

    for (x in minX..maxX) {
        for (y in minY..maxY) {
            val sample = basemap.sample(zoom, x, y) ?: continue
            val tileNorth = BasemapTileCache.tileNorth(y, zoom)
            val tileSouth = BasemapTileCache.tileNorth(y + 1, zoom)
            val tileWest = BasemapTileCache.tileWest(x, zoom)
            val tileEast = BasemapTileCache.tileWest(x + 1, zoom)

            val topLeftPage = frame.geoToPage(tileNorth, tileWest) ?: continue
            val bottomRightPage = frame.geoToPage(tileSouth, tileEast) ?: continue

            val left = originX + (topLeftPage.first / pageWidthPoints).toFloat() * drawWidth
            val top = originY +
                (1f - (topLeftPage.second / pageHeightPoints).toFloat()) * drawHeight
            val right = originX + (bottomRightPage.first / pageWidthPoints).toFloat() * drawWidth
            val bottom = originY +
                (1f - (bottomRightPage.second / pageHeightPoints).toFloat()) * drawHeight

            val width = (right - left).roundToInt()
            val height = (bottom - top).roundToInt()
            if (width <= 0 || height <= 0) continue

            drawImage(
                image = sample.bitmap.asImageBitmap(),
                srcOffset = IntOffset(sample.sourceLeft, sample.sourceTop),
                srcSize = IntSize(sample.sourceSize, sample.sourceSize),
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                // Overdraw by a pixel: adjacent tiles are positioned
                // independently and rounding leaves hairline seams otherwise.
                dstSize = IntSize(width + 1, height + 1)
            )
        }
    }
}

/** A resource pin: a coloured plate carrying its abbreviation, with the
 * identifier beneath it. */
private fun DrawScope.drawResourcePin(
    center: Offset,
    symbol: ResourceSymbol,
    title: String,
    lifted: Boolean
) {
    val halfWidth = 30f
    val halfHeight = 19f
    val scale = if (lifted) 1.18f else 1f
    val left = center.x - halfWidth * scale
    val top = center.y - halfHeight * scale

    drawCircle(Color.Black, radius = 4f, center = center, alpha = 0.5f)
    drawRoundRect(
        color = Color.Black,
        topLeft = Offset(left - 2f, top - 2f),
        size = androidx.compose.ui.geometry.Size(
            halfWidth * 2 * scale + 4f, halfHeight * 2 * scale + 4f
        ),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(7f, 7f),
        alpha = 0.55f
    )
    drawRoundRect(
        color = Color(symbol.colorArgb),
        topLeft = Offset(left, top),
        size = androidx.compose.ui.geometry.Size(halfWidth * 2 * scale, halfHeight * 2 * scale),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
    )

    drawContext.canvas.nativeCanvas.apply {
        val glyphPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 22f * scale
            isAntiAlias = true
            isFakeBoldText = true
        }
        drawText(symbol.glyph, center.x, center.y + 8f * scale, glyphPaint)

        if (title.isNotBlank()) {
            val labelPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = 21f
                isAntiAlias = true
                isFakeBoldText = true
                setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
            }
            drawText(title.take(12), center.x, top + halfHeight * 2 * scale + 22f, labelPaint)
        }
    }
}

/** A provisional drop point read off the sheet, drawn so it can be checked. */
private fun DrawScope.drawDropPointMarker(center: Offset) {
    drawCircle(Color(0xFF00E5FF), radius = 13f, center = center, alpha = 0.30f)
    drawCircle(Color(0xFF00E5FF), radius = 13f, center = center, style = Stroke(width = 2.5f))
}

private fun DrawScope.drawPositionDot(center: Offset, simulated: Boolean) {
    val fill = if (simulated) Color(0xFFE65100) else Color(0xFF1565C0)
    drawCircle(Color.White, radius = 16f, center = center)
    drawCircle(fill, radius = 12f, center = center)
    drawCircle(Color.White, radius = 4f, center = center)
}

/**
 * Draws a marker on the sheet edge pointing toward an off-sheet position.
 *
 * Being off the map is the moment the operator most needs to know which way
 * the map is, so the edge is annotated rather than left blank.
 */
private fun DrawScope.drawOffSheetArrow(
    target: Offset,
    sheetCentre: Offset,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float
) {
    val anchor = Offset(
        target.x.coerceIn(left + 18f, right - 18f),
        target.y.coerceIn(top + 18f, bottom - 18f)
    )
    val angle = Math.toDegrees(
        atan2((target.y - sheetCentre.y).toDouble(), (target.x - sheetCentre.x).toDouble())
    ).toFloat()

    drawCircle(Color.White, radius = 17f, center = anchor)
    drawCircle(Color(0xFFB3261E), radius = 14f, center = anchor)
    rotate(degrees = angle, pivot = anchor) {
        val head = Path().apply {
            moveTo(anchor.x + 9f, anchor.y)
            lineTo(anchor.x - 4f, anchor.y - 6f)
            lineTo(anchor.x - 4f, anchor.y + 6f)
            close()
        }
        drawPath(head, Color.White)
    }
}

/** A banner shown when the live position is not on the active sheet. */
@Composable
fun OffMapBanner(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFB3261E), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

/** A banner shown whenever the displayed position is not a real GPS fix. */
@Composable
fun SimulatedBanner(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFE65100), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

/**
 * What remains on screen once the controls fold away.
 *
 * Position, accuracy, and whether travel is being recorded. Everything else
 * can wait for a touch; these are the things someone would otherwise have to
 * bring the whole interface back to check.
 */
@androidx.compose.runtime.Composable
fun CompactStatusStrip(
    coordinates: String,
    accuracy: Float?,
    simulated: Boolean,
    recording: Boolean,
    paused: Boolean,
    distanceMeters: Double,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF10161B).copy(alpha = 0.82f), RoundedCornerShape(9.dp))
            .clickable { onTap() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                coordinates,
                color = if (simulated) Color(0xFFFFB74D) else Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                buildString {
                    append(accuracy?.let { "±%.0f m".format(it) } ?: "—")
                    if (recording) {
                        append(if (paused) "  ·  PAUSED  " else "  ·  RECORDING  ")
                        append("%.2f mi".format(distanceMeters / 1609.344))
                    }
                },
                color = when {
                    paused -> Color(0xFFFFA000)
                    recording -> Color(0xFFFF80AB)
                    else -> Color.White.copy(alpha = 0.6f)
                },
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
