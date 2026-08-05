package com.rhecyee.firelinemap.web

import com.rhecyee.firelinemap.map.MapCoverage
import com.rhecyee.firelinemap.share.PolylineCodec
import com.rhecyee.firelinemap.share.TextCodec
import com.rhecyee.firelinemap.util.GridCoordinates
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The browser must agree with the phone.
 *
 * The whole reason the web app compiles the phone's own Kotlin instead of
 * carrying a JavaScript rewrite is that two implementations of a grid
 * conversion eventually disagree, and the disagreement is about where somebody
 * is standing. These check the same reference values the Android suite checks,
 * on the platform that actually ships to a browser -- because "it compiled" is
 * not the same as "the arithmetic survived".
 *
 * JavaScript has no 64-bit integer of its own and no java.lang.Math, and both
 * are load-bearing here: the grid runs on doubles and the share codec runs on
 * Long bit shifts. If either were quietly wrong, this is where it shows.
 */
class SharedLogicTest {

    /** The pyproj reference positions the Android suite is held to. */
    private val reference = listOf(
        Reference("Burnt Creek", 45.20575, -117.63700, 11, 'T', 449974.7, 5006004.4),
        Reference("Eagle Butte", 44.99277, -101.24338, 14, 'T', 323165.0, 4984595.5),
        Reference("Glacier", 48.75000, -113.80000, 12, 'U', 294188.5, 5403447.4),
        Reference("Miami", 25.76170, -80.19180, 17, 'R', 581046.9, 2849542.5)
    )

    private class Reference(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val zone: Int,
        val band: Char,
        val easting: Double,
        val northing: Double
    )

    @Test
    fun theGridIsTheSameGridInABrowser() {
        reference.forEach {
            val utm = GridCoordinates.toUtm(it.latitude, it.longitude)
            assertNotNull(utm, it.name)
            assertEquals(it.zone, utm.zone, it.name)
            assertEquals(it.band, utm.band, it.name)
            assertTrue(abs(utm.easting - it.easting) < 0.5, "${it.name} easting ${utm.easting}")
            assertTrue(
                abs(utm.northing - it.northing) < 0.5,
                "${it.name} northing ${utm.northing}"
            )
        }
    }

    @Test
    fun aGridReferenceRoundTripsInABrowser() {
        reference.forEach {
            val mgrs = GridCoordinates.toMgrs(it.latitude, it.longitude, 5)
            assertNotNull(mgrs, it.name)
            val back = GridCoordinates.fromMgrs(mgrs)
            assertNotNull(back, mgrs)
            val off = MapCoverage.distanceMeters(
                it.latitude, it.longitude, back.first, back.second
            )
            assertTrue(off < 2.0, "${it.name} landed $off m out")
        }
    }

    /**
     * The share codec runs on Long shifts, which JavaScript does not have
     * natively. Kotlin emulates them, and this is the check that the emulation
     * carries a real track.
     */
    @Test
    fun aTrackSurvivesTheShareCodecInABrowser() {
        val points = (0 until 200).map { step ->
            (45.20575 + step * 25.0 / 111_194.93) to (-117.63700 + step * 3.0 / 78_000.0)
        }
        val encoded = PolylineCodec.encodePoints(points)
        val back = PolylineCodec.decodePoints(encoded)

        assertEquals(points.size, back.size)
        points.forEachIndexed { index, point ->
            val off = MapCoverage.distanceMeters(
                point.first, point.second, back[index].first, back[index].second
            )
            assertTrue(off < 1.0, "point $index came back $off m out")
        }
    }

    @Test
    fun aPastedPartReadsTheSameWayItDoesOnThePhone() {
        val pkg = PackageJson.read(
            """
            {"incidentName":"Burnt Creek 2026","author":"L. Yee",
             "pins":[{"id":"p","title":"DP 12","latitude":45.20575,
                      "longitude":-117.637,"symbolId":"drop_point"}],
             "tracks":[{"id":"t","name":"Div Z","points":[
                {"latitude":45.205,"longitude":-117.637,"timeMillis":1754390000000},
                {"latitude":45.206,"longitude":-117.636,"timeMillis":1754390005000},
                {"latitude":45.207,"longitude":-117.635,"timeMillis":1754390010000}]}]}
            """.trimIndent()
        )

        val parts = TextCodec.parts(pkg)
        val assembled = parts.fold(TextCodec.Assembly()) { assembly, part ->
            assembly.plus(TextCodec.readPart(part)!!)
        }
        assertTrue(assembled.verified(), "checksum must verify in a browser too")

        val back = TextCodec.decode(assembled.body()!!)
        assertNotNull(back)
        assertEquals("Burnt Creek 2026", back.incidentName)
        assertEquals(1, back.pins.size)
        assertEquals("DP 12", back.pins.first().title)
        assertEquals(3, back.tracks.first().points.size)
        // Times are what the merge readout is built on, and they cross a
        // 64-bit boundary JavaScript does not have.
        assertEquals(1_754_390_000_000L, back.tracks.first().points.first().timeMillis)
    }

    @Test
    fun theJavaScriptFacadeAnswersTheSameAsTheKotlinBehindIt() {
        val parsed = Api.parseCoordinate("45 12.345 117 38.220")
        assertNotNull(parsed)
        assertTrue(abs(parsed.latitude - 45.20575) < 1e-5, "was ${parsed.latitude}")
        // West is assumed, which is what the phone does.
        assertTrue(parsed.longitude < 0, "was ${parsed.longitude}")

        assertEquals(
            "N 45 12.345 W 117 38.220",
            Api.formatDdm(45.20575, -117.63700)
        )
        assertTrue(Api.formatMgrs(45.20575, -117.63700, 5)!!.startsWith("11T"))
        assertEquals("11T 449974 5006004", Api.formatUtm(45.20575, -117.63700))
    }

    @Test
    fun theRecorderInABrowserOpensATrackTheSameWay() {
        val recorder = Api.recorder(300)
        var started = false
        // Walking at 1.4 m/s, a fix every five seconds, for a minute.
        for (step in 0..12) {
            val event = recorder.onFix(
                latitude = 45.20575 + (1.4 * step * 5) / 111_194.93,
                longitude = -117.63700,
                timeMillis = (1_754_390_000_000L + step * 5_000L).toDouble(),
                accuracyMeters = 8.0,
                speedMetersPerSecond = 1.4
            )
            if (event == "started") started = true
        }
        assertTrue(started, "a minute of walking must open a track")
        assertTrue(recorder.recording)
        assertTrue(recorder.distanceMeters > 50.0, "was ${recorder.distanceMeters}")
    }
}
