package com.rhecyee.firelinemap.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.rhecyee.firelinemap.location.LocationRepository
import com.rhecyee.firelinemap.location.TrackRecordingService
import com.rhecyee.firelinemap.util.CoordinateFormat
import com.rhecyee.firelinemap.util.CoordinateFormatter
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirelineApp() {
    val context = LocalContext.current
    val app = context.applicationContext as FirelineApplication
    val incidents by app.database.dao().observeIncidents().collectAsState(initial = emptyList())
    val activeIncident = incidents.firstOrNull { it.isActive }
    var coordinateFormat by remember { mutableStateOf(CoordinateFormat.DDM) }
    var isRecording by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        if (incidents.isEmpty()) {
            val now = System.currentTimeMillis()
            app.database.dao().upsertIncident(
                IncidentEntity(
                    id = UUID.randomUUID().toString(),
                    name = "Burnt Creek 2026",
                    year = 2026,
                    createdAt = now,
                    isActive = true
                )
            )
        }
    }

    val locationRepository = remember { LocationRepository(context) }
    val location by locationRepository.locations.collectAsState(initial = null)

    LaunchedEffect(Unit) { locationRepository.start() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(activeIncident?.name ?: "Fireline Map", fontWeight = FontWeight.Bold)
                        Text("OFFLINE INCIDENT MAP", style = MaterialTheme.typography.labelSmall)
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CoordinateCard(
                formatted = location?.let { CoordinateFormatter.format(it.latitude, it.longitude, coordinateFormat) }
                    ?: "Waiting for GPS…",
                accuracy = location?.accuracy,
                elevation = location?.altitude,
                format = coordinateFormat,
                onCycleFormat = {
                    coordinateFormat = if (coordinateFormat == CoordinateFormat.DDM) CoordinateFormat.DECIMAL_DEGREES else CoordinateFormat.DDM
                },
                onCopy = { value ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Coordinates", value))
                }
            )

            MapPlaceholder(modifier = Modifier.weight(1f))

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
                        action = if (isRecording) TrackRecordingService.ACTION_STOP else TrackRecordingService.ACTION_START
                        putExtra(TrackRecordingService.EXTRA_INCIDENT_ID, activeIncident?.id)
                    }
                    ContextCompat.startForegroundService(context, intent)
                    isRecording = !isRecording
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
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
                "Accuracy: ${accuracy?.let { "±%.0f m".format(it) } ?: "—"}   Elevation: ${elevation?.let { "%.0f m".format(it) } ?: "—"}   Tap card to copy",
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun MapPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF2F3A2D), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("MAP CANVAS", color = Color.White, fontWeight = FontWeight.Black)
            Text("GeoPDF / MapLibre integration next", color = Color.White.copy(alpha = 0.75f))
        }
    }
}

@Composable
private fun ToolButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Card(modifier = modifier.height(68.dp)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
        }
    }
}
