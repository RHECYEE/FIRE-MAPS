package com.rhecyee.firelinemap.satellite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class FirmsFeedTest {

    private val south = 38.9000
    private val west = -120.1000
    private val north = 38.9500
    private val east = -120.0500

    private fun window(
        south: Double = this.south,
        west: Double = this.west,
        north: Double = this.north,
        east: Double = this.east
    ) = DetectionWindow.of(south, west, north, east)

    // --- the window ------------------------------------------------------

    @Test
    fun `the requested box covers everything on screen`() {
        val result = window()!!
        assertTrue("north edge left unasked", result.north >= north)
        assertTrue("south edge left unasked", result.south <= south)
        assertTrue("west edge left unasked", result.west <= west)
        assertTrue("east edge left unasked", result.east >= east)
    }

    @Test
    fun `a nudge of the map does not cost another request`() {
        // FIRMS is a rate-limited public service, not a tile server. A window
        // that tracked the viewport would spend the allowance on one pan.
        val first = window()
        val nudge = 0.00002
        assertEquals(
            first,
            window(
                south = south + nudge, west = west + nudge,
                north = north + nudge, east = east + nudge
            )
        )
    }

    @Test
    fun `a real pan does fetch the next piece of ground`() {
        val first = window()!!
        val shift = (east - west) * 4
        val second = window(west = west + shift, east = east + shift)!!
        assertTrue("the window did not follow the map", second.west > first.west)
    }

    @Test
    fun `spans land on the same steps however the view drifts`() {
        val heights = (0..40).map { step ->
            val drift = step * 0.0007
            val result = window(
                south = south + drift, west = west + drift,
                north = north + drift, east = east + drift
            )!!
            Math.round((result.north - result.south) * 1e9)
        }.toSet()
        assertEquals("window heights drifted: $heights", 1, heights.size)
    }

    @Test
    fun `a box the wrong way round is refused rather than asked`() {
        assertNull(window(south = 38.95, north = 38.90))
        assertNull(window(west = -120.05, east = -120.10))
        assertNull(window(south = 38.9, north = 38.9))
    }

    @Test
    fun `values that are not numbers are refused`() {
        assertNull(window(south = Double.NaN))
        assertNull(window(north = Double.POSITIVE_INFINITY))
        assertNull(window(west = Double.NaN))
        assertNull(window(east = Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `latitude never runs off the top of the projection`() {
        val result = window(south = 84.90, west = 10.0, north = 84.99, east = 10.1)!!
        assertTrue(result.north <= 85.0)
        assertTrue(result.south >= -85.0)
    }

    // --- the confidence floor -------------------------------------------

    @Test
    fun `a floor of low lets everything through`() {
        DetectionConfidence.entries.forEach {
            assertTrue(it.name, FirmsFeed.passes(it, DetectionConfidence.LOW))
        }
    }

    @Test
    fun `an ungraded detection never clears a floor above the bottom`() {
        // The alternative is letting a detection whose grading did not come
        // through pass as though it had been graded, which is the wrong way
        // round to guess on a fire.
        assertFalse(
            FirmsFeed.passes(DetectionConfidence.UNKNOWN, DetectionConfidence.NOMINAL)
        )
        assertFalse(
            FirmsFeed.passes(DetectionConfidence.UNKNOWN, DetectionConfidence.HIGH)
        )
    }

    @Test
    fun `the floor admits its own grade and everything above it`() {
        assertTrue(FirmsFeed.passes(DetectionConfidence.NOMINAL, DetectionConfidence.NOMINAL))
        assertTrue(FirmsFeed.passes(DetectionConfidence.HIGH, DetectionConfidence.NOMINAL))
        assertFalse(FirmsFeed.passes(DetectionConfidence.LOW, DetectionConfidence.NOMINAL))
        assertFalse(FirmsFeed.passes(DetectionConfidence.NOMINAL, DetectionConfidence.HIGH))
    }

    @Test
    fun `the grades are ordered low to high so the floor can compare them`() {
        // passes() reads ordinals. If UNKNOWN were moved between the grades,
        // or the grades reordered, "high only" would quietly mean something
        // else -- so the order is pinned here rather than assumed.
        assertTrue(DetectionConfidence.LOW.ordinal < DetectionConfidence.NOMINAL.ordinal)
        assertTrue(DetectionConfidence.NOMINAL.ordinal < DetectionConfidence.HIGH.ordinal)
        assertEquals(
            DetectionConfidence.entries.size - 1,
            DetectionConfidence.UNKNOWN.ordinal
        )
    }

    // --- how hard it tries ----------------------------------------------

    /** Counts requests and answers with whatever the test wants. */
    private class Counting(private val status: Int, private val body: String) {
        var requests = 0
            private set

        fun client() = FirmsClient { url ->
            requests++
            object : HttpURLConnection(URL(url)) {
                override fun getResponseCode(): Int = status
                override fun getInputStream(): InputStream =
                    ByteArrayInputStream(body.toByteArray())
                override fun getErrorStream(): InputStream =
                    ByteArrayInputStream(body.toByteArray())
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy(): Boolean = false
            }
        }
    }

    private val oneDetection = "latitude,longitude,acq_date,confidence,version\n" +
        "40.1,-121.5,2026-09-23,h,2.0NRT"

    private fun feedOf(status: Int, body: String): Pair<FirmsFeed, Counting> {
        val counting = Counting(status, body)
        return FirmsFeed(counting.client()) to counting
    }

    @Test
    fun `the same ground is not asked about twice`() = runBlocking {
        val (feed, counting) = feedOf(200, oneDetection)
        val box = window()!!
        val one = setOf(SatelliteSource.VIIRS_NOAA20)
        feed.refresh("abc123", one, box)
        feed.refresh("abc123", one, box)
        feed.refresh("abc123", one, box)
        assertEquals("FIRMS meters by key", 1, counting.requests)
        assertEquals(1, feed.detections.size)
    }

    @Test
    fun `a rejected key is not retried until it is corrected`() = runBlocking {
        // The car redraws several times a second. Retrying a bad key on every
        // frame would spend somebody's whole rate limit inside a minute.
        val (feed, counting) = feedOf(400, "Invalid MAP_KEY.")
        val box = window()!!
        val one = setOf(SatelliteSource.VIIRS_NOAA20)
        repeat(20) { feed.refresh("wrong", one, box) }
        assertEquals(1, counting.requests)
        assertTrue(feed.keyRejected)

        // Correcting it is a new question, so it goes straight out.
        assertTrue(feed.wouldFetch("right", one, box))
    }

    @Test
    fun `a transient failure waits before being tried again`() = runBlocking {
        val (feed, counting) = feedOf(429, "")
        val box = window()!!
        val one = setOf(SatelliteSource.VIIRS_NOAA20)
        repeat(10) { feed.refresh("abc123", one, box) }
        assertEquals(1, counting.requests)
        assertFalse(feed.keyRejected)

        val now = System.currentTimeMillis()
        assertFalse("retried immediately", feed.wouldFetch("abc123", one, box, now))
        assertTrue(
            "never retried at all",
            feed.wouldFetch("abc123", one, box, now + FirmsFeed.RETRY_MILLIS + 1)
        )
    }

    @Test
    fun `a map that has not moved still refreshes eventually`() = runBlocking {
        // Parked at a staging area the window never changes. Without this the
        // detections loaded on arrival would be the detections all shift.
        val (feed, _) = feedOf(200, oneDetection)
        val box = window()!!
        val one = setOf(SatelliteSource.VIIRS_NOAA20)
        feed.refresh("abc123", one, box)

        val now = System.currentTimeMillis()
        assertFalse("refetched immediately", feed.wouldFetch("abc123", one, box, now))
        assertTrue(
            "went stale in place",
            feed.wouldFetch("abc123", one, box, now + FirmsFeed.STALE_MILLIS + 1)
        )
    }

    @Test
    fun `panning to new ground does fetch again`() = runBlocking {
        val (feed, counting) = feedOf(200, oneDetection)
        val one = setOf(SatelliteSource.VIIRS_NOAA20)
        feed.refresh("abc123", one, window()!!)
        val shift = (east - west) * 8
        feed.refresh("abc123", one, window(west = west + shift, east = east + shift)!!)
        assertEquals(2, counting.requests)
    }

    @Test
    fun `each satellite is its own request because FIRMS has no combined one`() =
        runBlocking {
            val (feed, counting) = feedOf(200, oneDetection)
            feed.refresh("abc123", SatelliteSource.DEFAULT, window()!!)
            assertEquals(SatelliteSource.DEFAULT.size, counting.requests)
            assertEquals(
                "answers must merge, not replace each other",
                SatelliteSource.DEFAULT.size,
                feed.detections.size
            )
        }

    @Test
    fun `a failure is carried rather than shown as a quiet day`() = runBlocking {
        val (feed, _) = feedOf(400, "Invalid MAP_KEY.")
        feed.refresh("wrong", setOf(SatelliteSource.VIIRS_NOAA20), window()!!)
        assertTrue(feed.detections.isEmpty())
        assertNotNull("an empty map must say why it is empty", feed.error)
        assertTrue(feed.caption(DetectionConfidence.LOW).contains("key", ignoreCase = true))
    }

    @Test
    fun `clearing forgets the key that was rejected`() = runBlocking {
        val (feed, counting) = feedOf(400, "Invalid MAP_KEY.")
        val box = window()!!
        val one = setOf(SatelliteSource.VIIRS_NOAA20)
        feed.refresh("wrong", one, box)
        feed.clear()
        assertFalse(feed.keyRejected)
        assertNull(feed.error)
        feed.refresh("wrong", one, box)
        assertEquals("a cleared feed starts over", 2, counting.requests)
    }

    // --- the satellites --------------------------------------------------

    @Test
    fun `every satellite carries both the raster layer and the api name`() {
        // One enum reached two ways. A bird added to the keyless raster and
        // forgotten in the API list would show detections on one path and
        // silently none on the other.
        SatelliteSource.entries.forEach {
            assertTrue(it.name, it.layerId.isNotBlank())
            assertTrue(it.name, it.firmsId.isNotBlank())
            assertTrue("${it.name} is not near-real-time", it.firmsId.endsWith("_NRT"))
        }
    }
}
