package com.rhecyee.firelinemap.web

import com.rhecyee.firelinemap.location.Fix
import com.rhecyee.firelinemap.location.TrackDetectionSettings
import com.rhecyee.firelinemap.location.TrackDetector
import com.rhecyee.firelinemap.location.TrackEvent
import com.rhecyee.firelinemap.location.TrackLine
import com.rhecyee.firelinemap.location.TrackRecord
import com.rhecyee.firelinemap.location.TravelReadout
import com.rhecyee.firelinemap.location.TrackOverlap
import com.rhecyee.firelinemap.map.MapCoverage
import com.rhecyee.firelinemap.map.TileMath
import com.rhecyee.firelinemap.share.ExactDuplicates
import com.rhecyee.firelinemap.share.SharePackage
import com.rhecyee.firelinemap.share.SharePin
import com.rhecyee.firelinemap.share.SharePoint
import com.rhecyee.firelinemap.share.ShareText
import com.rhecyee.firelinemap.share.ShareTrack
import com.rhecyee.firelinemap.share.TextCodec
import com.rhecyee.firelinemap.util.CoordinateParseResult
import com.rhecyee.firelinemap.util.CoordinateParser
import com.rhecyee.firelinemap.util.GridCoordinates
import kotlin.js.json

/**
 * What the browser is allowed to call.
 *
 * A deliberate seam. Everything behind it is the phone app's own Kotlin,
 * compiled unchanged; everything in front of it is JavaScript. Keeping the
 * surface small and explicit means the page cannot come to depend on the
 * internals of a class that was written for Android, and the two can be read
 * separately.
 *
 * Plain types only across the boundary. Kotlin data classes do survive into
 * JavaScript, but as objects whose field names the compiler is free to change,
 * and a page built on those breaks silently on a compiler upgrade.
 */
@JsExport
@JsName("Fireline")
object Api {

    // ------------------------------------------------------------ coordinates

    /**
     * Reads a typed coordinate, in every format the phone accepts.
     *
     * Returns null when it is not yet a position, so the page can leave the
     * query alone while it is being typed rather than flashing an error at
     * every keystroke.
     */
    fun parseCoordinate(text: String): CoordinateResult? {
        val parsed = CoordinateParser.parse(text)
        if (parsed !is CoordinateParseResult.Success) return null
        val it = parsed.coordinate
        return CoordinateResult(
            latitude = it.latitude,
            longitude = it.longitude,
            south = it.southLatitude,
            north = it.northLatitude,
            west = it.westLongitude,
            east = it.eastLongitude,
            format = it.format.label,
            exact = it.shape.name == "POINT"
        )
    }

    /** "N 45 12.345 W 117 38.220", the form that goes over a radio. */
    fun formatDdm(latitude: Double, longitude: Double): String =
        ShareText.degreesDecimalMinutes(latitude, longitude)

    /** "11TNL4997506004", for aviation. Null outside the grid's range. */
    fun formatMgrs(latitude: Double, longitude: Double, digits: Int): String? =
        GridCoordinates.toMgrs(latitude, longitude, digits)

    /** "11T 449974 5006004". */
    fun formatUtm(latitude: Double, longitude: Double): String? =
        GridCoordinates.toUtm(latitude, longitude)?.format()

    // ----------------------------------------------------------------- ground

    fun distanceMeters(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double
    ): Double = MapCoverage.distanceMeters(
        fromLatitude, fromLongitude, toLatitude, toLongitude
    )

    /** Web Mercator tile column for a longitude at a zoom. */
    fun tileX(longitude: Double, zoom: Int): Int = TileMath.tileX(longitude, zoom)

    /** Web Mercator tile row for a latitude at a zoom. */
    fun tileY(latitude: Double, zoom: Int): Int = TileMath.tileY(latitude, zoom)

    // ------------------------------------------------------------- recording

    /**
     * Opens a recorder with the phone's own thresholds.
     *
     * The same detector, so a track recorded in a browser starts, pauses and
     * ends where one recorded on a phone would. Anything else and two people
     * on the same road produce tracks that cannot be compared.
     */
    fun recorder(stopThresholdSeconds: Int): Recorder = Recorder(
        TrackDetector(
            TrackDetectionSettings(stopThresholdMillis = stopThresholdSeconds * 1000L)
        )
    )

    // --------------------------------------------------------------- sharing

    /** Splits an incident into pasteable parts. */
    fun encodeParts(json: String): Array<String> =
        TextCodec.parts(PackageJson.read(json)).toTypedArray()

    /**
     * Reads a pasted part.
     *
     * Assembly is left to the page, which already has to hold the parts
     * between pastes and show what is missing.
     */
    fun readPart(pasted: String): PartResult? {
        val part = TextCodec.readPart(pasted) ?: return null
        return PartResult(part.checksum, part.index, part.total, part.slice)
    }

    /** Whether a reassembled body is what was sent. */
    fun verify(body: String, checksum: String): Boolean =
        TextCodec.checksum(body) == checksum

    /** Decodes a reassembled body into the page's own JSON shape. */
    fun decodeParts(body: String): String? =
        TextCodec.decode(body)?.let { PackageJson.write(it) }

    /**
     * What of [incomingJson] is not already in [existingJson].
     *
     * The same rule the phone applies, run by the same code: a record that
     * encodes to exactly the bytes of one already held is the same thing
     * arriving twice. Two pins near each other are not, and are never merged --
     * that is a judgement for a person on a radio.
     *
     * Exposed rather than reimplemented in the page, because a second version
     * of this would eventually disagree with the first about which pin to
     * throw away.
     */
    fun withoutDuplicates(incomingJson: String, existingJson: String): String {
        val result = ExactDuplicates.filter(
            PackageJson.read(incomingJson),
            PackageJson.read(existingJson)
        )
        return PackageJson.writeFiltered(result)
    }

    /**
     * What a track's points show, and what they only imply.
     *
     * The browser is where this matters. iOS suspends a web app the moment it
     * is backgrounded or the screen locks, so a shift routinely comes back as
     * recorded pieces with unobserved stretches between them. Those stretches
     * are real travel and worth keeping -- but a line drawn across one looks
     * exactly like a line that was followed, so the page needs to know which
     * is which in order to draw it differently and label it honestly.
     *
     * Returns the gaps as well as the figures, so the page can dash them.
     */
    fun provenanceOf(pointsJson: String): String {
        val points = PackageJson.readTracks(pointsJson)
            .firstOrNull()?.points.orEmpty()
            .map { Fix(it.latitude, it.longitude, it.timeMillis ?: 0L) }
        return PackageJson.writeProvenance(TrackRecord.of(points))
    }

    // ---------------------------------------------------------------- merging

    /**
     * Every track through a spot, with the figures the readout leads with.
     *
     * The same analysis the phone runs, so a road quoted from a browser and
     * the same road quoted from a phone give the same answer.
     */
    fun tracksAt(latitude: Double, longitude: Double, tracksJson: String): String {
        val lines = PackageJson.readTracks(tracksJson).map { track ->
            TrackLine(
                id = track.id,
                name = track.name,
                points = track.points.map {
                    Fix(it.latitude, it.longitude, it.timeMillis ?: 0L)
                }
            )
        }
        return PackageJson.writeOverlap(TrackOverlap.at(latitude, longitude, lines))
    }
}

/** A parsed coordinate, flattened for JavaScript. */
@JsExport
class CoordinateResult(
    val latitude: Double,
    val longitude: Double,
    val south: Double,
    val north: Double,
    val west: Double,
    val east: Double,
    val format: String,
    val exact: Boolean
)

/** One pasted part. */
@JsExport
class PartResult(
    val checksum: String,
    val index: Int,
    val total: Int,
    val slice: String
)

/**
 * A live recording, driven a fix at a time.
 *
 * Wraps the phone's detector rather than reimplementing it. The page feeds
 * positions in as the browser reports them and reads back what changed, so
 * every decision about when travel started, paused or ended is made by code
 * that has been tested against real shifts.
 */
@JsExport
class Recorder internal constructor(private val detector: TrackDetector) {

    var recording: Boolean = false
        private set
    var paused: Boolean = false
        private set
    var distanceMeters: Double = 0.0
        private set

    // Kept here rather than in the detector: they are about what the browser's
    // receiver is reporting, which is a different question from what the
    // detector makes of it, and the panel has to be able to tell the two apart.
    private var fixCount: Int = 0
    private var rejectedCount: Int = 0
    private var lastAccuracyMeters: Double = 0.0
    private var lastSpeedMetersPerSecond: Double = 0.0

    /** Feeds a fix. Returns the name of what happened, for the page to act on. */
    fun onFix(
        latitude: Double,
        longitude: Double,
        timeMillis: Double,
        accuracyMeters: Double,
        speedMetersPerSecond: Double
    ): String {
        fixCount++
        lastAccuracyMeters = accuracyMeters
        if (speedMetersPerSecond >= 0) lastSpeedMetersPerSecond = speedMetersPerSecond
        if (accuracyMeters > detector.settings.maxUsableAccuracyMeters) rejectedCount++

        val event = detector.onFix(
            Fix(
                latitude = latitude,
                longitude = longitude,
                timeMillis = timeMillis.toLong(),
                accuracyMeters = accuracyMeters.toFloat(),
                speedMetersPerSecond = speedMetersPerSecond.takeIf { it >= 0 }
            )
        )
        recording = detector.isRecording
        paused = detector.isPaused
        distanceMeters = detector.currentDistanceMeters
        return when (event) {
            is TrackEvent.Started -> "started"
            is TrackEvent.Extended -> "extended"
            is TrackEvent.Paused -> "paused"
            is TrackEvent.Resumed -> "resumed"
            is TrackEvent.Segmented -> "segmented"
            is TrackEvent.Ended -> if (event.kept) "ended" else "discarded"
            TrackEvent.None -> "none"
        }
    }

    /**
     * The live readout, worded by the shared code.
     *
     * The browser asks the same question the phone's panel asks and gets the
     * same sentences back, including the split between elapsed and moving
     * time. Two apps describing one shift differently is worse than either
     * describing it at all.
     */
    fun stats(nowMillis: Double): String {
        val now = nowMillis.toLong()
        val figures = TravelReadout.of(
            recording = detector.isRecording,
            paused = detector.isPaused,
            armed = true,
            elapsedMillis = detector.currentElapsedMillis(now),
            distanceMeters = detector.currentDistanceMeters,
            movingMillis = detector.currentMovingMillis,
            pausedMillis = detector.currentPausedMillis,
            pointCount = detector.currentPointCount,
            fixCount = fixCount,
            rejectedCount = rejectedCount,
            lastAccuracyMeters = lastAccuracyMeters,
            lastSpeedMetersPerSecond = lastSpeedMetersPerSecond,
            movingNow = detector.lastFixWasMoving,
            movingHeldMillis = detector.movingHeldMillis(now),
            startSustainedMillis = detector.settings.startSustainedMillis
        )
        return json(
            "state" to figures.state,
            "accent" to figures.accent.name,
            "recording" to figures.recording,
            "elapsed" to figures.elapsed,
            "moving" to figures.moving,
            "paused" to figures.paused,
            "distance" to figures.distance,
            "chains" to figures.chains,
            "averageSpeed" to figures.averageSpeed,
            "movingSpeed" to figures.movingSpeed,
            "points" to figures.points,
            "waiting" to figures.waiting,
            "diagnostics" to figures.diagnostics
        ).let { JSON.stringify(it) }
    }

    /** The line so far, as flat latitude and longitude pairs. */
    fun trace(): Array<Double> {
        val out = mutableListOf<Double>()
        detector.currentTrace.forEach { out += it.first; out += it.second }
        return out.toTypedArray()
    }

    /** Closes the track. Returns the finished track as JSON, or null if it was dropped. */
    fun finish(): String? {
        val event = detector.finish()
        recording = false
        paused = false
        if (event !is TrackEvent.Ended || !event.kept) return null
        return PackageJson.write(
            SharePackage(
                incidentName = "",
                tracks = listOf(
                    ShareTrack(
                        id = "",
                        name = "Travel",
                        points = event.track.points.map {
                            SharePoint(it.latitude, it.longitude, it.timeMillis)
                        },
                        startedAt = event.track.startedAt,
                        endedAt = event.track.endedAt,
                        distanceMeters = event.track.distanceMeters
                    )
                )
            )
        )
    }
}

/** Unused placeholder so the pin type is exported alongside the rest. */
@JsExport
fun pinOf(
    title: String,
    latitude: Double,
    longitude: Double,
    symbolId: String
): String = PackageJson.write(
    SharePackage(
        incidentName = "",
        pins = listOf(SharePin("", title, latitude, longitude, symbolId))
    )
)
