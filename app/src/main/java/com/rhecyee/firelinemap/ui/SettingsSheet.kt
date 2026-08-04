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
import com.rhecyee.firelinemap.data.PowerMode
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
    terrainDiagnostics: String,
    onClearTerrain: () -> Unit,
    crashReport: String?,
    onCopyCrash: () -> Unit,
    onClearCrash: () -> Unit,
    chromeTimeoutSeconds: Int,
    onChromeTimeout: (Int) -> Unit,
    locationIntervalSeconds: Int,
    onLocationInterval: (Int) -> Unit,
    powerMode: PowerMode,
    onPowerMode: (PowerMode) -> Unit,
    onDismiss: () -> Unit
) {
    val effectiveInterval = AppSettings.effectiveInterval(locationIntervalSeconds, powerMode)
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
                Heading("SCREEN")
                Text(
                    "How long the controls stay up before the map takes the screen " +
                        "back. Never keeps them until they are closed, for planning " +
                        "somewhere the screen is not the only thing to look at.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ChipRow(
                    options = AppSettings.CHROME_TIMEOUT_CHOICES,
                    selected = chromeTimeoutSeconds,
                    label = { AppSettings.describeChromeTimeout(it) },
                    onSelect = onChromeTimeout
                )

                HorizontalDivider()
                Heading("POSITION AND BATTERY")
                Text(
                    "How often a fix is asked for. Faster is a sharper track and a " +
                        "shorter battery.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ChipRow(
                    options = AppSettings.LOCATION_INTERVAL_CHOICES,
                    selected = locationIntervalSeconds,
                    label = { AppSettings.describeInterval(it) },
                    onSelect = onLocationInterval
                )
                ChipRow(
                    options = PowerMode.entries,
                    selected = powerMode,
                    label = { it.label },
                    onSelect = onPowerMode
                )
                Text(
                    powerMode.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (effectiveInterval != locationIntervalSeconds) {
                    // Said out loud rather than silently overriding the chip,
                    // so nobody is left believing they are on a rate they are
                    // not while working out why a track looks blocky.
                    Text(
                        "${powerMode.label} holds this at " +
                            AppSettings.describeInterval(effectiveInterval) + ".",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }

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
                        "already there when there is not. Elevation is fetched first " +
                        "and finished before the basemap picture starts: it is what " +
                        "contours and slope come out of, and it is a fraction of the " +
                        "size, so it is the part worth having when the signal is poor.",
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
                Text(
                    terrainDiagnostics,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

                if (crashReport != null) {
                    HorizontalDivider()
                    Heading("LAST CRASH")
                    Text(
                        "The app died and this is why. Copy it and send it on -- " +
                            "nothing is transmitted anywhere on its own.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        crashReport,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.errorContainer,
                                RoundedCornerShape(6.dp)
                            )
                            .padding(8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            "COPY",
                            modifier = Modifier.clickable { onCopyCrash() },
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            "DISMISS",
                            modifier = Modifier.clickable { onClearCrash() },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
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
