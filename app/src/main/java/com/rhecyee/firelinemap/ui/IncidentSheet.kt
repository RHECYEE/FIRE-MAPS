package com.rhecyee.firelinemap.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rhecyee.firelinemap.data.IncidentEntity
import com.rhecyee.firelinemap.incident.IncidentData
import com.rhecyee.firelinemap.incident.IncidentNaming
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What an incident is holding, for the list to show without opening it. */
data class IncidentTally(
    val markers: Int = 0,
    val tracks: Int = 0,
    val medical: Int = 0,
    val sheets: Int = 0
) {
    val isEmpty: Boolean get() = markers == 0 && tracks == 0 && medical == 0 && sheets == 0

    fun describe(): String {
        if (isEmpty) return "Nothing recorded yet"
        return buildList {
            if (markers > 0) add("$markers pin${plural(markers)}")
            if (tracks > 0) add("$tracks track${plural(tracks)}")
            if (medical > 0) add("$medical medical")
            if (sheets > 0) add("$sheets sheet${plural(sheets)}")
        }.joinToString(" · ")
    }

    private fun plural(count: Int) = if (count == 1) "" else "s"
}

/**
 * Choosing which fire the app is working on.
 *
 * The name is typed, not generated. A fire has a name before it has anything
 * else and it is the name that goes over the radio, so an app that invents one
 * makes the operator carry a label that matches nothing anybody says.
 *
 * Switching is a hard boundary: pins, tracks, medical reports and sheets all
 * belong to the incident they were taken on, and the whole point of a switch is
 * that none of them follow. Terrain does follow, because ridges do not move.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncidentSheet(
    incidents: List<IncidentEntity>,
    activeId: String?,
    tallies: Map<String, IncidentTally>,
    /** Set while a track is recording, which is the one time a switch is refused. */
    blockedReason: String?,
    onStart: (String) -> Unit,
    onSwitch: (IncidentEntity) -> Unit,
    onRename: (IncidentEntity, String) -> Unit,
    onDelete: (IncidentEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var typed by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<IncidentEntity?>(null) }
    var confirmingDelete by remember { mutableStateOf<IncidentEntity?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Incidents", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            if (blockedReason != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF5D2A00))
                ) {
                    Text(
                        blockedReason,
                        modifier = Modifier.padding(12.dp),
                        color = Color(0xFFFFD9B0),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Starting one is the first thing in the sheet because it is the
            // reason the sheet is usually opened.
            Text(
                "START A NEW INCIDENT",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary
            )
            OutlinedTextField(
                value = typed,
                onValueChange = { if (it.length <= IncidentNaming.MAX_LENGTH) typed = it },
                label = { Text("Incident name") },
                placeholder = { Text("Burnt Creek 2026") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (IncidentNaming.clashes(typed, incidents.map { it.name }) && typed.isNotBlank()) {
                Text(
                    "There is already an incident called that. Starting another " +
                        "gives you a second, empty one.",
                    color = Color(0xFFFFB74D),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Button(
                onClick = {
                    onStart(IncidentNaming.clean(typed))
                    typed = ""
                },
                enabled = blockedReason == null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("START — clears the map for a new fire", fontWeight = FontWeight.Bold)
            }

            // Said plainly rather than discovered. An operator who thinks a
            // switch keeps their pins will only find out once they are gone.
            Text(
                "Cleared: " + IncidentData.cleared.joinToString(", ") { it.label.lowercase() } +
                    ".\nKept: " + IncidentData.kept.joinToString(", ") { it.label.lowercase() } + ".",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            Text(
                "SWITCH TO",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary
            )

            LazyColumn(
                modifier = Modifier.heightIn(max = 340.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(incidents, key = { it.id }) { incident ->
                    IncidentRow(
                        incident = incident,
                        active = incident.id == activeId,
                        tally = tallies[incident.id] ?: IncidentTally(),
                        switchable = blockedReason == null,
                        onSwitch = { onSwitch(incident) },
                        onRename = { renaming = incident },
                        onDelete = { confirmingDelete = incident }
                    )
                }
            }
        }
    }

    renaming?.let { incident ->
        RenameDialog(
            incident = incident,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                onRename(incident, name)
                renaming = null
            }
        )
    }

    confirmingDelete?.let { incident ->
        DeleteDialog(
            incident = incident,
            tally = tallies[incident.id] ?: IncidentTally(),
            onDismiss = { confirmingDelete = null },
            onConfirm = {
                onDelete(incident)
                confirmingDelete = null
            }
        )
    }
}

@Composable
private fun IncidentRow(
    incident: IncidentEntity,
    active: Boolean,
    tally: IncidentTally,
    switchable: Boolean,
    onSwitch: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (switchable && !active) Modifier.clickable { onSwitch() } else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (active) {
                Icon(Icons.Default.Check, contentDescription = "Open now")
                Spacer(Modifier.padding(horizontal = 4.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(incident.name, fontWeight = FontWeight.Bold)
                Text(
                    started(incident.createdAt) + " · " + tally.describe(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRename) {
                Icon(Icons.Default.Edit, contentDescription = "Rename ${incident.name}")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete ${incident.name}",
                    tint = Color(0xFFEF5350)
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(
    incident: IncidentEntity,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var typed by remember(incident.id) { mutableStateOf(incident.name) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename incident") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { if (it.length <= IncidentNaming.MAX_LENGTH) typed = it },
                    singleLine = true,
                    label = { Text("Incident name") }
                )
                // Renaming only changes the label. Said so because the delete
                // button is next to it and the two must not be confusable.
                Text(
                    "Only the name changes. Pins, tracks and sheets stay where they are.",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(IncidentNaming.clean(typed)) }) {
                Text("SAVE", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}

@Composable
private fun DeleteDialog(
    incident: IncidentEntity,
    tally: IncidentTally,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    // Typing the name back is deliberate friction. This is the one action in
    // the app that destroys a shift's work with no way back, and it sits an
    // inch from the button that switches incidents.
    var typed by remember(incident.id) { mutableStateOf("") }
    val matches = typed.trim().equals(incident.name.trim(), ignoreCase = true)
    val needsConfirming = !tally.isEmpty

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${incident.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (needsConfirming) {
                        "This deletes ${tally.describe().lowercase()}. It cannot be undone."
                    } else {
                        "Nothing has been recorded on this incident yet."
                    }
                )
                Text(
                    "Downloaded terrain and basemap are kept — they belong to the " +
                        "ground, not the fire.",
                    style = MaterialTheme.typography.labelSmall
                )
                if (needsConfirming) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        label = { Text("Type the name to confirm") }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !needsConfirming || matches
            ) {
                Text("DELETE", fontWeight = FontWeight.Bold, color = Color(0xFFEF5350))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}

/**
 * Naming prompt shown once, when the app has just made an incident by itself.
 *
 * The app has to have somewhere to put a pin from the first second it is open,
 * so it creates one rather than refusing to work until a form is filled in.
 * This is what turns that placeholder into the fire's actual name.
 */
@Composable
fun NameThisIncidentDialog(
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var typed by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What is this incident called?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { if (it.length <= IncidentNaming.MAX_LENGTH) typed = it },
                    singleLine = true,
                    label = { Text("Incident name") },
                    placeholder = { Text("Burnt Creek 2026") }
                )
                Text(
                    "It goes on medical reports and in the radio readout, so use the " +
                        "name dispatch uses. You can change it later.",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(IncidentNaming.clean(typed)) },
                enabled = typed.isNotBlank()
            ) {
                Text("SAVE", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("LATER — call it \"$placeholder\"") }
        }
    )
}

private fun started(millis: Long): String =
    "Started " + SimpleDateFormat("MMM d", Locale.US).format(Date(millis))
