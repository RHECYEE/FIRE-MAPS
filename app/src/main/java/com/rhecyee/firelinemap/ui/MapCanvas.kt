package com.rhecyee.firelinemap.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.rhecyee.firelinemap.map.BasemapTileCache
import com.rhecyee.firelinemap.geopdf.DropPoint
import com.rhecyee.firelinemap.geopdf.ImportedMap
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
    onMapTap: ((latitude: Double, longitude: Double) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var scale by remember(map?.id) { mutableFloatStateOf(1f) }
    var offset by remember(map?.id) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

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

        val fit = if (viewport.width > 0 && viewport.height > 0) {
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
            val drawWidth = image.width * fit * atScale
            val drawHeight = image.height * fit * atScale
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
            val drawWidth = image.width * fit * scale
            val drawHeight = image.height * fit * scale
            val originX = (viewport.width - drawWidth) / 2f + offset.x
            val originY = (viewport.height - drawHeight) / 2f + offset.y
            val fx = (point.x - originX) / drawWidth
            val fy = (point.y - originY) / drawHeight
            if (fx !in 0f..1f || fy !in 0f..1f) return null
            // Bitmap y runs downward; PDF page space runs upward.
            return fx * pageWidthPoints.toDouble() to (1f - fy) * pageHeightPoints.toDouble()
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(map.id) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val next = (scale * zoom).coerceIn(1f, 12f)
                        scale = next
                        offset = clamp(offset + pan, next)
                    }
                }
                .pointerInput(map.id, onMapTap) {
                    if (onMapTap != null) {
                        detectTapGestures { point ->
                            val page = screenToPagePoints(point) ?: return@detectTapGestures
                            val geo = map.frame?.pageToGeo(page.first, page.second)
                                ?: return@detectTapGestures
                            onMapTap(geo.latitude, geo.longitude)
                        }
                    }
                }
        ) {
            val drawWidth = image.width * fit * scale
            val drawHeight = image.height * fit * scale
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

            if (fx in 0f..1f && fy in 0f..1f) {
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
                            val drawWidth = image.width * fit * next
                            val drawHeight = image.height * fit * next
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
            val tile = basemap.tile(zoom, x, y) ?: continue
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
                image = tile.asImageBitmap(),
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(width, height)
            )
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
