package com.rhecyee.firelinemap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import com.rhecyee.firelinemap.data.MarkerEntity
import com.rhecyee.firelinemap.resources.ResourceSymbol

/**
 * The symbol palette.
 *
 * A single scrolling row rather than a categorised drawer: one gesture to
 * pick, one to place. Categories are a filing decision, and filing is not
 * what someone is doing when they are trying to mark where an engine is.
 */
@Composable
fun ResourcePalette(
    selected: ResourceSymbol?,
    onSelect: (ResourceSymbol) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1F2430), RoundedCornerShape(10.dp))
            .padding(vertical = 8.dp)
    ) {
        Text(
            if (selected == null) {
                "Pick a symbol, then tap the map"
            } else {
                "${selected.label} — tap the map to place, drag a pin to move it"
            },
            modifier = Modifier.padding(horizontal = 12.dp),
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelMedium
        )
        LazyRow(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)
        ) {
            items(ResourceSymbol.entries) { symbol ->
                val isSelected = symbol == selected
                Column(
                    modifier = Modifier
                        .width(62.dp)
                        .clickable { onSelect(symbol) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(symbol.colorArgb), RoundedCornerShape(7.dp))
                            .border(
                                width = if (isSelected) 3.dp else 0.dp,
                                color = if (isSelected) Color.White else Color.Transparent,
                                shape = RoundedCornerShape(7.dp)
                            )
                            .padding(vertical = 9.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            symbol.glyph,
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Text(
                        symbol.label,
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** Identifier and note, asked for once at placement. */
@Composable
fun PlaceResourceDialog(
    symbol: ResourceSymbol,
    onDismiss: () -> Unit,
    onConfirm: (title: String, note: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Place ${symbol.label}") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Identifier") },
                    placeholder = { Text("E-621, CRW-10, REMS 2") }
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    label = { Text("Note (optional)") },
                    placeholder = { Text("Channel, assignment, status") }
                )
                Text(
                    "A reported position, not live tracking. Drag the pin later " +
                        "to report it somewhere new.",
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title, note) }) { Text("Place") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Details of a placed pin, with the option to remove it. */
@Composable
fun ResourceDetailDialog(
    marker: MarkerEntity,
    reportCount: Int,
    coordinates: String,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    val symbol = ResourceSymbol.byId(marker.symbol)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${marker.title} · ${symbol.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(coordinates, fontWeight = FontWeight.Bold)
                marker.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "Last reported ${relative(marker.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (reportCount > 1) {
                    Text(
                        "$reportCount reported positions on record",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    "Drag the pin on the map to report a new position.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

private fun relative(timeMillis: Long): String {
    val minutes = (System.currentTimeMillis() - timeMillis) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 60 * 24 -> "${minutes / 60} h ago"
        else -> "${minutes / (60 * 24)} d ago"
    }
}
