package com.rhecyee.firelinemap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the settings that decide behaviour rather than store it.
 *
 * The stored values need a Context and are exercised on the device; what is
 * worth pinning here is how a chosen rate and a chosen power mode combine,
 * because that answer reaches both the receiver and the sheet and they have
 * to agree.
 */
class AppSettingsTest {

    @Test
    fun theModeSetsTheFloorAndTheChosenRateSetsTheRest() {
        // Asking for a fix every second in Saver contradicts the mode.
        assertEquals(30, AppSettings.effectiveInterval(1, PowerMode.SAVER))
        // A slower choice than the floor is honoured; the floor is a minimum,
        // not a target.
        assertEquals(60, AppSettings.effectiveInterval(60, PowerMode.SAVER))
        assertEquals(2, AppSettings.effectiveInterval(2, PowerMode.PRECISE))
        assertEquals(5, AppSettings.effectiveInterval(1, PowerMode.BALANCED))
    }

    @Test
    fun everyOfferedRateIsReachableInAtLeastOneMode() {
        for (seconds in AppSettings.LOCATION_INTERVAL_CHOICES) {
            val reachable = PowerMode.entries.any {
                AppSettings.effectiveInterval(seconds, it) == seconds
            }
            assertTrue("$seconds s is offered but never applied", reachable)
        }
    }

    @Test
    fun theFloorsGetLooserAsTheModeGetsThriftier() {
        assertTrue(PowerMode.PRECISE.intervalFloorSeconds < PowerMode.BALANCED.intervalFloorSeconds)
        assertTrue(PowerMode.BALANCED.intervalFloorSeconds < PowerMode.SAVER.intervalFloorSeconds)
    }

    @Test
    fun anUnknownOrMissingModeFallsBackToBalanced() {
        assertEquals(PowerMode.BALANCED, PowerMode.fromName(null))
        assertEquals(PowerMode.BALANCED, PowerMode.fromName(""))
        assertEquals(PowerMode.BALANCED, PowerMode.fromName("ULTRA"))
        // Round trips, which is what the preference actually relies on.
        for (mode in PowerMode.entries) {
            assertEquals(mode, PowerMode.fromName(mode.name))
        }
    }

    @Test
    fun theFullScreenTimeoutReadsTheWayItIsOffered() {
        assertEquals("Never", AppSettings.describeChromeTimeout(0))
        assertEquals("20s", AppSettings.describeChromeTimeout(20))
        assertTrue(AppSettings.DEFAULT_CHROME_TIMEOUT_SECONDS in AppSettings.CHROME_TIMEOUT_CHOICES)
        // Never has to be offered, or someone reading a map at a table has no
        // way to stop the controls disappearing.
        assertTrue(0 in AppSettings.CHROME_TIMEOUT_CHOICES)
    }

    @Test
    fun intervalsReadInSecondsUntilTheyAreLongEnoughToBeMinutes() {
        assertEquals("1s", AppSettings.describeInterval(1))
        assertEquals("30s", AppSettings.describeInterval(30))
        assertEquals("1 min", AppSettings.describeInterval(60))
        assertEquals("2 min", AppSettings.describeInterval(120))
    }

    @Test
    fun theDefaultRateIsOffered() {
        assertTrue(
            AppSettings.DEFAULT_LOCATION_INTERVAL_SECONDS in
                AppSettings.LOCATION_INTERVAL_CHOICES
        )
    }
}
