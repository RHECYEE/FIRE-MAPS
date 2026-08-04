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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rhecyee.firelinemap.medical.MedicalReport
import com.rhecyee.firelinemap.medical.MedicalResource
import com.rhecyee.firelinemap.medical.Priority
import com.rhecyee.firelinemap.medical.RadioReadout
import com.rhecyee.firelinemap.medical.ReportFormat
import com.rhecyee.firelinemap.medical.TransportMode

/**
 * The medical form.
 *
 * Everything the app already knows is filled in before this opens. What is
 * left is one tap per decision and a microphone for the three things only a
 * person can say. Nothing here needs a keyboard.
 */
@Composable
fun MedicalSheet(
    report: MedicalReport,
    onChange: (MedicalReport) -> Unit,
    onType: (field: DictationField) -> Unit,
    onDictate: (field: DictationField) -> Unit,
    onNameNearby: () -> Unit,
    onReadout: () -> Unit,
    onAddUpdate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MEDICAL", fontWeight = FontWeight.Black, color = Color(0xFFD50000))
                Text(
                    "  ${report.incidentName}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    report.format.label.takeIf { report.format == ReportFormat.MIR } ?: "8-Line",
                    modifier = Modifier.clickable {
                        onChange(
                            report.copy(
                                format = if (report.format == ReportFormat.MIR) {
                                    ReportFormat.EIGHT_LINE
                                } else {
                                    ReportFormat.MIR
                                }
                            )
                        )
                    },
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Section("PRIORITY")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Priority.entries.forEach { priority ->
                        Chip(
                            label = priority.label,
                            selected = report.priority == priority,
                            tint = Color(priority.colorArgb),
                            modifier = Modifier.weight(1f)
                        ) { onChange(report.copy(priority = priority)) }
                    }
                }

                Section("PATIENTS")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..4).forEach { count ->
                        Chip(
                            label = if (count == 4) "4+" else "$count",
                            selected = report.patientCount == count,
                            modifier = Modifier.weight(1f)
                        ) { onChange(report.copy(patientCount = count)) }
                    }
                }

                Section("TRANSPORT")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TransportMode.entries.forEach { mode ->
                        Chip(
                            label = mode.label,
                            selected = report.transport == mode,
                            modifier = Modifier.weight(1f)
                        ) { onChange(report.copy(transport = mode)) }
                    }
                }

                Section("RESOURCES")
                MedicalResource.entries.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { resource ->
                            Chip(
                                label = resource.label,
                                selected = resource in report.resources,
                                modifier = Modifier.weight(1f)
                            ) {
                                onChange(
                                    report.copy(
                                        resources = if (resource in report.resources) {
                                            report.resources - resource
                                        } else {
                                            report.resources + resource
                                        }
                                    )
                                )
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }

                Section("RADIO NAME")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${report.radioName} Medical",
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Bold
                    )
                    Chip("Edit", selected = false) { onType(DictationField.RADIO_NAME) }
                    Chip("Nearby", selected = false) { onNameNearby() }
                }

                Section("DETAIL")
                Entry("Nature of injury", report.natureOfInjury,
                    onType = { onType(DictationField.NATURE) },
                    onDictate = { onDictate(DictationField.NATURE) })
                Entry("Patient assessment", report.patientAssessment,
                    onType = { onType(DictationField.ASSESSMENT) },
                    onDictate = { onDictate(DictationField.ASSESSMENT) })
                Entry("LZ hazards", report.lzHazards,
                    onType = { onType(DictationField.HAZARDS) },
                    onDictate = { onDictate(DictationField.HAZARDS) })

                if (report.updates.isNotEmpty()) {
                    Section("UPDATES")
                    report.updates.forEach {
                        Text("· ${it.text}", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Text(
                    "Auto: ${report.incidentName} · " +
                        com.rhecyee.firelinemap.util.CoordinateFormatter.format(
                            report.latitude, report.longitude,
                            com.rhecyee.firelinemap.util.CoordinateFormat.DDM
                        ) +
                        (report.reporterName?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!report.isReadyToTransmit) {
                    Text(
                        "Still needed: ${report.missing.joinToString(", ")}",
                        color = Color(0xFFF9A825),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onReadout) {
                Text("READ OUT", fontWeight = FontWeight.Black, color = Color(0xFFD50000))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onAddUpdate) { Text("Update") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

enum class DictationField { NATURE, ASSESSMENT, HAZARDS, UPDATE, RADIO_NAME }

/** Typed entry, which is the preferred way in; the microphone is the option. */
@Composable
fun TextEntryDialog(
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val text = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(initial)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text.value,
                onValueChange = { text.value = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** The generated script, ready to read. */
@Composable
fun RadioReadoutDialog(
    report: MedicalReport,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val script = RadioReadout.script(report)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Read this") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    RadioReadout.spoken(report),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text("— or line by line —", style = MaterialTheme.typography.labelSmall)
                RadioReadout.lines(report).forEach { line ->
                    Column {
                        Text(
                            "${line.number}. ${line.heading}",
                            fontWeight = FontWeight.Black,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(line.body, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCopy(script) }) { Text("Copy") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Black,
        style = MaterialTheme.typography.labelSmall
    )
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    onClick: () -> Unit
) {
    val background = when {
        selected && tint != null -> tint
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Text(
        label,
        modifier = modifier
            .background(background, RoundedCornerShape(7.dp))
            .clickable { onClick() }
            .padding(vertical = 11.dp),
        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Black,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        style = MaterialTheme.typography.labelMedium
    )
}

/**
 * A field with typing as the main action and the microphone beside it.
 *
 * Tapping the row types. Speech recognition mishears names and numbers and
 * needs a connection, so it is the alternative rather than the default.
 */
@Composable
private fun Entry(
    label: String,
    value: String?,
    onType: () -> Unit,
    onDictate: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(7.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { onType() }
                .padding(10.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(
                value?.takeIf { it.isNotBlank() } ?: "Tap to type",
                fontWeight = FontWeight.Bold,
                color = if (value.isNullOrBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
        Text(
            "🎤",
            modifier = Modifier
                .clickable { onDictate() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.titleLarge
        )
    }
}

@Composable
private fun Spacer(modifier: Modifier) {
    androidx.compose.foundation.layout.Spacer(modifier)
}
