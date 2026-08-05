package com.rhecyee.firelinemap.share

/** One position on a shared track. */
data class SharePoint(
    val latitude: Double,
    val longitude: Double,
    /** Milliseconds since the epoch, or null when the time was not recorded. */
    val timeMillis: Long? = null,
    val elevationMeters: Double? = null
)

/** A track being handed to somebody else. */
data class ShareTrack(
    val id: String,
    val name: String,
    val points: List<SharePoint>,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val distanceMeters: Double = 0.0,
    val activityType: String? = null,
    val note: String? = null
)

/** A pin being handed to somebody else. */
data class SharePin(
    val id: String,
    val title: String,
    val latitude: Double,
    val longitude: Double,
    /** A [com.rhecyee.firelinemap.resources.ResourceSymbol] id. */
    val symbolId: String? = null,
    val note: String? = null,
    val status: String? = null,
    val createdAt: Long? = null
)

/**
 * What one person hands to another.
 *
 * Deliberately a file rather than a link or an account. Everything else this
 * app refuses to depend on -- a server, a login, an agency org -- would come
 * straight back in through a sharing feature that needed any of them, and the
 * places this gets used are the places with no signal to reach them over.
 *
 * A file goes over whatever the phone already has: Bluetooth, AirDrop, a
 * message, an email, a cable. None of that has to be built here, and all of it
 * works when a tower does not.
 */
data class SharePackage(
    val incidentName: String,
    val tracks: List<ShareTrack> = emptyList(),
    val pins: List<SharePin> = emptyList(),
    val createdAt: Long = 0L,
    /** Who it came from, so a track arriving on a phone has an owner. */
    val author: String? = null
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && pins.isEmpty()

    /** "3 tracks · 12 pins", for the button and the confirmation. */
    fun describe(): String {
        if (isEmpty) return "Nothing to send"
        return buildList {
            if (tracks.isNotEmpty()) add("${tracks.size} track${plural(tracks.size)}")
            if (pins.isNotEmpty()) add("${pins.size} pin${plural(pins.size)}")
        }.joinToString(" · ")
    }

    private fun plural(count: Int) = if (count == 1) "" else "s"
}
