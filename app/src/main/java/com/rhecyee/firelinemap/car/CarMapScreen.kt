package com.rhecyee.firelinemap.car

import android.content.Intent
import android.graphics.Rect
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.rhecyee.firelinemap.FirelineApplication
import com.rhecyee.firelinemap.R
import com.rhecyee.firelinemap.location.LocationRepository
import com.rhecyee.firelinemap.location.TrackGeometry
import com.rhecyee.firelinemap.location.TrackRecordingService
import com.rhecyee.firelinemap.location.TrackRecordingState
import com.rhecyee.firelinemap.location.TrackColours
import com.rhecyee.firelinemap.location.TravelReadout
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The map, on the head unit.
 *
 * Reads the same state the phone screen reads -- the same recording service,
 * the same database, the same tile cache -- so the two are never showing
 * different things about the same shift. Nothing here owns any data of its own.
 *
 * Four controls, which is as many as belong on a screen somebody looks at while
 * driving: record, zoom in, zoom out, and re-centre. Everything that involves
 * reading or deciding stays on the phone.
 */
class CarMapScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    private val app = carContext.applicationContext as FirelineApplication
    private val tiles = com.rhecyee.firelinemap.map.BasemapTileCache(carContext)
    private val renderer = CarMapRenderer(tiles)
    private val locations = LocationRepository(carContext)

    private var surface: SurfaceContainer? = null
    private var visibleArea: Rect? = null

    private var state = CarMapState(viewport = CarViewport(0.0, 0.0, 13.0, 0, 0))
    private var watchers = mutableListOf<Job>()

    init {
        carContext.getCarService(androidx.car.app.AppManager::class.java)
            .setSurfaceCallback(this)

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = watch()
            override fun onStop(owner: LifecycleOwner) {
                watchers.forEach { it.cancel() }
                watchers.clear()
                locations.stop()
            }
        })
    }

    override fun onGetTemplate(): Template =
        NavigationTemplate.Builder()
            .setBackgroundColor(CarColor.SECONDARY)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(if (state.recording) "Stop" else "Record")
                            .setOnClickListener { toggleRecording() }
                            .build()
                    )
                    .addAction(
                        Action.Builder()
                            .setTitle(if (state.following) "Free" else "Centre")
                            .setOnClickListener {
                                state = state.copy(following = !state.following)
                                if (state.following) centreOnPosition()
                                invalidate()
                                render()
                            }
                            .build()
                    )
                    .build()
            )
            .setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.Builder(Action.PAN).build())
                    .addAction(
                        Action.Builder()
                            .setIcon(icon(android.R.drawable.ic_menu_add))
                            .setOnClickListener { zoomBy(1.0) }
                            .build()
                    )
                    .addAction(
                        Action.Builder()
                            .setIcon(icon(android.R.drawable.ic_menu_revert))
                            .setOnClickListener { zoomBy(-1.0) }
                            .build()
                    )
                    .build()
            )
            .build()

    private fun icon(resource: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, resource)).build()

    // ------------------------------------------------------------- the data

    /**
     * Watches everything the display shows.
     *
     * Each source drives a redraw of its own rather than a timer polling all of
     * them: a position arrives every couple of seconds and the incident changes
     * once a week, and a shared tick would either lag the first or waste the
     * battery on the second.
     */
    private fun watch() {
        locations.start()

        watchers += lifecycleScope.launch {
            locations.locations.collectLatest { location ->
                if (location == null) return@collectLatest
                state = state.copy(
                    position = CarPosition(location.latitude, location.longitude)
                )
                if (state.following) centreOnPosition()
                render()
            }
        }

        watchers += lifecycleScope.launch {
            TrackRecordingState.live.collectLatest { live ->
                val figures = TravelReadout.of(
                    recording = live.recording,
                    paused = live.paused,
                    armed = true,
                    elapsedMillis = live.elapsedMillis,
                    distanceMeters = live.distanceMeters,
                    movingMillis = live.movingMillis,
                    pausedMillis = live.pausedMillis,
                    pointCount = live.points.size
                )
                val was = state.recording
                state = state.copy(
                    liveTrack = live.points,
                    recording = live.recording,
                    paused = live.paused,
                    distance = figures.distance,
                    // Moving time, because on a division that is the number
                    // that says how the road actually drives.
                    elapsed = figures.moving
                )
                // The action strip only changes wording when recording starts
                // or stops; rebuilding the template on every fix would flicker.
                if (was != live.recording) invalidate()
                render()
            }
        }

        watchers += lifecycleScope.launch {
            app.database.dao().observeActiveIncident().collectLatest { incident ->
                state = state.copy(incidentName = incident?.name)
                if (incident == null) { render(); return@collectLatest }

                launch {
                    app.database.dao().observeMarkers(incident.id).collectLatest { markers ->
                        state = state.copy(markers = markers)
                        render()
                    }
                }
                launch {
                    app.database.dao().observeTracks(incident.id).collectLatest { tracks ->
                        state = state.copy(
                            savedTracks = tracks.map { track ->
                                CarTrack(
                                    points = TrackGeometry.readPositions(track.geometryGeoJson),
                                    colourArgb = TrackColours.forId(track.id)
                                )
                            }
                        )
                        render()
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------- controls

    private fun toggleRecording() {
        val intent = Intent(carContext, TrackRecordingService::class.java).apply {
            action = if (state.recording) TrackRecordingService.ACTION_STOP
            else TrackRecordingService.ACTION_START
        }
        ContextCompat.startForegroundService(carContext, intent)
    }

    private fun zoomBy(steps: Double) {
        state = state.copy(
            viewport = state.viewport.copy(
                zoom = CarViewport.clampZoom(state.viewport.zoom + steps)
            )
        )
        render()
    }

    private fun centreOnPosition() {
        val at = state.position ?: return
        state = state.copy(
            viewport = state.viewport.copy(latitude = at.latitude, longitude = at.longitude)
        )
    }

    // -------------------------------------------------------------- surface

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        surface = container
        resize(container)
        render()
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        surface = null
    }

    override fun onVisibleAreaChanged(area: Rect) {
        visibleArea = area
        render()
    }

    override fun onStableAreaChanged(area: Rect) {
        visibleArea = area
        render()
    }

    /**
     * Panning, when the host offers it.
     *
     * Only while not following: a map that fights the driver's finger to
     * re-centre on the vehicle is worse than one that does not move at all.
     */
    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (state.following) return
        val view = state.viewport
        if (view.widthPixels == 0) return
        val centre = view.toGround(
            view.widthPixels / 2f + distanceX,
            view.heightPixels / 2f + distanceY
        )
        state = state.copy(
            viewport = view.copy(latitude = centre.latitude, longitude = centre.longitude)
        )
        render()
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (scaleFactor <= 0f) return
        val steps = kotlin.math.ln(scaleFactor.toDouble()) / kotlin.math.ln(2.0)
        zoomBy(steps)
    }

    private fun resize(container: SurfaceContainer) {
        state = state.copy(
            viewport = state.viewport.copy(
                widthPixels = container.width,
                heightPixels = container.height
            )
        )
    }

    private fun render() {
        val container = surface ?: return
        if (container.width <= 0 || container.height <= 0) return
        if (state.viewport.widthPixels != container.width) resize(container)

        val canvas = container.surface?.lockCanvas(null) ?: return
        try {
            renderer.draw(canvas, state)
        } finally {
            container.surface?.unlockCanvasAndPost(canvas)
        }
    }
}
