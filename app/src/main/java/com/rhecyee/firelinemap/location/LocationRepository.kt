package com.rhecyee.firelinemap.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocationRepository(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val appContext = context.applicationContext
    private val _locations = MutableStateFlow<Location?>(null)
    val locations: StateFlow<Location?> = _locations.asStateFlow()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            _locations.value = result.lastLocation
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val fine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateDistanceMeters(2f)
            .build()
        client.requestLocationUpdates(request, callback, appContext.mainLooper)
    }

    fun stop() = client.removeLocationUpdates(callback)
}
