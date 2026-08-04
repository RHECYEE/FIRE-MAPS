package com.rhecyee.firelinemap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rhecyee.firelinemap.measure.AreaUnit
import com.rhecyee.firelinemap.measure.DistanceUnit
import com.rhecyee.firelinemap.measure.MeasureMode
import com.rhecyee.firelinemap.measure.MeasureResult

/**
 * Live readout while measuring.
 *
 * Slope is shown only where both ends of a leg have an elevation. An absent
 * figure is left absent rather than filled with a guess -- a grade is the kind
 * of number a crew boss would act on.
 */
@Composable
fun MeasurePanel(
    result: MeasureResult,
    mode: MeasureMode,
    distanceUnit: DistanceUnit,
    areaUnit: AreaUnit,
    elevationPending: Boolean,
    onCycleDistanceUnit: () -> Unit,
    onCycleAreaUnit: () -> Unit,
    onToggleMode: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF3A2F00), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (mode == MeasureMode.AREA) "AREA" else "DISTANCE",
                modifier = Modifier.clickable { onToggleMode() },
                color = Color(0xFFFFC400),
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                "  tap to switch",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                "UNDO",
                modifier = Modifier
                    .clickable { onUndo() }
                    .padding(horizontal = 8.dp),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                "CLEAR",
                modifier = Modifier.clickable { onClear() },
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium
            )
        }

        if (result.pointCount < 2) {
            Text(
                if (mode == MeasureMode.AREA) {
                    "Tap three or more points to enclose an area."
                } else {
                    "Tap two points for a line, more to follow a road."
                },
                color = Color.White,
                style = MaterialTheme.typography.bodySmall
            )
            return@Column
        }

        val total = distanceUnit.from(result.totalDistanceMeters)
        Text(
            buildString {
                append(if (mode == MeasureMode.AREA) "Perimeter " else "Total ")
                append(format(total))
                append(' ')
                append(distanceUnit.label)
                append("   ·   ${result.pointCount} points")
            },
            modifier = Modifier.clickable { onCycleDistanceUnit() },
            color = Color.White,
            fontWeight = FontWeight.Bold
        )

        result.areaSquareMeters?.let { squareMeters ->
            Text(
                "Area ${format(areaUnit.from(squareMeters))} ${areaUnit.label}",
                modifier = Modifier.clickable { onCycleAreaUnit() },
                color = Color(0xFFFFC400),
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.titleMedium
            )
        }

        result.segments.lastOrNull()?.let { segment ->
            val leg = StringBuilder()
            leg.append("Last leg ${format(distanceUnit.from(segment.distanceMeters))} ")
            leg.append(distanceUnit.label)
            leg.append("  bearing ${segment.bearingDegrees.toInt()}°")
            segment.slopePercent?.let {
                leg.append("  slope ${"%.1f".format(it)}% (${"%.0f".format(segment.slopeDegrees)}°)")
            }
            Text(
                leg.toString(),
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodySmall
            )
        }

        val gain = result.gainMeters
        val loss = result.lossMeters
        when {
            gain != null && loss != null -> Text(
                "Gain ${format(distanceUnit.from(gain))} ${distanceUnit.label}  ·  " +
                    "Loss ${format(distanceUnit.from(loss))} ${distanceUnit.label}" +
                    (result.overallSlopePercent?.let {
                        "  ·  overall ${"%.1f".format(it)}%"
                    } ?: ""),
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodySmall
            )
            elevationPending -> Text(
                "Looking up ground elevation…",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall
            )
            else -> Text(
                "Slope unavailable — needs a connection for elevation",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun format(value: Double): String = when {
    value >= 1_000 -> "%,.0f".format(value)
    value >= 100 -> "%.0f".format(value)
    value >= 10 -> "%.1f".format(value)
    else -> "%.2f".format(value)
}
