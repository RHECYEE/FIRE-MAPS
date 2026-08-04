package com.rhecyee.firelinemap.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Timer
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.rhecyee.firelinemap.FirelineApplication
import com.rhecyee.firelinemap.data.IncidentEntity
import com.rhecyee.firelinemap.data.MarkerEntity
import com.rhecyee.firelinemap.resources.ResourceRepository
import com.rhecyee.firelinemap.resources.ResourceSymbol
import com.rhecyee.firelinemap.geopdf.DropPoint
import com.rhecyee.firelinemap.geopdf.DropPointDetector
import com.rhecyee.firelinemap.geopdf.ImportedMap
import com.rhecyee.firelinemap.geopdf.MapDocumentRepository
import com.rhecyee.firelinemap.geopdf.MapUrlImporter
import com.rhecyee.firelinemap.geopdf.PdfKind
import com.rhecyee.firelinemap.geopdf.RemotePdf
import com.rhecyee.firelinemap.geopdf.UrlProbe
import com.rhecyee.firelinemap.location.LocationRepository
import com.rhecyee.firelinemap.location.TrackRecordingState
import com.rhecyee.firelinemap.location.TrackRecordingService
import com.rhecyee.firelinemap.location.SegmentAnchor
import com.rhecyee.firelinemap.location.TrackSettingsStore
import com.rhecyee.firelinemap.map.BasemapTileCache
import com.rhecyee.firelinemap.measure.AreaUnit
import com.rhecyee.firelinemap.measure.DistanceUnit
import com.rhecyee.firelinemap.measure.ElevationService
import com.rhecyee.firelinemap.measure.MeasureMode
import com.rhecyee.firelinemap.measure.MeasurePoint
import com.rhecyee.firelinemap.measure.MeasureSession
import com.rhecyee.firelinemap.map.GeoBounds
import com.rhecyee.firelinemap.map.IncidentMapCoverage
import com.rhecyee.firelinemap.map.MapCoverage
import com.rhecyee.firelinemap.util.CoordinateFormat
import com.rhecyee.firelinemap.util.CoordinateFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirelineApp() {
    val context = LocalContext.current
    val app = context.applicationContext as FirelineApplication
    val scope = rememberCoroutineScope()
    val incidents by app.database.dao().observeIncidents().collectAsState(initial = emptyList())
    val activeIncident = incidents.firstOrNull { it.isActive }

    var coordinateFormat by remember { mutableStateOf(CoordinateFormat.DDM) }

    val trackSettings = remember { TrackSettingsStore(context) }
    var watching by remember { mutableStateOf(false) }
    var stopThreshold by remember { mutableIntStateOf(trackSettings.stopThresholdSeconds) }
    var showTrackSettings by remember { mutableStateOf(false) }
    val liveTrack by TrackRecordingState.live.collectAsState()
    var segmentAtDropPoints by remember { mutableStateOf(trackSettings.segmentAtDropPoints) }
    var dropPoints by remember { mutableStateOf<List<DropPoint>>(emptyList()) }

    val repository = remember { MapDocumentRepository(context) }
    val urlImporter = remember { MapUrlImporter(context.cacheDir) }
    val basemap = remember { BasemapTileCache(context) }
    val elevations = remember { ElevationService() }
    val resources = remember { ResourceRepository(app.database.dao()) }

    var placingResources by remember { mutableStateOf(false) }
    var selectedSymbol by remember { mutableStateOf<ResourceSymbol?>(null) }
    var pendingPlacement by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var inspecting by remember { mutableStateOf<MarkerEntity?>(null) }
    var inspectingReports by remember { mutableIntStateOf(0) }
    var simMode by remember { mutableStateOf(false) }

    val measureSession = remember { MeasureSession() }
    var measuring by remember { mutableStateOf(false) }
    var measureMode by remember { mutableStateOf(MeasureMode.DISTANCE) }
    var measurePoints by remember { mutableStateOf<List<MeasurePoint>>(emptyList()) }
    var distanceUnit by remember { mutableStateOf(DistanceUnit.FEET) }
    var areaUnit by remember { mutableStateOf(AreaUnit.ACRES) }
    var elevationPending by remember { mutableStateOf(false) }
    var activeMap by remember { mutableStateOf<ImportedMap?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pageWidth by remember { mutableIntStateOf(0) }
    var pageHeight by remember { mutableIntStateOf(0) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    var showUrlDialog by remember { mutableStateOf(false) }
    var urlBusy by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var listing by remember { mutableStateOf<List<RemotePdf>>(emptyList()) }

    // A position typed in by hand so registration can be checked away from the
    // incident. Always rendered and labelled differently from a real fix.
    var simulated by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    val locationRepository = remember { LocationRepository(context) }
    val gpsLocation by locationRepository.locations.collectAsState(initial = null)
    var hasLocationPermission by remember { mutableStateOf(locationRepository.hasPermission()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // Start only once the prompt resolves. Calling start() before this
        // point is what left the panel stuck on "waiting for GPS".
        hasLocationPermission = grants.values.any { it }
        if (hasLocationPermission) locationRepository.start()
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val imported = repository.importFrom(uri)
            activeMap = imported ?: activeMap
            statusMessage = if (imported == null) "That file could not be read as a PDF." else null
        }
    }

    LaunchedEffect(Unit) {
        if (!locationRepository.hasPermission()) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            locationRepository.start()
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

    // Permission can also be granted from settings while the app is backgrounded.
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && locationRepository.hasPermission()) {
                hasLocationPermission = true
                locationRepository.start()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(activeMap?.id) {
        val map = activeMap
        if (map == null) {
            bitmap = null
            return@LaunchedEffect
        }
        val rendered = withContext(Dispatchers.IO) {
            MapDocumentRepository.pageSize(map.file) to
                MapDocumentRepository.renderPage(map.file, targetWidth = 2048)
        }
        pageWidth = rendered.first?.first ?: 0
        pageHeight = rendered.first?.second ?: 0
        bitmap = rendered.second

        // Read drop points off the freshly rendered sheet. Provisional: they
        // come from matching symbol colour, so they are drawn on the map for
        // the operator to confirm rather than trusted silently.
        val frameForScan = map.frame
        val rasterised = rendered.second
        dropPoints = if (frameForScan == null || rasterised == null) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) {
                val pixels = IntArray(rasterised.width * rasterised.height)
                rasterised.getPixels(
                    pixels, 0, rasterised.width, 0, 0, rasterised.width, rasterised.height
                )
                DropPointDetector.detect(
                    pixels = pixels,
                    width = rasterised.width,
                    height = rasterised.height,
                    frame = frameForScan,
                    pageWidthPoints = (rendered.first?.first ?: 0).toDouble(),
                    pageHeightPoints = (rendered.first?.second ?: 0).toDouble()
                )
            }
        }
        app.dropPoints = dropPoints.map { SegmentAnchor(it.id, it.latitude, it.longitude) }
    }

    val displayLatitude = simulated?.first ?: gpsLocation?.latitude
    val displayLongitude = simulated?.second ?: gpsLocation?.longitude
    val trackEntities by (activeIncident?.id?.let { app.database.dao().observeTracks(it) }
        ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .collectAsState(initial = emptyList())

    val savedTracks = remember(trackEntities) {
        trackEntities.filter { !it.isRecording }.mapNotNull { entity ->
            val points = parseLineString(entity.geometryGeoJson)
            if (points.size < 2) null else SavedTrack(
                id = entity.id,
                name = entity.name,
                points = points,
                distanceMeters = entity.distanceMeters,
                elapsedSeconds = entity.elapsedSeconds
            )
        }
    }
    var inspectingTrack by remember { mutableStateOf<SavedTrack?>(null) }

    val markers by (activeIncident?.id?.let { app.database.dao().observeMarkers(it) }
        ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .collectAsState(initial = emptyList())

    val frame = activeMap?.frame

    val coverage = remember(activeMap?.id, displayLatitude, displayLongitude) {
        val lat = displayLatitude
        val lon = displayLongitude
        if (lat == null || lon == null) {
            null
        } else {
            val bounds = frame?.geographicBounds()?.let { GeoBounds(it[0], it[1], it[2], it[3]) }
            MapCoverage.resolve(lat, lon, bounds, emptyList())
        }
    }

    pendingPlacement?.let { (lat, lon) ->
        val symbol = selectedSymbol
        if (symbol == null) {
            pendingPlacement = null
        } else {
            PlaceResourceDialog(
                symbol = symbol,
                onDismiss = { pendingPlacement = null },
                onConfirm = { title, note ->
                    val incident = activeIncident?.id
                    pendingPlacement = null
                    if (incident != null) {
                        scope.launch {
                            resources.place(incident, symbol, title, note, lat, lon)
                        }
                    }
                }
            )
        }
    }

    inspectingTrack?.let { track ->
        TrackDetailDialog(
            track = track,
            distanceUnit = distanceUnit,
            onDismiss = { inspectingTrack = null },
            onDelete = {
                val id = track.id
                inspectingTrack = null
                scope.launch { app.database.dao().deleteTrack(id) }
            }
        )
    }

    inspecting?.let { marker ->
        ResourceDetailDialog(
            marker = marker,
            reportCount = inspectingReports,
            coordinates = CoordinateFormatter.format(
                marker.latitude, marker.longitude, coordinateFormat
            ),
            onDismiss = { inspecting = null },
            onDelete = {
                val id = marker.id
                inspecting = null
                scope.launch { resources.delete(id) }
            }
        )
    }

    if (showTrackSettings) {
        TrackSettingsDialog(
            stopThresholdSeconds = stopThreshold,
            segmentAtDropPoints = segmentAtDropPoints,
            dropPointsFound = dropPoints.size,
            onDismiss = { showTrackSettings = false },
            onSelect = { seconds ->
                trackSettings.stopThresholdSeconds = seconds
                stopThreshold = trackSettings.stopThresholdSeconds
            },
            onToggleSegmenting = {
                trackSettings.segmentAtDropPoints = !trackSettings.segmentAtDropPoints
                segmentAtDropPoints = trackSettings.segmentAtDropPoints
            }
        )
    }

    if (showUrlDialog) {
        UrlImportDialog(
            busy = urlBusy,
            error = urlError,
            onDismiss = { showUrlDialog = false; urlError = null },
            onFetch = { address ->
                urlBusy = true
                urlError = null
                scope.launch {
                    when (val result = withContext(Dispatchers.IO) { urlImporter.probe(address) }) {
                        is UrlProbe.Downloaded -> {
                            val imported = withContext(Dispatchers.IO) {
                                repository.importFromFile(result.file, result.name)
                            }
                            urlBusy = false
                            if (imported != null) {
                                activeMap = imported
                                showUrlDialog = false
                                statusMessage = null
                            } else {
                                urlError = "Downloaded, but it could not be read as a PDF."
                            }
                        }
                        is UrlProbe.Listing -> {
                            urlBusy = false
                            showUrlDialog = false
                            listing = result.entries
                        }
                        is UrlProbe.Failed -> {
                            urlBusy = false
                            urlError = result.reason
                        }
                    }
                }
            }
        )
    }

    if (listing.isNotEmpty()) {
        RemoteListingDialog(
            entries = listing,
            busy = urlBusy,
            progress = urlError,
            onDismiss = { listing = emptyList() },
            onImportAll = {
                urlBusy = true
                scope.launch {
                    var imported = 0
                    var failed = 0
                    var last: ImportedMap? = null
                    for ((index, entry) in listing.withIndex()) {
                        urlError = "Importing ${index + 1} of ${listing.size}…"
                        val result = withContext(Dispatchers.IO) { urlImporter.download(entry) }
                        if (result is UrlProbe.Downloaded) {
                            val map = withContext(Dispatchers.IO) {
                                repository.importFromFile(result.file, result.name)
                            }
                            if (map != null) {
                                imported++
                                last = map
                            } else {
                                failed++
                            }
                        } else {
                            failed++
                        }
                    }
                    urlBusy = false
                    urlError = null
                    listing = emptyList()
                    if (last != null) activeMap = last
                    statusMessage = if (failed == 0) {
                        null
                    } else {
                        "Imported $imported, $failed could not be read."
                    }
                }
            },
            onSelect = { entry ->
                urlBusy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { urlImporter.download(entry) }
                    if (result is UrlProbe.Downloaded) {
                        val imported = withContext(Dispatchers.IO) {
                            repository.importFromFile(result.file, result.name)
                        }
                        urlBusy = false
                        if (imported != null) {
                            activeMap = imported
                            listing = emptyList()
                            statusMessage = null
                        } else {
                            statusMessage = "Downloaded, but it could not be read as a PDF."
                        }
                    } else {
                        urlBusy = false
                        statusMessage = (result as? UrlProbe.Failed)?.reason ?: "Download failed."
                    }
                }
            }
        )
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
                    IconButton(onClick = { showTrackSettings = true }) {
                        Icon(Icons.Default.Timer, contentDescription = "Track settings")
                    }
                    IconButton(onClick = { showUrlDialog = true }) {
                        Icon(Icons.Default.Link, contentDescription = "Import from URL")
                    }
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/pdf")) }) {
                        Icon(Icons.Default.FileOpen, contentDescription = "Import from file")
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
                formatted = when {
                    displayLatitude != null && displayLongitude != null ->
                        CoordinateFormatter.format(
                            displayLatitude, displayLongitude, coordinateFormat
                        )
                    !hasLocationPermission -> "Location permission not granted"
                    else -> "Waiting for GPS…"
                },
                accuracy = if (simulated == null) gpsLocation?.accuracy else null,
                elevation = if (simulated == null) gpsLocation?.altitude else null,
                format = coordinateFormat,
                onCycleFormat = {
                    coordinateFormat =
                        if (coordinateFormat == CoordinateFormat.DDM)
                            CoordinateFormat.DECIMAL_DEGREES
                        else CoordinateFormat.DDM
                },
                onCopy = { value ->
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Coordinates", value))
                }
            )

            MapStatusRow(activeMap, statusMessage)

            if (simMode && simulated == null) {
                SimulatedBanner("SIM MODE — tap the map to set a test position")
            }

            if (simulated != null) {
                SimulatedBanner("SIMULATED POSITION — NOT A GPS FIX · Sim off returns to GPS")
            }

            if (coverage?.incident == IncidentMapCoverage.OFF_MAP) {
                val meters = coverage.metersOffMap?.roundToInt() ?: 0
                val bearing = coverage.bearingToMapDegrees?.roundToInt() ?: 0
                val distance = if (meters >= 1000) "%.1f km".format(meters / 1000.0) else "$meters m"
                OffMapBanner("OFF THIS SHEET — $distance, bearing $bearing° back onto it")
            }

            if (watching || liveTrack.recording) {
                TravelPanel(live = liveTrack, armed = watching, unit = distanceUnit)
            }

            if (placingResources) {
                ResourcePalette(
                    symbols = ResourceSymbol.RESOURCES,
                    selected = selectedSymbol,
                    onSelect = { selectedSymbol = it }
                )
            }

            if (measuring) {
                MeasurePanel(
                    result = measureSession.result(),
                    mode = measureMode,
                    distanceUnit = distanceUnit,
                    areaUnit = areaUnit,
                    elevationPending = elevationPending,
                    onCycleDistanceUnit = { distanceUnit = distanceUnit.next() },
                    onCycleAreaUnit = { areaUnit = areaUnit.next() },
                    onSelectMode = { chosen ->
                        measureMode = chosen
                        measureSession.mode = chosen
                        measurePoints = measureSession.currentPoints
                    },
                    onUndo = {
                        measureSession.undo()
                        measurePoints = measureSession.currentPoints
                    },
                    onClear = {
                        measureSession.clear()
                        measurePoints = emptyList()
                    }
                )
            }

            MapCanvas(
                map = activeMap,
                bitmap = bitmap,
                pageWidthPoints = pageWidth,
                pageHeightPoints = pageHeight,
                latitude = displayLatitude,
                longitude = displayLongitude,
                positionIsSimulated = simulated != null,
                dropPoints = if (segmentAtDropPoints) dropPoints else emptyList(),
                basemap = basemap,
                measurePoints = measurePoints,
                measureMode = measureMode,
                markers = markers,
                trackPoints = liveTrack.points,
                savedTracks = savedTracks,
                onTrackTap = { inspectingTrack = it },
                onMarkerTap = { marker ->
                    inspecting = marker
                    scope.launch { inspectingReports = resources.reportCount(marker.id) }
                },
                onMarkerMoved = { marker, lat, lon ->
                    scope.launch { resources.move(marker, lat, lon) }
                },
                onMapTap = { lat, lon ->
                    if (placingResources && selectedSymbol != null) {
                        pendingPlacement = lat to lon
                    } else if (simMode) {
                        simulated = lat to lon
                    } else if (measuring) {
                        measureSession.mode = measureMode
                        measureSession.add(lat, lon)
                        measurePoints = measureSession.currentPoints
                        val index = measureSession.size - 1
                        elevationPending = true
                        scope.launch {
                            val elevation = withContext(Dispatchers.IO) {
                                elevations.elevationMeters(lat, lon)
                            }
                            if (elevation != null) {
                                measureSession.setElevation(index, elevation)
                                measurePoints = measureSession.currentPoints
                            }
                            elevationPending = false
                        }
                    }
                    // With no tool armed a tap does nothing. It used to drop a
                    // simulated position, which silently replaced the live GPS
                    // readout with a fake one from a stray touch.
                },
                modifier = Modifier.weight(1f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolButton(
                    "Measure",
                    Icons.Default.Straighten,
                    Modifier.weight(1f),
                    active = measuring
                ) {
                    measuring = !measuring
                    if (measuring) { placingResources = false; simMode = false }
                    if (!measuring) {
                        measureSession.clear()
                        measurePoints = emptyList()
                    }
                }
                ToolButton(
                    "Resources",
                    Icons.Default.People,
                    Modifier.weight(1f),
                    active = placingResources
                ) {
                    placingResources = !placingResources
                    if (placingResources) {
                        measuring = false
                        simMode = false
                    } else {
                        selectedSymbol = null
                    }
                }
                ToolButton(
                    "Sim",
                    Icons.Default.Draw,
                    Modifier.weight(1f),
                    active = simMode
                ) {
                    simMode = !simMode
                    if (simMode) {
                        measuring = false
                        placingResources = false
                    } else {
                        // Leaving the mode returns the panel to the real fix.
                        simulated = null
                    }
                }
            }

            Button(
                onClick = {
                    val intent = Intent(context, TrackRecordingService::class.java).apply {
                        action = if (watching) TrackRecordingService.ACTION_STOP
                        else TrackRecordingService.ACTION_START
                        putExtra(TrackRecordingService.EXTRA_INCIDENT_ID, activeIncident?.id)
                    }
                    ContextCompat.startForegroundService(context, intent)
                    watching = !watching
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (watching) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (watching) "STOP AUTO RECORDING" else "\u25cf  AUTO RECORD TRAVEL",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        if (watching) {
                            "Records on movement \u00b7 pauses after " +
                                TrackSettingsStore.describe(stopThreshold) + " stopped"
                        } else {
                            "Tracks start themselves when you move"
                        },
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/**
 * Reads a GeoJSON LineString's coordinates.
 *
 * Hand-parsed rather than routed through a JSON library: the shape is fixed,
 * it is written by this app, and org.json is only a stub on the unit test
 * classpath.
 */
private fun parseLineString(geoJson: String): List<Pair<Double, Double>> {
    val open = geoJson.indexOf("[[")
    if (open < 0) return emptyList()
    val close = geoJson.lastIndexOf("]]")
    if (close <= open) return emptyList()
    return Regex("""\[\s*(-?[0-9.eE+-]+)\s*,\s*(-?[0-9.eE+-]+)\s*\]""")
        .findAll(geoJson.substring(open, close + 2))
        .mapNotNull { match ->
            // GeoJSON is longitude first.
            val longitude = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
            val latitude = match.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            latitude to longitude
        }
        .toList()
}

@Composable
private fun MapStatusRow(map: ImportedMap?, message: String?) {
    val text: String
    val colour: Color
    when {
        message != null -> {
            text = message
            colour = Color(0xFFB3261E)
        }
        map == null -> {
            text = "No map loaded — import from a file or a URL"
            colour = Color(0xFF5F6368)
        }
        map.kind == PdfKind.GEOREFERENCED -> {
            val insets = map.document.insetFrames.size
            val extra =
                if (insets > 0) " · $insets inset${if (insets > 1) "s" else ""} ignored" else ""
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
    modifier: Modifier,
    active: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    // An armed tool is coloured, so which mode a tap will land in is visible
    // without reading the label.
    Card(
        modifier = if (onClick != null) modifier.height(64.dp).clickable { onClick() }
        else modifier.height(64.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) Color(0xFFFFC400) else MaterialTheme.colorScheme.surface,
            contentColor = if (active) Color(0xFF1A1400) else MaterialTheme.colorScheme.onSurface
        )
    ) {
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
