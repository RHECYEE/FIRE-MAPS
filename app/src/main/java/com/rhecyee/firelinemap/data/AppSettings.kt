package com.rhecyee.firelinemap.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

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

        const val MAX_RADIUS_MILES = 50
        val RADIUS_CHOICES = listOf(0, 5, 10, 25, 50)

        fun describeRadius(miles: Int): String =
            if (miles <= 0) "Off" else "$miles mi"
    }
}
