package com.rhecyee.firelinemap.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.rhecyee.firelinemap.data.AppSettings
import com.rhecyee.firelinemap.data.PowerMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocationRepository(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val appContext = context.applicationContext
    private val settings = AppSettings(appContext)
    private val _locations = MutableStateFlow<Location?>(null)
    val locations: StateFlow<Location?> = _locations.asStateFlow()

    private var started = false

    /** What the running request was built from, so a change can be noticed. */
    private var appliedInterval = 0L
    private var appliedMode: PowerMode? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { _locations.value = it }
        }
    }

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_FINE_LOCATION
        )
        val coarse = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_COARSE_LOCATION
        )
        return fine == PackageManager.PERMISSION_GRANTED ||
            coarse == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Begins location updates, reporting whether it could.
     *
     * Safe to call repeatedly. The caller is expected to call it again once
     * the permission prompt resolves: the first call happens while the dialog
     * is still on screen, and without a retry the app would sit on "waiting
     * for GPS" forever despite holding the permission.
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!hasPermission()) return false

        val interval = settings.effectiveLocationIntervalMillis()
        val mode = settings.powerMode
        // A rate or mode change has to replace the request; leaving the old one
        // running means the setting appears to do nothing until a restart.
        if (started && interval == appliedInterval && mode == appliedMode) return true
        if (started) client.removeLocationUpdates(callback)
        started = true
        appliedInterval = interval
        appliedMode = mode

        // A periodic request only delivers once the receiver produces a new
        // fix, which can take a while cold. Ask for whatever is already known
        // so the panel populates immediately.
        runCatching {
            client.lastLocation.addOnSuccessListener { location ->
                if (location != null && _locations.value == null) _locations.value = location
            }
            client.getCurrentLocation(
                CurrentLocationRequest.Builder()
                    .setPriority(priorityFor(mode))
                    .build(),
                null
            ).addOnSuccessListener { location ->
                if (location != null) _locations.value = location
            }
        }

        val request = LocationRequest.Builder(priorityFor(mode), interval)
            // No minimum displacement: a stationary operator still needs the
            // accuracy and elevation readout to refresh.
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(false)
            .build()
        client.requestLocationUpdates(request, callback, appContext.mainLooper)
        return true
    }

    /** Re-reads the settings and rebuilds the request if they moved. */
    fun refresh() {
        if (started) start()
    }

    fun stop() {
        client.removeLocationUpdates(callback)
        started = false
        appliedMode = null
    }

    companion object {
        /**
         * Saver drops to the fused providers, which is what actually saves the
         * battery -- a slower GNSS interval on its own barely helps, because
         * the receiver stays warm between fixes.
         */
        fun priorityFor(mode: PowerMode): Int = when (mode) {
            PowerMode.PRECISE, PowerMode.BALANCED -> Priority.PRIORITY_HIGH_ACCURACY
            PowerMode.SAVER -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
    }
}
