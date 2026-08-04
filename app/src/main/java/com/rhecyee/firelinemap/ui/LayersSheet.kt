package com.rhecyee.firelinemap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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

/**
 * What is drawn under and over the sheet.
 *
 * The imported product maps, the terrain the app supplies itself, and who
 * administers the ground. Property parcels used to live here too; they are
 * gone, because no source of parcel geometry exists that this app is allowed
 * to use, and a switch that can never be turned on is worse than no switch.
 */
@Composable
fun LayersSheet(
    importedMaps: List<com.rhecyee.firelinemap.geopdf.ImportedMap>,
    activeMapId: String?,
    onSelectMap: (com.rhecyee.firelinemap.geopdf.ImportedMap) -> Unit,
    topographyOn: Boolean,
    onToggleTopography: (Boolean) -> Unit,
    contoursOn: Boolean,
    onToggleContours: (Boolean) -> Unit,
    contourSummary: String,
    landOwnershipOn: Boolean,
    onToggleLandOwnership: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Layers") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SectionHeading("PRODUCT MAPS")
                if (importedMaps.isEmpty()) {
                    Text(
                        "No maps imported yet.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    importedMaps.forEach { map ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectMap(map) }
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (map.id == activeMapId) "●" else "○",
                                color = if (map.id == activeMapId) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                fontWeight = FontWeight.Black
                            )
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(
                                    map.displayName.take(40),
                                    fontWeight = if (map.id == activeMapId) {
                                        FontWeight.Bold
                                    } else {
                                        FontWeight.Normal
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    map.kindLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (map.document.isGeoreferenced) {
                                        Color(0xFF2E7D32)
                                    } else {
                                        Color(0xFF8A6D00)
                                    }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()
                SectionHeading("TERRAIN AND OWNERSHIP")

                ToggleRow(
                    title = "Topographic basemap",
                    subtitle = "USGS contours and shaded relief beneath the sheet. " +
                        "Tiles are kept once seen.",
                    checked = topographyOn,
                    onCheckedChange = onToggleTopography
                )
                ToggleRow(
                    title = "Contour lines",
                    subtitle = contourSummary,
                    checked = contoursOn,
                    onCheckedChange = onToggleContours
                )
                Text(
                    com.rhecyee.firelinemap.terrain.DemTileCache.ATTRIBUTION,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ToggleRow(
                    title = "Land ownership",
                    subtitle = "Tap bare ground for the administering agency. " +
                        "Free BLM data; needs a connection.",
                    checked = landOwnershipOn,
                    onCheckedChange = onToggleLandOwnership
                )

            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Black,
        style = MaterialTheme.typography.labelSmall
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Whose ground a tapped position is on. */
@Composable
fun LandOwnerDialog(
    owner: com.rhecyee.firelinemap.land.LandOwner?,
    busy: Boolean,
    coordinates: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Land status") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(coordinates, fontWeight = FontWeight.Bold)
                when {
                    busy -> Text("Looking up…")
                    owner == null -> Text(
                        "No answer. This needs a connection; there is no offline " +
                            "ownership package yet."
                    )
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                modifier = Modifier
                                    .background(
                                        Color(owner.agency.colorArgb),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    owner.agency.shortLabel,
                                    color = Color.White,
                                    fontWeight = FontWeight.Black
                                )
                            }
                            Text(
                                owner.summary(),
                                modifier = Modifier.padding(start = 10.dp),
                                fontWeight = FontWeight.Bold
                            )
                        }
                        owner.stateCode?.let {
                            Text("State: $it", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            if (owner.agency.isFederal) "Federal land."
                            else if (owner.isPrivate) "Private land."
                            else "Not federal.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "From the BLM Surface Management Agency layer, which is " +
                                "generalised for national display. Near a boundary it can " +
                                "name the neighbour. Not a land status record.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
