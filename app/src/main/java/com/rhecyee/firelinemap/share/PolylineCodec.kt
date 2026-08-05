package com.rhecyee.firelinemap.share

import kotlin.math.round

/**
 * Track shapes as text short enough to send in a message.
 *
 * Google's encoded polyline algorithm. Chosen rather than invented because it
 * is the format every mapping tool already reads, it is about one character
 * per point instead of the forty a pair of decimal degrees costs, and it has
 * been carried through URLs, spreadsheets and messages for twenty years, so
 * its awkward cases are known rather than waiting.
 *
 * The trick is that it stores each point as the difference from the last one.
 * Along a road those differences are tiny, and small numbers take few
 * characters -- which is exactly the shape of the data.
 *
 * Five decimal places, about a metre. Finer than any receiver reports and
 * finer than a track thinned to fit a message anyway.
 *
 * Every character it emits lies between '?' and '~'. Nothing below that range
 * ever appears, which is what lets the surrounding format use ';' and '!' as
 * separators without escaping the payload.
 */
object PolylineCodec {

    private const val PRECISION = 1e5

    /** The lowest character the encoding can produce. */
    const val LOWEST_CHARACTER = '?'

    fun encodePoints(points: List<Pair<Double, Double>>): String {
        val out = StringBuilder(points.size * 6)
        var previousLatitude = 0L
        var previousLongitude = 0L
        points.forEach { (latitude, longitude) ->
            val scaledLatitude = round(latitude * PRECISION).toLong()
            val scaledLongitude = round(longitude * PRECISION).toLong()
            writeSigned(out, scaledLatitude - previousLatitude)
            writeSigned(out, scaledLongitude - previousLongitude)
            previousLatitude = scaledLatitude
            previousLongitude = scaledLongitude
        }
        return out.toString()
    }

    fun decodePoints(text: String): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        val cursor = Cursor(text)
        var latitude = 0L
        var longitude = 0L
        while (cursor.hasMore()) {
            val deltaLatitude = cursor.readSigned() ?: break
            val deltaLongitude = cursor.readSigned() ?: break
            latitude += deltaLatitude
            longitude += deltaLongitude
            out += (latitude / PRECISION) to (longitude / PRECISION)
        }
        return out
    }

    /**
     * Times, delta encoded the same way.
     *
     * Carried separately because a shape with no times is still a track worth
     * drawing, and because the times are what let several passes over one road
     * be read together for a speed. Whole seconds: nothing here is measured
     * finer, and the millisecond digits would triple the length for nothing.
     *
     * A zero stands for "not known", and stays zero rather than becoming a
     * moment in 1970.
     */
    fun encodeTimes(millis: List<Long>): String {
        if (millis.none { it > 0 }) return ""
        val out = StringBuilder(millis.size * 3)
        var previous = 0L
        millis.forEach { value ->
            val seconds = if (value > 0) value / 1000 else 0L
            writeSigned(out, seconds - previous)
            previous = seconds
        }
        return out.toString()
    }

    fun decodeTimes(text: String, count: Int): List<Long> {
        if (text.isEmpty()) return List(count) { 0L }
        val out = mutableListOf<Long>()
        val cursor = Cursor(text)
        var seconds = 0L
        while (cursor.hasMore() && out.size < count) {
            val delta = cursor.readSigned() ?: break
            seconds += delta
            out += if (seconds > 0) seconds * 1000 else 0L
        }
        while (out.size < count) out += 0L
        return out
    }

    private fun writeSigned(out: StringBuilder, value: Long) {
        // The sign goes in the lowest bit, so that small negatives stay short.
        var shifted = if (value < 0) (value shl 1).inv() else (value shl 1)
        while (shifted >= 0x20) {
            out.append((((shifted and 0x1f) or 0x20) + 63).toInt().toChar())
            shifted = shifted shr 5
        }
        out.append((shifted + 63).toInt().toChar())
    }

    private class Cursor(private val text: String) {
        private var index = 0

        fun hasMore(): Boolean = index < text.length

        fun readSigned(): Long? {
            var result = 0L
            var shift = 0
            while (true) {
                if (index >= text.length) return null
                val value = text[index].code - 63
                index++
                // Anything outside the alphabet is not part of this run.
                if (value < 0) return null
                result = result or ((value and 0x1f).toLong() shl shift)
                if (value < 0x20) break
                shift += 5
                // A run this long is corrupt rather than merely large.
                if (shift > 60) return null
            }
            return if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)
        }
    }
}
