package com.rhecyee.firelinemap.location

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.rhecyee.firelinemap.FirelineApplication
import com.rhecyee.firelinemap.MainActivity
import com.rhecyee.firelinemap.data.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

class TrackRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val points = mutableListOf<Pair<Double, Double>>()
    private var startedAt = 0L
    private var trackId: String? = null
    private var incidentId: String? = null

    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { points += it.longitude to it.latitude }
            persist(isRecording = true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            else -> startRecording(intent?.getStringExtra(EXTRA_INCIDENT_ID))
        }
        return START_STICKY
    }

    private fun startRecording(requestedIncidentId: String?) {
        if (trackId != null) return
        incidentId = requestedIncidentId ?: return
        trackId = UUID.randomUUID().toString()
        startedAt = System.currentTimeMillis()
        startForeground(NOTIFICATION_ID, notification("Travel recording active"))

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            stopSelf()
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3_000L)
            .setMinUpdateDistanceMeters(3f)
            .build()
        client.requestLocationUpdates(request, callback, mainLooper)
        persist(isRecording = true)
    }

    private fun stopRecording() {
        client.removeLocationUpdates(callback)
        persist(isRecording = false, endedAt = System.currentTimeMillis())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun persist(isRecording: Boolean, endedAt: Long? = null) {
        val id = trackId ?: return
        val incident = incidentId ?: return
        val now = endedAt ?: System.currentTimeMillis()
        val geometry = points.joinToString(prefix = "{\"type\":\"LineString\",\"coordinates\":[", postfix = "]}") {
            "[${it.first},${it.second}]"
        }
        scope.launch {
            val app = application as FirelineApplication
            app.database.dao().upsertTrack(
                TrackEntity(
                    id = id,
                    incidentId = incident,
                    name = "Travel ${java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.US).format(startedAt)}",
                    startedAt = startedAt,
                    endedAt = endedAt,
                    elapsedSeconds = (now - startedAt).coerceAtLeast(0) / 1000,
                    geometryGeoJson = geometry,
                    isRecording = isRecording
                )
            )
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Travel recording", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle("Fireline Map")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.rhecyee.firelinemap.START_TRACK"
        const val ACTION_STOP = "com.rhecyee.firelinemap.STOP_TRACK"
        const val EXTRA_INCIDENT_ID = "incident_id"
        private const val CHANNEL_ID = "travel_recording"
        private const val NOTIFICATION_ID = 4102
    }
}
