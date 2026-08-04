package com.rhecyee.firelinemap.medical

import android.content.Context
import android.location.Geocoder
import java.util.Locale

/**
 * Names a position after something nearby.
 *
 * Uses the platform geocoder, which needs no key and is backed by Google on
 * devices that carry the services. What comes back in open country is usually
 * a creek, a road or a county rather than a street address, which is exactly
 * what is wanted: "Chico Creek Medical" places a call for anyone listening in
 * a way that a date does not.
 *
 * Best effort. It needs a connection, it can return nothing, and the caller is
 * expected to carry on without it.
 */
class PlaceNamer(context: Context) {

    private val geocoder: Geocoder? =
        if (Geocoder.isPresent()) Geocoder(context.applicationContext, Locale.US) else null

    /** Blocking. Callers run it off the main thread. */
    @Suppress("DEPRECATION")
    fun nearbyName(latitude: Double, longitude: Double): String? {
        val geocoder = geocoder ?: return null
        val address = runCatching {
            geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
        }.getOrNull() ?: return null

        // In order of how useful each is for saying out loud.
        val candidates = listOfNotNull(
            address.featureName?.takeIf { it.isNotBlank() && !it.all(Char::isDigit) },
            address.thoroughfare,
            address.locality,
            address.subAdminArea,
            address.adminArea
        )
        return candidates.firstOrNull()?.trim()?.take(28)
    }
}
