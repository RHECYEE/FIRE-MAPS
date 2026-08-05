package com.rhecyee.firelinemap.web

import com.rhecyee.firelinemap.location.OverlapReport
import com.rhecyee.firelinemap.share.SharePackage
import com.rhecyee.firelinemap.share.SharePin
import com.rhecyee.firelinemap.share.SharePoint
import com.rhecyee.firelinemap.share.ShareTrack
import kotlin.js.Json
import kotlin.js.json

/**
 * The shape the page and the Kotlin agree on.
 *
 * JSON rather than exported classes, because the field names of an exported
 * Kotlin class are the compiler's to change and a page built on them breaks
 * quietly on an upgrade. A named JSON shape is a contract both sides can be
 * read against.
 *
 * The browser's own parser does the work: it is faster than anything written
 * here and it is already there.
 */
internal object PackageJson {

    fun write(pkg: SharePackage): String = JSON.stringify(toJson(pkg))

    fun read(text: String): SharePackage {
        val raw = JSON.parse<Json>(text)
        return SharePackage(
            incidentName = raw["incidentName"] as? String ?: "Incident",
            author = raw["author"] as? String,
            createdAt = (raw["createdAt"] as? Number)?.toLong() ?: 0L,
            pins = readArray(raw["pins"]).map { pin ->
                SharePin(
                    id = pin["id"] as? String ?: "",
                    title = pin["title"] as? String ?: "Pin",
                    latitude = number(pin["latitude"]) ?: 0.0,
                    longitude = number(pin["longitude"]) ?: 0.0,
                    symbolId = pin["symbolId"] as? String,
                    note = pin["note"] as? String,
                    status = pin["status"] as? String,
                    createdAt = (pin["createdAt"] as? Number)?.toLong()
                )
            },
            tracks = readTracksFrom(raw["tracks"])
        )
    }

    fun readTracks(text: String): List<ShareTrack> =
        readTracksFrom(JSON.parse<Json>(text)["tracks"] ?: JSON.parse(text))

    private fun readTracksFrom(value: Any?): List<ShareTrack> =
        readArray(value).map { track ->
            ShareTrack(
                id = track["id"] as? String ?: "",
                name = track["name"] as? String ?: "Track",
                points = readArray(track["points"]).mapNotNull { point ->
                    val latitude = number(point["latitude"]) ?: return@mapNotNull null
                    val longitude = number(point["longitude"]) ?: return@mapNotNull null
                    SharePoint(
                        latitude = latitude,
                        longitude = longitude,
                        timeMillis = (point["timeMillis"] as? Number)?.toLong()
                            ?.takeIf { it > 0 },
                        elevationMeters = number(point["elevationMeters"])
                    )
                },
                startedAt = (track["startedAt"] as? Number)?.toLong(),
                endedAt = (track["endedAt"] as? Number)?.toLong(),
                distanceMeters = number(track["distanceMeters"]) ?: 0.0,
                activityType = track["activityType"] as? String,
                note = track["note"] as? String
            )
        }

    /** The merge readout, with the figures already formatted the way it reads. */
    fun writeOverlap(report: OverlapReport): String = JSON.stringify(
        json(
            "count" to report.passes.size,
            "isOverlap" to report.isOverlap,
            "stoppedCount" to report.stoppedCount,
            "averageSpeed" to report.averageTrackSpeed?.let {
                OverlapReport.formatSpeed(it)
            },
            "averageTime" to report.averageElapsedMillis?.let {
                OverlapReport.formatElapsed(it)
            },
            "totalDistance" to OverlapReport.formatDistance(report.totalDistanceMeters),
            "totalTime" to OverlapReport.formatElapsed(report.totalElapsedMillis),
            "spotSpeed" to report.averageSpeedMetersPerSecond?.let {
                OverlapReport.formatSpeed(it)
            },
            "passes" to report.passes.map { pass ->
                json(
                    "trackId" to pass.trackId,
                    "name" to pass.trackName,
                    "atMillis" to (pass.atMillis?.toDouble() ?: 0.0),
                    "distance" to OverlapReport.formatDistance(pass.trackDistanceMeters),
                    "elapsed" to OverlapReport.formatElapsed(pass.trackElapsedMillis),
                    "average" to pass.trackAverageSpeed?.let {
                        OverlapReport.formatSpeed(it)
                    },
                    "spotSpeed" to pass.speedMetersPerSecond?.let {
                        OverlapReport.formatSpeed(it)
                    },
                    "stopped" to pass.stopped,
                    "offMeters" to pass.closestMeters.toInt()
                )
            }.toTypedArray()
        )
    )

    private fun toJson(pkg: SharePackage): Json = json(
        "incidentName" to pkg.incidentName,
        "author" to pkg.author,
        "createdAt" to pkg.createdAt.toDouble(),
        "pins" to pkg.pins.map { pin ->
            json(
                "id" to pin.id,
                "title" to pin.title,
                "latitude" to pin.latitude,
                "longitude" to pin.longitude,
                "symbolId" to pin.symbolId,
                "note" to pin.note,
                "status" to pin.status,
                "createdAt" to (pin.createdAt?.toDouble() ?: 0.0)
            )
        }.toTypedArray(),
        "tracks" to pkg.tracks.map { track ->
            json(
                "id" to track.id,
                "name" to track.name,
                "startedAt" to (track.startedAt?.toDouble() ?: 0.0),
                "endedAt" to (track.endedAt?.toDouble() ?: 0.0),
                "distanceMeters" to track.distanceMeters,
                "activityType" to track.activityType,
                "note" to track.note,
                "points" to track.points.map { point ->
                    json(
                        "latitude" to point.latitude,
                        "longitude" to point.longitude,
                        "timeMillis" to (point.timeMillis?.toDouble() ?: 0.0),
                        "elevationMeters" to point.elevationMeters
                    )
                }.toTypedArray()
            )
        }.toTypedArray()
    )

    @Suppress("UNCHECKED_CAST")
    private fun readArray(value: Any?): List<Json> {
        val array = value as? Array<Json> ?: return emptyList()
        return array.toList()
    }

    private fun number(value: Any?): Double? = (value as? Number)?.toDouble()
}
