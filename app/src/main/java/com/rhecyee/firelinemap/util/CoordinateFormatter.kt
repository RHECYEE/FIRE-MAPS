package com.rhecyee.firelinemap.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

enum class CoordinateFormat { DDM, DECIMAL_DEGREES }

object CoordinateFormatter {
    fun format(latitude: Double, longitude: Double, format: CoordinateFormat): String =
        when (format) {
            CoordinateFormat.DDM -> "${toDdm(latitude, true)}  ${toDdm(longitude, false)}"
            CoordinateFormat.DECIMAL_DEGREES -> String.format(
                Locale.US,
                "%.6f, %.6f",
                latitude,
                longitude
            )
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
        return String.format(Locale.US, "%s %0${width}d° %06.3f'", hemisphere, degrees, minutes)
    }
}
