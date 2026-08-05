package com.rhecyee.firelinemap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.rhecyee.firelinemap.data.MarkerEntity
import com.rhecyee.firelinemap.location.Provenance
import com.rhecyee.firelinemap.location.TrackRecord
import com.rhecyee.firelinemap.measure.DistanceUnit
import com.rhecyee.firelinemap.resources.ResourceSymbol
import com.rhecyee.firelinemap.util.CoordinateFormat
import com.rhecyee.firelinemap.util.CoordinateFormatter

/**
 * Everything on this incident, as a list.
 *
 * The map answers "what is near me". This answers "what have we got", which is
 * the question asked when writing a shift ticket or checking that a drop point
 * somebody called in actually got dropped. Finding a pin by panning around
 * looking for it is not an answer to that, and neither is a map with forty
 * markers on it.
 *
 * Tapping anything centres the map on it and closes, because the reason to
 * look something up in a list is almost always to then look at where it is.
 */
@Composable
fun ListSheet(
    incidentName: String,
    markers: List<MarkerEntity>,
    tracks: List<SavedTrack>,
    /** Point times, so a track can say what was recorded and what was inferred. */
    trackRecords: Map<String, TrackRecord>,
    onShowMarker: (MarkerEntity) -> Unit,
    onDeleteMarker: (MarkerEntity) -> Unit,
    onShowTrack: (SavedTrack) -> Unit,
    onDeleteTrack: (SavedTrack) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(incidentName) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Heading("TRACKS · ${tracks.size}")
                if (tracks.isEmpty()) {
                    Text(
                        "No tracks yet. Auto record opens one when you start moving.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                tracks.forEach { track ->
                    TrackRow(
                        track = track,
                        record = trackRecords[track.id],
                        onOpen = { onShowTrack(track) },
                        onDelete = { onDeleteTrack(track) }
                    )
                }

                if (tracks.isNotEmpty()) QualityLegend()

                Heading("PINS · ${markers.size}")
                if (markers.isEmpty()) {
                    Text(
                        "Nothing dropped yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Grouped by what they are, because that is how they get read
                // back: every drop point together, every helispot together.
                markers.groupBy { it.symbol }.forEach { (symbol, group) ->
                    Text(
                        ResourceSymbol.byId(symbol).label.uppercase() + " · ${group.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                    group.forEach { marker ->
                        MarkerRow(
                            marker = marker,
                            onOpen = { onShowMarker(marker) },
                            onDelete = { onDeleteMarker(marker) }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun TrackRow(
    track: SavedTrack,
    record: TrackRecord?,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The swatch ties the row to the line on the map, which on a shift's
        // worth of tracks is the only way to tell which one this is.
        Column(
            modifier = Modifier
                .padding(start = 8.dp)
                .size(width = 5.dp, height = 34.dp)
                .background(Color(track.colourArgb), RoundedCornerShape(3.dp))
        ) {}
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { onOpen() }
                .padding(10.dp)
        ) {
            Text(track.name, fontWeight = FontWeight.Bold)
            Text(
                DistanceUnit.readable(track.distanceMeters) +
                    " · " + clock(track.elapsedSeconds) +
                    " · ${track.points.size} positions",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Said out loud when any of it was only inferred. A distance that
            // includes unobserved ground is an estimate and has to read as one.
            record?.takeIf { it.provenance != Provenance.RECORDED }?.let {
                Text(
                    it.summary().joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFB74D)
                )
            }
        }
        TextButton(onClick = onDelete) { Text("Delete") }
    }
}

@Composable
private fun MarkerRow(
    marker: MarkerEntity,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { onOpen() }
                .padding(10.dp)
        ) {
            Text(marker.title, fontWeight = FontWeight.Bold)
            Text(
                CoordinateFormatter.format(
                    marker.latitude, marker.longitude, CoordinateFormat.DDM
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            marker.note?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TextButton(onClick = onDelete) { Text("Delete") }
    }
}

/** What the line styles on the map mean, shown where tracks are read. */
@Composable
fun QualityLegend() {
    Text(
        "Solid — recorded, a receiver followed this ground.\n" +
            "Dotted — inferred, only the ends are real and the distance is an " +
            "estimate.\nDashed — received from somebody else.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun Heading(title: String) {
    Text(
        title,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Black,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 8.dp)
    )
}

private fun clock(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = seconds % 60
    fun pad(value: Long) = if (value < 10) "0$value" else value.toString()
    return "${pad(hours)}:${pad(minutes)}:${pad(rest)}"
}
