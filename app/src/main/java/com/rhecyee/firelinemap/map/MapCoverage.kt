package com.rhecyee.firelinemap.map

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Where the operator is relative to the active incident map's coverage. */
enum class IncidentMapCoverage {
    /** Position falls inside the active map's extent. Fireline detail is valid here. */
    ON_MAP,

    /** Position is off the edge of the active map. Incident detail does not exist here. */
    OFF_MAP,

    /** No georeferenced map is active, so there is nothing to be on or off of. */
    NO_ACTIVE_MAP
}

/** How much preloaded terrain exists underneath the current position. */
enum class TerrainCoverage {
    /** Preloaded to a zoom useful for ground navigation. */
    DETAILED,

    /** Preloaded, but only at low zoom. Good for orientation, not for routing. */
    COARSE,

    /** Nothing preloaded here. The map falls back to a graticule. */
    NONE
}

/**
 * A preloaded terrain region on local storage.
 *
 * Deliberately decoupled from any specific tile format so a downloaded
 * MapLibre offline region and a sideloaded MBTiles file can be reasoned about
 * identically.
 */
data class TerrainRegion(
    val id: String,
    val bounds: GeoBounds,
    val minZoom: Int,
    val maxZoom: Int,
    val complete: Boolean
)

/**
 * The map state the operator is in right now.
 *
 * [metersOffMap] and [bearingToMapDegrees] are populated only when
 * [incident] is [IncidentMapCoverage.OFF_MAP], and describe the shortest way
 * back onto the incident map.
 */
data class CoverageStatus(
    val incident: IncidentMapCoverage,
    val terrain: TerrainCoverage,
    val metersOffMap: Double? = null,
    val bearingToMapDegrees: Double? = null
) {
    /**
     * True when the operator should be told, prominently, that incident detail
     * is not being displayed for their position.
     */
    val needsOffMapWarning: Boolean
        get() = incident == IncidentMapCoverage.OFF_MAP
}

/**
 * Resolves what the map canvas can actually show for a given position.
 *
 * The terrain basemap is not a fallback that replaces the incident map; it is
 * always rendered beneath it. This type exists to drive the operator-facing
 * warning and the preload prompts, not to switch rendering modes.
 */
object MapCoverage {

    /** Below this max zoom a preloaded region is orientation-only, not navigational. */
    const val DETAILED_MIN_MAX_ZOOM = 13

    private const val EARTH_RADIUS_METERS = 6371008.8

    fun resolve(
        latitude: Double,
        longitude: Double,
        activeMapBounds: GeoBounds?,
        terrainRegions: List<TerrainRegion>
    ): CoverageStatus {
        val terrain = resolveTerrain(latitude, longitude, terrainRegions)

        if (activeMapBounds == null) {
            return CoverageStatus(IncidentMapCoverage.NO_ACTIVE_MAP, terrain)
        }
        if (activeMapBounds.contains(latitude, longitude)) {
            return CoverageStatus(IncidentMapCoverage.ON_MAP, terrain)
        }

        val (nearestLat, nearestLon) = activeMapBounds.nearestPointTo(latitude, longitude)
        return CoverageStatus(
            incident = IncidentMapCoverage.OFF_MAP,
            terrain = terrain,
            metersOffMap = distanceMeters(latitude, longitude, nearestLat, nearestLon),
            bearingToMapDegrees = bearingDegrees(latitude, longitude, nearestLat, nearestLon)
        )
    }

    private fun resolveTerrain(
        latitude: Double,
        longitude: Double,
        regions: List<TerrainRegion>
    ): TerrainCoverage {
        var best = TerrainCoverage.NONE
        for (region in regions) {
            if (!region.complete) continue
            if (!region.bounds.contains(latitude, longitude)) continue
            if (region.maxZoom >= DETAILED_MIN_MAX_ZOOM) return TerrainCoverage.DETAILED
            best = TerrainCoverage.COARSE
        }
        return best
    }

    /** Haversine great-circle distance in metres. */
    fun distanceMeters(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double
    ): Double {
        val lat1 = Math.toRadians(fromLatitude)
        val lat2 = Math.toRadians(toLatitude)
        val deltaLat = Math.toRadians(toLatitude - fromLatitude)
        val deltaLon = Math.toRadians(toLongitude - fromLongitude)
        val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(a)))
    }

    /** Initial bearing in degrees clockwise from true north. */
    fun bearingDegrees(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double
    ): Double {
        val lat1 = Math.toRadians(fromLatitude)
        val lat2 = Math.toRadians(toLatitude)
        val deltaLon = Math.toRadians(toLongitude - fromLongitude)
        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
        if (abs(y) < 1e-12 && abs(x) < 1e-12) return 0.0
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}
