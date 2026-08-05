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
    onNoMap: () -> Unit,
    topographyOn: Boolean,
    onToggleTopography: (Boolean) -> Unit,
    contoursOn: Boolean,
    onToggleContours: (Boolean) -> Unit,
    contourSummary: String,
    contourDetail: com.rhecyee.firelinemap.terrain.ContourDetail,
    onContourDetail: (com.rhecyee.firelinemap.terrain.ContourDetail) -> Unit,
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNoMap() }
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (activeMapId == null) "●" else "○",
                        color = if (activeMapId == null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Black
                    )
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(
                            "None — own terrain only",
                            fontWeight = if (activeMapId == null) FontWeight.Bold
                            else FontWeight.Normal,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Terrain, contours and every tool, with no sheet in the way",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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
                if (contoursOn) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        com.rhecyee.firelinemap.terrain.ContourDetail.entries.forEach { option ->
                            val chosen = option == contourDetail
                            Text(
                                option.label,
                                modifier = Modifier
                                    .weight(1f)
                                    .background(
                                        if (chosen) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable { onContourDetail(option) }
                                    .padding(vertical = 8.dp),
                                color = if (chosen) Color.White
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
                Text(
                    com.rhecyee.firelinemap.terrain.DemTileCache.ATTRIBUTION,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ToggleRow(
                    title = "Land status",
                    subtitle = "Outlines administered ground and names it. Tap anywhere " +
                        "for the agency, the unit and the county. Free federal and " +
                        "census data; needs a connection.",
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
    status: com.rhecyee.firelinemap.land.LandStatus,
    busy: Boolean,
    coordinates: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Land status") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(coordinates, fontWeight = FontWeight.Bold)

                when {
                    busy -> Text("Looking up…")
                    status.isEmpty -> Text(
                        "No answer. This needs a connection; there is no offline " +
                            "ownership package yet."
                    )
                    else -> {
                        val agency = status.agency()
                        if (agency != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(
                                    modifier = Modifier
                                        .background(
                                            Color(agency.colorArgb),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        agency.shortLabel,
                                        color = Color.White,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                                Text(
                                    status.headline().orEmpty(),
                                    modifier = Modifier.padding(start = 10.dp),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // The named unit, which is the thing anyone would
                        // actually say over a radio.
                        status.unit?.let { unit ->
                            HorizontalDivider()
                            SectionHeading("UNIT")
                            Text(unit.name, fontWeight = FontWeight.Bold)
                            val descriptors = listOfNotNull(
                                unit.designationLabel(),
                                unit.localOwner?.takeIf { !it.equals(unit.name, true) },
                                when {
                                    unit.isFederal -> "Federal"
                                    unit.isState -> "State"
                                    else -> null
                                }
                            )
                            if (descriptors.isNotEmpty()) {
                                Text(
                                    descriptors.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(
                                com.rhecyee.firelinemap.land.LandStatusParser
                                    .PROTECTED_ATTRIBUTION,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        status.owner?.let { owner ->
                            HorizontalDivider()
                            SectionHeading("SURFACE")
                            Text(owner.summary(), fontWeight = FontWeight.Bold)
                            Text(
                                if (owner.agency.isFederal) "Federal land."
                                else if (owner.isPrivate) "Private land."
                                else "Not federal.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "BLM Surface Management Agency, generalised for national " +
                                    "display. Near a boundary it can name the neighbour. " +
                                    "Not a land status record.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Who dispatches, and who to ring for mutual aid. The
                        // one part of this that answers on private ground.
                        status.county?.let { county ->
                            HorizontalDivider()
                            SectionHeading("JURISDICTION")
                            Text(county.label, fontWeight = FontWeight.Bold)
                            val extra = listOfNotNull(
                                county.stateName,
                                county.fips?.let { "FIPS $it" }
                            )
                            if (extra.isNotEmpty()) {
                                Text(
                                    extra.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(
                                com.rhecyee.firelinemap.land.LandStatusParser
                                    .COUNTY_ATTRIBUTION,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (status.unit == null && status.owner?.isPrivate != false) {
                            HorizontalDivider()
                            Text(
                                "No public land record here, which usually means private " +
                                    "ground. Deed and parcel records are not available to " +
                                    "this app.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
