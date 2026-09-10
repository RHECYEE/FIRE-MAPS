package com.rhecyee.firelinemap.car

import android.Manifest
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import com.rhecyee.firelinemap.FirelineApplication
import com.rhecyee.firelinemap.R
import com.rhecyee.firelinemap.car.CarLinkLog
import com.rhecyee.firelinemap.data.ensureActiveIncident
import com.rhecyee.firelinemap.location.TrackRecordingService
import com.rhecyee.firelinemap.location.TrackRecordingState
import kotlinx.coroutines.launch

/**
 * The map screen shown on the car display.
 *
 * The map itself is drawn by [CarMapRenderer] straight onto the car surface;
 * this class only supplies the template around it. Everything reachable from
 * here is deliberately a single press with no list to read: pan, zoom,
 * recentre, which way is up, and whether travel is being recorded.
 */
class FirelineMapScreen(
    carContext: CarContext,
    private val renderer: CarMapRenderer
) : Screen(carContext) {

    private val application = carContext.applicationContext as FirelineApplication

    init {
        CarLinkLog.record(carContext, "Map screen created")
        renderer.onStateChanged = { invalidate() }
        lifecycleScope.launch {
            // The phone can arm or disarm recording while the car screen is up.
            TrackRecordingState.armed.collect { invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        val builder = NavigationTemplate.Builder()
            .setActionStrip(actionStrip())

        // Pan and the map controls arrived in car API level 2. Declaring a
        // minimum of 1 keeps the app on older head units, which then simply get
        // the map without them rather than not getting the app at all.
        if (carContext.carAppApiLevel >= CAR_API_MAP_CONTROLS) {
            builder.setMapActionStrip(mapActionStrip())
        }
        return builder.build()
    }

    private fun actionStrip(): ActionStrip {
        val builder = ActionStrip.Builder()
        if (application.location.hasPermission()) {
            builder.addAction(recordAction())
        } else {
            builder.addAction(
                Action.Builder()
                    .setTitle("Grant GPS")
                    .setOnClickListener { requestLocation() }
                    .build()
            )
        }
        builder.addAction(
            Action.Builder()
                .setIcon(icon(R.drawable.ic_car_orientation))
                .setTitle(if (renderer.camera.headingUp) "Heading" else "North")
                .setOnClickListener {
                    renderer.camera.toggleHeadingUp()
                    renderer.render()
                    invalidate()
                }
                .build()
        )
        return builder.build()
    }

    private fun mapActionStrip(): ActionStrip = ActionStrip.Builder()
        .addAction(Action.Builder(Action.PAN).build())
        .addAction(
            Action.Builder()
                .setIcon(icon(R.drawable.ic_car_recenter))
                .setOnClickListener {
                    renderer.camera.recenter()
                    renderer.render()
                    invalidate()
                }
                .build()
        )
        .addAction(
            Action.Builder()
                .setIcon(icon(R.drawable.ic_car_zoom_in))
                .setOnClickListener {
                    renderer.camera.zoomIn()
                    renderer.render()
                }
                .build()
        )
        .addAction(
            Action.Builder()
                .setIcon(icon(R.drawable.ic_car_zoom_out))
                .setOnClickListener {
                    renderer.camera.zoomOut()
                    renderer.render()
                }
                .build()
        )
        .build()

    private fun recordAction(): Action {
        val armed = TrackRecordingState.armed.value
        return Action.Builder()
            .setIcon(icon(if (armed) R.drawable.ic_car_record_stop else R.drawable.ic_car_record))
            .setTitle(if (armed) "Stop" else "Record")
            .setOnClickListener { toggleRecording(armed) }
            .build()
    }

    /**
     * Arms or disarms travel recording from the car.
     *
     * The point of having this here is that the phone is usually in a pocket or
     * face down on the dash by the time anyone realises the drive in was not
     * recorded. Failure is reported on the car screen rather than thrown: the
     * platform can refuse to start a foreground service depending on what state
     * the app is in, and that must not take the map down with it.
     */
    private fun toggleRecording(armed: Boolean) {
        lifecycleScope.launch {
            // Settled rather than read. Android Auto can start the service
            // with the phone screen never having run this shift, and a
            // recording begun with no incident to file against was finalised
            // into nothing at all.
            val incidentId = runCatching {
                ensureActiveIncident(application.database.dao())
            }.getOrNull()

            val intent = Intent(carContext, TrackRecordingService::class.java).apply {
                action = if (armed) TrackRecordingService.ACTION_STOP
                else TrackRecordingService.ACTION_START
                putExtra(TrackRecordingService.EXTRA_INCIDENT_ID, incidentId)
                // Record here means this drive, from now. Nobody presses a
                // button in a moving vehicle and means "consider it".
                putExtra(TrackRecordingService.EXTRA_RECORD_NOW, true)
            }
            val started = runCatching {
                ContextCompat.startForegroundService(carContext, intent)
            }.isSuccess
            if (!started) {
                CarToast.makeText(
                    carContext,
                    "Could not start recording — open the app on the phone",
                    CarToast.LENGTH_LONG
                ).show()
            }
            invalidate()
        }
    }

    private fun requestLocation() {
        CarToast.makeText(
            carContext, "Check your phone to allow location", CarToast.LENGTH_LONG
        ).show()
        carContext.requestPermissions(
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        ) { granted, _ ->
            if (granted.isNotEmpty()) application.location.start()
            invalidate()
        }
    }

    private fun icon(@DrawableRes resource: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, resource)).build()

    private companion object {
        /** Car API level that introduced pan and the map action strip. */
        const val CAR_API_MAP_CONTROLS = 2
    }
}
