package com.rhecyee.firelinemap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rhecyee.firelinemap.geopdf.UtmProjection
import com.rhecyee.firelinemap.map.MapCoverage
import com.rhecyee.firelinemap.measure.DistanceUnit
import com.rhecyee.firelinemap.util.CoordinateFormat
import com.rhecyee.firelinemap.util.CoordinateFormatter
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** A spot the operator asked about. */
data class QueriedPosition(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double? = null,
    val elevationPending: Boolean = false
)

/**
 * What is at a tapped spot, in the forms it gets passed on in.
 *
 * Both coordinate formats at once rather than whichever one the readout
 * happens to be cycled to. Someone reading a position to a helicopter and
 * someone typing it into a dispatch system want different formats, and asking
 * again in the other one means tapping the ground a second time and getting a
 * slightly different answer.
 *
 * Every line copies on its own, and the whole block copies together, because
 * which of those is wanted depends entirely on what it is being pasted into.
 */
@Composable
fun WhereIsThisDialog(
    position: QueriedPosition,
    fromLatitude: Double?,
    fromLongitude: Double?,
    projection: UtmProjection?,
    distanceUnit: DistanceUnit,
    onCopy: (String) -> Unit,
    onKeep: () -> Unit,
    onCycleDistanceUnit: () -> Unit,
    onDismiss: () -> Unit
) {
    val ddm = CoordinateFormatter.format(
        position.latitude, position.longitude, CoordinateFormat.DDM
    )
    val dd = CoordinateFormatter.format(
        position.latitude, position.longitude, CoordinateFormat.DECIMAL_DEGREES
    )
    val utm = projection?.let { utmText(it, position.latitude, position.longitude) }

    val relative = if (fromLatitude != null && fromLongitude != null) {
        val meters = MapCoverage.distanceMeters(
            fromLatitude, fromLongitude, position.latitude, position.longitude
        )
        val bearing = MapCoverage.bearingDegrees(
            fromLatitude, fromLongitude, position.latitude, position.longitude
        )
        "%s %s at %03d°".format(
            formatDistance(distanceUnit.from(meters)),
            distanceUnit.label,
            bearing.roundToInt() % 360
        )
    } else {
        null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Where is this") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CopyRow("DDM", ddm, onCopy)
                CopyRow("DD", dd, onCopy)
                if (utm != null) CopyRow("UTM", utm, onCopy)

                if (relative != null) {
                    Text(
                        "From you: $relative",
                        modifier = Modifier.clickable { onCycleDistanceUnit() },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        "No fix yet, so there is no range and bearing from you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    when {
                        position.elevationMeters != null -> {
                            val feet = position.elevationMeters * 3.280839895
                            "Ground elevation ${feet.roundToInt()} ft " +
                                "(${position.elevationMeters.roundToInt()} m)"
                        }
                        position.elevationPending -> "Looking up ground elevation…"
                        else -> "Ground elevation unavailable — needs a connection"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    if (projection == null) {
                        "Read off the sheet's own georeferencing."
                    } else {
                        "Read off the sheet's own georeferencing, in ${projection.name}."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCopy(
                    buildString {
                        appendLine(ddm)
                        appendLine(dd)
                        if (utm != null) appendLine(utm)
                        if (relative != null) append("From current position: $relative")
                    }.trim()
                )
            }) { Text("Copy all") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onKeep) { Text("Keep as pin") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

@Composable
private fun CopyRow(label: String, value: String, onCopy: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCopy(value) }
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.padding(end = 10.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "COPY",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1B5E20)
        )
    }
}

/**
 * Grid coordinates in the sheet's own projection.
 *
 * The zone comes from the central meridian rather than from the projection's
 * name, which is free text out of a WKT literal and is not something to
 * arithmetic on.
 */
private fun utmText(projection: UtmProjection, latitude: Double, longitude: Double): String {
    val (easting, northing) = projection.forward(latitude, longitude)
    val zone = ((projection.centralMeridian + 180.0) / 6.0).toInt() + 1
    val band = if (latitude >= 0) "N" else "S"
    return "${zone}$band ${easting.roundToLong()}E ${northing.roundToLong()}N"
}

private fun formatDistance(value: Double): String = when {
    value >= 1_000 -> "%,.0f".format(value)
    value >= 100 -> "%.0f".format(value)
    value >= 10 -> "%.1f".format(value)
    else -> "%.2f".format(value)
}
