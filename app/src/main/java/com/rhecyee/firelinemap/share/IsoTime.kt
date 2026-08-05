package com.rhecyee.firelinemap.share

/**
 * Epoch milliseconds to an ISO 8601 UTC stamp and back.
 *
 * Written out rather than taken from a library because this has to compile
 * everywhere the sharing format does, and the platform date classes do not:
 * the app's own formatter is a JVM one, and a shared file has to be readable
 * by a phone that is not an Android phone.
 *
 * Always UTC, always the same shape: "2026-08-05T14:30:00Z". A track that
 * crossed a time zone -- or was recorded by somebody in one -- has to compare
 * against the rest without anybody reasoning about offsets.
 */
object IsoTime {

    private const val MILLIS_PER_DAY = 86_400_000L

    fun format(epochMillis: Long): String {
        val days = floorDiv(epochMillis, MILLIS_PER_DAY)
        val millisOfDay = epochMillis - days * MILLIS_PER_DAY
        val (year, month, day) = civilFromDays(days)

        val second = (millisOfDay / 1000) % 60
        val minute = (millisOfDay / 60_000) % 60
        val hour = millisOfDay / 3_600_000

        return pad(year, 4) + "-" + pad(month, 2) + "-" + pad(day, 2) +
            "T" + pad(hour, 2) + ":" + pad(minute, 2) + ":" + pad(second, 2) + "Z"
    }

    /**
     * Reads a stamp back.
     *
     * Tolerant on purpose. This parses files written by other programs, and
     * GPX in the wild carries fractional seconds, a "+00:00" offset instead of
     * a Z, and occasionally no zone marker at all. None of those are worth
     * losing a track over, so an unmarked time is read as UTC and a fraction
     * is kept to the millisecond.
     */
    fun parse(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.length < 19) return null

        val year = trimmed.substring(0, 4).toIntOrNull() ?: return null
        if (trimmed[4] != '-' || trimmed[7] != '-') return null
        val month = trimmed.substring(5, 7).toIntOrNull() ?: return null
        val day = trimmed.substring(8, 10).toIntOrNull() ?: return null
        if (trimmed[10] != 'T' && trimmed[10] != ' ') return null
        val hour = trimmed.substring(11, 13).toIntOrNull() ?: return null
        val minute = trimmed.substring(14, 16).toIntOrNull() ?: return null
        val second = trimmed.substring(17, 19).toIntOrNull() ?: return null

        if (month !in 1..12 || day !in 1..31) return null
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null

        var rest = trimmed.substring(19)
        var millis = 0L
        if (rest.startsWith(".")) {
            val digits = rest.drop(1).takeWhile { it.isDigit() }
            rest = rest.drop(1 + digits.length)
            if (digits.isNotEmpty()) {
                val fraction = digits.take(3).padEnd(3, '0')
                millis = fraction.toLongOrNull() ?: 0L
            }
        }

        // An offset means the stamp is local to somewhere; subtract it to get
        // back to UTC. "Z" and nothing at all are both already UTC.
        var offsetMillis = 0L
        if (rest.isNotEmpty() && (rest[0] == '+' || rest[0] == '-')) {
            val sign = if (rest[0] == '-') -1 else 1
            val body = rest.drop(1).replace(":", "")
            if (body.length >= 4) {
                val offsetHours = body.substring(0, 2).toIntOrNull() ?: return null
                val offsetMinutes = body.substring(2, 4).toIntOrNull() ?: return null
                offsetMillis = sign * (offsetHours * 3_600_000L + offsetMinutes * 60_000L)
            }
        }

        val days = daysFromCivil(year, month, day)
        val total = days * MILLIS_PER_DAY +
            hour * 3_600_000L + minute * 60_000L + second * 1000L + millis
        return total - offsetMillis
    }

    private fun pad(value: Long, width: Int): String {
        val text = (if (value < 0) -value else value).toString()
        val padded = if (text.length >= width) text else "0".repeat(width - text.length) + text
        return if (value < 0) "-$padded" else padded
    }

    private fun pad(value: Int, width: Int): String = pad(value.toLong(), width)

    private fun floorDiv(value: Long, divisor: Long): Long {
        var result = value / divisor
        if (value % divisor != 0L && (value xor divisor) < 0) result--
        return result
    }

    /**
     * Days since 1970-01-01 to a calendar date, and back.
     *
     * Hinnant's civil calendar algorithms. Chosen because they are exact over
     * the whole range with plain integer arithmetic -- no leap year special
     * cases to get subtly wrong, and no dependency to carry to another
     * platform.
     */
    private fun civilFromDays(daysSinceEpoch: Long): Triple<Int, Int, Int> {
        val z = daysSinceEpoch + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val dayOfEra = z - era * 146_097
        val yearOfEra =
            (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val year = yearOfEra + era * 400
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val mp = (5 * dayOfYear + 2) / 153
        val day = dayOfYear - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        return Triple(
            (if (month <= 2) year + 1 else year).toInt(),
            month.toInt(),
            day.toInt()
        )
    }

    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val dayOfYear =
            (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }
}
