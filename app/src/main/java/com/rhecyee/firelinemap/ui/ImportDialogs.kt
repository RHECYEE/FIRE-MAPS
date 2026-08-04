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
    onDismiss: () -> Unit,
    onSelect: (RemotePdf) -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("${entries.size} products") },
        text = {
            if (busy) {
                CircularProgressIndicator()
            } else {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
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
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") }
        }
    )
}

/** Adjusts how long a stop must last before it ends a track. */
@androidx.compose.runtime.Composable
fun TrackSettingsDialog(
    stopThresholdSeconds: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("End a track after stopping for") },
        text = {
            Column {
                Text(
                    "Travel records itself once you start moving. A stop shorter " +
                        "than this stays part of the same track, so a gate, traffic, " +
                        "or working a patient does not split the trip.",
                    style = MaterialTheme.typography.bodySmall
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
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
