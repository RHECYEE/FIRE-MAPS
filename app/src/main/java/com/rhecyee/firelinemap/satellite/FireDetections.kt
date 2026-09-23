package com.rhecyee.firelinemap.satellite

import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * A satellite whose thermal anomalies can be put on the map.
 *
 * All of these are near-real-time. There is a science-quality version of the
 * same data, human-checked and reprocessed, and it is not offered here
 * because it is not an alternative: at the time of writing the newest
 * science-quality VIIRS is more than six years behind today. It is a research
 * archive. On a going fire the only satellite product that exists is this
 * one, and it has not been looked at by anybody.
 *
 * That is why the layer says which satellite and which pass on the map
 * itself. A detection is evidence that something was hot when the bird went
 * over. It is not a fire perimeter, it is not confirmed, and the sensor
 * cannot tell a fire from a flare stack, a burn pile or a hot roof.
 */
enum class SatelliteSource(
    /** Shown on the map and in the key. */
    val label: String,
    /** The GIBS layer behind it. */
    val layerId: String,
    /** Ground sample distance, which is the honest limit on precision. */
    val resolutionMeters: Int,
    val detail: String
) {
    VIIRS_NOAA20(
        label = "VIIRS NOAA-20",
        layerId = "VIIRS_NOAA20_Thermal_Anomalies_375m_All_v2_NRT",
        resolutionMeters = 375,
        detail = "375 m · about two passes a day"
    ),
    VIIRS_NOAA21(
        label = "VIIRS NOAA-21",
        layerId = "VIIRS_NOAA21_Thermal_Anomalies_375m_All_v2_NRT",
        resolutionMeters = 375,
        detail = "375 m · newest of the three"
    ),
    VIIRS_SNPP(
        label = "VIIRS Suomi-NPP",
        layerId = "VIIRS_SNPP_Thermal_Anomalies_375m_All_v2_NRT",
        resolutionMeters = 375,
        detail = "375 m · the longest VIIRS record"
    ),

    /**
     * Coarser, and kept for the overpass times rather than the detail.
     *
     * A kilometre is most of a division. What MODIS is good for is that Terra
     * and Aqua cross at different hours from the VIIRS birds, so between them
     * there are more looks at the ground in a day.
     */
    MODIS(
        label = "MODIS Terra + Aqua",
        layerId = "MODIS_Combined_Thermal_Anomalies_All_v61_NRT",
        resolutionMeters = 1_000,
        detail = "1 km · different overpass times"
    );

    companion object {
        /** What is on by default once the layer is switched on at all. */
        val DEFAULT: Set<SatelliteSource> = setOf(VIIRS_NOAA20, VIIRS_NOAA21, VIIRS_SNPP)

        fun from(names: Set<String>): Set<SatelliteSource> =
            entries.filter { it.name in names }.toSet()
    }
}

/**
 * Builds the request for one map tile of detections.
 *
 * GIBS serves thermal anomalies through WMTS as vector tiles, which would
 * mean carrying a protobuf vector-tile decoder to draw four coloured dots.
 * The WMS renders the same layer to a PNG, so the tile is asked for by the
 * bounding box of the slippy tile that wants it -- which keeps the whole
 * thing inside the tile cache the terrain already uses, and means it works
 * offline afterwards exactly as terrain does.
 *
 * No key, no account, no agency login: the same reason the basemap comes
 * from the National Map and the elevation from Terrain Tiles.
 */
object DetectionTileRequest {

    /** Web Mercator's half-circumference, where the projection is cut off. */
    const val WORLD_EDGE_METERS = 20_037_508.342789244

    const val TILE_PIXELS = 256

    private const val ENDPOINT = "https://gibs.earthdata.nasa.gov/wms/epsg3857/nrt/wms.cgi"

    /** The EPSG:3857 bounds of a slippy tile, as west, south, east, north. */
    fun boundsOf(zoom: Int, x: Int, y: Int): DoubleArray {
        val span = 2.0 * WORLD_EDGE_METERS / (1 shl zoom)
        val west = -WORLD_EDGE_METERS + x * span
        val north = WORLD_EDGE_METERS - y * span
        return doubleArrayOf(west, north - span, west + span, north)
    }

    fun url(source: SatelliteSource, date: String, zoom: Int, x: Int, y: Int): String {
        val (west, south, east, north) = boundsOf(zoom, x, y).toList()
        return buildString {
            append(ENDPOINT)
            append("?SERVICE=WMS&REQUEST=GetMap&VERSION=1.3.0")
            append("&LAYERS=").append(source.layerId)
            append("&CRS=EPSG:3857")
            append("&BBOX=")
            append(
                String.format(
                    Locale.US, "%.6f,%.6f,%.6f,%.6f", west, south, east, north
                )
            )
            append("&WIDTH=").append(TILE_PIXELS)
            append("&HEIGHT=").append(TILE_PIXELS)
            append("&FORMAT=image/png&TRANSPARENT=true")
            append("&TIME=").append(date)
        }
    }

    private operator fun <T> List<T>.component4(): T = this[3]
}

/**
 * How old the detections on screen are.
 *
 * Carried around as its own thing rather than left implicit, because a
 * detection layer that does not say when it is from is worse than no layer:
 * the tiles keep rendering offline long after the connection went, and
 * yesterday's heat drawn over today's map without a date on it is a lie the
 * map is telling.
 */
data class DetectionAge(
    /** The pass being shown, as GIBS dates them: yyyy-MM-dd, UTC. */
    val date: String,
    val fetchedAt: Long?
) {
    /** Hours since the tiles on screen were actually retrieved, if known. */
    fun hoursSinceFetch(now: Long = System.currentTimeMillis()): Long? {
        val at = fetchedAt ?: return null
        return TimeUnit.MILLISECONDS.toHours((now - at).coerceAtLeast(0L))
    }

    /**
     * What the map says about its own age.
     *
     * Always names the date. Never says "live", because it is not: a VIIRS
     * pass is a snapshot from whenever the satellite crossed, and the nearest
     * this gets to real time is a few hours behind.
     */
    fun caption(sources: Collection<SatelliteSource>): String {
        val who = when (sources.size) {
            0 -> "No satellite selected"
            1 -> sources.first().label
            else -> "${sources.size} satellites"
        }
        val since = hoursSinceFetch()
        val held = when {
            since == null -> "not yet fetched"
            since < 1 -> "fetched just now"
            since == 1L -> "fetched 1 h ago"
            else -> "fetched $since h ago"
        }
        return "$who · pass of $date · $held"
    }
}
