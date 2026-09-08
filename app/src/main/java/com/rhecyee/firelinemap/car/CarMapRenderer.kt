package com.rhecyee.firelinemap.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.rhecyee.firelinemap.FirelineApplication
import com.rhecyee.firelinemap.data.AppSettings
import com.rhecyee.firelinemap.data.MarkerEntity
import com.rhecyee.firelinemap.data.parseLineString
import com.rhecyee.firelinemap.geopdf.MapDocumentRepository
import com.rhecyee.firelinemap.geopdf.MapFrame
import com.rhecyee.firelinemap.location.TrackRecordingState
import com.rhecyee.firelinemap.map.BasemapTileCache
import com.rhecyee.firelinemap.resources.ResourceSymbol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Draws the incident map onto the car display.
 *
 * The car surface is a bare [android.view.Surface]: there is no Compose and no
 * view hierarchy out here, so the same content the phone canvas draws is drawn
 * again with a plain [Canvas]. What is worth keeping in mind reading this:
 *
 * - Terrain, the imported sheet and the track are all drawn inside a canvas
 *   that has already been rotated to the map bearing. In that frame they are
 *   axis-aligned and go down as ordinary bitmap and path draws.
 * - The vehicle marker, the readouts and the scale bar are drawn after the
 *   rotation is undone, so they stay upright whichever way the truck is
 *   pointing.
 * - Nothing here may throw. A drawing fault on a head unit takes the map away
 *   from a driver who is using it to get somewhere, so every frame is drawn
 *   defensively and a failed frame is skipped rather than propagated.
 */
class CarMapRenderer(
    private val carContext: CarContext
) : SurfaceCallback, DefaultLifecycleObserver {

    /** Invalidates the template when something the action strip shows changes. */
    var onStateChanged: (() -> Unit)? = null

    val camera = CarMapCamera()

    private val application = carContext.applicationContext as FirelineApplication
    private val settings = AppSettings(carContext)
    private val basemap = BasemapTileCache(carContext)

    private var surfaceContainer: SurfaceContainer? = null
    private var visibleArea: Rect? = null
    private var stableArea: Rect? = null
    private var renderJob: Job? = null
    private var lifecycleOwner: LifecycleOwner? = null

    private var location: Location? = null
    private var markers: List<MarkerEntity> = emptyList()

    /** Finished travel for this incident, and the drop points read off the sheet. */
    private var savedTracks: List<List<Pair<Double, Double>>> = emptyList()
    private val dropPoints get() = application.dropPoints

    /** The imported sheet, rendered once and held while the car screen is up. */
    private var sheetBitmap: Bitmap? = null
    private var sheetFrame: MapFrame? = null
    private var sheetName: String? = null
    private var pageWidthPoints = 0
    private var pageHeightPoints = 0
    private var loadedMapId: String? = null
    private var attemptedMapId: String? = null
    private var attemptedSheet = false
    private var sheetLoadInFlight = false

    /** Set while the driver is panning, so the readout can say the map is off-vehicle. */
    val browsing: Boolean get() = !camera.following

    // ---------------------------------------------------------------- paints

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val sheetPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        // Held back a shade so the position and track stay findable on top of a
        // sheet that is mostly white paper.
        alpha = SHEET_ALPHA
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    // ------------------------------------------------------------- lifecycle

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)

        owner.lifecycleScope.launch {
            application.location.locations.collect { fix ->
                location = fix
            }
        }
        owner.lifecycleScope.launch { observeMarkers() }
        owner.lifecycleScope.launch { observeSavedTracks() }
        refreshSheet()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun observeMarkers() {
        val dao = application.database.dao()
        dao.observeActiveIncident()
            .flatMapLatest { incident ->
                if (incident == null) flowOf(emptyList()) else dao.observeMarkers(incident.id)
            }
            .collect { markers = it }
    }

    /**
     * Travel already recorded for this incident.
     *
     * The car drew only the drive in progress, which is the half an operator
     * least needs: where they have already been is what says whether a spur has
     * been checked. Parsed once per change rather than per frame -- a shift's
     * worth of line geometry is not something to re-read four times a second.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun observeSavedTracks() {
        val dao = application.database.dao()
        dao.observeActiveIncident()
            .flatMapLatest { incident ->
                if (incident == null) flowOf(emptyList()) else dao.observeTracks(incident.id)
            }
            .collect { entities ->
                savedTracks = entities
                    .filter { !it.isRecording }
                    .map { parseLineString(it.geometryGeoJson) }
                    .filter { it.size >= 2 }
            }
    }

    /**
     * Brings up whichever sheet the phone has open, and follows it if it changes.
     *
     * The id is read from settings rather than from the running screen. Android
     * Auto can start this service with the phone activity long since gone, and a
     * car display showing terrain but not the incident map the crew is working
     * off is the failure this whole screen exists to avoid. Keyed on the wanted
     * id rather than on what actually loaded, so a sheet that cannot be rendered
     * is attempted once instead of being retried on every frame.
     */
    private fun refreshSheet() {
        if (sheetLoadInFlight) return
        val wanted = settings.activeMapId
        if (attemptedSheet && wanted == attemptedMapId) return
        val owner = lifecycleOwner ?: return

        sheetLoadInFlight = true
        attemptedMapId = wanted
        attemptedSheet = true
        owner.lifecycleScope.launch {
            try {
                loadSheet(wanted)
            } finally {
                sheetLoadInFlight = false
            }
        }
    }

    private suspend fun loadSheet(wanted: String?) {
        val repository = MapDocumentRepository(carContext)
        val loaded = withContext(Dispatchers.IO) {
            // Terrain chosen deliberately on the phone. The car draws terrain
            // regardless, so this is simply no sheet on top of it rather than a
            // reason to go and pick one the operator has just set aside.
            if (wanted == AppSettings.TERRAIN_ONLY) return@withContext null
            val available = repository.imported()
            // Falls back to the most recent import when the remembered sheet
            // has since been deleted off the phone.
            val map = available.firstOrNull { it.id == wanted }
                ?: available.firstOrNull()
                ?: return@withContext null
            val size = MapDocumentRepository.pageSize(map.file) ?: return@withContext null
            val bitmap = MapDocumentRepository.renderPage(map.file, targetWidth = SHEET_WIDTH)
                ?: return@withContext null
            SheetLoad(map.id, map.displayName, map.frame, bitmap, size.first, size.second)
        }

        sheetBitmap = loaded?.bitmap
        sheetFrame = loaded?.frame
        sheetName = loaded?.name
        pageWidthPoints = loaded?.pageWidth ?: 0
        pageHeightPoints = loaded?.pageHeight ?: 0
        loadedMapId = loaded?.id
        onStateChanged?.invoke()
    }

    private data class SheetLoad(
        val id: String,
        val name: String,
        val frame: MapFrame?,
        val bitmap: Bitmap,
        val pageWidth: Int,
        val pageHeight: Int
    )

    override fun onDestroy(owner: LifecycleOwner) {
        renderJob?.cancel()
        renderJob = null
        surfaceContainer = null
        sheetBitmap = null
    }

    // --------------------------------------------------------- surface hooks

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        this.surfaceContainer = surfaceContainer
        CarLinkLog.record(
            carContext,
            "Car surface ${surfaceContainer.width}x${surfaceContainer.height}"
        )
        startRendering()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        renderJob?.cancel()
        renderJob = null
        this.surfaceContainer = null
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = Rect(visibleArea)
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        this.stableArea = Rect(stableArea)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        val projection = currentProjection() ?: return
        camera.pan(distanceX, distanceY, projection)
        onStateChanged?.invoke()
        render()
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        camera.scaleBy(scaleFactor)
        render()
    }

    override fun onClick(x: Float, y: Float) {
        // Left as a no-op on purpose. A tap that changed the map would be a
        // hazard on a screen a driver glances at; zoom, pan and recentre are
        // on the action strip where they can be found without looking.
    }

    // ------------------------------------------------------------- rendering

    /**
     * Redraws on a timer while the car screen is up.
     *
     * Not driven off location updates alone: terrain tiles land asynchronously
     * as they finish downloading or decoding, and the frame that shows them has
     * to come from somewhere. A fixed low rate is cheap, and the surface only
     * exists while the app is actually on the car display.
     */
    private fun startRendering() {
        renderJob?.cancel()
        val owner = lifecycleOwner ?: return
        renderJob = owner.lifecycleScope.launch {
            while (isActive) {
                refreshSheet()
                render()
                delay(FRAME_INTERVAL_MILLIS)
            }
        }
    }

    /** Ties the surface, the render loop and the data feeds to the car connection. */
    fun attach(owner: LifecycleOwner) {
        lifecycleOwner = owner
        owner.lifecycle.addObserver(this)
    }

    private fun anchor(width: Int, height: Int): Pair<Float, Float> {
        val area = visibleArea?.takeIf { !it.isEmpty } ?: stableArea?.takeIf { !it.isEmpty }
        val centerX = area?.exactCenterX() ?: (width / 2f)
        val centerY = area?.exactCenterY() ?: (height / 2f)
        // Heading-up puts the vehicle low so the ground being driven into is
        // the ground on screen. North-up centres it, since there is no "ahead".
        return if (camera.headingUp && camera.following) {
            val top = area?.top?.toFloat() ?: 0f
            val bottom = area?.bottom?.toFloat() ?: height.toFloat()
            centerX to (top + (bottom - top) * VEHICLE_SCREEN_FRACTION)
        } else {
            centerX to centerY
        }
    }

    private fun currentProjection(): CarMapProjection? {
        val container = surfaceContainer ?: return null
        val width = container.width
        val height = container.height
        if (width <= 0 || height <= 0) return null
        val fix = location
        val (anchorX, anchorY) = anchor(width, height)
        val speed = fix?.speed ?: 0f
        return camera.projection(
            vehicleLatitude = fix?.latitude,
            vehicleLongitude = fix?.longitude,
            vehicleBearing = if (fix?.hasBearing() == true) fix.bearing else null,
            vehicleMoving = speed >= CarMapCamera.MOVING_METERS_PER_SECOND,
            widthPixels = width,
            heightPixels = height,
            anchorX = anchorX,
            anchorY = anchorY
        )
    }

    fun render() {
        val container = surfaceContainer ?: return
        val surface = container.surface ?: return
        if (!surface.isValid) return

        val canvas = runCatching { surface.lockCanvas(null) }.getOrNull() ?: return
        try {
            drawFrame(canvas, container)
        } catch (error: Throwable) {
            // A half-drawn frame still gets posted below; losing the map for
            // one tick beats taking the screen down mid-drive.
        } finally {
            runCatching { surface.unlockCanvasAndPost(canvas) }
        }
    }

    private fun drawFrame(canvas: Canvas, container: SurfaceContainer) {
        val density = (container.dpi.takeIf { it > 0 } ?: 160) / 160f
        canvas.drawColor(BACKGROUND)

        val projection = currentProjection()
        if (projection == null) {
            drawWaiting(canvas, container, density)
            return
        }

        canvas.save()
        canvas.rotate(
            (-camera.bearing()).toFloat(), projection.anchorX, projection.anchorY
        )
        drawTerrain(canvas, projection)
        drawSheet(canvas, projection)
        drawSavedTracks(canvas, projection, density)
        drawTrack(canvas, projection, density)
        canvas.restore()

        drawDropPoints(canvas, projection, density)
        drawMarkers(canvas, projection, density)
        drawVehicle(canvas, projection, density)
        drawReadout(canvas, container, projection, density)
    }

    // --------------------------------------------------------------- terrain

    private fun drawTerrain(canvas: Canvas, projection: CarMapProjection) {
        val tileZoom = projection.zoom.roundToInt().coerceIn(0, MAX_TILE_ZOOM)
        val bounds = projection.visibleBounds()
        val minX = BasemapTileCache.tileX(bounds[1], tileZoom)
        val maxX = BasemapTileCache.tileX(bounds[3], tileZoom)
        val minY = BasemapTileCache.tileY(bounds[2], tileZoom)
        val maxY = BasemapTileCache.tileY(bounds[0], tileZoom)
        // A view straddling the antimeridian inverts the column range. Not
        // ground this tool covers, and a wrapped fetch is not worth carrying.
        if (maxX < minX || maxY < minY) return
        if ((maxX - minX + 1).toLong() * (maxY - minY + 1) > MAX_TILES_PER_FRAME) return

        val span = (projection.worldSize / (1 shl tileZoom)).toFloat()
        val destination = RectF()
        val source = Rect()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                val sample = basemap.sample(tileZoom, x, y) ?: continue
                val left = (projection.anchorX + (x * span - projection.centerWorldX)).toFloat()
                val top = (projection.anchorY + (y * span - projection.centerWorldY)).toFloat()
                destination.set(left, top, left + span, top + span)
                source.set(
                    sample.sourceLeft,
                    sample.sourceTop,
                    sample.sourceLeft + sample.sourceSize,
                    sample.sourceTop + sample.sourceSize
                )
                canvas.drawBitmap(sample.bitmap, source, destination, bitmapPaint)
            }
        }
    }

    // ----------------------------------------------------------------- sheet

    /**
     * Lays the imported sheet over the terrain.
     *
     * A georeferenced page is an affine function of the projected ground it
     * covers, so the four frame corners are enough to place the whole raster
     * exactly -- no resampling pass, and no per-pixel projection. The draw is
     * clipped to the frame's own quad so the sheet's collar, title block and
     * legend stay off a display that is being glanced at from a moving vehicle.
     */
    private fun drawSheet(canvas: Canvas, projection: CarMapProjection) {
        val bitmap = sheetBitmap ?: return
        val frame = sheetFrame ?: return
        if (bitmap.isRecycled) return
        if (pageWidthPoints <= 0 || pageHeightPoints <= 0) return

        val pageCorners = frame.pageCorners
        if (pageCorners.size != 4) return

        val visible = projection.visibleBounds()
        val sheetBounds = frame.geographicBounds()
        val separated = sheetBounds[2] < visible[0] || sheetBounds[0] > visible[2] ||
            sheetBounds[3] < visible[1] || sheetBounds[1] > visible[3]
        if (separated) return

        val source = FloatArray(8)
        val destination = FloatArray(8)
        pageCorners.forEachIndexed { index, (pageX, pageY) ->
            source[index * 2] = (pageX / pageWidthPoints * bitmap.width).toFloat()
            // PDF pages count up from the bottom; bitmaps count down from the top.
            source[index * 2 + 1] =
                ((1.0 - pageY / pageHeightPoints) * bitmap.height).toFloat()
            val ground = frame.pageToGeo(pageX, pageY) ?: return
            val point = projection.toUnrotated(ground.latitude, ground.longitude)
            if (!point.x.isFinite() || !point.y.isFinite()) return
            destination[index * 2] = point.x
            destination[index * 2 + 1] = point.y
        }

        val matrix = Matrix()
        if (!matrix.setPolyToPoly(source, 0, destination, 0, 4)) return

        val clip = Path().apply {
            moveTo(destination[0], destination[1])
            lineTo(destination[2], destination[3])
            lineTo(destination[4], destination[5])
            lineTo(destination[6], destination[7])
            close()
        }
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(bitmap, matrix, sheetPaint)
        canvas.restore()
    }

    // ----------------------------------------------------------------- track

    private fun drawTrack(canvas: Canvas, projection: CarMapProjection, density: Float) {
        val points = TrackRecordingState.live.value.points
        if (points.size < 2) return

        val path = Path()
        var started = false
        points.forEach { (latitude, longitude) ->
            val point = projection.toUnrotated(latitude, longitude)
            if (!point.x.isFinite() || !point.y.isFinite()) return@forEach
            if (started) path.lineTo(point.x, point.y) else {
                path.moveTo(point.x, point.y)
                started = true
            }
        }
        if (!started) return

        strokePaint.color = TRACK_CASING
        strokePaint.strokeWidth = 8f * density
        canvas.drawPath(path, strokePaint)
        strokePaint.color = TRACK
        strokePaint.strokeWidth = 4f * density
        canvas.drawPath(path, strokePaint)
    }

    private fun drawSavedTracks(
        canvas: Canvas,
        projection: CarMapProjection,
        density: Float
    ) {
        if (savedTracks.isEmpty()) return
        strokePaint.color = SAVED_TRACK
        strokePaint.strokeWidth = 3f * density
        savedTracks.forEach { points ->
            val path = Path()
            var started = false
            points.forEach { (latitude, longitude) ->
                val point = projection.toUnrotated(latitude, longitude)
                if (!point.x.isFinite() || !point.y.isFinite()) return@forEach
                if (started) path.lineTo(point.x, point.y) else {
                    path.moveTo(point.x, point.y)
                    started = true
                }
            }
            if (started) canvas.drawPath(path, strokePaint)
        }
    }

    /**
     * Drop points read off the sheet.
     *
     * Held on the application rather than in the database: they are derived
     * from whichever sheet is open, and the recording service already reads
     * them from there to decide where to split travel.
     */
    private fun drawDropPoints(
        canvas: Canvas,
        projection: CarMapProjection,
        density: Float
    ) {
        val points = dropPoints
        if (points.isEmpty()) return
        val radius = 7f * density
        points.forEach { anchor ->
            val point = projection.toScreen(anchor.latitude, anchor.longitude)
            if (!point.x.isFinite() || !point.y.isFinite()) return@forEach
            if (point.x < -radius || point.y < -radius) return@forEach
            if (point.x > projection.widthPixels + radius) return@forEach
            if (point.y > projection.heightPixels + radius) return@forEach

            strokePaint.color = DROP_POINT
            strokePaint.strokeWidth = 2.5f * density
            canvas.drawCircle(point.x, point.y, radius, strokePaint)
            fillPaint.color = DROP_POINT
            canvas.drawCircle(point.x, point.y, radius * 0.35f, fillPaint)
        }
    }

    // --------------------------------------------------------------- markers

    /**
     * Resources and points, in the palette the phone map uses.
     *
     * Drawn after the map rotation is undone rather than with the map. A symbol
     * has to stay upright and the same size whichever way the vehicle is
     * pointing, and working in real surface coordinates is also what makes the
     * off-screen test correct: an unrotated position compared against the
     * surface bounds drops marks that are visibly on the display.
     */
    private fun drawMarkers(canvas: Canvas, projection: CarMapProjection, density: Float) {
        if (markers.isEmpty()) return
        val radius = 9f * density
        markers.forEach { marker ->
            val point = projection.toScreen(marker.latitude, marker.longitude)
            if (!point.x.isFinite() || !point.y.isFinite()) return@forEach
            if (point.x < -radius || point.y < -radius) return@forEach
            if (point.x > projection.widthPixels + radius) return@forEach
            if (point.y > projection.heightPixels + radius) return@forEach

            fillPaint.color = Color.WHITE
            canvas.drawCircle(point.x, point.y, radius + 2f * density, fillPaint)
            fillPaint.color = ResourceSymbol.byId(marker.symbol).colorArgb
            canvas.drawCircle(point.x, point.y, radius, fillPaint)
        }
    }

    // --------------------------------------------------------------- vehicle

    private fun drawVehicle(canvas: Canvas, projection: CarMapProjection, density: Float) {
        val fix = location ?: return
        val point = projection.toScreen(fix.latitude, fix.longitude)
        if (!point.x.isFinite() || !point.y.isFinite()) return

        // The accuracy ring is drawn to scale: an operator has to be able to
        // see when the fix is too loose to trust for a position report.
        val metersPerPixel = projection.metersPerPixel()
        if (fix.hasAccuracy() && metersPerPixel > 0) {
            val accuracyRadius = (fix.accuracy / metersPerPixel).toFloat()
            if (accuracyRadius > 4f * density && accuracyRadius < projection.widthPixels) {
                fillPaint.color = ACCURACY_FILL
                canvas.drawCircle(point.x, point.y, accuracyRadius, fillPaint)
                strokePaint.color = ACCURACY_RING
                strokePaint.strokeWidth = 1.5f * density
                canvas.drawCircle(point.x, point.y, accuracyRadius, strokePaint)
            }
        }

        val size = 13f * density
        // Heading is drawn relative to the map, so on a heading-up map the
        // chevron points up and on a north-up map it points where the truck is.
        val heading = (if (fix.hasBearing()) fix.bearing.toDouble() else camera.bearing()) -
            camera.bearing()
        canvas.save()
        canvas.rotate(heading.toFloat(), point.x, point.y)
        val chevron = Path().apply {
            moveTo(point.x, point.y - size)
            lineTo(point.x + size * 0.7f, point.y + size * 0.8f)
            lineTo(point.x, point.y + size * 0.35f)
            lineTo(point.x - size * 0.7f, point.y + size * 0.8f)
            close()
        }
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 3f * density
        canvas.drawPath(chevron, strokePaint)
        fillPaint.color = VEHICLE
        canvas.drawPath(chevron, fillPaint)
        canvas.restore()
    }

    // --------------------------------------------------------------- readout

    private fun drawWaiting(canvas: Canvas, container: SurfaceContainer, density: Float) {
        textPaint.color = Color.WHITE
        textPaint.textSize = 20f * density
        textPaint.textAlign = Paint.Align.CENTER
        val area = visibleArea?.takeIf { !it.isEmpty }
        val x = area?.exactCenterX() ?: (container.width / 2f)
        val y = area?.exactCenterY() ?: (container.height / 2f)
        val message = if (!application.location.hasPermission()) {
            "Location permission not granted"
        } else {
            "Waiting for GPS"
        }
        canvas.drawText(message, x, y, textPaint)
        textPaint.textSize = 14f * density
        textPaint.color = SUBDUED
        canvas.drawText(
            if (!application.location.hasPermission()) {
                "Grant it on the phone, then come back"
            } else {
                "The map draws as soon as there is a fix"
            },
            x, y + 24f * density, textPaint
        )
        textPaint.textAlign = Paint.Align.LEFT
    }

    /**
     * The strip of text along the bottom.
     *
     * Deliberately short. Everything on it is something that changes what the
     * driver should do with what they are seeing: whether the map is on the
     * vehicle at all, whether the sheet under them is the incident's, how loose
     * the fix is, and what the scale bar is worth.
     */
    private fun drawReadout(
        canvas: Canvas,
        container: SurfaceContainer,
        projection: CarMapProjection,
        density: Float
    ) {
        val area = visibleArea?.takeIf { !it.isEmpty }
        val left = (area?.left?.toFloat() ?: 0f) + 12f * density
        val bottom = (area?.bottom?.toFloat() ?: container.height.toFloat()) - 12f * density

        drawScaleBar(canvas, projection, left, bottom - 26f * density, density)

        textPaint.textSize = 13f * density
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = if (browsing) BROWSING else SUBDUED
        canvas.drawText(statusLine(), left, bottom, textPaint)
    }

    private fun statusLine(): String {
        val parts = mutableListOf<String>()
        if (browsing) parts += "PANNED — not following"
        val fix = location
        if (fix != null && fix.hasAccuracy()) parts += "±${fix.accuracy.roundToInt()} m"
        if (fix != null && fix.hasSpeed()) {
            val milesPerHour = fix.speed * 2.236936f
            if (milesPerHour >= 1f) parts += "${milesPerHour.roundToInt()} mph"
        }
        val onSheet = sheetFrame != null && fix != null &&
            sheetFrame?.containsGeo(fix.latitude, fix.longitude) == true
        parts += when {
            sheetName == null -> "Terrain"
            sheetFrame == null -> "${sheetName} (no georeferencing)"
            onSheet -> sheetName.orEmpty()
            else -> "Off ${sheetName}"
        }
        if (TrackRecordingState.live.value.recording) parts += "REC"
        TrackRecordingState.lastOutcome.value?.let { parts += it }
        return parts.joinToString("  ·  ")
    }

    /**
     * A scale bar rounded to something readable.
     *
     * Snapped to a round number of feet or miles rather than drawn at a fixed
     * pixel width, because the number is the part being read: "500 ft" tells a
     * driver whether the turning they can see is the one on the sheet.
     */
    private fun drawScaleBar(
        canvas: Canvas,
        projection: CarMapProjection,
        left: Float,
        bottom: Float,
        density: Float
    ) {
        val metersPerPixel = projection.metersPerPixel()
        if (metersPerPixel <= 0 || !metersPerPixel.isFinite()) return

        val targetPixels = 120f * density
        val targetFeet = metersPerPixel * targetPixels * FEET_PER_METER
        val feet = SCALE_STEPS_FEET.lastOrNull { it <= targetFeet } ?: SCALE_STEPS_FEET.first()
        val pixels = (feet / FEET_PER_METER / metersPerPixel).toFloat()
        if (!pixels.isFinite() || pixels <= 0f) return

        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 2f * density
        canvas.drawLine(left, bottom, left + pixels, bottom, strokePaint)
        canvas.drawLine(left, bottom - 4f * density, left, bottom + 4f * density, strokePaint)
        canvas.drawLine(
            left + pixels, bottom - 4f * density, left + pixels, bottom + 4f * density, strokePaint
        )

        textPaint.color = Color.WHITE
        textPaint.textSize = 12f * density
        textPaint.textAlign = Paint.Align.LEFT
        val label = if (feet >= 5280) {
            val miles = feet / 5280
            if (abs(miles - miles.roundToInt()) < 0.01) "${miles.roundToInt()} mi" else "%.1f mi".format(miles)
        } else {
            "${feet.roundToInt()} ft"
        }
        canvas.drawText(label, left + pixels + 8f * density, bottom + 4f * density, textPaint)
    }

    companion object {
        private const val BACKGROUND = 0xFF20261F.toInt()
        private const val SUBDUED = 0xFFBFCBB8.toInt()
        private const val BROWSING = 0xFFFFC24D.toInt()
        private const val VEHICLE = 0xFF2E7D32.toInt()
        private const val TRACK = 0xFFFF7043.toInt()
        private const val TRACK_CASING = 0xCC1A1A1A.toInt()

        /** Travel already recorded: present, but never louder than the live line. */
        private const val SAVED_TRACK = 0xAAFFA270.toInt()
        private const val DROP_POINT = 0xFF64B5F6.toInt()
        private const val ACCURACY_FILL = 0x332E7D32
        private const val ACCURACY_RING = 0x882E7D32.toInt()

        /** The sheet is drawn slightly back so the position on top of it stays findable. */
        private const val SHEET_ALPHA = 236

        /** Where the vehicle sits down the display on a heading-up map. */
        private const val VEHICLE_SCREEN_FRACTION = 0.68f

        private const val FRAME_INTERVAL_MILLIS = 250L

        /** Rendered narrower than the phone's 2048: a car display is smaller and further away. */
        private const val SHEET_WIDTH = 1536

        private const val MAX_TILE_ZOOM = 16
        private const val MAX_TILES_PER_FRAME = 240L

        private const val FEET_PER_METER = 3.280839895

        private val SCALE_STEPS_FEET = listOf(
            100.0, 200.0, 500.0, 1000.0, 2000.0, 5280.0, 10560.0, 26400.0, 52800.0
        )
    }
}
