package com.rhecyee.firelinemap.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.rhecyee.firelinemap.satellite.SatelliteSource
import com.rhecyee.firelinemap.terrain.ContourField
import com.rhecyee.firelinemap.terrain.ContourGenerator

/**
 * Settings that belong to the device rather than to an incident.
 *
 * Plain preferences: these are how this phone is being used on this
 * assignment, and they have to be readable without waiting on a database.
 */
class AppSettings(context: Context) {

    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    /** Miles of terrain to keep around the operator. Zero is off. */
    var autoDownloadRadiusMiles: Int
        get() = preferences.getInt(KEY_RADIUS, 0)
        set(value) = preferences.edit()
            .putInt(KEY_RADIUS, value.coerceIn(0, MAX_RADIUS_MILES)).apply()

    /**
     * Whether keeping terrain is allowed on cellular.
     *
     * Defaults to Wi-Fi only. Terrain is the largest thing this app moves, and
     * quietly spending someone's hotspot allowance from a truck is not a
     * default worth having.
     */
    var autoDownloadWifiOnly: Boolean
        get() = preferences.getBoolean(KEY_WIFI_ONLY, true)
        set(value) = preferences.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    /** Whether the terrain basemap draws at all. */
    var topographyEnabled: Boolean
        get() = preferences.getBoolean(KEY_TOPO, true)
        set(value) = preferences.edit().putBoolean(KEY_TOPO, value).apply()

    var landOwnershipEnabled: Boolean
        get() = preferences.getBoolean(KEY_OWNERSHIP, true)
        set(value) = preferences.edit().putBoolean(KEY_OWNERSHIP, value).apply()

    /**
     * Whether contour lines are drawn over whatever map is open.
     *
     * Distinct from [topographyEnabled], which is the basemap picture. These
     * are drawn from elevation data, so they go over an incident sheet as
     * readily as over terrain -- which is the point of having them.
     */
    var contourLinesEnabled: Boolean
        get() = preferences.getBoolean(KEY_CONTOURS, true)
        set(value) = preferences.edit().putBoolean(KEY_CONTOURS, value).apply()

    /**
     * Vertical spacing between contour lines, in feet.
     *
     * Forty is what a USGS quad uses over most of the mountain west. Ground
     * flatter or steeper than that wants a different band, and the operator is
     * the one who can see which.
     */
    var contourIntervalFeet: Int
        get() = preferences.getInt(KEY_CONTOUR_INTERVAL, DEFAULT_CONTOUR_INTERVAL_FEET)
        set(value) = preferences.edit().putInt(
            KEY_CONTOUR_INTERVAL,
            if (value in CONTOUR_INTERVALS_FEET) value else DEFAULT_CONTOUR_INTERVAL_FEET
        ).apply()

    /**
     * Whether ground is tinted by how steep it is.
     *
     * On by default, which contour lines are not quite enough to justify on
     * their own: the lines are the more exact reading, but they have to be
     * counted and interpolated between, and the question "is that too steep
     * to put a dozer on" wants answering in the time it takes to glance at a
     * phone on a dashboard.
     */
    var slopeShadingEnabled: Boolean
        get() = preferences.getBoolean(KEY_SLOPE_SHADING, true)
        set(value) = preferences.edit().putBoolean(KEY_SLOPE_SHADING, value).apply()

    /**
     * Whether a shaded relief is drawn under the slope tint.
     *
     * Off by default, and not because it is not useful. Both basemaps already
     * carry relief of their own -- the USGS topo has it printed in, and the
     * incident products come with hillshade baked into the sheet -- so adding
     * a second one mostly muddies the first. It earns its place over a plain
     * sheet, or wherever the printed shading is too faint to read, and that
     * is a judgement only the person looking at it can make.
     */
    var hillshadeEnabled: Boolean
        get() = preferences.getBoolean(KEY_HILLSHADE, false)
        set(value) = preferences.edit().putBoolean(KEY_HILLSHADE, value).apply()

    /** How strongly the shading is laid over the map, nought to one. */
    var shadingOpacity: Float
        get() = preferences.getFloat(KEY_SHADING_OPACITY, DEFAULT_SHADING_OPACITY)
        set(value) = preferences.edit()
            .putFloat(KEY_SHADING_OPACITY, value.coerceIn(0.2f, 1f)).apply()

    /**
     * Whether satellite thermal anomalies are drawn.
     *
     * Off by default, and it should stay a deliberate choice. These are
     * unverified near-real-time detections: a satellite saw something hot
     * when it went over, which is not the same as a fire, and the nearest it
     * gets to now is a few hours behind. Switched on without being asked for,
     * it would be read as where the fire is.
     */
    var satelliteDetectionsEnabled: Boolean
        get() = preferences.getBoolean(KEY_DETECTIONS, false)
        set(value) = preferences.edit().putBoolean(KEY_DETECTIONS, value).apply()

    /** Which satellites, by name. More birds means more looks in a day. */
    var satelliteSources: Set<String>
        get() = preferences.getStringSet(KEY_DETECTION_SOURCES, null)
            ?: SatelliteSource.DEFAULT.map { it.name }.toSet()
        set(value) = preferences.edit().putStringSet(KEY_DETECTION_SOURCES, value).apply()

    /**
     * The sheet the operator has open.
     *
     * Persisted so the Android Auto screen shows the same map as the phone.
     * The car display can be brought up with the phone activity long dead, and
     * a car screen showing terrain but not the incident's sheet would be the
     * exact failure the car screen exists to avoid.
     */
    val terrainOnly: Boolean get() = activeMapId == TERRAIN_ONLY

    var activeMapId: String?
        get() = preferences.getString(KEY_ACTIVE_MAP, null)
        set(value) = preferences.edit().putString(KEY_ACTIVE_MAP, value).apply()

    /** Null when offline; true when the connection is not metered. */
    fun connectionState(): Pair<Boolean, Boolean> {
        val manager = app.getSystemService(ConnectivityManager::class.java)
            ?: return false to false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            ?: return false to false
        val connected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val unmetered = capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_NOT_METERED
        )
        return connected to unmetered
    }

    /** Whether terrain may be fetched right now. */
    fun mayAutoDownload(): Boolean {
        if (autoDownloadRadiusMiles <= 0) return false
        val (connected, unmetered) = connectionState()
        if (!connected) return false
        return unmetered || !autoDownloadWifiOnly
    }

    companion object {
        private const val KEY_RADIUS = "auto_download_radius_miles"
        private const val KEY_WIFI_ONLY = "auto_download_wifi_only"
        private const val KEY_TOPO = "topography_enabled"
        private const val KEY_OWNERSHIP = "land_ownership_enabled"
        private const val KEY_ACTIVE_MAP = "active_map_id"
        private const val KEY_CONTOURS = "contour_lines_enabled"
        private const val KEY_CONTOUR_INTERVAL = "contour_interval_feet"
        private const val KEY_SLOPE_SHADING = "slope_shading_enabled"
        private const val KEY_HILLSHADE = "hillshade_enabled"
        private const val KEY_SHADING_OPACITY = "shading_opacity"
        private const val KEY_DETECTIONS = "satellite_detections_enabled"
        private const val KEY_DETECTION_SOURCES = "satellite_detection_sources"

        const val DEFAULT_SHADING_OPACITY = 0.85f

        // Aliased rather than restated: two lists of intervals is one list
        // that will eventually disagree with the other.
        const val DEFAULT_CONTOUR_INTERVAL_FEET = ContourField.DEFAULT_INTERVAL_FEET
        val CONTOUR_INTERVALS_FEET = ContourGenerator.INTERVALS_FEET

        fun describeInterval(feet: Int): String = "$feet ft"

        /**
         * Stored in place of a sheet id when terrain is the map on purpose.
         *
         * Distinct from having stored nothing. Nothing means the app has never
         * chosen, and the most recent import wins; this means the operator
         * chose terrain over the sheets they have, and it has to survive being
         * closed or the choice would be undone by the next launch.
         */
        const val TERRAIN_ONLY = "terrain-only"

        const val MAX_RADIUS_MILES = 50
        val RADIUS_CHOICES = listOf(0, 5, 10, 25, 50)

        fun describeRadius(miles: Int): String =
            if (miles <= 0) "Off" else "$miles mi"
    }
}
