package com.rhecyee.firelinemap.share

import com.rhecyee.firelinemap.util.GridCoordinates
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Positions as a message somebody can read off a screen.
 *
 * The attachment is the better answer when it arrives. This is the answer when
 * it does not -- and it very often does not: a picture message is capped
 * around three hundred kilobytes on most carriers, a file type nothing
 * recognises gets refused outright, and the person receiving it may have
 * nothing installed that opens a GPX at all.
 *
 * Text has none of those problems. It reaches any phone made in the last
 * thirty years, it survives being read aloud over a radio, and it can be
 * copied straight back into this app's coordinate search, which already reads
 * everything written here.
 *
 * Degrees and decimal minutes, because that is what gets read over a radio,
 * with the grid alongside for aviation.
 */
object ShareText {

    /**
     * Roughly four message segments.
     *
     * Concatenated text splits every 153 characters. Past about four parts,
     * phones start delivering them out of order and the person reading has to
     * reassemble a coordinate by hand, which is where a digit goes missing.
     */
    const val COMFORTABLE_LENGTH = 600

    /**
     * A whole package as a message.
     *
     * Pins in full, because a pin is a position and a position is the point.
     * Tracks only as a summary: a line does not survive being typed out, and
     * anybody who needs the shape needs the file.
     */
    fun message(pkg: SharePackage, includeGrid: Boolean = true): String {
        val out = StringBuilder()
        out.append(pkg.incidentName)

        pkg.pins.forEach { pin ->
            out.append('\n').append(pin(pin, includeGrid))
        }

        pkg.tracks.forEach { track ->
            out.append('\n').append(trackSummary(track))
        }

        if (pkg.tracks.isNotEmpty()) {
            out.append("\n(Track shapes need the GPX file.)")
        }
        pkg.author?.takeIf { it.isNotBlank() }?.let { out.append("\n-- ").append(it) }
        return out.toString()
    }

    /** One pin, on one line where it fits. */
    fun pin(pin: SharePin, includeGrid: Boolean = true): String {
        val out = StringBuilder()
        out.append(pin.title).append("  ").append(degreesDecimalMinutes(pin.latitude, pin.longitude))
        if (includeGrid) {
            GridCoordinates.toMgrs(pin.latitude, pin.longitude, 5)?.let {
                out.append("  ").append(it)
            }
        }
        pin.note?.takeIf { it.isNotBlank() }?.let { out.append("\n  ").append(it) }
        return out.toString()
    }

    private fun trackSummary(track: ShareTrack): String {
        val miles = track.distanceMeters / 1609.344
        val distance = if (miles >= 0.1) "${round(miles, 1)} mi" else "${track.distanceMeters.roundToInt()} m"
        val start = track.points.firstOrNull()
        val where = start?.let { " from ${degreesDecimalMinutes(it.latitude, it.longitude)}" } ?: ""
        return "${track.name}: $distance, ${track.points.size} points$where"
    }

    /**
     * "N 45 12.345 W 117 38.221".
     *
     * Written out here rather than taken from the app's formatter because this
     * has to compile for the web app too, and that formatter is built on a
     * platform one. Deliberately spaces rather than degree symbols and primes:
     * those survive a text message unpredictably, and this app's own parser
     * reads the plain form back without them.
     */
    fun degreesDecimalMinutes(latitude: Double, longitude: Double): String {
        return axis(latitude, north = true) + " " + axis(longitude, north = false)
    }

    private fun axis(value: Double, north: Boolean): String {
        val hemisphere = when {
            north -> if (value >= 0) "N" else "S"
            else -> if (value >= 0) "E" else "W"
        }
        val magnitude = abs(value)
        val degrees = magnitude.toInt()
        val minutes = (magnitude - degrees) * 60.0
        // Three places is about two metres, which is finer than any receiver
        // this will ever read from.
        return "$hemisphere $degrees ${round(minutes, 3, pad = 3)}"
    }

    /**
     * Whether this will go as one readable message.
     *
     * Not a limit -- a long message still sends. It is what lets the app say
     * "this is six parts" before somebody sends six parts to a division
     * supervisor on a hilltop with one bar.
     */
    fun segments(text: String): Int {
        if (text.length <= 160) return 1
        return (text.length + 152) / 153
    }

    fun isComfortable(text: String): Boolean = text.length <= COMFORTABLE_LENGTH

    private fun round(value: Double, places: Int, pad: Int = 0): String {
        var scale = 1L
        repeat(places) { scale *= 10 }
        val scaled = kotlin.math.round(abs(value) * scale).toLong()
        val whole = scaled / scale
        val fraction = scaled % scale
        val sign = if (value < 0) "-" else ""
        if (places == 0) return "$sign$whole"
        val digits = fraction.toString().padStart(places, '0')
        val trimmed = if (pad > 0) digits else digits.trimEnd('0').ifEmpty { "0" }
        return "$sign$whole.$trimmed"
    }
}
