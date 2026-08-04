package com.rhecyee.firelinemap.util

import kotlin.math.abs

enum class CoordinateInputFormat(val label: String) {
    DECIMAL_DEGREES("decimal degrees"),
    DEGREES_MINUTES("degrees and minutes"),
    DEGREES_MINUTES_SECONDS("degrees, minutes and seconds")
}

/** What a query narrows the position down to. */
enum class SearchShape {
    /** Everything was known. */
    POINT,

    /** One axis is uncertain, so the answer lies along a line. */
    LINE,

    /** Both axes are uncertain, so the answer lies inside a box. */
    AREA
}

data class ParsedCoordinate(
    /** Centre of the possible range. Equals the exact value when nothing is unknown. */
    val latitude: Double,
    val longitude: Double,
    val southLatitude: Double,
    val northLatitude: Double,
    val westLongitude: Double,
    val eastLongitude: Double,
    val format: CoordinateInputFormat,
    /** True where a hemisphere was assumed rather than typed. */
    val assumedHemisphere: Boolean,
    /** True where a decimal point was inferred into a run of digits. */
    val inferredDecimal: Boolean
) {
    val shape: SearchShape
        get() {
            val latSpan = northLatitude - southLatitude
            val lonSpan = eastLongitude - westLongitude
            val latKnown = latSpan < 1e-9
            val lonKnown = lonSpan < 1e-9
            return when {
                latKnown && lonKnown -> SearchShape.POINT
                latKnown || lonKnown -> SearchShape.LINE
                else -> SearchShape.AREA
            }
        }
}

sealed interface CoordinateParseResult {
    data class Success(val coordinate: ParsedCoordinate) : CoordinateParseResult

    /** Valid so far, but not yet enough numbers to be a position. */
    data class Incomplete(val message: String) : CoordinateParseResult

    data class Invalid(val message: String) : CoordinateParseResult
}

/**
 * Reads a coordinate typed as bare numbers, with room for what was missed.
 *
 * Built for copying a position off the radio. Whoever reads it out will not
 * say which format it is in, so the count of numbers decides: two is decimal
 * degrees, four is degrees and minutes, six adds seconds. Degree symbols,
 * primes, commas and hemisphere letters are accepted; none are required.
 *
 * Two allowances for the radio itself:
 *
 * * A dropped decimal point is put back. Four three one seven seven in a
 *   minutes slot can only be 43.177, because 43177 minutes is not a thing.
 * * An X stands for a digit that was not caught. The result then covers every
 *   position it could still be -- a line if one axis is uncertain, a box if
 *   both -- so a partial copy is still worth something, and narrows as the
 *   rest of it arrives.
 *
 * Hemispheres default to north and west. Every incident this serves is in the
 * western United States, and an unsigned longitude is far likelier to be a
 * dropped minus than a position off China.
 */
object CoordinateParser {

    /** A slot's value, which may be a range when digits were missed. */
    private data class Slot(val min: Double, val max: Double, val inferredDecimal: Boolean) {
        val known: Boolean get() = max - min < 1e-9
    }

    fun parse(input: String): CoordinateParseResult {
        val text = input.trim()
        if (text.isEmpty()) return CoordinateParseResult.Incomplete("Type a coordinate")

        val upper = text.uppercase()
        if (upper.any { it.isLetter() && it !in "NSEWX" }) {
            return CoordinateParseResult.Invalid("Only numbers, X, and N S E W")
        }

        val tokens = TOKEN.findAll(upper).map { it.value }.toList()
        if (tokens.isEmpty()) return CoordinateParseResult.Incomplete("Type a coordinate")

        val north = !upper.contains('S')
        val west = !upper.contains('E')
        val statedHemisphere = upper.any { it in "NSEW" }
        val signed = tokens.any { it.startsWith("-") }
        val assumed = !statedHemisphere && !signed

        return when (tokens.size) {
            2 -> assemble(
                latitudeSlots = listOf(slot(tokens[0], 90.0) ?: return bad(tokens[0])),
                longitudeSlots = listOf(slot(tokens[1], 180.0) ?: return bad(tokens[1])),
                format = CoordinateInputFormat.DECIMAL_DEGREES,
                north = north, west = west, assumed = assumed
            )

            4 -> assemble(
                latitudeSlots = listOf(
                    slot(tokens[0], 90.0) ?: return bad(tokens[0]),
                    slot(tokens[1], 60.0) ?: return bad(tokens[1])
                ),
                longitudeSlots = listOf(
                    slot(tokens[2], 180.0) ?: return bad(tokens[2]),
                    slot(tokens[3], 60.0) ?: return bad(tokens[3])
                ),
                format = CoordinateInputFormat.DEGREES_MINUTES,
                north = north, west = west, assumed = assumed
            )

            6 -> assemble(
                latitudeSlots = listOf(
                    slot(tokens[0], 90.0) ?: return bad(tokens[0]),
                    slot(tokens[1], 60.0) ?: return bad(tokens[1]),
                    slot(tokens[2], 60.0) ?: return bad(tokens[2])
                ),
                longitudeSlots = listOf(
                    slot(tokens[3], 180.0) ?: return bad(tokens[3]),
                    slot(tokens[4], 60.0) ?: return bad(tokens[4]),
                    slot(tokens[5], 60.0) ?: return bad(tokens[5])
                ),
                format = CoordinateInputFormat.DEGREES_MINUTES_SECONDS,
                north = north, west = west, assumed = assumed
            )

            1, 3, 5 -> CoordinateParseResult.Incomplete(
                "Keep going — ${tokens.size} so far"
            )

            else -> CoordinateParseResult.Invalid("Too many numbers")
        }
    }

    private fun bad(token: String) = CoordinateParseResult.Invalid("Cannot read \"$token\"")

    /**
     * Reads one slot, resolving wildcards and dropped decimals.
     *
     * A bare X is the whole range for that slot. An X among digits is that
     * digit unknown, so the value spans nought to nine in its place.
     */
    private fun slot(token: String, max: Double): Slot? {
        val negative = token.startsWith("-")
        val body = token.removePrefix("-")

        if (body.isEmpty()) return null
        if (body.all { it == 'X' }) return Slot(0.0, max, false)

        if (body.contains('X')) {
            val low = body.replace('X', '0').toDoubleOrNull() ?: return null
            val high = body.replace('X', '9').toDoubleOrNull() ?: return null
            val fixed = repair(low, high, body, max) ?: return null
            return if (negative) Slot(-fixed.max, -fixed.min, fixed.inferredDecimal) else fixed
        }

        val value = body.toDoubleOrNull() ?: return null
        if (value <= max) {
            return if (negative) Slot(-value, -value, false) else Slot(value, value, false)
        }

        // Out of range with no decimal point: put the decimal back.
        val placed = insertDecimal(body, max) ?: return null
        return if (negative) Slot(-placed, -placed, true) else Slot(placed, placed, true)
    }

    private fun repair(low: Double, high: Double, body: String, max: Double): Slot? {
        if (high <= max) return Slot(low, high, false)
        val lowFixed = insertDecimal(body.replace('X', '0'), max) ?: return null
        val highFixed = insertDecimal(body.replace('X', '9'), max) ?: return null
        return Slot(minOf(lowFixed, highFixed), maxOf(lowFixed, highFixed), true)
    }

    /**
     * Puts a decimal point back into a run of digits.
     *
     * Takes the longest leading run of up to three digits that still fits the
     * slot. Four three one seven seven in a minutes slot becomes 43.177,
     * because forty-three is the most that can be minutes.
     */
    private fun insertDecimal(digits: String, max: Double): Double? {
        if (digits.contains('.')) return null
        if (digits.length < 2) return null
        var best: Double? = null
        for (prefixLength in minOf(3, digits.length - 1) downTo 1) {
            val prefix = digits.substring(0, prefixLength)
            val whole = prefix.toDoubleOrNull() ?: continue
            if (whole > max) continue
            val fraction = digits.substring(prefixLength)
            // Rebuild from the digits, not from the parsed value: 43 read back
            // as a Double is "43.0", which then produces "43.0.177".
            val candidate = "$prefix.$fraction".toDoubleOrNull() ?: continue
            if (candidate <= max) {
                best = candidate
                break
            }
        }
        return best
    }

    private fun assemble(
        latitudeSlots: List<Slot>,
        longitudeSlots: List<Slot>,
        format: CoordinateInputFormat,
        north: Boolean,
        west: Boolean,
        assumed: Boolean
    ): CoordinateParseResult {
        val latitude = combine(latitudeSlots) ?: return CoordinateParseResult.Invalid(
            "Latitude is out of range"
        )
        val longitude = combine(longitudeSlots) ?: return CoordinateParseResult.Invalid(
            "Longitude is out of range"
        )

        var south = latitude.first
        var northEdge = latitude.second
        if (south >= 0 && !north) {
            val flippedSouth = -northEdge
            northEdge = -south
            south = flippedSouth
        }

        var westEdge = longitude.first
        var east = longitude.second
        if (westEdge >= 0 && west) {
            val flipped = -east
            east = -westEdge
            westEdge = flipped
        } else if (westEdge < 0 && !west) {
            val flipped = abs(east)
            east = abs(westEdge)
            westEdge = minOf(flipped, east)
        }

        if (abs(south) > 90.0 || abs(northEdge) > 90.0) {
            return CoordinateParseResult.Invalid("Latitude must be 90 or less")
        }
        if (abs(westEdge) > 180.0 || abs(east) > 180.0) {
            return CoordinateParseResult.Invalid("Longitude must be 180 or less")
        }

        val inferred = (latitudeSlots + longitudeSlots).any { it.inferredDecimal }

        return CoordinateParseResult.Success(
            ParsedCoordinate(
                latitude = (south + northEdge) / 2.0,
                longitude = (westEdge + east) / 2.0,
                southLatitude = minOf(south, northEdge),
                northLatitude = maxOf(south, northEdge),
                westLongitude = minOf(westEdge, east),
                eastLongitude = maxOf(westEdge, east),
                format = format,
                assumedHemisphere = assumed,
                inferredDecimal = inferred
            )
        )
    }

    /** Folds degrees, minutes and seconds into a low and high degree value. */
    private fun combine(slots: List<Slot>): Pair<Double, Double>? {
        val divisors = listOf(1.0, 60.0, 3600.0)
        var low = 0.0
        var high = 0.0
        var negative = false
        slots.forEachIndexed { index, s ->
            if (index == 0 && s.min < 0) negative = true
            val divisor = divisors.getOrNull(index) ?: return null
            low += abs(s.min) / divisor
            high += abs(s.max) / divisor
        }
        return if (negative) -high to -low else low to high
    }

    /** A number, or a run containing X standing in for missed digits. */
    private val TOKEN = Regex("""-?(?:[0-9X]+(?:\.[0-9X]+)?|\.[0-9X]+)""")
}
