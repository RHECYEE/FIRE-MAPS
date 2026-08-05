package com.rhecyee.firelinemap.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
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
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
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
import com.rhecyee.firelinemap.map.MapProjection
import com.rhecyee.firelinemap.map.ViewClamp
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
    projection: MapProjection?,
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
    contours: com.rhecyee.firelinemap.terrain.ContourRender? = null,
    boundaries: com.rhecyee.firelinemap.land.BoundaryRender? = null,
    onViewBounds: ((north: Double, south: Double, west: Double, east: Double, zoom: Int) -> Unit)? = null,
    onContourDrawFailed: ((Throwable) -> Unit)? = null,
    onWhereAmILooking: ((String) -> Unit)? = null,
    centreOn: Pair<Double, Double>? = null,
    onCentred: () -> Unit = {},
    onInteraction: () -> Unit = {},
    onTrackTap: ((SavedTrack) -> Unit)? = null,
    onMarkerTap: ((MarkerEntity) -> Unit)? = null,
    onMarkerMoved: ((MarkerEntity, Double, Double) -> Unit)? = null,
    onMapTap: ((latitude: Double, longitude: Double) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Keyed on the projection, not the map.
    //
    // A sheet arrives a moment after the map that owns it -- the page has to be
    // rendered first -- so the view spends that moment on plain ground, whose
    // content is a different size in different units and allows a different
    // zoom. Carrying a scale and a pan across that switch lands the view
    // nowhere, which reads as the map having jumped away on import.
    var scale by remember(projection) { mutableFloatStateOf(1f) }
    var offset by remember(projection) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var draggingMarkerId by remember { mutableStateOf<String?>(null) }
    var dragPoint by remember { mutableStateOf(Offset.Zero) }

    /**
     * Whether the map keeps itself on the operator.
     *
     * A mode rather than a one-shot press: on a moving vehicle the useful
     * thing is for the map to keep up, and pressing a button every few seconds
     * to make it do so is not something anyone can manage while driving a
     * line. Panning or pinching turns it off, because looking ahead up a road
     * is the other thing people need, and a map that hauls itself back under
     * the finger cannot be read at all.
     */
    var following by remember(projection) { mutableStateOf(false) }

    /**
     * Where a pinch is anchored, while one is happening.
     *
     * Shown because zooming was described as the map teleporting, and the
     * honest answer is that it does not: it holds the ground between the
     * fingers still and everything else moves away from it. That is correct
     * and it is also invisible, which makes it indistinguishable from the map
     * jumping. Drawing the anchor turns an unexplained movement into an
     * obvious one.
     */
    var zoomAnchor by remember { mutableStateOf<Offset?>(null) }



    // The tile level last drawn at, so it can be held across a pinch. A plain
    // holder rather than snapshot state on purpose: this is written during the
    // draw pass, and writing snapshot state there would invalidate the frame
    // that is being drawn and loop.
    val tileZoom = remember(projection) { TileZoomHolder() }

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
        // A sheet is no longer required. With none imported the app draws its
        // own terrain around wherever the operator is, which is the state
        // every fire starts in: there is no product on day one, and that is
        // the day somebody most needs to know where they are.
        if (projection == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("WAITING FOR A POSITION", color = Color.White, fontWeight = FontWeight.Black)
                Text(
                    "Terrain draws around you as soon as there is a fix, " +
                        "or import a GeoPDF",
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            return@Box
        }

        val image = remember(bitmap) { bitmap?.asImageBitmap() }
        val contentWidth = projection.contentWidth
        val contentHeight = projection.contentHeight

        // An external request to bring a position into view, used by the
        // search so a found region can be looked at without hunting for it.
        androidx.compose.runtime.LaunchedEffect(centreOn, viewport, projection) {
            val target = centreOn ?: return@LaunchedEffect
            if (viewport.width == 0) return@LaunchedEffect
            val unit = projection.toUnit(target.first, target.second)
            if (unit != null) {
                val fitNow = minOf(
                    viewport.width.toFloat() / contentWidth,
                    viewport.height.toFloat() / contentHeight
                )
                val next = maxOf(scale, minOf(6f, projection.maxScale))
                scale = next
                val (x, y) = ViewClamp.clamp(
                    offsetX = contentWidth * fitNow * next * (0.5f - unit.first),
                    offsetY = contentHeight * fitNow * next * (0.5f - unit.second),
                    contentWidth = contentWidth * fitNow * next,
                    contentHeight = contentHeight * fitNow * next,
                    viewportWidth = viewport.width.toFloat(),
                    viewportHeight = viewport.height.toFloat()
                )
                offset = Offset(x, y)
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
                    viewport.width.toFloat() / contentWidth,
                    viewport.height.toFloat() / contentHeight
                )
            } else {
                1f
            }

        fun contentSize(atScale: Float): Pair<Float, Float> {
            val fit = fitScale()
            return contentWidth * fit * atScale to contentHeight * fit * atScale
        }

        fun origin(atScale: Float): Offset {
            val (width, height) = contentSize(atScale)
            return Offset(
                (viewport.width - width) / 2f + offset.x,
                (viewport.height - height) / 2f + offset.y
            )
        }

        /**
         * Bounds a pan without ever hauling the view somewhere it was not.
         *
         * Centring on a position off the content sets the pan directly, so the
         * view can legitimately sit far outside the normal range. The limit
         * therefore always contains where the view already is; a limit that
         * ignored that would snap it back on the first drag, which read as
         * teleporting.
         *
         * Within that it is a real clamp rather than a refusal. It used to
         * return the offset unchanged whenever a drag would cross the edge, so
         * the pan stopped wherever the finger happened to be instead of at the
         * edge, and pushing further did nothing -- the map felt like it did
         * not want to go that way.
         */
        fun clamp(candidate: Offset, atScale: Float): Offset {
            val (drawWidth, drawHeight) = contentSize(atScale)
            val (x, y) = ViewClamp.clamp(
                offsetX = candidate.x,
                offsetY = candidate.y,
                contentWidth = drawWidth,
                contentHeight = drawHeight,
                viewportWidth = viewport.width.toFloat(),
                viewportHeight = viewport.height.toFloat()
            )
            return Offset(x, y)
        }


        /** Ground to a point on screen. */
        fun screenPosition(latitude: Double, longitude: Double): Offset? {
            val unit = projection.toUnit(latitude, longitude) ?: return null
            val (width, height) = contentSize(scale)
            val at = origin(scale)
            return Offset(at.x + unit.first * width, at.y + unit.second * height)
        }

        fun screenToGeoPoint(point: Offset): Pair<Double, Double>? {
            val (width, height) = contentSize(scale)
            if (width <= 0f || height <= 0f) return null
            val at = origin(scale)
            // Not clamped to the content. Taps land beyond it all the time --
            // the drive in, ICP, a spot across the road -- and refusing them
            // there made the tools look dead whenever the sheet did not fill
            // the view.
            return projection.toGeo((point.x - at.x) / width, (point.y - at.y) / height)
        }

        /** Page coordinates, which only exist when a sheet is behind the view. */
        fun screenToPagePoints(point: Offset): Pair<Double, Double>? {
            if (pageWidthPoints <= 0 || pageHeightPoints <= 0) return null
            val (width, height) = contentSize(scale)
            if (width <= 0f || height <= 0f) return null
            val at = origin(scale)
            val fx = (point.x - at.x) / width
            val fy = (point.y - at.y) / height
            // Bitmap y runs downward; PDF page space runs upward.
            return fx * pageWidthPoints.toDouble() to (1f - fy) * pageHeightPoints.toDouble()
        }

        fun markerScreenPosition(marker: MarkerEntity): Offset? =
            screenPosition(marker.latitude, marker.longitude)

        fun markerAt(point: Offset): MarkerEntity? = currentMarkers.lastOrNull { marker ->
            val position = markerScreenPosition(marker) ?: return@lastOrNull false
            // Generous target: this gets used with gloves on.
            (point - position).getDistance() <= 48f
        }

        /** The saved track a tap lands on, if any. */
        fun trackAt(point: Offset): SavedTrack? {
            for (saved in currentSavedTracks) {
                var previous: Offset? = null
                for ((latitude, longitude) in saved.points) {
                    val current = screenPosition(latitude, longitude) ?: continue
                    val start = previous
                    if (start != null && distanceToSegment(point, start, current) <= 44f) {
                        return saved
                    }
                    previous = current
                }
            }
            return null
        }

        // The invariant, enforced continuously rather than only where the pan
        // is written.
        //
        // Every write goes through the clamp, but the clamp depends on the
        // viewport and on the scale, and both change independently of it --
        // the first layout pass, a rotation, a zoom applied elsewhere. An
        // offset that was legal a moment ago can stop being legal without
        // anything touching it. Re-checking here is what makes "some of the
        // sheet is always on screen" a property of the view rather than a
        // property of four call sites remembering to ask.
        androidx.compose.runtime.LaunchedEffect(viewport, scale, projection) {
            if (viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
            val bounded = clamp(offset, scale)
            if (bounded != offset) offset = bounded
        }

        /**
         * The ground currently on screen, and the tile level it amounts to.
         *
         * Computed from the same numbers the draw uses, but outside it: the
         * contour layer needs to know what to cut, and it cannot be told from
         * inside a draw pass without invalidating the frame being drawn.
         */
        fun viewBounds(): Pair<DoubleArray, Int>? {
            if (viewport.width <= 0 || viewport.height <= 0) return null
            val (width, height) = contentSize(scale)
            if (width <= 0f || height <= 0f) return null
            val at = origin(scale)

            fun geo(x: Float, y: Float) =
                projection.toGeo((x - at.x) / width, (y - at.y) / height)

            val topLeft = geo(0f, 0f) ?: return null
            val bottomRight = geo(viewport.width.toFloat(), viewport.height.toFloat())
                ?: return null

            val north = maxOf(topLeft.first, bottomRight.first)
            val south = minOf(topLeft.first, bottomRight.first)
            val west = minOf(topLeft.second, bottomRight.second)
            val east = maxOf(topLeft.second, bottomRight.second)
            if (north <= south || east <= west) return null

            val centre = (north + south) / 2.0
            val spanMeters = com.rhecyee.firelinemap.map.MapCoverage.distanceMeters(
                centre, west, centre, east
            )
            if (spanMeters <= 0.0) return null
            val zoom = BasemapTileCache.zoomFor(centre, spanMeters / viewport.width)
            return doubleArrayOf(north, south, west, east) to zoom.coerceIn(4, 18)
        }

        // Reported once the view settles rather than while it moves. Cutting
        // contours mid-pan would be work thrown away several times a second,
        // and the settle is short enough not to be noticed as a wait.
        val reportBounds by rememberUpdatedState(onViewBounds)
        androidx.compose.runtime.LaunchedEffect(scale, offset, viewport, projection) {
            if (reportBounds == null) return@LaunchedEffect
            kotlinx.coroutines.delay(VIEW_SETTLE_MILLIS)
            val (box, zoom) = viewBounds() ?: return@LaunchedEffect
            reportBounds?.invoke(box[0], box[1], box[2], box[3], zoom)
        }

        /** Puts the current position in the middle of the view. */
        fun centreOnPosition(): Boolean {
            if (latitude == null || longitude == null) return false
            val unit = projection.toUnit(latitude, longitude) ?: return false
            // Zoomed in enough that centring can take effect: at a
            // fit-to-view scale the pan clamp pins the content in place.
            val next = maxOf(scale, minOf(4f, projection.maxScale))
            scale = next
            val (width, height) = contentSize(next)
            // Clamped like any other pan. Centring on ground well off the
            // sheet stops at the edge of what can be panned back from, and
            // the arrow at the screen edge carries on pointing at the
            // position from there.
            offset = clamp(
                Offset(width * (0.5f - unit.first), height * (0.5f - unit.second)),
                next
            )
            return true
        }

        // Recovers the view if the map has had nothing on it for a moment.
        //
        // A safety net, and named as one. The right fix is for the view never
        // to reach a state with no ground in it, and that is still being
        // chased; meanwhile an operator should not have to know which button
        // is the way out. A map that recovers itself is usable; one that needs
        // a specific press, discovered by trial, is not.
        //
        // Polled rather than driven by the draw. The draw reporting into
        // Compose state was a write during the drawing phase, which schedules
        // a composition, which draws, which writes again -- at frame rate,
        // through every zoom, until the app was killed. That was the crash.
        val watched = basemap
        androidx.compose.runtime.LaunchedEffect(watched, projection) {
            if (watched == null) return@LaunchedEffect
            while (true) {
                kotlinx.coroutines.delay(EMPTY_POLL_MILLIS)
                val since = watched.emptySinceMillis
                if (since == 0L) continue
                if (System.currentTimeMillis() - since < EMPTY_RECOVERY_MILLIS) continue
                if (!centreOnPosition()) {
                    // No fix to centre on. Fit the whole thing instead, which
                    // is always somewhere with ground in it.
                    scale = 1f
                    offset = Offset.Zero
                }
                // Cleared here rather than waiting for the next draw, so a
                // recovery that does not help is retried rather than repeated
                // without pause.
                watched.emptySinceMillis = 0L
            }
        }

        // While following, every fix re-centres. Keyed on the position so it
        // happens when the operator moves rather than on a timer.
        androidx.compose.runtime.LaunchedEffect(following, latitude, longitude, viewport) {
            if (following) centreOnPosition()
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // One handler for everything. Three competing pointerInput
                // blocks meant drag and transform each claimed the pointer
                // stream and taps frequently never arrived at all.
                .pointerInput(projection, bitmap) {
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
                        val canvasCentre = Offset(size.width / 2f, size.height / 2f)
                        try {
                            do {
                                val event = awaitPointerEvent()
                                pointers = maxOf(pointers, event.changes.count { it.pressed })
                                // All three of these can come back as not a
                                // number, and one that gets through poisons
                                // the pan permanently.
                                //
                                // calculateCentroid returns Offset.Unspecified
                                // -- which is a pair of NaNs -- whenever no
                                // pointer was down both this event and last,
                                // which happens the instant a finger lifts off
                                // a pinch. Multiplying it by anything, zero
                                // included, gives NaN; the pan becomes NaN and
                                // stays NaN. From there every tile projects to
                                // NaN and is rejected as off screen, the view
                                // corners will not convert so nothing is even
                                // fetched, and the map is blank with a full
                                // cache behind it. Only centring recovers,
                                // because it is the one path that builds the
                                // pan from scratch instead of from itself.
                                //
                                // That is the whole of the blank-map fault,
                                // and lately the crash as well: the notice
                                // added to diagnose it rounds the geometry for
                                // display, and rounding NaN throws.
                                val zoomChange = event.calculateZoom()
                                    .takeIf { it.isFinite() && it > 0f } ?: 1f
                                val panChange = event.calculatePan()
                                    .takeIf { it.x.isFinite() && it.y.isFinite() } ?: Offset.Zero
                                val centroid = event.calculateCentroid(useCurrent = false)
                                    .takeIf { it.x.isFinite() && it.y.isFinite() }
                                travelled += panChange.getDistance() + abs(1f - zoomChange) * 200f

                                if (travelled > viewConfiguration.touchSlop) {
                                    // Moving the map by hand is a statement
                                    // about where to look, so it ends follow.
                                    following = false
                                    val next = (scale * zoomChange).coerceIn(1f, projection.maxScale)
                                    // The ratio actually applied, which is not the
                                    // one asked for once the limits are reached.
                                    // Using the requested ratio there slides the
                                    // sheet sideways while the zoom sits pinned.
                                    val applied = if (scale > 0f) next / scale else 1f
                                    scale = next

                                    // Zoom about the fingers, not about the middle
                                    // of the screen. The sheet is drawn from the
                                    // centre outward, so growing it moves every
                                    // point away from the centre in proportion --
                                    // and the pan has to grow with it or the ground
                                    // under the pinch shoots off across the view.
                                    // That was the map appearing to teleport.
                                    val focus =
                                        centroid?.takeIf { pointers > 1 } ?: canvasCentre
                                    if (pointers > 1) zoomAnchor = focus
                                    val zoomed = offset * applied +
                                        (focus - canvasCentre) * (1f - applied)

                                    offset = clamp(zoomed + panChange, next)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        } finally {
                            zoomAnchor = null
                        }

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
            val (drawWidth, drawHeight) = contentSize(scale)
            val originX = (size.width - drawWidth) / 2f + offset.x
            val originY = (size.height - drawHeight) / 2f + offset.y

            /** Ground to this frame's screen coordinates. */
            fun place(latitude: Double, longitude: Double): Offset? {
                val unit = projection.toUnit(latitude, longitude) ?: return null
                return Offset(
                    originX + unit.first * drawWidth,
                    originY + unit.second * drawHeight
                )
            }

            // Terrain first, so any ground the sheet does not cover is filled
            // rather than left blank -- and, with no sheet at all, so there is
            // something to stand on.
            // Guarded, and drawn before the sheet. Anything thrown here used
            // to abort the whole draw, so a fault in the terrain layer took
            // the sheet, the position and the tracks with it -- a blank
            // screen instead of a missing background.
            if (basemap != null) {
                drawBasemap(
                    basemap = basemap,
                    projection = projection,
                    originX = originX,
                    originY = originY,
                    drawWidth = drawWidth,
                    drawHeight = drawHeight,
                    held = tileZoom
                )
            }

            // Only the part of the sheet that is actually on screen.
            //
            // Drawing the whole page into a destination rectangle the size of
            // the zoomed sheet means asking the canvas to scale a twenty
            // megabyte bitmap into something tens of thousands of pixels
            // across, every frame, to show a phone screen's worth of it. Past
            // a certain zoom those coordinates leave the range the renderer
            // works in and the draw is simply dropped -- the sheet vanishes
            // and the background is all that is left, which is the grey.
            //
            // Cropping first keeps every coordinate inside the viewport at any
            // zoom, and hands the renderer a few hundred pixels of source
            // instead of the whole page.
            if (image != null && drawWidth > 0f && drawHeight > 0f) {
                val u0 = ((0f - originX) / drawWidth).coerceIn(0f, 1f)
                val u1 = ((size.width - originX) / drawWidth).coerceIn(0f, 1f)
                val v0 = ((0f - originY) / drawHeight).coerceIn(0f, 1f)
                val v1 = ((size.height - originY) / drawHeight).coerceIn(0f, 1f)

                val srcLeft = (u0 * image.width).roundToInt()
                val srcTop = (v0 * image.height).roundToInt()
                val srcWidth = ((u1 - u0) * image.width).roundToInt()
                val srcHeight = ((v1 - v0) * image.height).roundToInt()
                val dstLeft = (originX + u0 * drawWidth).roundToInt()
                val dstTop = (originY + v0 * drawHeight).roundToInt()
                val dstWidth = ((u1 - u0) * drawWidth).roundToInt()
                val dstHeight = ((v1 - v0) * drawHeight).roundToInt()

                if (srcWidth > 0 && srcHeight > 0 && dstWidth > 0 && dstHeight > 0) {
                    drawImage(
                        image = image,
                        srcOffset = IntOffset(srcLeft, srcTop),
                        srcSize = IntSize(
                            srcWidth.coerceAtMost(image.width - srcLeft),
                            srcHeight.coerceAtMost(image.height - srcTop)
                        ),
                        dstOffset = IntOffset(dstLeft, dstTop),
                        dstSize = IntSize(dstWidth, dstHeight)
                    )
                }
            }

            // Contours sit directly on the terrain and under everything else.
            // They are ground, not incident information: a line of a crew's
            // making must never be mistakable for a line of the earth's.
            if (contours != null && !contours.isEmpty) {
                // Guarded because this is decoration on top of a working map.
                // Anything thrown from a draw kills the process, and losing a
                // navigation tool on a fireline because a terrain layer could
                // not draw is not a trade worth making. Everything below still
                // comes out.
                runCatching {
                    drawContours(
                        lines = contours.lines,
                        originX = originX,
                        originY = originY,
                        drawWidth = drawWidth,
                        drawHeight = drawHeight
                    )
                }.onFailure { onContourDrawFailed?.invoke(it) }
            }

            // Above terrain and below anything a crew made. Whose ground this
            // is belongs with the ground, not with the incident drawn on it.
            if (boundaries != null && !boundaries.isEmpty) {
                runCatching {
                    drawBoundaries(
                        boundaries = boundaries.boundaries,
                        originX = originX,
                        originY = originY,
                        drawWidth = drawWidth,
                        drawHeight = drawHeight
                    )
                }
            }

            // Drop points are read off a sheet, so they only exist with one.
            if (projection.hasSheet && pageWidthPoints > 0 && pageHeightPoints > 0) {
                for (point in dropPoints) {
                    val dx = (point.pageX / pageWidthPoints).toFloat()
                    val dy = 1f - (point.pageY / pageHeightPoints).toFloat()
                    if (dx !in 0f..1f || dy !in 0f..1f) continue
                    drawDropPointMarker(
                        Offset(originX + dx * drawWidth, originY + dy * drawHeight)
                    )
                }
            }

            for (saved in savedTracks) {
                drawTrack(saved.points, ::place, Color(saved.colourArgb))
            }

            if (trackPoints.size >= 2) {
                drawTrack(trackPoints, ::place, Color(0xFFE91E63))
            }

            if (measurePoints.isNotEmpty()) {
                drawMeasurement(measurePoints, measureMode, ::place)
            }

            // Where the searched position could still be. A point when it is
            // known, a line or a box while digits are missing.
            if (searchRegion != null) {
                val southWest = place(searchRegion.south, searchRegion.west)
                val northEast = place(searchRegion.north, searchRegion.east)
                val northWest = place(searchRegion.north, searchRegion.west)
                val southEast = place(searchRegion.south, searchRegion.east)

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

            if (latitude == null || longitude == null) return@Canvas
            val target = place(latitude, longitude) ?: return@Canvas

            // Drawn wherever it lands, not only inside the neatline. Off the
            // sheet there is terrain underneath now, and the whole question
            // being asked of the screen is "where am I".
            val onScreen = target.x >= -40f && target.x <= size.width + 40f &&
                target.y >= -40f && target.y <= size.height + 40f
            if (onScreen) {
                drawPositionDot(target, positionIsSimulated)
            } else {
                // Off the content: point at where the position actually is
                // rather than drawing nothing at all.
                drawOffSheetArrow(
                    target = target,
                    sheetCentre = Offset(originX + drawWidth / 2f, originY + drawHeight / 2f)
                )
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val anchor = zoomAnchor ?: return@Canvas
            drawZoomAnchor(anchor)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalIconButton(
                onClick = {
                    following = !following
                    // Act at once rather than waiting for the next fix, which
                    // at a slow update rate is half a minute away.
                    if (following) centreOnPosition()
                },
                enabled = latitude != null && longitude != null,
                colors = if (following) {
                    IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color(0xFF1565C0),
                        contentColor = Color.White
                    )
                } else {
                    IconButtonDefaults.filledTonalIconButtonColors()
                }
            ) {
                Icon(
                    if (following) Icons.Default.GpsFixed else Icons.Default.MyLocation,
                    contentDescription = if (following) {
                        "Following your position — tap to stop"
                    } else {
                        "Follow your position"
                    }
                )
            }
            FilledTonalIconButton(onClick = { scale = 1f; offset = Offset.Zero }) {
                Icon(Icons.Default.CenterFocusStrong, contentDescription = "Fit sheet to view")
            }
            if (onWhereAmILooking != null) {
                // Reports where the view actually is, in the terms the drawing
                // uses. Asked for after the map went blank several times with
                // no way to say anything about it beyond that it was blank.
                FilledTonalIconButton(onClick = {
                    val bounds = viewBounds()
                    onWhereAmILooking(
                        buildString {
                            appendLine("scale %.3f".format(scale))
                            appendLine("pan ${offset.x.describe()}, ${offset.y.describe()}")
                            appendLine(
                                "viewport ${viewport.width}x${viewport.height} · " +
                                    "content ${contentWidth.toInt()}x${contentHeight.toInt()} · " +
                                    "fit %.4f".format(fitScale())
                            )
                            val (width, height) = contentSize(scale)
                            val at = origin(scale)
                            appendLine(
                                "drawn ${width.describe()}x${height.describe()} " +
                                    "at ${at.x.describe()},${at.y.describe()}"
                            )
                            appendLine(
                                if (projection.hasSheet) "sheet projection"
                                else "own ground, span %.0f km".format(
                                    projection.contentSpanMeters / 1000.0
                                )
                            )
                            if (bounds == null) {
                                appendLine("VIEW BOUNDS UNAVAILABLE")
                            } else {
                                val (box, zoom) = bounds
                                appendLine(
                                    "N %.5f S %.5f".format(box[0], box[1]) +
                                        " W %.5f E %.5f".format(box[2], box[3])
                                )
                                appendLine("wants level $zoom")
                            }
                            append(basemap?.diagnostics() ?: "no terrain layer")
                        }
                    )
                }) {
                    Icon(Icons.Default.HelpOutline, contentDescription = "Where am I looking")
                }
            }
        }
    }
}

/**
 * The most terrain tiles worth drawing in one frame.
 *
 * A screenful at a matched level is a few dozen. This is the ceiling that
 * catches a level left too fine, and being over it coarsens the level rather
 * than abandoning the frame.
 */
private const val MAX_TILES_PER_FRAME = 220L

/**
 * How many levels coarser the always-present base layer sits.
 *
 * Three, which is a sixty-fourth of the tiles: a handful for any view, cheap
 * to hold in memory and cheap to fetch, and never more than eight times
 * softer than what is wanted.
 */
private const val BASE_LAYER_STEPS = 3

/**
 * How long the map may have nothing on it before the view is recovered.
 *
 * A second. Long enough that tiles arriving normally are never interrupted,
 * short enough that nobody has to work out for themselves which button brings
 * the map back.
 */
private const val EMPTY_RECOVERY_MILLIS = 1_000L

/** Prints a float so a broken one is unmistakable rather than rounded away. */
private fun Float.describe(): String = if (isFinite()) "%.0f".format(this) else toString()

/** How often the watcher looks. Cheap: it reads one long. */
private const val EMPTY_POLL_MILLIS = 250L



/** How long the view has to hold still before contours are re-cut. */
private const val VIEW_SETTLE_MILLIS = 300L

/** Carries the tile level between draws so a pinch cannot make it flip. */
internal class TileZoomHolder(var value: Int? = null)

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
) {
    /** Stable for the life of the track, so the map does not re-label itself. */
    val colourArgb: Int
        get() = com.rhecyee.firelinemap.location.TrackColours.forId(id)
}

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


/**
 * A line of travel.
 *
 * Takes a placing function rather than a frame and a page size: the same line
 * has to draw over an imported sheet and over the app's own terrain, and the
 * only difference between those is how ground becomes a point on screen.
 */
private fun DrawScope.drawTrack(
    points: List<Pair<Double, Double>>,
    place: (Double, Double) -> Offset?,
    colour: Color
) {
    if (points.size < 2) return
    val screen = points.mapNotNull { (latitude, longitude) -> place(latitude, longitude) }
    if (screen.size < 2) return

    val path = Path().apply {
        moveTo(screen.first().x, screen.first().y)
        screen.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(path, Color.Black, alpha = 0.45f, style = Stroke(width = 9f))
    drawPath(path, colour, style = Stroke(width = 5f))
    drawCircle(colour, radius = 7f, center = screen.first())
    drawCircle(Color.White, radius = 3f, center = screen.first())
}

/** Draws the in-progress measurement over the sheet. */
private fun DrawScope.drawMeasurement(
    points: List<MeasurePoint>,
    mode: MeasureMode,
    place: (Double, Double) -> Offset?
) {
    val screen = points.mapNotNull { point -> place(point.latitude, point.longitude) }
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
    projection: MapProjection,
    originX: Float,
    originY: Float,
    drawWidth: Float,
    drawHeight: Float,
    held: TileZoomHolder
) {
    // Whatever is already decoded goes down first, every frame.
    //
    // This used to run only when everything else had failed, and the reading
    // off a phone showed why that is not enough: a hundred and eighty-seven
    // tiles held, nothing fetching, nothing waiting, and a blank screen. The
    // tiles existed; none of them were where the view had just moved to, and
    // by the time the ones that were had decoded, a frame had gone by with
    // nothing in it. That frame is the flash.
    //
    // Drawn underneath rather than instead, so it is simply the oldest thing
    // on screen and the current level covers it as it arrives. Cheap: the
    // cache is a couple of hundred entries and almost all of them are rejected
    // by an off-screen test costing two multiplications.
    val underlay = runCatching {
        drawHeldTiles(basemap, projection, originX, originY, drawWidth, drawHeight)
    }.getOrDefault(0)

    val drawn = runCatching {
        drawTerrain(basemap, projection, originX, originY, drawWidth, drawHeight, held)
    }.getOrElse {
        basemap.lastFailure = it::class.java.simpleName
        0
    }
    basemap.lastRescue = underlay
    // Recorded on the cache, which is a plain field. Reporting this into
    // Compose state from here would be a write during the drawing phase.
    basemap.noteDraw(drawn > 0 || underlay > 0, System.currentTimeMillis())
    if (drawn > 0 || underlay > 0) return

    // Nothing anywhere. Say so on the map rather than leaving a grey rectangle
    // that is indistinguishable from the app having failed, and say enough
    // that the next report is a fact instead of a description.
    drawEmptyTerrainNotice(
        basemap.diagnostics(),
        buildString {
            if (terrainReason.isNotEmpty()) append("$terrainReason · ")
            append("view ${size.width.roundToInt()}x${size.height.roundToInt()}")
            append(" · origin ${originX.roundToInt()},${originY.roundToInt()}")
            append(" · content ${drawWidth.roundToInt()}x${drawHeight.roundToInt()}")
        }
    )
}

/** Why the terrain could not be worked out, when it could not. */
private var terrainReason: String = ""

/** Returns how many tiles were drawn. */
private fun DrawScope.drawTerrain(
    basemap: BasemapTileCache,
    projection: MapProjection,
    originX: Float,
    originY: Float,
    drawWidth: Float,
    drawHeight: Float,
    held: TileZoomHolder
): Int {
    if (drawWidth <= 0f || drawHeight <= 0f || size.width <= 0f) { terrainReason = "no content size"; return 0 }

    fun screenToGeo(x: Float, y: Float): Pair<Double, Double>? =
        projection.toGeo((x - originX) / drawWidth, (y - originY) / drawHeight)

    // All four corners. A sheet is not obliged to be north-up, and two
    // opposite corners of a rotated view describe a box that need not contain
    // what is actually on screen -- so the tiles fetched would be for ground
    // beside the one being looked at.
    val corners = listOfNotNull(
        screenToGeo(0f, 0f),
        screenToGeo(size.width, 0f),
        screenToGeo(0f, size.height),
        screenToGeo(size.width, size.height)
    )
    if (corners.size < 4) { terrainReason = "corners will not project"; return 0 }

    val north = corners.maxOf { it.first }
    val south = corners.minOf { it.first }
    val west = corners.minOf { it.second }
    val east = corners.maxOf { it.second }
    if (north <= south || east <= west) { terrainReason = "empty view box"; return 0 }
    if (!north.isFinite() || !south.isFinite() || !west.isFinite() || !east.isFinite()) { terrainReason = "view box not finite"; return 0 }

    val centreLatitude = (north + south) / 2.0
    val spanMeters = com.rhecyee.firelinemap.map.MapCoverage.distanceMeters(
        centreLatitude, west, centreLatitude, east
    )
    if (spanMeters <= 0.0 || !spanMeters.isFinite()) { terrainReason = "no span"; return 0 }

    var zoom = BasemapTileCache.zoomForStable(
        latitude = centreLatitude,
        targetMetersPerPixel = spanMeters / size.width,
        previous = held.value
    ).coerceIn(4, 15)

    // Step coarser until the view is a sane number of tiles, rather than
    // giving up on it. Giving up drew nothing at all, not even the fallback.
    while (zoom > 0 && tileCount(north, south, west, east, zoom) > MAX_TILES_PER_FRAME) {
        zoom--
    }
    held.value = zoom
    basemap.lastLevel = zoom
    terrainReason = ""

    basemap.protectBelow(zoom - BASE_LAYER_STEPS)

    // A coarse layer underneath, always.
    //
    // A gesture sweeps through several levels in a second; each one loads a
    // screenful of tiles and pushes the level before it out of memory, so by
    // the time the gesture settles there is nothing left to scale up. Three
    // levels coarser is a sixty-fourth of the tiles -- a handful, cheap to
    // hold and cheap to fetch -- and it is held back from eviction, so there
    // is always something to draw. Soft is not the same as absent.
    val baseZoom = (zoom - BASE_LAYER_STEPS).coerceAtLeast(0)
    var drawn = 0
    if (baseZoom < zoom) {
        drawn += drawTileLayer(
            basemap, projection, baseZoom, north, south, west, east,
            originX, originY, drawWidth, drawHeight
        )
    }
    drawn += drawTileLayer(
        basemap, projection, zoom, north, south, west, east,
        originX, originY, drawWidth, drawHeight
    )
    return drawn
}

/**
 * Draws every decoded tile that lands on screen, at whatever level it is.
 *
 * The last resort. If neither the wanted level nor the coarse layer under it
 * produced anything, the alternative is an empty screen -- and an empty screen
 * is indistinguishable from the app being broken, which is the one outcome
 * this layer must never produce. Anything in memory overlapping the view is
 * better than nothing, however soft, however odd the level it came from. The
 * cache is a couple of hundred entries, so looking through all of it costs
 * nothing.
 *
 * Returns how many tiles were drawn.
 */
private fun DrawScope.drawHeldTiles(
    basemap: BasemapTileCache,
    projection: MapProjection,
    originX: Float,
    originY: Float,
    drawWidth: Float,
    drawHeight: Float
): Int {
    var drawn = 0
    // Coarsest first, so finer detail lands on top of it.
    for ((zoom, x, y) in basemap.cached().sortedBy { it.first }) {
        val bitmap = basemap.peek(zoom, x, y) ?: continue
        val topLeftUnit = projection.toUnit(
            BasemapTileCache.tileNorth(y, zoom), BasemapTileCache.tileWest(x, zoom)
        ) ?: continue
        val bottomRightUnit = projection.toUnit(
            BasemapTileCache.tileNorth(y + 1, zoom), BasemapTileCache.tileWest(x + 1, zoom)
        ) ?: continue

        if (
            drawTileCropped(
                bitmap = bitmap,
                sourceLeft = 0,
                sourceTop = 0,
                sourceSize = bitmap.width,
                left = originX + topLeftUnit.first * drawWidth,
                top = originY + topLeftUnit.second * drawHeight,
                right = originX + bottomRightUnit.first * drawWidth,
                bottom = originY + bottomRightUnit.second * drawHeight
            )
        ) {
            drawn++
        }
    }
    return drawn
}

/** Says why the terrain is empty, on the terrain. */
private fun DrawScope.drawEmptyTerrainNotice(detail: String, geometry: String) {
    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(190, 255, 255, 255)
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 26f
            isAntiAlias = true
            isFakeBoldText = true
        }
        drawText("NO TERRAIN FOR THIS VIEW", size.width / 2f, size.height / 2f - 14f, paint)
        paint.textSize = 20f
        paint.isFakeBoldText = false
        paint.color = android.graphics.Color.argb(150, 255, 255, 255)
        drawText(detail, size.width / 2f, size.height / 2f + 18f, paint)
        paint.textSize = 17f
        drawText(geometry, size.width / 2f, size.height / 2f + 44f, paint)
    }
}

private fun tileCount(
    north: Double,
    south: Double,
    west: Double,
    east: Double,
    zoom: Int
): Long {
    val minX = BasemapTileCache.tileX(west, zoom)
    val maxX = BasemapTileCache.tileX(east, zoom)
    val minY = BasemapTileCache.tileY(north, zoom)
    val maxY = BasemapTileCache.tileY(south, zoom)
    return (maxX - minX + 1).toLong() * (maxY - minY + 1).toLong()
}

/**
 * Draws a tile, cropped to the part of it that is on screen.
 *
 * Tiles get very large. At street zoom the coarse layer's tiles are tens of
 * thousands of pixels across, and a destination rectangle that size leaves the
 * range the renderer works in -- the draw is dropped and nothing appears. The
 * previous version skipped those tiles outright, which meant the layer that
 * exists to cover a gap was itself missing exactly when the gap was widest.
 *
 * Cropping first keeps every coordinate inside the viewport at any zoom, and
 * hands over a few pixels of source instead of a whole tile. No tile is ever
 * too big to draw now; it is only ever partly visible.
 *
 * Returns whether anything was drawn.
 */
private fun DrawScope.drawTileCropped(
    bitmap: android.graphics.Bitmap,
    sourceLeft: Int,
    sourceTop: Int,
    sourceSize: Int,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float
): Boolean {
    val spanX = right - left
    val spanY = bottom - top
    if (spanX <= 0f || spanY <= 0f) return false
    if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) {
        return false
    }
    if (right < 0f || left > size.width || bottom < 0f || top > size.height) return false

    val u0 = ((0f - left) / spanX).coerceIn(0f, 1f)
    val u1 = ((size.width - left) / spanX).coerceIn(0f, 1f)
    val v0 = ((0f - top) / spanY).coerceIn(0f, 1f)
    val v1 = ((size.height - top) / spanY).coerceIn(0f, 1f)
    if (u1 <= u0 || v1 <= v0) return false

    val srcLeft = sourceLeft + (u0 * sourceSize).roundToInt()
    val srcTop = sourceTop + (v0 * sourceSize).roundToInt()
    val srcWidth = ((u1 - u0) * sourceSize).roundToInt().coerceAtLeast(1)
    val srcHeight = ((v1 - v0) * sourceSize).roundToInt().coerceAtLeast(1)
    if (srcLeft >= bitmap.width || srcTop >= bitmap.height) return false

    val dstLeft = (left + u0 * spanX).roundToInt()
    val dstTop = (top + v0 * spanY).roundToInt()
    val dstWidth = ((u1 - u0) * spanX).roundToInt()
    val dstHeight = ((v1 - v0) * spanY).roundToInt()
    if (dstWidth <= 0 || dstHeight <= 0) return false

    drawImage(
        image = bitmap.asImageBitmap(),
        srcOffset = IntOffset(srcLeft, srcTop),
        srcSize = IntSize(
            srcWidth.coerceAtMost(bitmap.width - srcLeft),
            srcHeight.coerceAtMost(bitmap.height - srcTop)
        ),
        dstOffset = IntOffset(dstLeft, dstTop),
        // Overdraw by a pixel: adjacent tiles are positioned independently and
        // rounding leaves hairline seams otherwise.
        dstSize = IntSize(dstWidth + 1, dstHeight + 1)
    )
    return true
}

/** One level of terrain across the view. */
private fun DrawScope.drawTileLayer(
    basemap: BasemapTileCache,
    projection: MapProjection,
    zoom: Int,
    north: Double,
    south: Double,
    west: Double,
    east: Double,
    originX: Float,
    originY: Float,
    drawWidth: Float,
    drawHeight: Float
): Int {
    var drawn = 0
    val minX = BasemapTileCache.tileX(west, zoom)
    val maxX = BasemapTileCache.tileX(east, zoom)
    val minY = BasemapTileCache.tileY(north, zoom)
    val maxY = BasemapTileCache.tileY(south, zoom)
    if ((maxX - minX + 1).toLong() * (maxY - minY + 1).toLong() > MAX_TILES_PER_FRAME) return 0

    for (x in minX..maxX) {
        for (y in minY..maxY) {
            val sample = basemap.sample(zoom, x, y) ?: continue
            val tileNorth = BasemapTileCache.tileNorth(y, zoom)
            val tileSouth = BasemapTileCache.tileNorth(y + 1, zoom)
            val tileWest = BasemapTileCache.tileWest(x, zoom)
            val tileEast = BasemapTileCache.tileWest(x + 1, zoom)

            val topLeftUnit = projection.toUnit(tileNorth, tileWest) ?: continue
            val bottomRightUnit = projection.toUnit(tileSouth, tileEast) ?: continue

            val left = originX + topLeftUnit.first * drawWidth
            val top = originY + topLeftUnit.second * drawHeight
            val right = originX + bottomRightUnit.first * drawWidth
            val bottom = originY + bottomRightUnit.second * drawHeight

            if (
                drawTileCropped(
                    bitmap = sample.bitmap,
                    sourceLeft = sample.sourceLeft,
                    sourceTop = sample.sourceTop,
                    sourceSize = sample.sourceSize,
                    left = left, top = top, right = right, bottom = bottom
                )
            ) {
                drawn++
            }
        }
    }
    return drawn
}

/**
 * Contour lines, cut to the zoom and labelled where they are index lines.
 *
 * Drawn in the brown of a printed quadrangle rather than in any of the colours
 * the incident uses. On a map where a purple line is a track someone drove and
 * a yellow one is a measurement, terrain has to be unmistakably neither.
 *
 * Only index lines carry a number, and only once each. Labelling every line
 * fills the screen with figures nobody reads; labelling every occurrence of an
 * index line does the same on ground that folds back on itself.
 */
private fun DrawScope.drawContours(
    lines: List<com.rhecyee.firelinemap.terrain.ProjectedContour>,
    originX: Float,
    originY: Float,
    drawWidth: Float,
    drawHeight: Float
) {
    // Room off screen so a line entering the view is not clipped at its first
    // point, which would leave a visible notch at the edge.
    val margin = 120f
    val labelled = mutableSetOf<Int>()
    val path = Path()

    for (line in lines) {
        path.reset()
        var onScreen = false
        for (index in line.xs.indices) {
            val x = originX + line.xs[index] * drawWidth
            val y = originY + line.ys[index] * drawHeight
            if (!onScreen && x > -margin && x < size.width + margin &&
                y > -margin && y < size.height + margin
            ) {
                onScreen = true
            }
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (!onScreen) continue

        drawPath(
            path = path,
            color = if (line.isIndex) INDEX_CONTOUR else CONTOUR,
            style = Stroke(width = if (line.isIndex) 2.6f else 1.4f),
            alpha = if (line.isIndex) 0.95f else 0.75f
        )

        if (!line.hasLabel || !labelled.add(line.elevationFeet)) continue
        val at = Offset(
            originX + line.labelX * drawWidth,
            originY + line.labelY * drawHeight
        )
        if (at.x < 40f || at.x > size.width - 40f || at.y < 30f || at.y > size.height - 30f) {
            labelled.remove(line.elevationFeet)
            continue
        }

        rotate(degrees = line.labelDegrees, pivot = at) {
            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = 24f
                    isAntiAlias = true
                    isFakeBoldText = true
                }
                // Punched out of the line rather than laid over it, so the
                // contour is not made ambiguous by its own label.
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = 6f
                paint.color = android.graphics.Color.argb(210, 250, 246, 238)
                drawText("${line.elevationFeet}", at.x, at.y + 8f, paint)

                paint.style = android.graphics.Paint.Style.FILL
                paint.color = INDEX_CONTOUR_ARGB
                drawText("${line.elevationFeet}", at.x, at.y + 8f, paint)
            }
        }
    }
}

private val CONTOUR = Color(0xFF9A6634)
private val INDEX_CONTOUR = Color(0xFF6E3F14)
private const val INDEX_CONTOUR_ARGB = 0xFF6E3F14.toInt()

/**
 * The point a pinch is holding still.
 *
 * Only while two fingers are down. A zoom anchored anywhere but the middle of
 * the screen moves everything except the ground under the fingers, which is
 * right and is also indistinguishable from the map jumping unless the anchor
 * is visible.
 */
private fun DrawScope.drawZoomAnchor(at: Offset) {
    val arm = 26f
    val ring = 13f
    // Cased in dark first so it reads over pale rock and dark timber alike.
    for ((colour, width) in listOf(Color.Black.copy(alpha = 0.55f) to 6f, Color.White to 2.5f)) {
        drawCircle(colour, radius = ring, center = at, style = Stroke(width = width))
        drawLine(colour, Offset(at.x - arm, at.y), Offset(at.x - ring - 3f, at.y), width)
        drawLine(colour, Offset(at.x + ring + 3f, at.y), Offset(at.x + arm, at.y), width)
        drawLine(colour, Offset(at.x, at.y - arm), Offset(at.x, at.y - ring - 3f), width)
        drawLine(colour, Offset(at.x, at.y + ring + 3f), Offset(at.x, at.y + arm), width)
    }
    drawCircle(Color.White, radius = 2.5f, center = at)
}

/**
 * Administered ground, outlined and named.
 *
 * Stroked rather than filled. A fill would either hide the terrain the
 * boundary is meant to be read against or, at an opacity low enough not to,
 * tint the whole screen a colour that means something -- and on this map
 * colour already means the operator, a track, a measurement or a hazard.
 *
 * Each unit is drawn in its agency's colour, the same one the tap dialog uses
 * for its badge, so the outline and the answer are visibly the same thing.
 */
private fun DrawScope.drawBoundaries(
    boundaries: List<com.rhecyee.firelinemap.land.ProjectedBoundary>,
    originX: Float,
    originY: Float,
    drawWidth: Float,
    drawHeight: Float
) {
    val path = Path()
    for (boundary in boundaries) {
        path.reset()
        var onScreen = false
        for (ring in 0 until boundary.ringStarts.size - 1) {
            val from = boundary.ringStarts[ring]
            val until = boundary.ringStarts[ring + 1]
            if (until - from < 3) continue
            for (index in from until until) {
                val x = originX + boundary.xs[index] * drawWidth
                val y = originY + boundary.ys[index] * drawHeight
                if (!onScreen && x > -80f && x < size.width + 80f &&
                    y > -80f && y < size.height + 80f
                ) {
                    onScreen = true
                }
                if (index == from) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
        }
        if (!onScreen) continue

        val colour = Color(boundary.agency.colorArgb)
        // A dark casing first, so the line reads over pale rock and dark
        // timber alike without having to be thick enough to hide either.
        drawPath(path, Color.Black, alpha = 0.35f, style = Stroke(width = 7f))
        drawPath(
            path = path,
            color = colour,
            style = Stroke(
                width = 3.5f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                    floatArrayOf(18f, 9f)
                )
            )
        )

        if (!boundary.hasLabel) continue
        val at = Offset(
            originX + boundary.labelX * drawWidth,
            originY + boundary.labelY * drawHeight
        )
        if (at.x < 60f || at.x > size.width - 60f || at.y < 30f || at.y > size.height - 30f) {
            continue
        }
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint().apply {
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = 25f
                isAntiAlias = true
                isFakeBoldText = true
            }
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 6f
            paint.color = android.graphics.Color.argb(215, 255, 255, 255)
            drawText(boundary.name.take(28), at.x, at.y, paint)
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = boundary.agency.colorArgb
            drawText(boundary.name.take(28), at.x, at.y, paint)
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
    sheetCentre: Offset
) {
    // Pinned to the edge of the screen, not the edge of the content.
    //
    // It used to be clamped to the content rectangle, which is the same thing
    // only while that rectangle is on screen. Zoomed in and panned away it is
    // not, so the marker saying where the operator is got drawn somewhere
    // nobody could see it -- it simply vanished. This is the one thing on the
    // map that must never do that.
    val margin = 26f
    val anchor = Offset(
        target.x.coerceIn(margin, (size.width - margin).coerceAtLeast(margin)),
        target.y.coerceIn(margin, (size.height - margin).coerceAtLeast(margin))
    )
    // Point from the middle of the screen toward the position, for the same
    // reason: the content's centre may be nowhere in view.
    val from = if (
        sheetCentre.x in 0f..size.width && sheetCentre.y in 0f..size.height
    ) {
        sheetCentre
    } else {
        Offset(size.width / 2f, size.height / 2f)
    }
    val angle = Math.toDegrees(
        atan2((target.y - from.y).toDouble(), (target.x - from.x).toDouble())
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

/**
 * The medical button, always on screen.
 *
 * It does not fold away with the rest of the controls and it is never
 * disabled. Whatever else is or is not loaded, this opens.
 */
@androidx.compose.runtime.Composable
fun MedicalButton(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        if (active) "MED ●" else "MED",
        modifier = modifier
            .background(
                if (active) Color(0xFF7F0000) else Color(0xFFD50000),
                RoundedCornerShape(28.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 22.dp, vertical = 15.dp),
        color = Color.White,
        fontWeight = FontWeight.Black,
        fontSize = androidx.compose.ui.unit.TextUnit(17f, androidx.compose.ui.unit.TextUnitType.Sp)
    )
}
