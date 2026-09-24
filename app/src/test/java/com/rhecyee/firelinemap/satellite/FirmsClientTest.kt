package com.rhecyee.firelinemap.satellite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class FirmsClientTest {

    /** A connection that answers with whatever the test wants, and records the URL. */
    private class Canned(
        private val status: Int,
        private val body: String,
        url: String
    ) : HttpURLConnection(URL(url)) {
        override fun getResponseCode(): Int = status
        override fun getInputStream(): InputStream =
            ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream(): InputStream =
            ByteArrayInputStream(body.toByteArray())
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
    }

    private var lastUrl: String? = null

    private fun client(status: Int, body: String) = FirmsClient { url ->
        lastUrl = url
        Canned(status, body, url)
    }

    private fun ask(client: FirmsClient) = client.area(
        mapKey = "abc123",
        source = SatelliteSource.VIIRS_NOAA20,
        south = 39.0, west = -122.0, north = 41.0, east = -120.0
    )

    @Test
    fun aGoodResponseBecomesDetections() {
        val body = """
            latitude,longitude,acq_date,acq_time,confidence,version,frp
            40.1,-121.5,2026-09-23,2112,h,2.0URT,17.6
        """.trimIndent()
        val result = ask(client(200, body))
        assertTrue(result is FirmsResult.Detections)
        assertEquals(1, (result as FirmsResult.Detections).detections.size)
    }

    @Test
    fun theRequestCarriesTheKeyTheProductAndTheBox() {
        ask(client(200, "latitude,longitude\n"))
        val url = lastUrl!!
        assertTrue(url.startsWith(FirmsClient.ENDPOINT))
        assertTrue(url.contains("/abc123/"))
        assertTrue(url.contains("/VIIRS_NOAA20_NRT/"))
        // west,south,east,north, which is the order FIRMS wants. Swapping the
        // pairs would ask about a box on the other side of the world and get
        // an empty answer that looked like a quiet day.
        assertTrue("box was ${url}", url.contains("-122.0000,39.0000,-120.0000,41.0000"))
    }

    @Test
    fun aRejectedKeySaysSoAndSaysItWasTheKey() {
        // Verified against the live service: HTTP 400, one line of body.
        val result = ask(client(400, "Invalid MAP_KEY."))
        assertTrue(result is FirmsResult.Failed)
        result as FirmsResult.Failed
        assertTrue(result.keyRejected)
        assertTrue(result.reason.contains("key", ignoreCase = true))
    }

    @Test
    fun anUnknownProductIsNotBlamedOnTheKey() {
        val result = ask(client(400, "Invalid source.")) as FirmsResult.Failed
        assertFalse(result.keyRejected)
        assertTrue(result.reason.contains("product", ignoreCase = true))
    }

    @Test
    fun rateLimitingIsItsOwnMessage() {
        val result = ask(client(429, "")) as FirmsResult.Failed
        assertTrue(result.reason.contains("rate limiting", ignoreCase = true))
    }

    @Test
    fun anEmptyAnswerIsNoDetectionsRatherThanAFailure() {
        // A quiet day and a broken request must never look the same.
        val result = ask(client(200, "latitude,longitude,acq_date\n"))
        assertTrue(result is FirmsResult.Detections)
        assertEquals(0, (result as FirmsResult.Detections).detections.size)
    }

    @Test
    fun noKeyFailsBeforeAnythingIsSentAnywhere() {
        lastUrl = null
        val result = FirmsClient { url -> lastUrl = url; Canned(200, "", url) }
            .area("", SatelliteSource.VIIRS_NOAA20, 39.0, -122.0, 41.0, -120.0)
        assertTrue(result is FirmsResult.Failed)
        assertTrue((result as FirmsResult.Failed).keyRejected)
        assertEquals("nothing should have been requested", null, lastUrl)
    }

    @Test
    fun anInsideOutBoxIsRefusedRatherThanAsked() {
        lastUrl = null
        val result = FirmsClient { url -> lastUrl = url; Canned(200, "", url) }
            .area("abc123", SatelliteSource.VIIRS_NOAA20, 41.0, -120.0, 39.0, -122.0)
        assertTrue(result is FirmsResult.Failed)
        assertEquals(null, lastUrl)
    }

    @Test
    fun theDayRangeIsHeldInsideWhatTheServiceAccepts() {
        client(200, "latitude,longitude\n").area(
            "abc123", SatelliteSource.VIIRS_NOAA20, 39.0, -122.0, 41.0, -120.0, dayRange = 99
        )
        assertTrue("clamped to ten, got $lastUrl", lastUrl!!.endsWith("/10"))

        client(200, "latitude,longitude\n").area(
            "abc123", SatelliteSource.VIIRS_NOAA20, 39.0, -122.0, 41.0, -120.0, dayRange = 0
        )
        assertTrue("clamped to one, got $lastUrl", lastUrl!!.endsWith("/1"))
    }

    @Test
    fun aThrowingConnectionDoesNotTakeTheAppDown() {
        val result = FirmsClient { throw java.net.UnknownHostException("no dns") }
            .area("abc123", SatelliteSource.VIIRS_NOAA20, 39.0, -122.0, 41.0, -120.0)
        assertTrue(result is FirmsResult.Failed)
        assertTrue((result as FirmsResult.Failed).reason.contains("reach", ignoreCase = true))
    }

    @Test
    fun twoThingsWrongAtOnceReportsTheKeyFirst() {
        // Verified against the live service: a bad key AND a bad product
        // comes back as HTTP 400 with both lines in one body. The key is the
        // one to say, because it is the one the operator can act on and the
        // product is not something they chose.
        val result = ask(client(400, "Invalid MAP_KEY.\nInvalid source.")) as FirmsResult.Failed
        assertTrue(result.keyRejected)
        assertTrue(result.reason.contains("key", ignoreCase = true))
    }

    @Test
    fun aMultiLineErrorIsNeverReadAsDetections() {
        // Two lines means parse() sees a header and a row. Neither is data.
        assertEquals(0, FirmsCsv.parse("Invalid MAP_KEY.\nInvalid source.").size)
    }

    @Test
    fun theDefaultSelectionIsTheThreeViirsBirds() {
        assertEquals(3, SatelliteSource.DEFAULT.size)
        assertFalse(SatelliteSource.MODIS in SatelliteSource.DEFAULT)
        SatelliteSource.DEFAULT.forEach { assertEquals(375, it.resolutionMeters) }
        // Every default asks for the near-real-time product, which is the one
        // that carries URT. Standard processing runs years behind.
        SatelliteSource.DEFAULT.forEach { assertTrue(it.firmsId.endsWith("_NRT")) }
    }
}
