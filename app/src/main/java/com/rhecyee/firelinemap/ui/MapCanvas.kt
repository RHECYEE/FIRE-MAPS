package com.rhecyee.firelinemap.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.rhecyee.firelinemap.geopdf.ImportedMap
import kotlin.math.roundToInt

/**
 * Draws an imported map sheet with the live position on it.
 *
 * A whole-page bitmap rather than a tile pyramid. That is enough to prove
 * registration against a real product and to carry into the field; tiling is
 * a performance change, not a correctness one.
 */
@Composable
fun MapCanvas(
    map: ImportedMap?,
    bitmap: Bitmap?,
    pageWidthPoints: Int,
    pageHeightPoints: Int,
    latitude: Double?,
    longitude: Double?,
    modifier: Modifier = Modifier
) {
    var scale by remember(map?.id) { mutableFloatStateOf(1f) }
    var offset by remember(map?.id) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF2F3A2D), RoundedCornerShape(14.dp)),
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

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(map.id) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 12f)
                        offset += pan
                    }
                }
        ) {
            // Fit the sheet to the view, then apply the operator's pan and zoom.
            val fit = minOf(size.width / image.width, size.height / image.height)
            val drawScale = fit * scale
            val drawWidth = image.width * drawScale
            val drawHeight = image.height * drawScale
            val originX = (size.width - drawWidth) / 2f + offset.x
            val originY = (size.height - drawHeight) / 2f + offset.y

            drawImage(
                image = image,
                dstOffset = IntOffset(originX.roundToInt(), originY.roundToInt()),
                dstSize = IntSize(drawWidth.roundToInt(), drawHeight.roundToInt())
            )

            val frame = map.frame
            if (frame != null && latitude != null && longitude != null &&
                pageWidthPoints > 0 && pageHeightPoints > 0
            ) {
                val page = frame.geoToPage(latitude, longitude)
                if (page != null) {
                    // PDF page space has its origin at the lower left with y
                    // increasing upward; the bitmap's runs the other way.
                    val fx = (page.first / pageWidthPoints).toFloat()
                    val fy = 1f - (page.second / pageHeightPoints).toFloat()
                    if (fx in 0f..1f && fy in 0f..1f) {
                        drawPositionDot(
                            Offset(originX + fx * drawWidth, originY + fy * drawHeight)
                        )
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawPositionDot(center: Offset) {
    drawCircle(Color.White, radius = 15f, center = center)
    drawCircle(Color(0xFF1565C0), radius = 11f, center = center)
    drawCircle(Color.White, radius = 4f, center = center)
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
