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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import com.rhecyee.firelinemap.CrashLog
import com.rhecyee.firelinemap.FirelineApplication
import com.rhecyee.firelinemap.data.AppSettings
import com.rhecyee.firelinemap.data.IncidentEntity
import com.rhecyee.firelinemap.data.MarkerEntity
import com.rhecyee.firelinemap.resources.ResourceRepository
import com.rhecyee.firelinemap.resources.ResourceSymbol
import com.rhecyee.firelinemap.incident.IncidentNaming
import com.rhecyee.firelinemap.share.ShareIntents
import com.rhecyee.firelinemap.share.TextCodec
import com.rhecyee.firelinemap.share.SharePackage
import com.rhecyee.firelinemap.share.SharePin
import com.rhecyee.firelinemap.share.SharePoint
import com.rhecyee.firelinemap.share.ShareTrack
import com.rhecyee.firelinemap.geopdf.DropPoint
import com.rhecyee.firelinemap.geopdf.DropPointDetector
import com.rhecyee.firelinemap.geopdf.DropPointSettings
import com.rhecyee.firelinemap.geopdf.ImportedMap
import com.rhecyee.firelinemap.geopdf.MapDocumentRepository
import com.rhecyee.firelinemap.geopdf.MapUrlImporter
import com.rhecyee.firelinemap.geopdf.PdfKind
import com.rhecyee.firelinemap.geopdf.RemotePdf
import com.rhecyee.firelinemap.geopdf.UrlProbe
import com.rhecyee.firelinemap.location.LocationRepository
import com.rhecyee.firelinemap.location.TrackGeometry
import com.rhecyee.firelinemap.location.TrackRecordingState
import com.rhecyee.firelinemap.medical.MedicalReport
import com.rhecyee.firelinemap.medical.MedicalRepository
import com.rhecyee.firelinemap.medical.RadioReadout
import com.rhecyee.firelinemap.medical.PlaceNamer
import com.rhecyee.firelinemap.land.LandBoundaryLayer
import com.rhecyee.firelinemap.land.LandOwnershipService
import com.rhecyee.firelinemap.land.LandStatus
import java.io.File
import com.rhecyee.firelinemap.medical.ReporterProfile
import com.rhecyee.firelinemap.location.TrackRecordingService
import com.rhecyee.firelinemap.location.SegmentAnchor
import com.rhecyee.firelinemap.location.TrackSettingsStore
import com.rhecyee.firelinemap.map.BasemapTileCache
import com.rhecyee.firelinemap.map.GroundProjection
import com.rhecyee.firelinemap.map.MapProjection
import com.rhecyee.firelinemap.map.SheetProjection
import com.rhecyee.firelinemap.terrain.ContourLayer
import com.rhecyee.firelinemap.terrain.ContourStatus
import com.rhecyee.firelinemap.terrain.DemTileCache
import com.rhecyee.firelinemap.map.TileMath
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
import com.rhecyee.firelinemap.util.CoordinateParseResult
import com.rhecyee.firelinemap.util.CoordinateParser
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
    // Everything the operator records -- pins, tracks, medical reports and
    // imported sheets -- is filed against this one id. Switching it is the
    // whole of changing incident.
    val incidentId = activeIncident?.id

    var coordinateFormat by remember { mutableStateOf(CoordinateFormat.DDM) }

    val trackSettings = remember { TrackSettingsStore(context) }
    var watching by remember { mutableStateOf(false) }
    var stopThreshold by remember { mutableIntStateOf(trackSettings.stopThresholdSeconds) }
    var showTrackSettings by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }

    // The map runs full screen until it is touched. Everything else is a
    // reason to look away from the ground.
    var chromeVisible by remember { mutableStateOf(false) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    fun touched() {
        chromeVisible = true
        lastInteraction = System.currentTimeMillis()
    }
    var searchQuery by remember { mutableStateOf("") }
    var centreRequest by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val searchResult = remember(searchQuery) { CoordinateParser.parse(searchQuery) }
    val searchCoordinate = (searchResult as? CoordinateParseResult.Success)?.coordinate
    val searchRegion = remember(searchCoordinate) {
        searchCoordinate?.let {
            SearchRegion(it.southLatitude, it.westLongitude, it.northLatitude, it.eastLongitude)
        }
    }
    val liveTrack by TrackRecordingState.live.collectAsState()
    var segmentAtDropPoints by remember { mutableStateOf(trackSettings.segmentAtDropPoints) }
    var dropPoints by remember { mutableStateOf<List<DropPoint>>(emptyList()) }

    val repository = remember { MapDocumentRepository(context) }
    val urlImporter = remember { MapUrlImporter(context.cacheDir) }
    val basemap = remember { BasemapTileCache(context) }
    val contourLayer = remember { ContourLayer(context) }
    val contourSet by contourLayer.contours.collectAsState()
    val contourStatus by contourLayer.status.collectAsState()
    val contourFailure by contourLayer.failure.collectAsState()
    val boundaryLayer = remember { LandBoundaryLayer() }
    val boundaries by boundaryLayer.boundaries.collectAsState()
    // The ground on screen, reported by the canvas once it settles. Drives
    // both the contour cut and what the automatic download reaches for.
    //
    // A value type rather than an array: an array compares by identity, so a
    // view reported again with identical numbers still looked like a change
    // and restarted the cut and the download every time.
    var view by remember { mutableStateOf<MapView?>(null) }
    val elevations = remember { ElevationService() }
    val resources = remember { ResourceRepository(app.database.dao()) }
    val medical = remember { MedicalRepository(app.database.dao()) }
    // Read once at start: if the app died last time, the reason is worth
    // surfacing rather than leaving in a file nobody knows about.
    var crashReport by remember { mutableStateOf(CrashLog.summary(context)) }
    val reporter = remember { ReporterProfile(context) }
    var reporterName by remember { mutableStateOf(reporter.name) }
    var reporterQualification by remember { mutableStateOf(reporter.qualification) }
    val placeNamer = remember { PlaceNamer(context) }
    var typing by remember { mutableStateOf<DictationField?>(null) }

    var showLayers by remember { mutableStateOf(false) }
    var showLegend by remember { mutableStateOf(true) }
    val settings = remember { AppSettings(context) }
    var topographyOn by remember { mutableStateOf(settings.topographyEnabled) }
    var contoursOn by remember { mutableStateOf(settings.contoursEnabled) }
    var contourDetail by remember { mutableStateOf(settings.contourDetail) }
    var landOwnershipOn by remember { mutableStateOf(settings.landOwnershipEnabled) }
    var autoRadius by remember { mutableIntStateOf(settings.autoDownloadRadiusMiles) }
    var wifiOnly by remember { mutableStateOf(settings.autoDownloadWifiOnly) }
    var chromeTimeout by remember { mutableIntStateOf(settings.chromeTimeoutSeconds) }
    var locationInterval by remember { mutableIntStateOf(settings.locationIntervalSeconds) }
    var powerMode by remember { mutableStateOf(settings.powerMode) }
    var cachedTerrain by remember { mutableStateOf(0L) }
    var terrainDiagnostics by remember { mutableStateOf("") }
    var viewReport by remember { mutableStateOf<String?>(null) }
    var importedMaps by remember { mutableStateOf<List<com.rhecyee.firelinemap.geopdf.ImportedMap>>(emptyList()) }
    var showIncidents by remember { mutableStateOf(false) }
    // Held while the operator chooses how to send it, so the package is
    // built once and every size shown in the dialog is the real one.
    var sharing by remember { mutableStateOf<SharePackage?>(null) }
    // Parts pasted in from a message. Held here rather than in the dialog
    // so closing it to go and copy the next part does not lose the ones
    // already in, which is exactly what an operator will do.
    var pasted by remember { mutableStateOf(TextCodec.Assembly()) }
    var incidentTallies by remember { mutableStateOf<Map<String, IncidentTally>>(emptyMap()) }
    // Set when the app made an incident by itself, so it can ask for the real
    // name once rather than leaving a placeholder on every medical report.
    var namingIncident by remember { mutableStateOf(false) }
    var keypadOpen by remember { mutableStateOf(true) }
    val landOwnership = remember { LandOwnershipService() }
    var landStatus by remember { mutableStateOf(LandStatus()) }
    var landLookupAt by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var landLookupBusy by remember { mutableStateOf(false) }

    var medicalReport by remember { mutableStateOf<MedicalReport?>(null) }
    var showReadout by remember { mutableStateOf(false) }
    var dictating by remember { mutableStateOf<DictationField?>(null) }

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

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { activityResult ->
        val spoken = activityResult.data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
        val field = dictating
        dictating = null
        val current = medicalReport
        if (spoken != null && current != null) {
            medicalReport = when (field) {
                DictationField.NATURE -> current.copy(natureOfInjury = spoken)
                DictationField.ASSESSMENT -> current.copy(patientAssessment = spoken)
                DictationField.HAZARDS -> current.copy(lzHazards = spoken)
                DictationField.RADIO_NAME -> current.copy(radioNameOverride = spoken)
                DictationField.UPDATE -> {
                    scope.launch { medical.addUpdate(current.id, spoken) }
                    current.copy(
                        updates = current.updates +
                            com.rhecyee.firelinemap.medical.ReportUpdate(
                                System.currentTimeMillis(), spoken
                            )
                    )
                }
                null -> current
            }
            medicalReport?.let { updated -> scope.launch { medical.save(updated) } }
        }
    }

    fun dictate(field: DictationField) {
        dictating = field
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(
                android.speech.RecognizerIntent.EXTRA_PROMPT,
                when (field) {
                    DictationField.NATURE -> "Nature of injury"
                    DictationField.ASSESSMENT -> "Patient assessment"
                    DictationField.HAZARDS -> "LZ hazards"
                    DictationField.UPDATE -> "Update"
                    DictationField.RADIO_NAME -> "Radio name"
                }
            )
        }
        runCatching { speechLauncher.launch(intent) }.onFailure {
            statusMessage = "No speech recogniser on this device."
            dictating = null
        }
    }

    /**
     * Importing, off the main thread and several at a time.
     *
     * It used to run in this callback directly, which is the main thread.
     * Reading a georeferenced PDF means inflating its object streams and
     * scanning every viewport in it -- seconds of work for a large product
     * map, during which nothing on screen answers. That is what was reporting
     * the app as unresponsive, and it kept doing so after the app was closed
     * because the work carried on.
     *
     * Several at a time because a crew arrives with a folder of sheets, not
     * one, and picking them individually through a file browser with gloves on
     * is its own kind of failure.
     */
    var importing by remember { mutableStateOf(0) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        // Sheets are filed against the incident that is open. Without an
        // incident there is nowhere to file them, and silently dropping the
        // import is worse than saying so.
        val importInto = activeIncident?.id
        if (importInto == null) {
            statusMessage = "Start an incident before importing sheets."
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            importing = uris.size
            var lastGood: com.rhecyee.firelinemap.geopdf.ImportedMap? = null
            var failed = 0
            for (uri in uris) {
                // One at a time. Each holds a copy of the file and its parsed
                // structure; several at once is how the memory ran out.
                val imported = withContext(Dispatchers.IO) {
                    runCatching { repository.importFrom(uri, importInto) }.getOrNull()
                }
                if (imported != null) lastGood = imported else failed++
                importing--
            }
            importing = 0
            importedMaps = withContext(Dispatchers.IO) { repository.imported(importInto) }
            if (lastGood != null) activeMap = lastGood
            statusMessage = when {
                failed == 0 -> null
                lastGood == null && failed == 1 -> "That file could not be read as a PDF."
                lastGood == null -> "None of those $failed files could be read as PDFs."
                else -> "$failed of ${uris.size} could not be read as PDFs."
            }
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
        // An incident has to exist before anything can be recorded against it,
        // so one is made rather than the app refusing to work until a form is
        // filled in. It is named for today and the operator is asked once for
        // the real name -- which is the name dispatch uses, and the one that
        // goes on a medical report.
        val existing = app.database.dao().allIncidents()
        val incidentId = if (existing.isEmpty()) {
            val now = System.currentTimeMillis()
            val fresh = IncidentEntity(
                id = UUID.randomUUID().toString(),
                name = IncidentNaming.placeholder(now),
                year = IncidentNaming.yearOf("", now),
                createdAt = now,
                isActive = true
            )
            app.database.dao().startIncident(fresh)
            namingIncident = true
            fresh.id
        } else {
            (existing.firstOrNull { it.isActive } ?: existing.first()).also {
                if (!it.isActive) app.database.dao().setActiveIncident(it.id)
            }.id
        }

        // Sheets imported before they were held per incident live loose in the
        // maps folder. Moving them into whatever is open now means an operator
        // who updates mid-season still finds their maps.
        withContext(Dispatchers.IO) { repository.adoptLooseSheets(incidentId) }
        importedMaps = withContext(Dispatchers.IO) { repository.imported(incidentId) }
        if (activeMap == null) activeMap = importedMaps.firstOrNull()
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

    LaunchedEffect(activeMap?.id, activeIncident?.id) {
        val incidentId = activeIncident?.id
        importedMaps = if (incidentId == null) emptyList()
        else withContext(Dispatchers.IO) { repository.imported(incidentId) }
    }

    // Counts for the incident list, read when the sheet opens rather than kept
    // in step by hand -- so a row that says "3 pins" is saying what is actually
    // in the database, and a stale count never talks somebody into deleting the
    // wrong incident.
    LaunchedEffect(showIncidents, incidents.size) {
        if (!showIncidents) return@LaunchedEffect
        incidentTallies = withContext(Dispatchers.IO) {
            incidents.associate { incident ->
                incident.id to IncidentTally(
                    markers = app.database.dao().markerCount(incident.id),
                    tracks = app.database.dao().trackCount(incident.id),
                    medical = app.database.dao().medicalReportCount(incident.id),
                    sheets = repository.sheetCount(incident.id)
                )
            }
        }
    }

    LaunchedEffect(activeMap?.id) {
        val map = activeMap
        if (map == null) {
            bitmap = null
            dropPoints = emptyList()
            return@LaunchedEffect
        }
        // Dropped before the next one is rendered. A page raster is twenty odd
        // megabytes; holding the old one while building the new doubles the
        // peak for no reason, and importing several sheets in a row is exactly
        // when that matters.
        bitmap = null

        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                MapDocumentRepository.pageSize(map.file) to
                    MapDocumentRepository.renderPage(map.file, targetWidth = 2048)
            }
        }
        val rendered = outcome.getOrNull()
        if (rendered?.second == null) {
            // Including running out of memory, which is a message rather than
            // a reason to take the app down with the incident data in it.
            statusMessage = if (outcome.exceptionOrNull() is OutOfMemoryError) {
                "Not enough memory to open that sheet. Close other apps and try again."
            } else {
                "That sheet could not be rendered."
            }
            return@LaunchedEffect
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
            runCatching {
                withContext(Dispatchers.Default) {
                    // Sampled down rather than read whole. A full page is an
                    // integer per pixel on top of the bitmap it came from.
                    val stride = DROP_POINT_SCAN_STRIDE
                    val scanWidth = rasterised.width / stride
                    val scanHeight = rasterised.height / stride
                    if (scanWidth < 8 || scanHeight < 8) {
                        emptyList()
                    } else {
                        val pixels = IntArray(scanWidth * scanHeight)
                        val row = IntArray(rasterised.width)
                        for (y in 0 until scanHeight) {
                            rasterised.getPixels(
                                row, 0, rasterised.width, 0, y * stride, rasterised.width, 1
                            )
                            for (x in 0 until scanWidth) {
                                pixels[y * scanWidth + x] = row[x * stride]
                            }
                        }
                        DropPointDetector.detect(
                            pixels = pixels,
                            width = scanWidth,
                            height = scanHeight,
                            frame = frameForScan,
                            pageWidthPoints = (rendered.first?.first ?: 0).toDouble(),
                            pageHeightPoints = (rendered.first?.second ?: 0).toDouble(),
                            settings = with(DropPointDetector) {
                                DropPointSettings().scaledBy(stride)
                            }
                        )
                    }
                }
            }.getOrDefault(emptyList())
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

    /**
     * The incident's tracks and pins, ready to hand to somebody.
     *
     * Everything on the incident rather than a selection. A shift's worth is a
     * few tens of kilobytes, choosing takes longer than sending, and the thing
     * that actually goes wrong is sending half of it and not knowing.
     */
    fun sharePackage(): SharePackage = SharePackage(
        incidentName = activeIncident?.name ?: "Fireline Map",
        createdAt = System.currentTimeMillis(),
        author = reporterName.ifBlank { null },
        tracks = trackEntities.filter { !it.isRecording }.mapNotNull { entity ->
            val points = parseLineString(entity.geometryGeoJson)
            if (points.size < 2) return@mapNotNull null
            ShareTrack(
                id = entity.id,
                name = entity.name,
                points = points.map { SharePoint(it.first, it.second) },
                startedAt = entity.startedAt,
                endedAt = entity.endedAt,
                distanceMeters = entity.distanceMeters,
                activityType = entity.activityType,
                note = entity.note
            )
        },
        pins = markers.map { marker ->
            SharePin(
                id = marker.id,
                title = marker.title,
                latitude = marker.latitude,
                longitude = marker.longitude,
                symbolId = marker.symbol,
                note = marker.note,
                status = marker.status,
                createdAt = marker.createdAt
            )
        }
    )

    // A report opened before the receiver was ready takes the first fix it
    // sees, so nobody has to remember to come back and fill it in.
    LaunchedEffect(medicalReport?.id, gpsLocation) {
        val report = medicalReport ?: return@LaunchedEffect
        val fix = gpsLocation ?: return@LaunchedEffect
        if (!report.hasPosition) {
            val updated = report.copy(
                latitude = fix.latitude,
                longitude = fix.longitude,
                hasPosition = true,
                elevationMeters = fix.altitude,
                accuracyMeters = fix.accuracy
            )
            medicalReport = updated
            medical.save(updated)
            resources.place(
                updated.incidentId, ResourceSymbol.MEDICAL_INCIDENT,
                "MEDICAL", null, fix.latitude, fix.longitude
            )
        }
    }

    /**
     * Opens a medical report from wherever things stand.
     *
     * Deliberately refuses nothing. No incident, no map, and no fix are all
     * survivable: an incident is created if none exists, the map name is
     * simply absent, and a report opened before the receiver has a fix is
     * marked as having no position rather than being blocked. Waiting on any
     * of that with a patient on the ground is not acceptable.
     */
    fun openMedicalReport() {
        val lat = displayLatitude
        val lon = displayLongitude
        val id = UUID.randomUUID().toString()
        scope.launch {
            val incident = activeIncident ?: run {
                val now = System.currentTimeMillis()
                IncidentEntity(
                    id = UUID.randomUUID().toString(),
                    name = IncidentNaming.placeholder(now),
                    year = IncidentNaming.yearOf("", now),
                    createdAt = now,
                    isActive = true
                ).also {
                    app.database.dao().startIncident(it)
                    // The name goes straight onto the report and into the radio
                    // readout, so it gets asked for -- but after the pin is
                    // down, never before.
                    namingIncident = true
                }
            }

            val report = MedicalReport(
                id = id,
                incidentId = incident.id,
                createdAt = System.currentTimeMillis(),
                incidentName = incident.name,
                mapName = activeMap?.displayName,
                latitude = lat ?: 0.0,
                longitude = lon ?: 0.0,
                hasPosition = lat != null && lon != null,
                elevationMeters = gpsLocation?.altitude,
                accuracyMeters = gpsLocation?.accuracy,
                reporterName = reporter.name.ifBlank { null },
                reporterQualification = reporter.qualification.ifBlank { null },
                incidentCommander = reporter.name.ifBlank { null },
                trackId = if (liveTrack.recording) "recording" else null
            )
            medicalReport = report
            medical.save(report)
            if (lat != null && lon != null) {
                resources.place(
                    incident.id, ResourceSymbol.MEDICAL_INCIDENT,
                    "MEDICAL", null, lat, lon
                )
            }
        }
    }

    /**
     * What the canvas draws through.
     *
     * An imported sheet when there is one, and the app's own ground when there
     * is not. The second case is the one this tool exists for: on day one of a
     * fire there is no product yet, and that is the day somebody most needs to
     * know where they are. Terrain, contours, position, tracks, pins,
     * measurements and the coordinate search all work either way -- the only
     * things that need a sheet are the sheet itself and the drop points read
     * off it.
     */
    var groundAnchor by remember { mutableStateOf(settings.lastAnchor) }
    // How much ground the view covers when there is no sheet. Stepped in
    // factors of four as the operator zooms past either end, which is what
    // lets the range run from a couple of blocks to the whole country without
    // the drawn content growing to a size a float cannot place things in.
    var groundSpan by remember { mutableStateOf(GroundProjection.DEFAULT_SPAN_METERS) }
    var groundScale by remember { mutableStateOf(1f) }
    // Re-anchored on what is being looked at, not on where the operator is.
    //
    // It used to follow the position: walk far enough from the anchor and the
    // projection was re-cut around you, which resets the pan and drags the view
    // back onto your own marker. On a small span that is a few hundred metres
    // of walking, so a deliberate look at the far end of a division kept being
    // hauled back. Where the operator is standing is not a reason to move the
    // map.
    //
    // What this is actually for is keeping the projection's centre near the
    // ground being drawn, so the numbers stay well inside a float's precision.
    // Anchoring on the view's own centre does that and is invisible: the pan
    // becomes zero because the centre is now the anchor, and the same ground
    // stays on screen.
    LaunchedEffect(view) {
        val here = view ?: return@LaunchedEffect
        if (activeMap != null) return@LaunchedEffect
        val centreLatitude = (here.north + here.south) / 2.0
        val centreLongitude = (here.west + here.east) / 2.0
        val current = groundAnchor
        val far = current == null ||
            MapCoverage.distanceMeters(current.first, current.second, centreLatitude, centreLongitude) >
            groundSpan * GroundProjection.REANCHOR_FRACTION
        if (far) {
            groundAnchor = centreLatitude to centreLongitude
            settings.lastAnchor = centreLatitude to centreLongitude
        }
    }

    // The anchor is also where the map opens next time, so it follows the
    // operator while there is no view to speak of yet.
    LaunchedEffect(displayLatitude, displayLongitude) {
        val lat = displayLatitude ?: return@LaunchedEffect
        val lon = displayLongitude ?: return@LaunchedEffect
        if (groundAnchor == null) {
            groundAnchor = lat to lon
            settings.lastAnchor = lat to lon
        }
    }


    val projection = remember(
        activeMap?.id, pageWidth, pageHeight, bitmap, groundAnchor, groundSpan
    ) {
        val frame = activeMap?.frame
        val sheet = bitmap
        if (frame != null && sheet != null && pageWidth > 0 && pageHeight > 0) {
            SheetProjection(
                frame = frame,
                pageWidthPoints = pageWidth,
                pageHeightPoints = pageHeight,
                contentWidth = sheet.width.toFloat(),
                contentHeight = sheet.height.toFloat()
            )
        } else {
            groundAnchor?.let { (lat, lon) -> GroundProjection(lat, lon, groundSpan) }
        }
    }

    // Contours are re-cut whenever the view settles somewhere new. Cheap when
    // nothing has changed -- the layer recognises a view it has already
    // answered -- so this can key on every pan without re-doing the work.
    // Outlines for whatever is on screen, on the same settle as the contours.
    LaunchedEffect(view, landOwnershipOn, projection) {
        val here = view
        val where = projection
        if (!landOwnershipOn || here == null || where == null) {
            boundaryLayer.clear()
            return@LaunchedEffect
        }
        boundaryLayer.request(here.north, here.south, here.west, here.east, where)
    }

    LaunchedEffect(view, contoursOn, projection, contourDetail) {
        val here = view
        val where = projection
        if (!contoursOn || here == null || where == null) return@LaunchedEffect
        contourLayer.request(
            here.north, here.south, here.west, here.east, here.zoom, where, contourDetail
        )
    }

    // Elevation for what is on screen, fetched ahead of anything else.
    // Contours are the thing a crew reads terrain from, and a basemap picture
    // arriving first is no use to someone working out whether the slope above
    // them goes anywhere.
    LaunchedEffect(view, contoursOn, wifiOnly, projection) {
        val here = view
        val where = projection
        if (!contoursOn || here == null || where == null) return@LaunchedEffect
        // Gated on the connection rather than on the preload radius. The
        // radius is about keeping ground you are not looking at; this is the
        // ground on the screen, and having to set a preference before the
        // layer does anything is how a feature gets a reputation for not
        // working.
        if (!settings.mayFetchForView()) return@LaunchedEffect
        val fetched = withContext(Dispatchers.IO) {
            contourLayer.download(here.north, here.south, here.west, here.east, here.zoom)
        }
        if (fetched > 0) {
            contourLayer.request(
                here.north, here.south, here.west, here.east, here.zoom, where, contourDetail
            )
        }
    }

    // Terrain is kept around the operator while there is a connection, so it
    // is already on the device when there is not. Bounded by tile count as
    // well as radius: the point is to be useful, not to fill the phone.
    LaunchedEffect(autoRadius, wifiOnly, topographyOn, contoursOn, displayLatitude != null) {
        val lat = displayLatitude
        val lon = displayLongitude
        if (lat == null || lon == null) return@LaunchedEffect
        if (!topographyOn && !contoursOn) return@LaunchedEffect
        if (!settings.mayAutoDownload()) return@LaunchedEffect

        val area = TileMath.around(lat, lon, autoRadius * 1609.344)

        withContext(Dispatchers.IO) {
            // Elevation before imagery, and finished before imagery starts.
            //
            // The two compete for the same connection, and on a truck stop's
            // worth of signal only one of them is going to complete. Elevation
            // is the one worth having: it is what contours, slope and aspect
            // all come out of, and it is a twentieth the size. A basemap
            // picture with no elevation behind it can be looked at; elevation
            // with no picture can still be worked from.
            if (contoursOn) {
                for (zoom in ContourLayer.MIN_DEM_ZOOM..ContourLayer.MAX_DEM_ZOOM) {
                    contourLayer.download(
                        north = area.north,
                        south = area.south,
                        west = area.west,
                        east = area.east,
                        zoom = zoom,
                        limit = DEM_TILES_PER_LEVEL
                    )
                }
            }

            if (!topographyOn) return@withContext
            var requested = 0
            for (zoom in 9..14) {
                if (requested > 4_000) break
                val minX = BasemapTileCache.tileX(area.west, zoom)
                val maxX = BasemapTileCache.tileX(area.east, zoom)
                val minY = BasemapTileCache.tileY(area.north, zoom)
                val maxY = BasemapTileCache.tileY(area.south, zoom)
                for (x in minX..maxX) {
                    for (y in minY..maxY) {
                        if (requested > 4_000) break
                        // Straight to disk. Decoding these would evict the
                        // tiles on screen and leave the map grey while ground
                        // nobody is looking at streamed past behind it.
                        basemap.prefetch(zoom, x, y)
                        requested++
                    }
                }
            }
        }
    }

    /**
     * Pushes a changed rate or power mode straight through.
     *
     * Applied at once rather than at the next launch: someone picking Saver is
     * doing it because the battery is going now, and someone picking Precise is
     * about to walk a line they need recorded properly.
     */
    fun applyLocationSettings() {
        locationRepository.refresh()
        if (watching) {
            val intent = Intent(context, TrackRecordingService::class.java).apply {
                action = TrackRecordingService.ACTION_START
                putExtra(TrackRecordingService.EXTRA_INCIDENT_ID, activeIncident?.id)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    val toolArmed = measuring || placingResources || simMode || showSearch
    LaunchedEffect(lastInteraction, toolArmed, chromeVisible, chromeTimeout) {
        // An armed tool holds the controls open; nothing is more irritating
        // than a panel vanishing mid-measurement.
        val timeout = settings.chromeTimeoutMillis() ?: return@LaunchedEffect
        if (chromeVisible && !toolArmed) {
            kotlinx.coroutines.delay(timeout)
            chromeVisible = false
        }
    }

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

    typing?.let { field ->
        val report = medicalReport
        if (report == null) {
            typing = null
        } else {
            TextEntryDialog(
                label = when (field) {
                    DictationField.NATURE -> "Nature of injury"
                    DictationField.ASSESSMENT -> "Patient assessment"
                    DictationField.HAZARDS -> "LZ hazards"
                    DictationField.RADIO_NAME -> "Radio name"
                    DictationField.UPDATE -> "Update"
                },
                initial = when (field) {
                    DictationField.NATURE -> report.natureOfInjury.orEmpty()
                    DictationField.ASSESSMENT -> report.patientAssessment.orEmpty()
                    DictationField.HAZARDS -> report.lzHazards.orEmpty()
                    DictationField.RADIO_NAME -> report.radioName
                    DictationField.UPDATE -> ""
                },
                onDismiss = { typing = null },
                onConfirm = { entered ->
                    typing = null
                    val updated = when (field) {
                        DictationField.NATURE -> report.copy(natureOfInjury = entered)
                        DictationField.ASSESSMENT -> report.copy(patientAssessment = entered)
                        DictationField.HAZARDS -> report.copy(lzHazards = entered)
                        DictationField.RADIO_NAME -> report.copy(radioNameOverride = entered)
                        DictationField.UPDATE -> {
                            scope.launch { medical.addUpdate(report.id, entered) }
                            report.copy(
                                updates = report.updates +
                                    com.rhecyee.firelinemap.medical.ReportUpdate(
                                        System.currentTimeMillis(), entered
                                    )
                            )
                        }
                    }
                    medicalReport = updated
                    scope.launch { medical.save(updated) }
                }
            )
        }
    }

    medicalReport?.let { report ->
        if (showReadout) {
            RadioReadoutDialog(
                report = report,
                onCopy = { script ->
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Medical readout", script))
                    statusMessage = "Readout copied."
                },
                onDismiss = { showReadout = false }
            )
        } else {
            MedicalSheet(
                report = report,
                onChange = { updated ->
                    medicalReport = updated
                    scope.launch { medical.save(updated) }
                },
                onType = { typing = it },
                onDictate = { dictate(it) },
                onNameNearby = {
                    scope.launch {
                        val name = withContext(Dispatchers.IO) {
                            placeNamer.nearbyName(report.latitude, report.longitude)
                        }
                        if (name == null) {
                            statusMessage = "No nearby place name available."
                        } else {
                            val updated = report.copy(radioNameOverride = name)
                            medicalReport = updated
                            medical.save(updated)
                        }
                    }
                },
                onReadout = { showReadout = true },
                onAddUpdate = { typing = DictationField.UPDATE },
                onDismiss = { medicalReport = null }
            )
        }
    }

    if (showLayers) {
        LayersSheet(
            importedMaps = importedMaps,
            activeMapId = activeMap?.id,
            onSelectMap = { activeMap = it; showLayers = false },
            onNoMap = {
                // Not a failure state. A sheet is one layer among several, and
                // there is a whole map underneath it.
                activeMap = null
                showLayers = false
            },
            topographyOn = topographyOn,
            onToggleTopography = { settings.topographyEnabled = it; topographyOn = it },
            contoursOn = contoursOn,
            onToggleContours = { settings.contoursEnabled = it; contoursOn = it },
            contourDetail = contourDetail,
            onContourDetail = { settings.contourDetail = it; contourDetail = it },
            contourSummary = contourFailure
                ?: contourDescription(contoursOn, contourStatus, contourSet),
            landOwnershipOn = landOwnershipOn,
            onToggleLandOwnership = { settings.landOwnershipEnabled = it; landOwnershipOn = it },
            onDismiss = { showLayers = false }
        )
    }

    viewReport?.let { report ->
        AlertDialog(
            onDismissRequest = { viewReport = null },
            title = { Text("Where the map is looking") },
            text = {
                Text(
                    report,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            },
            confirmButton = { TextButton(onClick = { viewReport = null }) { Text("Done") } }
        )
    }

    landLookupAt?.let { (lat, lon) ->
        LandOwnerDialog(
            status = landStatus,
            busy = landLookupBusy,
            coordinates = CoordinateFormatter.format(lat, lon, coordinateFormat),
            onDismiss = { landLookupAt = null; landStatus = LandStatus() }
        )
    }

    if (showTrackSettings) {
        LaunchedEffect(Unit) {
            cachedTerrain = withContext(Dispatchers.IO) {
                basemap.cachedBytes() + contourLayer.cache.cachedBytes()
            }
            terrainDiagnostics = basemap.diagnostics()
        }
        SettingsSheet(
            reporterName = reporterName,
            reporterQualification = reporterQualification,
            onReporterChange = { name, qualification ->
                reporterName = name
                reporterQualification = qualification
                reporter.name = name
                reporter.qualification = qualification
            },
            stopThresholdSeconds = stopThreshold,
            onStopThreshold = { seconds ->
                trackSettings.stopThresholdSeconds = seconds
                stopThreshold = trackSettings.stopThresholdSeconds
            },
            segmentAtDropPoints = segmentAtDropPoints,
            onToggleSegmenting = {
                trackSettings.segmentAtDropPoints = it
                segmentAtDropPoints = it
            },
            dropPointsFound = dropPoints.size,
            mapSheetStatus = activeMap?.let { "${it.displayName} · ${it.kindLabel}" }
                ?: "None — own terrain only",
            autoDownloadRadius = autoRadius,
            onAutoDownloadRadius = {
                settings.autoDownloadRadiusMiles = it
                autoRadius = settings.autoDownloadRadiusMiles
            },
            wifiOnly = wifiOnly,
            onWifiOnly = { settings.autoDownloadWifiOnly = it; wifiOnly = it },
            chromeTimeoutSeconds = chromeTimeout,
            onChromeTimeout = { settings.chromeTimeoutSeconds = it; chromeTimeout = it },
            locationIntervalSeconds = locationInterval,
            onLocationInterval = {
                settings.locationIntervalSeconds = it
                locationInterval = settings.locationIntervalSeconds
                applyLocationSettings()
            },
            powerMode = powerMode,
            onPowerMode = {
                settings.powerMode = it
                powerMode = it
                applyLocationSettings()
            },
            cachedTerrainBytes = cachedTerrain,
            terrainDiagnostics = terrainDiagnostics,
            onClearTerrain = {
                scope.launch {
                    withContext(Dispatchers.IO) { basemap.clear(); contourLayer.clear() }
                    cachedTerrain = 0L
                }
            },
            crashReport = crashReport,
            onCopyCrash = {
                val full = CrashLog.read(context) ?: crashReport ?: return@SettingsSheet
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("Fireline Map crash", full))
                statusMessage = "Crash report copied."
            },
            onClearCrash = { CrashLog.clear(context); crashReport = null },
            onDismiss = { showTrackSettings = false }
        )
    }

    if (showUrlDialog && incidentId != null) {
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
                                repository.importFromFile(result.file, result.name, incidentId)
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

    if (listing.isNotEmpty() && incidentId != null) {
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
                                repository.importFromFile(result.file, result.name, incidentId)
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
                            repository.importFromFile(result.file, result.name, incidentId)
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

    /**
     * Everything on screen that belonged to the incident being left.
     *
     * Database-backed things -- pins, tracks, reports -- clear themselves,
     * because they are queried by incident id and the id has changed. What does
     * not clear itself is everything held in memory for the view: the sheet
     * being drawn, the lines cut over it, a half-finished measurement, an armed
     * tool. Left alone, the first pin dropped on the new fire lands on the old
     * fire's sheet.
     *
     * Terrain and the basemap are deliberately untouched. They are ground, not
     * incident, and re-downloading a district at the end of a road is how a
     * phone becomes useless exactly when it is needed.
     */
    fun clearForNewIncident() {
        activeMap = null
        bitmap = null
        pageWidth = 0
        pageHeight = 0
        dropPoints = emptyList()
        importedMaps = emptyList()

        boundaryLayer.clear()
        contourLayer.reset()

        measuring = false
        measurePoints = emptyList()
        measureSession.clear()
        elevationPending = false

        placingResources = false
        selectedSymbol = null
        pendingPlacement = null
        inspecting = null
        inspectingReports = 0

        medicalReport = null
        showReadout = false

        searchQuery = ""
        showSearch = false
        centreRequest = null
        view = null

        simulated = null
        simMode = false
        landStatus = LandStatus()
        landLookupAt = null
        statusMessage = null
    }

    suspend fun switchTo(incident: IncidentEntity) {
        clearForNewIncident()
        app.database.dao().setActiveIncident(incident.id)
        importedMaps = withContext(Dispatchers.IO) { repository.imported(incident.id) }
        activeMap = importedMaps.firstOrNull()
    }

    // A track being recorded is written against the incident that was open when
    // it started. Switching under it would leave the rest of the line filed to
    // a fire it was not walked on, so the switch is refused rather than
    // silently producing a wrong track.
    val switchBlocked = if (liveTrack.recording) {
        "Stop the recording track before changing incident — it is being saved " +
            "to ${activeIncident?.name ?: "this incident"}."
    } else {
        null
    }

    sharing?.let { pkg ->
        ShareSheet(
            pkg = pkg,
            assembly = pasted,
            onText = {
                statusMessage = if (ShareIntents.text(context, pkg)) null
                else "Nothing on this phone will send a message."
                sharing = null
            },
            onSendFile = {
                val prepared = ShareIntents.prepareForMessage(pkg)
                statusMessage = if (ShareIntents.share(context, prepared.pkg)) null
                else "Could not prepare the file to send."
                sharing = null
            },
            onSendFullFile = {
                statusMessage = if (ShareIntents.share(context, pkg)) null
                else "Could not prepare the file to send."
                sharing = null
            },
            onCopyPart = { part ->
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Fireline part", part))
                statusMessage = "Copied — paste it into a message."
            },
            onPaste = { text ->
                val part = TextCodec.readPart(text)
                if (part == null) {
                    statusMessage = "That is not a Fireline part — it should start FL1;"
                } else {
                    pasted = pasted.plus(part)
                    statusMessage = null
                }
            },
            onApplyPasted = {
                val body = pasted.body()
                val incoming = if (pasted.verified() && body != null) {
                    TextCodec.decode(body)
                } else {
                    null
                }
                val target = incidentId
                if (incoming == null || target == null) {
                    statusMessage = "Those parts could not be read."
                } else {
                    scope.launch {
                        // Added to whatever is open, and nothing already there
                        // is touched. Somebody else's tracks are information,
                        // not a replacement for your own.
                        incoming.pins.forEach { pin ->
                            resources.place(
                                target,
                                ResourceSymbol.byId(pin.symbolId),
                                pin.title,
                                pin.note,
                                pin.latitude,
                                pin.longitude
                            )
                        }
                        incoming.tracks.forEach { track ->
                            val fixes = track.points.map {
                                com.rhecyee.firelinemap.location.Fix(
                                    it.latitude, it.longitude, it.timeMillis ?: 0L
                                )
                            }
                            var metres = 0.0
                            for (index in 0 until fixes.lastIndex) {
                                metres += MapCoverage.distanceMeters(
                                    fixes[index].latitude, fixes[index].longitude,
                                    fixes[index + 1].latitude, fixes[index + 1].longitude
                                )
                            }
                            app.database.dao().upsertTrack(
                                com.rhecyee.firelinemap.data.TrackEntity(
                                    id = UUID.randomUUID().toString(),
                                    incidentId = target,
                                    // Whose track it is matters as much as
                                    // where it went.
                                    name = incoming.author?.let { "${track.name} ($it)" }
                                        ?: track.name,
                                    startedAt = track.startedAt ?: System.currentTimeMillis(),
                                    endedAt = track.endedAt,
                                    distanceMeters = metres,
                                    geometryGeoJson = TrackGeometry.write(fixes),
                                    note = "Received from ${incoming.author ?: "a message"}"
                                )
                            )
                        }
                        statusMessage = "Added ${incoming.describe()} from " +
                            (incoming.author ?: incoming.incidentName) + "."
                        pasted = TextCodec.Assembly()
                        sharing = null
                    }
                }
            },
            onClearPasted = { pasted = TextCodec.Assembly() },
            onDismiss = { sharing = null }
        )
    }

    if (showIncidents) {
        IncidentSheet(
            incidents = incidents,
            activeId = incidentId,
            tallies = incidentTallies,
            blockedReason = switchBlocked,
            onStart = { name ->
                scope.launch {
                    val now = System.currentTimeMillis()
                    val fresh = IncidentEntity(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        year = IncidentNaming.yearOf(name, now),
                        createdAt = now,
                        isActive = true
                    )
                    clearForNewIncident()
                    app.database.dao().startIncident(fresh)
                    showIncidents = false
                    statusMessage = "Now on ${fresh.name}."
                }
            },
            onSwitch = { incident ->
                scope.launch {
                    switchTo(incident)
                    showIncidents = false
                    statusMessage = "Now on ${incident.name}."
                }
            },
            onRename = { incident, name ->
                scope.launch {
                    app.database.dao().renameIncident(
                        incident.id, name, IncidentNaming.yearOf(name, incident.createdAt)
                    )
                }
            },
            onDelete = { incident ->
                scope.launch {
                    val wasActive = incident.id == incidentId
                    // The sheets go with it. Room's cascade takes the pins,
                    // tracks and reports; the PDFs are files and have to be
                    // deleted here or they outlive the incident on disk.
                    withContext(Dispatchers.IO) { repository.forget(incident.id) }
                    val promoted = app.database.dao().deleteIncidentAndPromote(incident.id)
                    if (wasActive) {
                        clearForNewIncident()
                        if (promoted != null) {
                            importedMaps = withContext(Dispatchers.IO) {
                                repository.imported(promoted.id)
                            }
                            activeMap = importedMaps.firstOrNull()
                            statusMessage = "Deleted. Now on ${promoted.name}."
                        } else {
                            // The last one is gone, so there is nowhere to
                            // record anything. Make a fresh one rather than
                            // leaving the app quietly dropping every pin.
                            val now = System.currentTimeMillis()
                            val fresh = IncidentEntity(
                                id = UUID.randomUUID().toString(),
                                name = IncidentNaming.placeholder(now),
                                year = IncidentNaming.yearOf("", now),
                                createdAt = now,
                                isActive = true
                            )
                            app.database.dao().startIncident(fresh)
                            namingIncident = true
                        }
                    }
                }
            },
            onDismiss = { showIncidents = false }
        )
    }

    if (namingIncident && activeIncident != null) {
        val current = activeIncident
        NameThisIncidentDialog(
            placeholder = current.name,
            onDismiss = { namingIncident = false },
            onConfirm = { name ->
                scope.launch {
                    app.database.dao().renameIncident(
                        current.id, name, IncidentNaming.yearOf(name, current.createdAt)
                    )
                }
                namingIncident = false
            }
        )
    }

    Scaffold(
        topBar = {
            if (chromeVisible) TopAppBar(
                title = {
                    // The incident name is the way in. Nothing else in the bar
                    // is a plausible place to look for "which fire is this",
                    // and it is already the thing being read.
                    Column(Modifier.clickable { showIncidents = true }) {
                        Text(activeIncident?.name ?: "Fireline Map", fontWeight = FontWeight.Bold)
                        Text(
                            if (activeIncident != null) "TAP TO CHANGE INCIDENT"
                            else "OFFLINE INCIDENT MAP",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                actions = {
                    // Sends whatever is on this incident through whatever the
                    // phone already has -- Bluetooth, a message, email, a
                    // nearby phone. The file is GPX, so it opens on an iPhone
                    // too, with nothing of ours installed.
                    IconButton(onClick = {
                        touched()
                        val pkg = sharePackage()
                        if (pkg.isEmpty) {
                            statusMessage = "Nothing to send yet — no tracks or pins."
                        } else {
                            sharing = pkg
                        }
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Send tracks and pins")
                    }
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Default.Search, contentDescription = "Go to coordinate")
                    }
                    IconButton(onClick = { showTrackSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { showUrlDialog = true }) {
                        Icon(Icons.Default.Link, contentDescription = "Import from URL")
                    }
                    IconButton(onClick = {
                        // Some file providers hand PDFs over as a generic
                        // binary, and a picker that hides them is a picker
                        // that says the map does not exist.
                        importLauncher.launch(
                            arrayOf("application/pdf", "application/octet-stream")
                        )
                    }) {
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
            if (chromeVisible && !showSearch) CoordinateCard(
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

            if (chromeVisible && !showSearch) {
                MapStatusRow(
                    activeMap,
                    if (importing > 0) "Reading $importing sheet${if (importing > 1) "s" else ""}…"
                    else statusMessage
                )
            }

            if (chromeVisible && !showSearch && simMode && simulated == null) {
                SimulatedBanner("SIM MODE — tap the map to set a test position")
            }

            if (chromeVisible && !showSearch && simulated != null) {
                SimulatedBanner("SIMULATED POSITION — NOT A GPS FIX · Sim off returns to GPS")
            }

            if (chromeVisible && !showSearch && coverage?.incident == IncidentMapCoverage.OFF_MAP) {
                val meters = coverage.metersOffMap?.roundToInt() ?: 0
                val bearing = coverage.bearingToMapDegrees?.roundToInt() ?: 0
                val distance = if (meters >= 1000) "%.1f km".format(meters / 1000.0) else "$meters m"
                OffMapBanner("OFF THIS SHEET — $distance, bearing $bearing° back onto it")
            }

            if (chromeVisible && !showSearch && (watching || liveTrack.recording)) {
                TravelPanel(live = liveTrack, armed = watching, unit = distanceUnit)
            }

            if (chromeVisible && !showSearch && placingResources) {
                ResourcePalette(
                    symbols = ResourceSymbol.RESOURCES,
                    selected = selectedSymbol,
                    onSelect = { selectedSymbol = it }
                )
            }

            if (chromeVisible && !showSearch && measuring) {
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

            Box(modifier = Modifier.weight(1f)) {
                MapCanvas(
                    map = activeMap,
                    bitmap = bitmap,
                pageWidthPoints = pageWidth,
                pageHeightPoints = pageHeight,
                latitude = displayLatitude,
                longitude = displayLongitude,
                positionIsSimulated = simulated != null,
                dropPoints = if (segmentAtDropPoints) dropPoints else emptyList(),
                basemap = basemap.takeIf { topographyOn },
                contours = contourSet.takeIf { contoursOn },
                boundaries = boundaries.takeIf { landOwnershipOn },
                onViewBounds = { north, south, west, east, zoom, atScale ->
                    view = MapView(north, south, west, east, zoom)
                    groundScale = atScale
                },
                initialScale = groundScale,
                onSpanChange = { factor, lat, lon ->
                    val ladder = GroundProjection.SPAN_LADDER
                    val wanted = groundSpan * factor
                    val stepped = ladder.minByOrNull { kotlin.math.abs(it - wanted) }
                    if (stepped != null && stepped != groundSpan &&
                        wanted >= ladder.first() * 0.9 && wanted <= ladder.last() * 1.1
                    ) {
                        // Anchored on the middle of the view and the scale set
                        // so the visible ground is unchanged: the span and the
                        // scale move by the same factor, and what is on screen
                        // is the span over the scale.
                        groundAnchor = lat to lon
                        settings.lastAnchor = lat to lon
                        groundScale = if (factor > 1.0) {
                            GroundProjection(lat, lon).maxScale
                        } else {
                            GroundProjection(lat, lon).minScale
                        }
                        groundSpan = stepped
                    }
                },
                onWhereAmILooking = { report ->
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText("Fireline Map view", report)
                    )
                    viewReport = report
                },
                onContourDrawFailed = {
                    // Turn the layer off rather than let it fail every frame,
                    // and say so: a layer that silently stops appearing is a
                    // layer nobody can report.
                    contoursOn = false
                    statusMessage = "Contours turned off: ${it::class.java.simpleName}"
                },
                measurePoints = measurePoints,
                measureMode = measureMode,
                markers = markers,
                trackPoints = liveTrack.points,
                savedTracks = savedTracks,
                    searchRegion = searchRegion,
                    centreOn = centreRequest,
                    onCentred = { centreRequest = null },
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
                    } else if (landOwnershipOn) {
                        // Nothing else claimed the tap: ask whose ground it is.
                        landLookupAt = lat to lon
                        landStatus = LandStatus()
                        landLookupBusy = true
                        scope.launch {
                            val found = withContext(Dispatchers.IO) {
                                landOwnership.statusAt(lat, lon)
                            }
                            landStatus = found
                            landLookupBusy = false
                        }
                    }
                    // With no tool armed and no parcel layer, a tap does
                    // nothing. It used to drop a simulated position, which
                    // silently replaced the live GPS readout from a stray touch.
                },
                    onInteraction = { touched() },
                    projection = projection,
                    modifier = Modifier.fillMaxSize()
                )

                // The one thing that stays. Coordinates are what gets read
                // over the radio, and hunting for them is not acceptable.
                // Always reachable, including full screen. The one control
                // that must never be behind another tap.
                MedicalButton(
                    active = medicalReport != null,
                    onClick = { touched(); openMedicalReport() },
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
                )

                if (chromeVisible && !showSearch && showLegend) {
                    MapLegend(
                        hasTrack = liveTrack.recording,
                        hasSavedTracks = savedTracks.isNotEmpty(),
                        contourInterval = contourSet.interval.describe()
                            .takeIf { contoursOn && !contourSet.isEmpty },
                        hasDropPoints = segmentAtDropPoints && dropPoints.isNotEmpty(),
                        hasSearch = searchRegion != null,
                        simulated = simulated != null,
                        onDismiss = { showLegend = false },
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                    )
                }

                if (!chromeVisible) {
                    CompactStatusStrip(
                        coordinates = when {
                            displayLatitude != null && displayLongitude != null ->
                                CoordinateFormatter.format(
                                    displayLatitude, displayLongitude, coordinateFormat
                                )
                            !hasLocationPermission -> "No location permission"
                            else -> "Waiting for GPS…"
                        },
                        accuracy = if (simulated == null) gpsLocation?.accuracy else null,
                        simulated = simulated != null,
                        recording = liveTrack.recording,
                        paused = liveTrack.paused,
                        distanceMeters = liveTrack.distanceMeters,
                        modifier = Modifier.align(Alignment.TopCenter).padding(6.dp),
                        onTap = { touched() }
                    )
                }

                // Overlaid rather than stacked above: the whole value of the
                // search is watching the highlight narrow, and a panel that
                // pushes the map off screen cannot do that.
                if (showSearch) {
                    CoordinateSearchReadout(
                        query = searchQuery,
                        result = searchResult,
                        onKeep = {
                            searchCoordinate?.let {
                                selectedSymbol = ResourceSymbol.OTHER
                                pendingPlacement = it.latitude to it.longitude
                            }
                        },
                        onShow = { centreRequest = searchCoordinate?.let { it.latitude to it.longitude } },
                        keypadOpen = keypadOpen,
                        onToggleKeypad = { keypadOpen = !keypadOpen },
                        onClear = { searchQuery = ""; showSearch = false; keypadOpen = true },
                        modifier = Modifier.align(Alignment.TopCenter).padding(6.dp)
                    )
                    if (keypadOpen) {
                        CoordinateKeypad(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp)
                        )
                    }
                }
            }

            if (chromeVisible && !showSearch) Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolButton(
                    "Measure",
                    Icons.Default.Straighten,
                    Modifier.weight(1f),
                    active = measuring
                ) {
                    touched()
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
                    touched()
                    placingResources = !placingResources
                    if (placingResources) {
                        measuring = false
                        simMode = false
                    } else {
                        selectedSymbol = null
                    }
                }
                // The 8 line moved out to the floating button, which is always
                // on screen including full screen. This slot goes to auto
                // record, which used to be a full-width button of its own
                // below -- one control, one place, and a strip of map back.
                ToolButton(
                    if (watching) "Recording" else "Auto Record",
                    if (watching) Icons.Default.FiberManualRecord else Icons.Default.Timeline,
                    Modifier.weight(1f),
                    active = watching
                ) {
                    val intent = Intent(context, TrackRecordingService::class.java).apply {
                        action = if (watching) TrackRecordingService.ACTION_STOP
                        else TrackRecordingService.ACTION_START
                        putExtra(TrackRecordingService.EXTRA_INCIDENT_ID, activeIncident?.id)
                    }
                    touched()
                    ContextCompat.startForegroundService(context, intent)
                    watching = !watching
                }
                ToolButton(
                    "Layers",
                    Icons.Default.Layers,
                    Modifier.weight(1f),
                    active = showLayers
                ) {
                    touched()
                    showLayers = true
                }
            }

            // What recording is doing, in a line rather than a button. The
            // control is in the row above; this only has to say what state it
            // is in, and it only says it while recording -- when it is off,
            // the row's own label already does.
            if (chromeVisible && !showSearch && watching) {
                Text(
                    "Recording on movement \u00b7 pauses after " +
                        TrackSettingsStore.describe(stopThreshold) + " stopped",
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/**
 * Reads a stored track's shape.
 *
 * Delegates so the reader and the writer cannot drift apart: the service
 * writes this column, and a track that reads back differently from how it was
 * written is a track drawn somewhere it was not walked.
 */
private fun parseLineString(geoJson: String): List<Pair<Double, Double>> =
    TrackGeometry.readPositions(geoJson)

/** Twenty seconds of no touching and the controls fold away again. */

@Composable
private fun MapStatusRow(map: ImportedMap?, message: String?) {
    // Running on the app's own terrain is the normal way to work, not a state
    // to get out of, so it no longer takes a strip of the screen to say so.
    // Which sheet is in use -- including none -- is in Settings, where it can
    // be looked up on the rare occasion it matters.
    if (message == null && map == null) return

    val text: String
    val colour: Color
    when {
        message != null -> {
            text = message
            colour = Color(0xFFB3261E)
        }
        map == null -> return
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

/**
 * What the contour switch should say about itself.
 *
 * The interval is not a fixed property of the layer -- it changes with zoom
 * and with how much relief is on screen -- so the only honest label is the one
 * that reports what is being drawn right now.
 */
private fun contourDescription(
    on: Boolean,
    status: ContourStatus,
    set: com.rhecyee.firelinemap.terrain.ContourRender
): String = when {
    !on -> "Cut from USGS elevation. Lines tighten as you zoom in."
    status == ContourStatus.FAILED -> "Could not cut lines for this view."
    status == ContourStatus.MISSING ->
        "No elevation held for this ground yet. It downloads first when you " +
            "have a connection."
    status == ContourStatus.WORKING -> "Cutting lines…"
    set.isEmpty -> "This ground is flat: nothing crosses a ${set.interval.feet} ft line."
    status == ContourStatus.PARTIAL -> "${set.interval.describe()} · part of this view is missing"
    else -> "${set.interval.describe()} · ${set.lowestFeet}–${set.highestFeet} ft in view"
}

/**
 * How many elevation tiles to take at each level around the operator.
 *
 * Deliberately modest. The point of the radius download is to have the ground
 * nearby when the signal goes, not to mirror a state onto a phone.
 */
private const val DEM_TILES_PER_LEVEL = 64

/**
 * The ground on screen, as the canvas last reported it.
 *
 * Compared by value so an unchanged view is recognised as unchanged. Held as
 * an array before, which compares by identity: every report looked like a
 * change, and every change cancelled the contour cut in progress and started
 * another. During a zoom that meant several full traces alive at once.
 */
private data class MapView(
    val north: Double,
    val south: Double,
    val west: Double,
    val east: Double,
    val zoom: Int
)

/**
 * How coarsely the sheet is scanned for drop points.
 *
 * Half. The plate thresholds scale with it, so the same plates are found for a
 * quarter of the memory -- and the memory is the point: a full-page scan costs
 * an integer per pixel on top of the page raster itself, which is what took
 * the app down when several sheets were imported together.
 */
private const val DROP_POINT_SCAN_STRIDE = 2
