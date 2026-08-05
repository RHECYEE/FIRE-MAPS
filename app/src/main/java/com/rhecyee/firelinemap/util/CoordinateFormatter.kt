package com.rhecyee.firelinemap.util

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

enum class CoordinateFormat { DDM, DECIMAL_DEGREES }

/**
 * Positions as they are written down and read out.
 *
 * The padding is not cosmetic. A column of coordinates that all sit at the
 * same width can be scanned for the digit that changed; a ragged one cannot,
 * and this is read off a screen in a hurry with a radio in the other hand.
 * Minutes always carry three decimals for the same reason -- "12.3" and
 * "12.300" are the same number, but the short one invites a missing digit.
 *
 * Written without the platform's formatter so the phone and the web app
 * produce the same string. The alternative is two spellings of one position,
 * which is the sort of difference nobody notices until two people are reading
 * different things to each other over a radio.
 */
object CoordinateFormatter {
    fun format(latitude: Double, longitude: Double, format: CoordinateFormat): String =
        when (format) {
            CoordinateFormat.DDM -> "${toDdm(latitude, true)}  ${toDdm(longitude, false)}"
            CoordinateFormat.DECIMAL_DEGREES ->
                "${fixed(latitude, 6)}, ${fixed(longitude, 6)}"
        }

    private fun toDdm(value: Double, latitude: Boolean): String {
        val hemisphere = when {
            latitude && value >= 0 -> "N"
            latitude -> "S"
            value >= 0 -> "E"
            else -> "W"
        }
        val absolute = abs(value)
        val degrees = floor(absolute).toInt()
        val minutes = (absolute - degrees) * 60.0
        val width = if (latitude) 2 else 3
        // Six wide including the point and its three decimals, so 6.04 reads
        // as "06.040" and lines up underneath 43.177.
        return "$hemisphere ${degrees.toString().padStart(width, '0')}° " +
            "${fixed(minutes, 3).padStart(6, '0')}'"
    }

    /** A number with exactly [places] decimals, rounded half away from zero. */
    private fun fixed(value: Double, places: Int): String {
        if (!value.isFinite()) return "0"
        var scale = 1L
        repeat(places) { scale *= 10 }
        val scaled = round(abs(value) * scale).toLong()
        val whole = scaled / scale
        val fraction = scaled % scale
        val sign = if (value < 0) "-" else ""
        if (places == 0) return "$sign$whole"
        return "$sign$whole." + fraction.toString().padStart(places, '0')
    }
}
