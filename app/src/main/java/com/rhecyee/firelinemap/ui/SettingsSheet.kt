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
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.rhecyee.firelinemap.util.CrashLog
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
/** One incident as the settings list shows it. */
data class IncidentSummary(val id: String, val name: String, val detail: String)


@Composable
fun SettingsSheet(
    incidentName: String,
    onIncidentName: (String) -> Unit,
    incidents: List<IncidentSummary>,
    activeIncidentId: String?,
    onSelectIncident: (String) -> Unit,
    onNewIncident: () -> Unit,
    appVersion: String,
    reporterName: String,
    reporterQualification: String,
    onReporterChange: (String, String) -> Unit,
    stopThresholdSeconds: Int,
    onStopThreshold: (Int) -> Unit,
    segmentAtDropPoints: Boolean,
    onToggleSegmenting: (Boolean) -> Unit,
    segmentAtVehicleStops: Boolean,
    onToggleVehicleSegmenting: (Boolean) -> Unit,
    dropPointsFound: Int,
    autoDownloadRadius: Int,
    onAutoDownloadRadius: (Int) -> Unit,
    wifiOnly: Boolean,
    onWifiOnly: (Boolean) -> Unit,
    cachedTerrainBytes: Long,
    onClearTerrain: () -> Unit,
    onCarCheck: () -> Unit,
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

                if (incidents.size > 1) {
                    Text(
                        "Everything filed from this phone belongs to the incident " +
                            "selected here. Switching changes what the map shows.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                incidents.forEach { summary ->
                    val active = summary.id == activeIncidentId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectIncident(summary.id) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (active) "\u25cf" else "\u25cb",
                            color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Black
                        )
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(
                                summary.name.take(38),
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                summary.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Text(
                    "NEW INCIDENT",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNewIncident() }
                        .padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Black,
                    style = MaterialTheme.typography.labelMedium
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
                Text(
                    "Fireline Map $appVersion",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()
                Heading("LAST CRASH")
                val crashContext = LocalContext.current
                var crash by remember { mutableStateOf(CrashLog.latest(crashContext)) }
                if (crash == null) {
                    Text(
                        "Nothing recorded. If the app closes itself, come back here " +
                            "afterwards: what threw will be waiting, and it is the one " +
                            "thing that says why.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        (crash ?: "").trim().lineSequence().take(14).joinToString("\n"),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(6.dp)
                            )
                            .padding(8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "COPY",
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    val clipboard = crashContext.getSystemService(
                                        ClipboardManager::class.java
                                    )
                                    clipboard?.setPrimaryClip(
                                        ClipData.newPlainText("Fireline crash", crash)
                                    )
                                }
                                .padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            "CLEAR",
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    CrashLog.clear(crashContext)
                                    crash = null
                                }
                                .padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                HorizontalDivider()
                Heading("ANDROID AUTO")
                Text(
                    "An app the car host rejects is simply not listed, with nothing " +
                        "reported anywhere to say why. This runs the checks over that, " +
                        "on this phone, and says which side the problem is on.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "RUN THE CHECK",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCarCheck() }
                        .padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Black
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Split legs when the vehicle stops", fontWeight = FontWeight.Bold)
                        Text(
                            "Android Auto goes away when the engine does, which is what " +
                                "happens when you pull in at a drop point and get out. " +
                                "The leg ends there and the next one starts when you turn " +
                                "the key again, so the time spent standing around is not " +
                                "counted as driving.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = segmentAtVehicleStops,
                        onCheckedChange = onToggleVehicleSegmenting
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
