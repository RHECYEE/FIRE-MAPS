package com.rhecyee.firelinemap.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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

        // Keep the sheet overlapping the viewport: at most it may be panned
        // until its edge reaches the edge of the view.
        fun clamp(candidate: Offset, atScale: Float): Offset {
            val drawWidth = image.width * fit * atScale
            val drawHeight = image.height * fit * atScale
            val maxX = ((drawWidth - viewport.width) / 2f).coerceAtLeast(0f)
            val maxY = ((drawHeight - viewport.height) / 2f).coerceAtLeast(0f)
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

            drawImage(
                image = image,
                dstOffset = IntOffset(originX.roundToInt(), originY.roundToInt()),
                dstSize = IntSize(drawWidth.roundToInt(), drawHeight.roundToInt())
            )

            val frame = map.frame
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

        FilledTonalIconButton(
            onClick = { scale = 1f; offset = Offset.Zero },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp)
        ) {
            Icon(Icons.Default.CenterFocusStrong, contentDescription = "Fit sheet to view")
        }
    }
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
