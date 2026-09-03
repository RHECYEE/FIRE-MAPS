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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

/**
 * Watches for travel and records it without being told to.
 *
 * The service stays resident once armed and opens a track when sustained
 * movement is detected, rather than waiting for someone to press a button
 * with gloves on. Detection itself lives in [TrackDetector], which is free of
 * Android types and covered by tests; this class handles the platform.
 */
class TrackRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var settingsStore: TrackSettingsStore
    private val detector by lazy { TrackDetector(settingsStore.settings()) }

    private var incidentId: String? = null
    private var trackId: String? = null
    private var armed = false
    private var fixesSincePersist = 0
    private var fixCount = 0
    private var rejectedCount = 0
    private var lastAccuracy = 0f
    private var lastSpeed = 0.0

    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { handle(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settingsStore = TrackSettingsStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                closeOpenTrack()
                disarm()
            }
            else -> {
                arm(intent?.getStringExtra(EXTRA_INCIDENT_ID))
                // Re-read in case the sheet or the setting changed while armed.
                detector.settings = settingsStore.settings()
                detector.anchors = (application as FirelineApplication).dropPoints
            }
        }
        // Restarting after process death resumes watching; the open track is
        // recovered from the database rather than being silently abandoned.
        return START_STICKY
    }

    private fun arm(requestedIncidentId: String?) {
        if (requestedIncidentId != null) incidentId = requestedIncidentId
        if (armed) return

        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            stopSelf()
            return
        }

        armed = true
        TrackRecordingState.setArmed(true)
        detector.settings = settingsStore.settings()
        detector.anchors = (application as FirelineApplication).dropPoints
        startForeground(NOTIFICATION_ID, notification("Watching for travel"))

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            // No displacement filter: the detector needs the stationary fixes
            // to decide that travel has ended.
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(false)
            .build()
        client.requestLocationUpdates(request, callback, mainLooper)

        scope.launch { recoverOpenTrack() }
    }

    private fun disarm() {
        TrackRecordingState.clear()
        TrackRecordingState.setArmed(false)
        client.removeLocationUpdates(callback)
        armed = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handle(location: android.location.Location) {
        val fix = Fix(
            latitude = location.latitude,
            longitude = location.longitude,
            timeMillis = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else 999f,
            speedMetersPerSecond = if (location.hasSpeed()) location.speed.toDouble() else null
        )

        fixCount++
        lastAccuracy = fix.accuracyMeters
        lastSpeed = fix.speedMetersPerSecond ?: 0.0
        if (fix.accuracyMeters > detector.settings.maxUsableAccuracyMeters) rejectedCount++

        val event = detector.onFix(fix)
        publish(fix.timeMillis)

        when (event) {
            is TrackEvent.Started -> {
                trackId = UUID.randomUUID().toString()
                updateNotification("Travel recording — 0.0 km")
                persist(event.atMillis, endedAt = null, isRecording = true)
            }
            is TrackEvent.Extended -> {
                updateNotification(
                    "Travel recording — %.1f km".format(event.distanceMeters / 1000.0)
                )
                // Writing the whole line on every fix would mean a growing
                // JSON blob every few seconds for a whole shift. Once a minute
                // is enough for the database's job, which is surviving a kill.
                fixesSincePersist++
                if (fixesSincePersist >= PERSIST_EVERY_FIXES) {
                    fixesSincePersist = 0
                    persist(detector.currentStartedAt, endedAt = null, isRecording = true)
                }
            }
            is TrackEvent.Paused -> {
                persist(detector.currentStartedAt, endedAt = null, isRecording = true)
                updateNotification(
                "Travel paused — %.1f km so far".format(detector.currentDistanceMeters / 1000.0)
                )
            }
            is TrackEvent.Resumed -> updateNotification(
                "Travel recording — %.1f km".format(detector.currentDistanceMeters / 1000.0)
            )
            is TrackEvent.Segmented -> updateNotification(
                "Leg %d ended at a drop point".format(detector.currentSegmentCount)
            )
            is TrackEvent.Ended -> {
                finalise(event)
                updateNotification("Watching for travel")
            }
            TrackEvent.None -> Unit
        }
    }

    private fun closeOpenTrack() {
        val event = detector.finish()
        if (event is TrackEvent.Ended) finalise(event)
    }

    private fun finalise(event: TrackEvent.Ended) {
        val id = trackId ?: return
        trackId = null
        val incident = incidentId ?: return

        if (!event.kept) {
            // Too short to be travel. Remove the in-progress row rather than
            // leaving a stub in the incident's track list.
            scope.launch {
                (application as FirelineApplication).database.dao().deleteTrack(id)
            }
            return
        }

        val track = event.track
        scope.launch {
            (application as FirelineApplication).database.dao().upsertTrack(
                TrackEntity(
                    id = id,
                    incidentId = incident,
                    name = "Travel ${NAME_FORMAT.format(track.startedAt)}",
                    startedAt = track.startedAt,
                    endedAt = track.endedAt,
                    elapsedSeconds = track.elapsedMillis / 1000,
                    distanceMeters = track.distanceMeters,
                    geometryGeoJson = geometryOf(track.points),
                    isRecording = false
                )
            )
        }
    }

    private fun persist(startedAt: Long, endedAt: Long?, isRecording: Boolean) {
        val id = trackId ?: return
        val incident = incidentId ?: return
        val distance = detector.currentDistanceMeters
        val now = endedAt ?: System.currentTimeMillis()
        scope.launch {
            (application as FirelineApplication).database.dao().upsertTrack(
                TrackEntity(
                    id = id,
                    incidentId = incident,
                    name = "Travel ${NAME_FORMAT.format(startedAt)}",
                    startedAt = startedAt,
                    endedAt = endedAt,
                    elapsedSeconds = (now - startedAt).coerceAtLeast(0) / 1000,
                    distanceMeters = distance,
                    geometryGeoJson = traceGeometry(),
                    isRecording = isRecording
                )
            )
        }
    }

    /** Closes out a track left open by a process kill. */
    private suspend fun recoverOpenTrack() {
        val dao = (application as FirelineApplication).database.dao()
        val open = dao.getActiveTrack() ?: return
        dao.upsertTrack(open.copy(isRecording = false, endedAt = open.endedAt ?: open.startedAt))
    }

    private fun publish(now: Long) {
        TrackRecordingState.update(
            LiveTrack(
                recording = detector.isRecording,
                paused = detector.isPaused,
                startedAt = detector.currentStartedAt,
                lastFixAt = now,
                distanceMeters = detector.currentDistanceMeters,
                movingMillis = detector.currentMovingMillis,
                pausedMillis = detector.currentPausedMillis,
                segmentCount = detector.currentSegmentCount,
                points = detector.currentTrace,
                fixCount = fixCount,
                rejectedCount = rejectedCount,
                lastAccuracyMeters = lastAccuracy,
                lastSpeedMetersPerSecond = lastSpeed,
                movingNow = detector.lastFixWasMoving,
                movingHeldMillis = detector.movingHeldMillis(now)
            )
        )
    }

    private fun traceGeometry(): String =
        detector.currentTrace.joinToString(
            prefix = "{\"type\":\"LineString\",\"coordinates\":[",
            postfix = "]}"
        ) { "[${it.second},${it.first}]" }

    private fun geometryOf(points: List<Fix>): String =
        points.joinToString(
            prefix = "{\"type\":\"LineString\",\"coordinates\":[",
            postfix = "]}"
        ) { "[${it.longitude},${it.latitude}]" }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Travel recording", NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
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

    override fun onDestroy() {
        closeOpenTrack()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.rhecyee.firelinemap.START_TRACK"
        const val ACTION_STOP = "com.rhecyee.firelinemap.STOP_TRACK"
        const val EXTRA_INCIDENT_ID = "incident_id"
        private const val CHANNEL_ID = "travel_recording"
        private const val NOTIFICATION_ID = 4102

        /** At a five second update this is roughly once a minute. */
        private const val PERSIST_EVERY_FIXES = 12
        private val NAME_FORMAT = SimpleDateFormat("MMM d HH:mm", Locale.US)
    }
}
