package com.rhecyee.firelinemap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rhecyee.firelinemap.data.AppSettings
import com.rhecyee.firelinemap.location.TrackSettingsStore

/**
 * Everything adjustable, in one place.
 *
 * Previously the stop threshold hid behind a timer icon and the reporter's
 * name could only be set by a developer. Both belong here, alongside the
 * things that spend data or storage.
 */
@Composable
fun SettingsSheet(
    incidentName: String,
    onIncidentName: (String) -> Unit,
    reporterName: String,
    reporterQualification: String,
    onReporterChange: (String, String) -> Unit,
    stopThresholdSeconds: Int,
    onStopThreshold: (Int) -> Unit,
    segmentAtDropPoints: Boolean,
    onToggleSegmenting: (Boolean) -> Unit,
    dropPointsFound: Int,
    autoDownloadRadius: Int,
    onAutoDownloadRadius: (Int) -> Unit,
    wifiOnly: Boolean,
    onWifiOnly: (Boolean) -> Unit,
    cachedTerrainBytes: Long,
    onClearTerrain: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Heading("INCIDENT")
                Text(
                    "Names every track, marker and medical report filed from this " +
                        "phone. It arrives seeded and is meant to be changed: the " +
                        "incident you are on is not the one the app was built against.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = incidentName,
                    onValueChange = onIncidentName,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Incident name") },
                    placeholder = { Text("Burnt Creek 2026") }
                )

                HorizontalDivider()
                Heading("WHO IS REPORTING")
                Text(
                    "Filled into medical reports automatically, so nobody types it " +
                        "with a patient on the ground.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = reporterName,
                    onValueChange = { onReporterChange(it, reporterQualification) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Name") }
                )
                OutlinedTextField(
                    value = reporterQualification,
                    onValueChange = { onReporterChange(reporterName, it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Qualification") },
                    placeholder = { Text("EMT, Paramedic, REMS") }
                )

                HorizontalDivider()
                Heading("AUTO RECORDING")
                Text(
                    "A stop longer than this pauses the track. It does not end it, so a " +
                        "shift stays one record with its stops inside it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ChipRow(
                    options = TrackSettingsStore.CHOICES_SECONDS,
                    selected = stopThresholdSeconds,
                    label = { TrackSettingsStore.describe(it) },
                    onSelect = onStopThreshold
                )

                HorizontalDivider()
                Heading("OFFLINE TERRAIN")
                Text(
                    "Keeps terrain around you while there is a connection, so it is " +
                        "already there when there is not.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ChipRow(
                    options = AppSettings.RADIUS_CHOICES,
                    selected = autoDownloadRadius,
                    label = { AppSettings.describeRadius(it) },
                    onSelect = onAutoDownloadRadius
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Wi-Fi only", fontWeight = FontWeight.Bold)
                        Text(
                            "Terrain is the largest thing this app moves. Off means it " +
                                "may use cellular.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = wifiOnly, onCheckedChange = onWifiOnly)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Held: %.0f MB".format(cachedTerrainBytes / 1048576.0),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        "CLEAR",
                        modifier = Modifier.clickable { onClearTerrain() },
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                HorizontalDivider()
                Heading("EXPERIMENTAL")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Split travel at drop points", fontWeight = FontWeight.Bold)
                        Text(
                            "Drop points are read off the sheet by symbol colour, so they " +
                                "are provisional. Detections are ringed on the map to be " +
                                "checked before a split is trusted. $dropPointsFound found " +
                                "on this sheet.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = segmentAtDropPoints, onCheckedChange = onToggleSegmenting)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Black,
        style = MaterialTheme.typography.labelSmall
    )
}

@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        options.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                row.forEach { option ->
                    val isSelected = option == selected
                    Text(
                        label(option),
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { onSelect(option) }
                            .padding(vertical = 9.dp),
                        color = if (isSelected) Color.White
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                repeat(4 - row.size) {
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
