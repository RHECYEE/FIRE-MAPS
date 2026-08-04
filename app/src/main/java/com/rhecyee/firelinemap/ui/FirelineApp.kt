package com.rhecyee.firelinemap.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rhecyee.firelinemap.FirelineApplication
import com.rhecyee.firelinemap.data.IncidentEntity
import com.rhecyee.firelinemap.geopdf.ImportedMap
import com.rhecyee.firelinemap.geopdf.MapDocumentRepository
import com.rhecyee.firelinemap.geopdf.PdfKind
import com.rhecyee.firelinemap.location.LocationRepository
import com.rhecyee.firelinemap.location.TrackRecordingService
import com.rhecyee.firelinemap.map.GeoBounds
import com.rhecyee.firelinemap.map.IncidentMapCoverage
import com.rhecyee.firelinemap.map.MapCoverage
import com.rhecyee.firelinemap.util.CoordinateFormat
import com.rhecyee.firelinemap.util.CoordinateFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirelineApp() {
    val context = LocalContext.current
    val app = context.applicationContext as FirelineApplication
    val incidents by app.database.dao().observeIncidents().collectAsState(initial = emptyList())
    val activeIncident = incidents.firstOrNull { it.isActive }
    var coordinateFormat by remember { mutableStateOf(CoordinateFormat.DDM) }
    var isRecording by remember { mutableStateOf(false) }

    val repository = remember { MapDocumentRepository(context) }
    var activeMap by remember { mutableStateOf<ImportedMap?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pageWidth by remember { mutableIntStateOf(0) }
    var pageHeight by remember { mutableIntStateOf(0) }
    var importError by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            importError = null
            activeMap = repository.importFrom(uri)
            if (activeMap == null) importError = "That file could not be read as a PDF."
        }
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
        if (incidents.isEmpty()) {
            app.database.dao().upsertIncident(
                IncidentEntity(
                    id = UUID.randomUUID().toString(),
                    name = "Burnt Creek 2026",
                    year = 2026,
                    createdAt = System.currentTimeMillis(),
                    isActive = true
                )
            )
        }
        if (activeMap == null) activeMap = repository.imported().firstOrNull()
    }

    // Rendering a large sheet is slow enough to matter; keep it off the main thread.
    LaunchedEffect(activeMap?.id) {
        val map = activeMap
        if (map == null) {
            bitmap = null
            return@LaunchedEffect
        }
        val rendered = withContext(Dispatchers.IO) {
            val size = MapDocumentRepository.pageSize(map.file)
            size to MapDocumentRepository.renderPage(map.file, targetWidth = 2048)
        }
        pageWidth = rendered.first?.first ?: 0
        pageHeight = rendered.first?.second ?: 0
        bitmap = rendered.second
    }

    val locationRepository = remember { LocationRepository(context) }
    val location by locationRepository.locations.collectAsState(initial = null)
    LaunchedEffect(Unit) { locationRepository.start() }

    val frame = activeMap?.frame
    val coverage = remember(activeMap?.id, location?.latitude, location?.longitude) {
        val fix = location
        if (fix == null) {
            null
        } else {
            val bounds = frame?.geographicBounds()?.let { GeoBounds(it[0], it[1], it[2], it[3]) }
            MapCoverage.resolve(fix.latitude, fix.longitude, bounds, emptyList())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(activeIncident?.name ?: "Fireline Map", fontWeight = FontWeight.Bold)
                        Text("OFFLINE INCIDENT MAP", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/pdf")) }) {
                        Icon(Icons.Default.FileOpen, contentDescription = "Import map")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CoordinateCard(
                formatted = location?.let {
                    CoordinateFormatter.format(it.latitude, it.longitude, coordinateFormat)
                } ?: "Waiting for GPS…",
                accuracy = location?.accuracy,
                elevation = location?.altitude,
                format = coordinateFormat,
                onCycleFormat = {
                    coordinateFormat =
                        if (coordinateFormat == CoordinateFormat.DDM) CoordinateFormat.DECIMAL_DEGREES
                        else CoordinateFormat.DDM
                },
                onCopy = { value ->
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Coordinates", value))
                }
            )

            MapStatusRow(activeMap, importError)

            if (coverage?.incident == IncidentMapCoverage.OFF_MAP) {
                val meters = coverage.metersOffMap?.roundToInt() ?: 0
                val bearing = coverage.bearingToMapDegrees?.roundToInt() ?: 0
                OffMapBanner("OFF INCIDENT MAP — ${meters} m, bearing ${bearing}° back on")
            }

            MapCanvas(
                map = activeMap,
                bitmap = bitmap,
                pageWidthPoints = pageWidth,
                pageHeightPoints = pageHeight,
                latitude = location?.latitude,
                longitude = location?.longitude,
                modifier = Modifier.weight(1f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolButton("Measure", Icons.Default.Straighten, Modifier.weight(1f))
                ToolButton("Drop", Icons.Default.AddLocationAlt, Modifier.weight(1f))
                ToolButton("Resources", Icons.Default.People, Modifier.weight(1f))
                ToolButton("Draw", Icons.Default.Draw, Modifier.weight(1f))
            }

            Button(
                onClick = {
                    val intent = Intent(context, TrackRecordingService::class.java).apply {
                        action = if (isRecording) TrackRecordingService.ACTION_STOP
                        else TrackRecordingService.ACTION_START
                        putExtra(TrackRecordingService.EXTRA_INCIDENT_ID, activeIncident?.id)
                    }
                    ContextCompat.startForegroundService(context, intent)
                    isRecording = !isRecording
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    if (isRecording) "STOP AND SAVE" else "●  START TRAVEL",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * States what the loaded sheet actually is.
 *
 * A product with no georeferencing is labelled as view-only rather than being
 * shown as though a position on it meant something.
 */
@Composable
private fun MapStatusRow(map: ImportedMap?, error: String?) {
    val text: String
    val colour: Color
    when {
        error != null -> {
            text = error
            colour = Color(0xFFB3261E)
        }
        map == null -> {
            text = "No map loaded — use the import button to open a PDF"
            colour = Color(0xFF5F6368)
        }
        map.kind == PdfKind.GEOREFERENCED -> {
            val insets = map.document.insetFrames.size
            val extra = if (insets > 0) " · $insets inset${if (insets > 1) "s" else ""} ignored" else ""
            text = "${map.displayName.take(38)} · ${map.kindLabel}$extra"
            colour = Color(0xFF1B5E20)
        }
        else -> {
            text = "${map.displayName.take(38)} · ${map.kindLabel}"
            colour = Color(0xFF8A6D00)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colour)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun CoordinateCard(
    formatted: String,
    accuracy: Float?,
    elevation: Double?,
    format: CoordinateFormat,
    onCycleFormat: () -> Unit,
    onCopy: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCopy(formatted) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, contentDescription = null)
                Text(
                    formatted,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (format == CoordinateFormat.DDM) "DDM" else "DD",
                    modifier = Modifier.clickable { onCycleFormat() },
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Accuracy: ${accuracy?.let { "±%.0f m".format(it) } ?: "—"}   " +
                    "Elevation: ${elevation?.let { "%.0f m".format(it) } ?: "—"}   Tap card to copy",
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun ToolButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier
) {
    Card(modifier = modifier.height(64.dp)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
        }
    }
}
