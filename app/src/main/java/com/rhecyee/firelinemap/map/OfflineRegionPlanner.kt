package com.rhecyee.firelinemap.map

import kotlin.math.abs

/** A component of an offline region, each independently chosen and fetched. */
enum class RegionLayer(val label: String, val averageTileBytes: Long, val maxUsefulZoom: Int) {
    BASEMAP("Topographic basemap", 45_000, 16),
    HILLSHADE("Hillshade", 40_000, 14),
    IMAGERY("Aerial imagery", 90_000, 17);

    companion object {
        fun parse(csv: String): Set<RegionLayer> = csv.split(",")
            .mapNotNull { name -> entries.firstOrNull { it.name == name.trim() } }
            .toSet()
    }
}

/** How loudly to warn before starting. */
enum class SizeWarning {
    NONE,
    LARGE,
    VERY_LARGE,
    EXTREME,

    /** More than half the free space; needs explicit confirmation. */
    MOST_OF_FREE_SPACE,

    /** Will not fit even with room to work in; the one hard stop. */
    WILL_NOT_FIT
}

data class RegionLayerPlan(val layer: RegionLayer, val tiles: Long, val bytes: Long)

data class OfflineRegionPlan(
    val bounds: GeoBounds,
    val minZoom: Int,
    val maxZoom: Int,
    val layerPlans: List<RegionLayerPlan>
) {
    val totalTiles: Long get() = layerPlans.sumOf { it.tiles }
    val estimatedBytes: Long get() = layerPlans.sumOf { it.bytes }

    /**
     * Space needed on the way to finishing, not just at the end.
     *
     * A package needs room to arrive, be verified and be indexed, so the
     * figure that matters is larger than the download itself.
     */
    val requiredBytes: Long get() = (estimatedBytes * 1.25).toLong() + 256L * 1024 * 1024
}

/**
 * Sizes a user-drawn offline area before anything is fetched.
 *
 * The area is a polygon rather than a county because elevation, hydrography
 * and names do not follow county lines and incidents routinely cross them.
 *
 * Nothing here caps how much may be requested. Somebody with the storage and
 * a reason to hold half a state should be able to; the estimate exists so the
 * decision is informed, not so it can be refused. The only hard stop is the
 * download genuinely not fitting.
 */
object OfflineRegionPlanner {

    fun plan(
        bounds: GeoBounds,
        minZoom: Int,
        maxZoom: Int,
        layers: Set<RegionLayer>
    ): OfflineRegionPlan {
        val low = minZoom.coerceIn(0, 20)
        val high = maxZoom.coerceIn(low, 20)
        return OfflineRegionPlan(
            bounds = bounds,
            minZoom = low,
            maxZoom = high,
            layerPlans = layers.sortedBy { it.ordinal }.map { layer ->
                // A layer is not fetched past the zoom where it stops adding
                // anything: hillshade at zoom 17 is the same hillshade.
                val cap = minOf(high, layer.maxUsefulZoom)
                val tiles = if (cap < low) 0L else TileMath.tileCount(bounds, low, cap)
                RegionLayerPlan(layer, tiles, tiles * layer.averageTileBytes)
            }
        )
    }

    fun warningFor(plan: OfflineRegionPlan, freeBytes: Long): SizeWarning {
        if (freeBytes < plan.requiredBytes) return SizeWarning.WILL_NOT_FIT
        if (plan.estimatedBytes > freeBytes / 2) return SizeWarning.MOST_OF_FREE_SPACE
        val gigabytes = plan.estimatedBytes / 1_073_741_824.0
        return when {
            gigabytes >= 20 -> SizeWarning.EXTREME
            gigabytes >= 5 -> SizeWarning.VERY_LARGE
            gigabytes >= 1 -> SizeWarning.LARGE
            else -> SizeWarning.NONE
        }
    }

    fun warningText(warning: SizeWarning): String? = when (warning) {
        SizeWarning.NONE -> null
        SizeWarning.LARGE -> "Large offline area."
        SizeWarning.VERY_LARGE -> "Very large download."
        SizeWarning.EXTREME ->
            "Extremely large download. Check storage and power before starting."
        SizeWarning.MOST_OF_FREE_SPACE ->
            "This would use more than half the free space on this device."
        SizeWarning.WILL_NOT_FIT ->
            "Not enough free space, including room to verify and index."
    }

    /** Bounding box of a drawn ring. */
    fun boundsOf(ring: List<Pair<Double, Double>>): GeoBounds? {
        if (ring.size < 3) return null
        var south = 90.0
        var north = -90.0
        var west = 180.0
        var east = -180.0
        for ((latitude, longitude) in ring) {
            if (latitude < south) south = latitude
            if (latitude > north) north = latitude
            if (longitude < west) west = longitude
            if (longitude > east) east = longitude
        }
        if (south > north || west > east) return null
        return GeoBounds(south, west, north, east)
    }

    /**
     * Ground area of a drawn ring, in square miles.
     *
     * Spherical excess, the same approximation the measure tool uses; at these
     * sizes the difference from the ellipsoid is far inside the precision
     * anyone would quote an area to.
     */
    fun areaSquareMiles(ring: List<Pair<Double, Double>>): Double {
        if (ring.size < 3) return 0.0
        var total = 0.0
        for (i in ring.indices) {
            val (latitude1, longitude1) = ring[i]
            val (latitude2, longitude2) = ring[(i + 1) % ring.size]
            total += Math.toRadians(longitude2 - longitude1) *
                (2.0 + Math.sin(Math.toRadians(latitude1)) + Math.sin(Math.toRadians(latitude2)))
        }
        val squareMeters = abs(total * Earth.RADIUS_METERS * Earth.RADIUS_METERS / 2.0)
        return squareMeters / 2_589_988.110336
    }
}
