package com.rhecyee.firelinemap.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rhecyee.firelinemap.geopdf.RemotePdf

/** Prompts for a URL: either a product or the folder holding a day's products. */
@Composable
fun UrlImportDialog(
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onFetch: (String) -> Unit
) {
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Import map from URL") },
        text = {
            Column {
                Text(
                    "Paste a link to a PDF, or to the folder holding a day's products.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    singleLine = false,
                    enabled = !busy,
                    label = { Text("Address") }
                )
                if (busy) {
                    CircularProgressIndicator(Modifier.padding(top = 12.dp))
                }
                if (error != null) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onFetch(url) }, enabled = !busy && url.isNotBlank()) {
                Text("Fetch")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        }
    )
}

/** Lets the operator choose which product to pull from a folder listing. */
@Composable
fun RemoteListingDialog(
    entries: List<RemotePdf>,
    busy: Boolean,
    progress: String? = null,
    onDismiss: () -> Unit,
    onImportAll: () -> Unit,
    onSelect: (RemotePdf) -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("${entries.size} products") },
        text = {
            if (busy) {
                Column {
                    CircularProgressIndicator()
                    if (progress != null) {
                        Text(
                            progress,
                            modifier = Modifier.padding(top = 10.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(entries) { entry ->
                        Text(
                            entry.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(entry) }
                                .padding(vertical = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onImportAll, enabled = !busy) {
                Text("Import all ${entries.size}")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") }
        }
    )
}

/** Adjusts how long a stop must last before it ends a track. */
@androidx.compose.runtime.Composable
fun TrackSettingsDialog(
    stopThresholdSeconds: Int,
    segmentAtDropPoints: Boolean,
    dropPointsFound: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
    onToggleSegmenting: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Travel recording") },
        text = {
            Column {
                Text(
                    "Travel records itself once you start moving. A stop longer " +
                        "than this pauses the track; it does not end it, so a shift " +
                        "stays one record with its stops inside it.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Pause after",
                    modifier = Modifier.padding(top = 10.dp),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
                com.rhecyee.firelinemap.location.TrackSettingsStore.CHOICES_SECONDS
                    .forEach { seconds ->
                        val selected = seconds == stopThresholdSeconds
                        Text(
                            (if (selected) "●  " else "○  ") +
                                com.rhecyee.firelinemap.location.TrackSettingsStore
                                    .describe(seconds),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(seconds) }
                                .padding(vertical = 10.dp),
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                Text(
                    "EXPERIMENTAL \u00b7 split travel into legs at drop points read " +
                        "off the sheet by symbol colour. Detected drop points are " +
                        "ringed on the map so you can check them before trusting " +
                        "the split. $dropPointsFound found on this sheet.",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            TextButton(onClick = onToggleSegmenting) {
                Text(if (segmentAtDropPoints) "Legs: ON ($dropPointsFound)" else "Legs: OFF")
            }
        }
    )
}
