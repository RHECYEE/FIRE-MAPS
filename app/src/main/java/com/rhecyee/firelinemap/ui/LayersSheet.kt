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
import androidx.compose.ui.text.style.TextAlign
import com.rhecyee.firelinemap.data.AppSettings
import com.rhecyee.firelinemap.data.LayerPackageEntity
import com.rhecyee.firelinemap.parcels.CountyRecord

/**
 * The optional layer library.
 *
 * Parcels and topography are both bulky, both licensed in ways the base app is
 * not, and neither belongs to a single incident. They live here, off until
 * switched on, shared across incidents so a county is never held twice.
 */
@Composable
fun LayersSheet(
    importedMaps: List<com.rhecyee.firelinemap.geopdf.ImportedMap>,
    activeMapId: String?,
    onSelectMap: (com.rhecyee.firelinemap.geopdf.ImportedMap) -> Unit,
    onSelectTerrain: () -> Unit,
    topographyOn: Boolean,
    onToggleTopography: (Boolean) -> Unit,
    landOwnershipOn: Boolean,
    onToggleLandOwnership: (Boolean) -> Unit,
    contoursOn: Boolean,
    onToggleContours: (Boolean) -> Unit,
    contourIntervalFeet: Int,
    onContourInterval: (Int) -> Unit,
    packages: List<LayerPackageEntity>,
    onToggle: (LayerPackageEntity, Boolean) -> Unit,
    onOpacity: (LayerPackageEntity, Float) -> Unit,
    onToggleOwner: (LayerPackageEntity, Boolean) -> Unit,
    onRemove: (LayerPackageEntity) -> Unit,
    onImport: () -> Unit,
    onFindCounty: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Layers") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SectionHeading("PRODUCT MAPS")

                // Terrain is a map on its own, not only the fill around a
                // sheet, so it has to be selectable even when sheets exist --
                // the drive in and the search are often better read off topo
                // than off an ops map that stops at the neatline.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectTerrain() }
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (activeMapId == null) "\u25cf" else "\u25cb",
                        color = if (activeMapId == null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = FontWeight.Black
                    )
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(
                            "Terrain only",
                            fontWeight = if (activeMapId == null) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "USGS topo around you \u2014 no incident sheet",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF2E7D32)
                        )
                    }
                }

                if (importedMaps.isEmpty()) {
                    Text(
                        "No incident maps imported yet.",
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
                    subtitle = "USGS contours and shaded relief. Draws under an " +
                        "imported sheet, and is the map on its own when there is " +
                        "none. Tiles are kept once seen.",
                    checked = topographyOn,
                    onCheckedChange = onToggleTopography
                )
                ToggleRow(
                    title = "Contour lines",
                    subtitle = "Drawn from elevation data rather than taken off " +
                        "the basemap, so they lie over an imported sheet as well " +
                        "as over terrain, at whatever band the ground calls for.",
                    checked = contoursOn,
                    onCheckedChange = onToggleContours
                )
                if (contoursOn) {
                    Text(
                        "CONTOUR INTERVAL",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IntervalChips(
                        options = AppSettings.CONTOUR_INTERVALS_FEET,
                        selected = contourIntervalFeet,
                        label = { AppSettings.describeInterval(it) },
                        onSelect = onContourInterval
                    )
                    Text(
                        "Forty feet is what a USGS quad uses through most of the " +
                            "mountain west. Tighten it on gentle ground, open it up " +
                            "on a canyon wall.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ToggleRow(
                    title = "Land ownership",
                    subtitle = "Tap bare ground for the administering agency. " +
                        "Free BLM data; needs a connection.",
                    checked = landOwnershipOn,
                    onCheckedChange = onToggleLandOwnership
                )

                HorizontalDivider()
                SectionHeading("PROPERTY PARCELS")

                if (packages.isEmpty()) {
                    Text(
                        "No parcel packages installed.",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Property parcels and topographic packages are downloaded or " +
                            "imported per county and shared between incidents. Nothing " +
                            "here loads until it is switched on.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                packages.forEach { layer ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(layer.name, fontWeight = FontWeight.Bold)
                                Text(
                                    buildString {
                                        append(
                                            if (layer.kind == "PARCELS") "Parcels"
                                            else "Topographic"
                                        )
                                        append(" · ${layer.format}")
                                        if (layer.sizeBytes > 0) {
                                            append(" · %.0f MB".format(
                                                layer.sizeBytes / 1048576.0
                                            ))
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Switch(
                                checked = layer.enabled,
                                onCheckedChange = { onToggle(layer, it) }
                            )
                        }

                        if (layer.enabled) {
                            Text(
                                "Opacity ${(layer.opacity * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Slider(
                                value = layer.opacity,
                                onValueChange = { onOpacity(layer, it) },
                                valueRange = 0.15f..1f
                            )
                            if (layer.kind == "PARCELS") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Show owner names",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                        Text(
                                            "Off by default. Names carry privacy and " +
                                                "licensing weight that boundaries do not.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = layer.showOwner,
                                        onCheckedChange = { onToggleOwner(layer, it) }
                                    )
                                }
                            }
                            Text(
                                "REMOVE LOCAL COPY",
                                modifier = Modifier
                                    .clickable { onRemove(layer) }
                                    .padding(top = 4.dp),
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                HorizontalDivider()
                Text(
                    "Parcels draw from zoom 12 up: boundaries first, parcel numbers " +
                        "closer in. Below that they are hidden — unreadable and expensive.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onImport) { Text("Import file") } },
        dismissButton = {
            Row {
                TextButton(onClick = onFindCounty) { Text("Find county") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
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

/** County search, so a package can be identified before it can be fetched. */
@Composable
fun CountySearchDialog(
    results: List<CountyRecord>,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (CountyRecord) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find county") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("County, state, or FIPS") },
                    placeholder = { Text("Wallowa") }
                )
                LazyColumn(Modifier.heightIn(max = 300.dp).padding(top = 8.dp)) {
                    items(results) { county ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(county) }
                                .padding(vertical = 9.dp)
                        ) {
                            Text(county.label, fontWeight = FontWeight.Bold)
                            Text(
                                "${county.stateName} · FIPS ${county.fips}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** What a chosen county needs before it can be downloaded. */
@Composable
fun CountyPackageDialog(
    county: CountyRecord,
    onImport: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(county.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${county.stateName} · FIPS ${county.fips}", fontWeight = FontWeight.Bold)
                Text(
                    "No download service is connected yet. Parcel data is licensed per " +
                        "county, and the provider token has to live on a server rather " +
                        "than inside the app, where it could be pulled out of the APK.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "A county GeoPackage bought directly can be imported now and works " +
                        "fully offline.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        confirmButton = { TextButton(onClick = onImport) { Text("Import file") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** What a tapped parcel says about itself. */
@Composable
fun ParcelDetailDialog(
    parcel: com.rhecyee.firelinemap.parcels.Parcel,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(parcel.apn ?: parcel.parcelId.ifBlank { "Parcel" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                parcel.siteAddress?.let { Text(it, fontWeight = FontWeight.Bold) }
                parcel.acres?.let { Text("%.2f acres".format(it)) }
                parcel.landUse?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                parcel.ownerName?.let {
                    Text("Owner: $it", style = MaterialTheme.typography.bodySmall)
                }
                if (parcel.ownerName == null) {
                    Text(
                        "Owner names are off. Turn them on in Layers if the licence " +
                            "allows it.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
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

/** A row of one-tap choices, sized so a gloved thumb can hit one. */
@Composable
private fun <T> IntervalChips(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        options.chunked(3).forEach { row ->
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
                repeat(3 - row.size) {
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
