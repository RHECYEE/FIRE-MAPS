package com.rhecyee.firelinemap.map

/**
 * One downloadable component of the offline area package.
 *
 * [averageTileBytes] is a calibration constant, not a measurement. It should
 * be re-derived from real downloads once a tile source is chosen; the estimate
 * shown to the operator is only as honest as this number.
 */
data class AreaDataSource(
    val id: String,
    val label: String,
    val minZoom: Int,
    val maxZoom: Int,
    val averageTileBytes: Long
) {
    companion object {
        /**
         * Vector terrain: roads, trails, hydrography, contours, place names.
         *
         * Capped at zoom 14 because vector tiles overzoom for free -- z14 data
         * renders correctly at z18 -- which is the difference between a ~12 MB
         * download and a ~700 MB one for the same ground.
         */
        val TERRAIN_VECTOR = AreaDataSource(
            id = "terrain-vector",
            label = "Terrain, roads and trails",
            minZoom = 0,
            maxZoom = 14,
            averageTileBytes = 15_000
        )

        /** Shaded relief. Capped lower still; hillshade carries no fine detail. */
        val HILLSHADE_DEM = AreaDataSource(
            id = "hillshade-dem",
            label = "Shaded relief",
            minZoom = 0,
            maxZoom = 13,
            averageTileBytes = 50_000
        )

        val DEFAULTS = listOf(TERRAIN_VECTOR, HILLSHADE_DEM)
    }
}

/** Per-source contribution to a planned download. */
data class AreaDataSourcePlan(
    val source: AreaDataSource,
    val tileCount: Long
) {
    val estimatedBytes: Long get() = tileCount * source.averageTileBytes
}

/** What a single press of the download button will fetch. */
data class AreaDataPlan(
    val bounds: GeoBounds,
    val sourcePlans: List<AreaDataSourcePlan>
) {
    val totalTiles: Long get() = sourcePlans.sumOf { it.tileCount }
    val estimatedBytes: Long get() = sourcePlans.sumOf { it.estimatedBytes }

    /**
     * Storage to require before starting.
     *
     * Carries headroom over the estimate because the tile-size constants are
     * averages and because filling the volume mid-write is a good way to
     * corrupt the incident database along with the download.
     */
    val requiredBytes: Long get() = (estimatedBytes * STORAGE_HEADROOM).toLong() + SLACK_BYTES

    companion object {
        const val STORAGE_HEADROOM = 1.35
        const val SLACK_BYTES = 32L * 1024 * 1024
    }
}

object AreaDataPlanner {

    /** Ground covered beyond the incident map's edge. Roughly three miles. */
    const val DEFAULT_BUFFER_METERS = 5_000.0

    /** Used when no georeferenced map has been imported yet. */
    const val DEFAULT_RADIUS_METERS = 15_000.0

    /**
     * Plans the download for the active incident.
     *
     * Prefers the imported map's extent, since that is the ground the incident
     * actually occupies. Falls back to a radius around the current position so
     * the button still works on day one, before any map has been imported --
     * which is precisely when someone is sitting at ICP with a connection.
     */
    fun plan(
        incidentBounds: GeoBounds?,
        currentLatitude: Double? = null,
        currentLongitude: Double? = null,
        bufferMeters: Double = DEFAULT_BUFFER_METERS,
        radiusMeters: Double = DEFAULT_RADIUS_METERS,
        sources: List<AreaDataSource> = AreaDataSource.DEFAULTS
    ): AreaDataPlan? {
        val target = when {
            incidentBounds != null -> TileMath.buffer(incidentBounds, bufferMeters)
            currentLatitude != null && currentLongitude != null ->
                TileMath.around(currentLatitude, currentLongitude, radiusMeters)
            else -> return null
        }

        return AreaDataPlan(
            bounds = target,
            sourcePlans = sources.map { source ->
                AreaDataSourcePlan(
                    source = source,
                    tileCount = TileMath.tileCount(target, source.minZoom, source.maxZoom)
                )
            }
        )
    }
}
