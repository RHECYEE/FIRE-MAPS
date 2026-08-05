package com.rhecyee.firelinemap.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Timestamps in the sharing format.
 *
 * Written by hand because the platform date classes do not go where this
 * format has to, so it is checked against the platform's own calendar -- the
 * one thing available here that is definitely right.
 */
class IsoTimeTest {

    private fun utc(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        minute: Int = 0,
        second: Int = 0
    ): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
        clear()
        set(year, month - 1, day, hour, minute, second)
    }.timeInMillis

    @Test
    fun aStampMatchesTheCalendarItClaimsToFollow() {
        val cases = listOf(
            utc(1970, 1, 1) to "1970-01-01T00:00:00Z",
            utc(2026, 8, 5, 14, 30, 0) to "2026-08-05T14:30:00Z",
            utc(2026, 1, 1, 0, 0, 1) to "2026-01-01T00:00:01Z",
            utc(2026, 12, 31, 23, 59, 59) to "2026-12-31T23:59:59Z",
            // A leap day, which is where hand-rolled calendars come apart.
            utc(2024, 2, 29, 12, 0, 0) to "2024-02-29T12:00:00Z",
            utc(2000, 2, 29, 0, 0, 0) to "2000-02-29T00:00:00Z"
        )
        cases.forEach { (millis, expected) ->
            assertEquals(expected, IsoTime.format(millis))
        }
    }

    @Test
    fun everyDayOfAFireSeasonRoundTrips() {
        // Every hour across a season, against the platform calendar. A single
        // wrong day in here is a track filed to the wrong shift.
        var millis = utc(2026, 5, 1)
        val end = utc(2026, 11, 1)
        while (millis < end) {
            val text = IsoTime.format(millis)
            assertEquals(text, millis, IsoTime.parse(text))
            millis += 3_600_000L
        }
    }

    @Test
    fun theStampAgreesWithTheCalendarAcrossDecades() {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
        var millis = utc(1971, 3, 3)
        val end = utc(2040, 1, 1)
        while (millis < end) {
            calendar.timeInMillis = millis
            val expected = "%04d-%02d-%02dT%02d:%02d:%02dZ".format(
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH),
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                calendar.get(Calendar.SECOND)
            )
            assertEquals(expected, IsoTime.format(millis))
            // A little over a month, to cover every month boundary and leap
            // year in the range without running for a minute.
            millis += 37L * 86_400_000L + 3_600_000L
        }
    }

    /**
     * GPX in the wild is not tidy.
     *
     * Every one of these shapes turns up in files written by real programs,
     * and none of them is worth losing a track over.
     */
    @Test
    fun stampsFromOtherProgramsAreRead() {
        val noon = utc(2026, 8, 5, 14, 30, 0)
        assertEquals(noon, IsoTime.parse("2026-08-05T14:30:00Z"))
        assertEquals(noon, IsoTime.parse("2026-08-05T14:30:00"))
        assertEquals(noon, IsoTime.parse("2026-08-05 14:30:00Z"))
        assertEquals(noon + 250, IsoTime.parse("2026-08-05T14:30:00.250Z"))
        // More precision than a millisecond, which several loggers write.
        assertEquals(noon + 250, IsoTime.parse("2026-08-05T14:30:00.250999Z"))
        assertEquals(noon, IsoTime.parse("  2026-08-05T14:30:00Z  "))
    }

    @Test
    fun anOffsetIsBroughtBackToUtcRatherThanIgnored() {
        val noon = utc(2026, 8, 5, 14, 30, 0)
        // Pacific daylight time. Read as if it were UTC, a track lands seven
        // hours from where it was walked.
        assertEquals(noon, IsoTime.parse("2026-08-05T07:30:00-07:00"))
        assertEquals(noon, IsoTime.parse("2026-08-05T07:30:00-0700"))
        assertEquals(noon, IsoTime.parse("2026-08-05T16:30:00+02:00"))
    }

    @Test
    fun nonsenseIsRefusedRatherThanLandingInNineteenSeventy() {
        assertNull(IsoTime.parse(""))
        assertNull(IsoTime.parse("today"))
        assertNull(IsoTime.parse("2026-08-05"))
        assertNull(IsoTime.parse("2026-13-05T00:00:00Z"))
        assertNull(IsoTime.parse("2026-08-05T25:00:00Z"))
        assertNull(IsoTime.parse("xxxx-08-05T00:00:00Z"))
    }

    @Test
    fun timeBeforeTheEpochDoesNotWrapAround() {
        // Not a case this app produces, but a file can carry anything and a
        // negative millisecond must not come back as a date in 1969 that is
        // off by a day.
        val early = utc(1965, 6, 15, 8, 0, 0)
        assertTrue(early < 0)
        val text = IsoTime.format(early)
        assertEquals("1965-06-15T08:00:00Z", text)
        assertEquals(early, IsoTime.parse(text))
    }
}
