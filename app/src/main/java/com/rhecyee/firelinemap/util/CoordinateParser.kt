package com.rhecyee.firelinemap.util

import kotlin.math.abs

enum class CoordinateInputFormat(val label: String) {
    DECIMAL_DEGREES("decimal degrees"),
    DEGREES_MINUTES("degrees and minutes"),
    DEGREES_MINUTES_SECONDS("degrees, minutes and seconds")
}

data class ParsedCoordinate(
    val latitude: Double,
    val longitude: Double,
    val format: CoordinateInputFormat,
    /** True where a hemisphere was assumed rather than typed. */
    val assumedHemisphere: Boolean
)

sealed interface CoordinateParseResult {
    data class Success(val coordinate: ParsedCoordinate) : CoordinateParseResult

    /** Valid so far, but not yet enough numbers to be a position. */
    data class Incomplete(val message: String) : CoordinateParseResult

    data class Invalid(val message: String) : CoordinateParseResult
}

/**
 * Reads a coordinate typed as bare numbers.
 *
 * Built for copying a position off the radio, where the person reading it out
 * will not say which format it is in and there is no time to pick one from a
 * menu. The count of numbers decides: two is decimal degrees, four is degrees
 * and minutes, six is degrees, minutes and seconds. Degree symbols, primes,
 * commas and hemisphere letters are all accepted but none are required.
 *
 * Hemispheres default to north and west when not stated. Every incident this
 * is built for is in the western United States, and a longitude typed without
 * a sign is far more likely to be a missing minus than a position in China.
 * The caller is expected to show what was assumed.
 */
object CoordinateParser {

    fun parse(input: String): CoordinateParseResult {
        val text = input.trim()
        if (text.isEmpty()) return CoordinateParseResult.Incomplete("Type a coordinate")

        val upper = text.uppercase()
        if (upper.any { it.isLetter() && it !in "NSEW" }) {
            return CoordinateParseResult.Invalid("Only numbers and N, S, E or W")
        }

        val numbers = NUMBER.findAll(upper).map { it.value }.toList()
        if (numbers.isEmpty()) return CoordinateParseResult.Incomplete("Type a coordinate")

        val values = numbers.mapNotNull { it.toDoubleOrNull() }
        if (values.size != numbers.size) {
            return CoordinateParseResult.Invalid("That is not a number")
        }

        val north = when {
            upper.contains('S') -> false
            else -> true
        }
        val west = when {
            upper.contains('E') -> false
            else -> true
        }
        val statedHemisphere = upper.any { it in "NSEW" }
        // A typed minus is an explicit statement of hemisphere too.
        val signed = numbers.any { it.startsWith("-") }

        return when (values.size) {
            2 -> build(
                latitudeDegrees = values[0],
                longitudeDegrees = values[1],
                format = CoordinateInputFormat.DECIMAL_DEGREES,
                north = north,
                west = west,
                assumed = !statedHemisphere && !signed
            )

            4 -> {
                if (values[1] < 0 || values[1] >= 60) {
                    return CoordinateParseResult.Invalid("Latitude minutes must be under 60")
                }
                if (values[3] < 0 || values[3] >= 60) {
                    return CoordinateParseResult.Invalid("Longitude minutes must be under 60")
                }
                build(
                    latitudeDegrees = combine(values[0], values[1] / 60.0),
                    longitudeDegrees = combine(values[2], values[3] / 60.0),
                    format = CoordinateInputFormat.DEGREES_MINUTES,
                    north = north,
                    west = west,
                    assumed = !statedHemisphere && !signed
                )
            }

            6 -> {
                if (values[1] < 0 || values[1] >= 60 || values[2] < 0 || values[2] >= 60) {
                    return CoordinateParseResult.Invalid("Latitude minutes and seconds under 60")
                }
                if (values[4] < 0 || values[4] >= 60 || values[5] < 0 || values[5] >= 60) {
                    return CoordinateParseResult.Invalid("Longitude minutes and seconds under 60")
                }
                build(
                    latitudeDegrees = combine(values[0], values[1] / 60.0 + values[2] / 3600.0),
                    longitudeDegrees = combine(values[3], values[4] / 60.0 + values[5] / 3600.0),
                    format = CoordinateInputFormat.DEGREES_MINUTES_SECONDS,
                    north = north,
                    west = west,
                    assumed = !statedHemisphere && !signed
                )
            }

            1, 3, 5 -> CoordinateParseResult.Incomplete(
                "Keep going — ${values.size} numbers so far"
            )

            else -> CoordinateParseResult.Invalid("Too many numbers")
        }
    }

    /** Applies a fractional part while preserving a negative degree value. */
    private fun combine(degrees: Double, fraction: Double): Double =
        if (degrees < 0) degrees - fraction else degrees + fraction

    private fun build(
        latitudeDegrees: Double,
        longitudeDegrees: Double,
        format: CoordinateInputFormat,
        north: Boolean,
        west: Boolean,
        assumed: Boolean
    ): CoordinateParseResult {
        // A typed sign wins over a hemisphere letter's default; an explicit
        // letter flips an unsigned value.
        var latitude = latitudeDegrees
        if (latitude >= 0 && !north) latitude = -latitude

        var longitude = longitudeDegrees
        if (longitude >= 0 && west) longitude = -longitude
        if (longitude < 0 && !west) longitude = abs(longitude)

        if (abs(latitude) > 90.0) {
            return CoordinateParseResult.Invalid("Latitude must be 90 or less")
        }
        if (abs(longitude) > 180.0) {
            return CoordinateParseResult.Invalid("Longitude must be 180 or less")
        }

        return CoordinateParseResult.Success(
            ParsedCoordinate(latitude, longitude, format, assumed)
        )
    }

    private val NUMBER = Regex("""-?\d+(?:\.\d+)?""")
}
