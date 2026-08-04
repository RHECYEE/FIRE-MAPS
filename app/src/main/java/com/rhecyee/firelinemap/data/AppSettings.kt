package com.rhecyee.firelinemap.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * How hard the receiver is driven.
 *
 * A phone is the only navigation device most crews carry and there is rarely
 * anywhere to charge it, so this is a real operational choice rather than a
 * nicety. The floor exists because asking for a fix every second in Saver is
 * a contradiction: the mode is chosen to stop doing that.
 */
enum class PowerMode(
    val label: String,
    val detail: String,
    val intervalFloorSeconds: Int
) {
    PRECISE(
        "Precise",
        "GNSS at full rate. For line construction and mapping, plugged in or " +
            "on a short shift.",
        1
    ),
    BALANCED(
        "Balanced",
        "Good enough to navigate and record travel on, at a fraction of the " +
            "drain. The default.",
        5
    ),
    SAVER(
        "Saver",
        "Coarse and slow. Position still updates and the track still records, " +
            "but corners will be cut off it.",
        30
    );

    companion object {
        fun fromName(name: String?): PowerMode =
            entries.firstOrNull { it.name == name } ?: BALANCED
    }
}

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

    /**
     * Whether contours are cut and drawn.
     *
     * Separate from the topographic basemap, which only has somebody else's
     * lines painted into a picture. These are cut here from elevation data, so
     * they follow the zoom and can be labelled -- and they cost a download of
     * their own, which is why they are their own switch.
     */
    var contoursEnabled: Boolean
        get() = preferences.getBoolean(KEY_CONTOURS, true)
        set(value) = preferences.edit().putBoolean(KEY_CONTOURS, value).apply()

    var landOwnershipEnabled: Boolean
        get() = preferences.getBoolean(KEY_OWNERSHIP, true)
        set(value) = preferences.edit().putBoolean(KEY_OWNERSHIP, value).apply()

    /**
     * How long the controls stay up before the map takes the screen back.
     *
     * Zero keeps them up until they are dismissed, which is what someone
     * planning at a table wants; twenty seconds is what someone walking wants.
     */
    var chromeTimeoutSeconds: Int
        get() = preferences.getInt(KEY_CHROME_TIMEOUT, DEFAULT_CHROME_TIMEOUT_SECONDS)
        set(value) = preferences.edit().putInt(KEY_CHROME_TIMEOUT, value.coerceIn(0, 300)).apply()

    /** How often a fix is asked for. Subject to the power mode's floor. */
    var locationIntervalSeconds: Int
        get() = preferences.getInt(KEY_LOCATION_INTERVAL, DEFAULT_LOCATION_INTERVAL_SECONDS)
        set(value) = preferences.edit()
            .putInt(KEY_LOCATION_INTERVAL, value.coerceIn(1, 120)).apply()

    var powerMode: PowerMode
        get() = PowerMode.fromName(preferences.getString(KEY_POWER_MODE, null))
        set(value) = preferences.edit().putString(KEY_POWER_MODE, value.name).apply()

    /**
     * The interval actually requested, once the power mode has had its say.
     *
     * The mode wins. Someone who has picked Saver because they are down to a
     * fifth of a battery on a night shift is not served by an interval left
     * over from when they were plugged in that morning.
     */
    fun effectiveLocationIntervalSeconds(): Int =
        effectiveInterval(locationIntervalSeconds, powerMode)

    fun effectiveLocationIntervalMillis(): Long =
        effectiveLocationIntervalSeconds() * 1_000L

    /** Milliseconds before the map takes the screen back, or null for never. */
    fun chromeTimeoutMillis(): Long? =
        chromeTimeoutSeconds.takeIf { it > 0 }?.let { it * 1_000L }

    /**
     * Where the map was last looking, so it opens there.
     *
     * With no sheet imported the app draws its own terrain around a point, and
     * that point has to come from somewhere before the first fix arrives.
     * Remembering it means someone who lands at camp with no signal still gets
     * the ground they were on yesterday, from tiles already on the device,
     * instead of a blank screen until the receiver settles.
     */
    var lastAnchor: Pair<Double, Double>?
        get() {
            val latitude = preferences.getFloat(KEY_ANCHOR_LATITUDE, Float.NaN)
            val longitude = preferences.getFloat(KEY_ANCHOR_LONGITUDE, Float.NaN)
            if (latitude.isNaN() || longitude.isNaN()) return null
            return latitude.toDouble() to longitude.toDouble()
        }
        set(value) {
            if (value == null) return
            preferences.edit()
                .putFloat(KEY_ANCHOR_LATITUDE, value.first.toFloat())
                .putFloat(KEY_ANCHOR_LONGITUDE, value.second.toFloat())
                .apply()
        }

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

    /**
     * Whether the ground currently on screen may be fetched.
     *
     * Separate from [mayAutoDownload], which asks whether to keep a radius of
     * ground the operator is not looking at. This is what is on the screen
     * right now: a few dozen tiles, the same handful the terrain picture is
     * already fetching unconditionally.
     *
     * It deliberately does not consult the Wi-Fi-only preference. That setting
     * exists to stop the app spending a hotspot allowance on ground nobody
     * asked for, and it was quietly stopping contours from ever appearing on a
     * cellular connection while the basemap picture arrived beside them --
     * which reads as the contour layer being broken.
     */
    fun mayFetchForView(): Boolean = connectionState().first

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
        private const val KEY_ANCHOR_LATITUDE = "last_anchor_latitude"
        private const val KEY_ANCHOR_LONGITUDE = "last_anchor_longitude"
        private const val KEY_CONTOURS = "contours_enabled"
        private const val KEY_OWNERSHIP = "land_ownership_enabled"
        private const val KEY_CHROME_TIMEOUT = "chrome_timeout_seconds"
        private const val KEY_LOCATION_INTERVAL = "location_interval_seconds"
        private const val KEY_POWER_MODE = "power_mode"

        const val MAX_RADIUS_MILES = 50
        val RADIUS_CHOICES = listOf(0, 5, 10, 25, 50)

        const val DEFAULT_CHROME_TIMEOUT_SECONDS = 20
        val CHROME_TIMEOUT_CHOICES = listOf(0, 10, 20, 45, 90)

        const val DEFAULT_LOCATION_INTERVAL_SECONDS = 2
        val LOCATION_INTERVAL_CHOICES = listOf(1, 2, 5, 15, 30)

        fun describeRadius(miles: Int): String =
            if (miles <= 0) "Off" else "$miles mi"

        fun describeChromeTimeout(seconds: Int): String =
            if (seconds <= 0) "Never" else "${seconds}s"

        fun describeInterval(seconds: Int): String =
            if (seconds >= 60) "${seconds / 60} min" else "${seconds}s"

        /**
         * The interval a chosen rate and mode actually come to.
         *
         * Kept as a function rather than inlined at each call site so the
         * settings sheet can show the same number the receiver will be given
         * -- a sheet that says two seconds while the request says thirty is
         * worse than no setting at all.
         */
        fun effectiveInterval(chosenSeconds: Int, mode: PowerMode): Int =
            maxOf(chosenSeconds, mode.intervalFloorSeconds)
    }
}
