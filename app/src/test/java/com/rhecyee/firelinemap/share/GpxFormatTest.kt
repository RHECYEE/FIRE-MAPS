package com.rhecyee.firelinemap.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sharing format.
 *
 * Held to two standards at once. It has to round-trip everything this app
 * knows, so a track handed to somebody else is the same track when it lands;
 * and it has to be ordinary GPX, so it opens in Gaia, CalTopo, Avenza or an
 * agency GIS -- which is the only thing that gets a track onto an iPhone
 * today.
 */
class GpxFormatTest {

    private val pkg = SharePackage(
        incidentName = "Burnt Creek 2026",
        author = "L. Yee",
        createdAt = 1_754_390_000_000L,
        pins = listOf(
            SharePin(
                id = "pin-1",
                title = "DP 12",
                latitude = 45.20575,
                longitude = -117.63700,
                symbolId = "drop_point",
                note = "Turnaround for tenders",
                status = "committed",
                createdAt = 1_754_390_000_000L
            ),
            SharePin(
                id = "pin-2",
                title = "Helispot 3",
                latitude = 45.21000,
                longitude = -117.64000,
                symbolId = "helispot"
            )
        ),
        tracks = listOf(
            ShareTrack(
                id = "track-1",
                name = "Div Z morning",
                distanceMeters = 4213.5,
                activityType = "VEHICLE",
                note = "5200 road",
                points = listOf(
                    SharePoint(45.20575, -117.63700, 1_754_390_000_000L, 1310.0),
                    SharePoint(45.20600, -117.63650, 1_754_390_005_000L, 1315.5),
                    SharePoint(45.20650, -117.63600, 1_754_390_010_000L)
                )
            )
        )
    )

    @Test
    fun everythingSurvivesTheRoundTrip() {
        val back = GpxFormat.read(GpxFormat.write(pkg))
        assertNotNull(back)
        assertEquals("Burnt Creek 2026", back!!.incidentName)
        assertEquals("L. Yee", back.author)
        assertEquals(2, back.pins.size)
        assertEquals(1, back.tracks.size)
    }

    @Test
    fun aPinKeepsEverythingThatMakesItThatPin() {
        val back = GpxFormat.read(GpxFormat.write(pkg))!!
        val pin = back.pins.first { it.id == "pin-1" }
        assertEquals("DP 12", pin.title)
        assertEquals("drop_point", pin.symbolId)
        assertEquals("Turnaround for tenders", pin.note)
        assertEquals("committed", pin.status)
        assertEquals(1_754_390_000_000L, pin.createdAt)
        assertEquals(45.20575, pin.latitude, 1e-7)
        assertEquals(-117.63700, pin.longitude, 1e-7)
    }

    @Test
    fun aTrackKeepsItsShapeItsTimesAndItsHeights() {
        val back = GpxFormat.read(GpxFormat.write(pkg))!!
        val track = back.tracks.first()
        assertEquals("Div Z morning", track.name)
        assertEquals("VEHICLE", track.activityType)
        assertEquals("5200 road", track.note)
        assertEquals(4213.5, track.distanceMeters, 0.05)
        assertEquals(3, track.points.size)

        assertEquals(45.20575, track.points[0].latitude, 1e-7)
        assertEquals(1310.0, track.points[0].elevationMeters!!, 0.05)
        assertEquals(1_754_390_000_000L, track.points[0].timeMillis)
        // The last point has no elevation, and that has to stay absent rather
        // than becoming a zero somebody later reads as sea level.
        assertNull(track.points[2].elevationMeters)
    }

    @Test
    fun theTracksOwnTimesComeBackFromItsPoints() {
        val back = GpxFormat.read(GpxFormat.write(pkg))!!
        val track = back.tracks.first()
        assertEquals(1_754_390_000_000L, track.startedAt)
        assertEquals(1_754_390_010_000L, track.endedAt)
    }

    @Test
    fun aPositionIsNotDegradedByBeingSent() {
        // Seven places is about a centimetre. A shared position must not come
        // back further from the ground than it started.
        val precise = SharePackage(
            incidentName = "x",
            pins = listOf(SharePin("p", "p", 45.2057512, -117.6370098))
        )
        val back = GpxFormat.read(GpxFormat.write(precise))!!.pins.first()
        assertEquals(45.2057512, back.latitude, 1e-7)
        assertEquals(-117.6370098, back.longitude, 1e-7)
    }

    /**
     * It has to be ordinary GPX.
     *
     * The whole reason for this format rather than one of the app's own is
     * that it arrives somewhere. If the standard elements are not where a
     * reader expects them, an iPhone gets a file it cannot open and the
     * feature has failed at the only thing it was for.
     */
    @Test
    fun theFileIsPlainGpxBeforeItIsAnythingOfOurs() {
        val xml = GpxFormat.write(pkg)
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(xml.contains("<gpx version=\"1.1\""))
        assertTrue(xml.contains("xmlns=\"http://www.topografix.com/GPX/1/1\""))
        assertTrue("waypoints must be standard", xml.contains("<wpt lat="))
        assertTrue("tracks must be standard", xml.contains("<trkpt lat="))
        assertTrue(xml.contains("<trkseg>"))
        // The symbol is written where other programs look for it too, so a pin
        // is not an unlabelled dot in Gaia.
        assertTrue(xml.contains("<sym>drop_point</sym>"))
    }

    /**
     * A file from something that is not this app.
     *
     * This is the common case for import: somebody sends a track out of Gaia
     * or CalTopo. None of the extensions are there, and it still has to
     * produce a usable track rather than nothing.
     */
    @Test
    fun aFileFromAnotherProgramStillReads() {
        val foreign = """
            <?xml version="1.0"?>
            <gpx version="1.1" creator="Gaia GPS"
                 xmlns="http://www.topografix.com/GPX/1/1">
              <metadata><name>Road 5200</name></metadata>
              <wpt lat="45.2100" lon="-117.6400">
                <name>Gate</name>
              </wpt>
              <trk>
                <name>Drive in</name>
                <trkseg>
                  <trkpt lat="45.2057" lon="-117.6370"><ele>1310</ele></trkpt>
                  <trkpt lat="45.2060" lon="-117.6365"><ele>1315</ele></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val back = GpxFormat.read(foreign)
        assertNotNull(back)
        assertEquals("Road 5200", back!!.incidentName)
        assertEquals(1, back.pins.size)
        assertEquals("Gate", back.pins.first().title)
        assertEquals(1, back.tracks.size)
        assertEquals(2, back.tracks.first().points.size)
        assertEquals("Drive in", back.tracks.first().name)
    }

    @Test
    fun selfClosingPointsAreReadRatherThanSwallowingTheRest() {
        // Written by several programs when a point has nothing but a position.
        // Read carelessly, the first one runs to the end of the file and the
        // track becomes one point.
        val terse = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <wpt lat="45.21" lon="-117.64"/>
              <wpt lat="45.22" lon="-117.65"><name>Second</name></wpt>
              <trk><name>T</name><trkseg>
                <trkpt lat="45.2057" lon="-117.6370"/>
                <trkpt lat="45.2060" lon="-117.6365"/>
                <trkpt lat="45.2065" lon="-117.6360"/>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val back = GpxFormat.read(terse)!!
        assertEquals(2, back.pins.size)
        assertEquals("Second", back.pins[1].title)
        assertEquals(3, back.tracks.first().points.size)
    }

    @Test
    fun anElementIsNotConfusedWithOneWhoseNameStartsTheSame() {
        // "<trk" is a prefix of "<trkpt" and "<trkseg". Matching on the prefix
        // turns every point into a track.
        val back = GpxFormat.read(GpxFormat.write(pkg))!!
        assertEquals(1, back.tracks.size)
    }

    @Test
    fun textThatWouldBreakTheFileIsCarriedSafely() {
        val awkward = SharePackage(
            incidentName = "Ben & Jerry's <Ridge>",
            pins = listOf(
                SharePin(
                    id = "p", title = "R&R \"spot\"", latitude = 45.0, longitude = -117.0,
                    note = "watch the <wire> & the drop"
                )
            )
        )
        val back = GpxFormat.read(GpxFormat.write(awkward))
        assertNotNull(back)
        assertEquals("Ben & Jerry's <Ridge>", back!!.incidentName)
        assertEquals("R&R \"spot\"", back.pins.first().title)
        assertEquals("watch the <wire> & the drop", back.pins.first().note)
    }

    @Test
    fun rubbishIsRefusedRatherThanProducingAnEmptyIncident() {
        assertNull(GpxFormat.read(""))
        assertNull(GpxFormat.read("hello"))
        assertNull(GpxFormat.read("{\"type\":\"FeatureCollection\"}"))
        // A GPX with nothing in it is not worth importing either.
        assertNull(
            GpxFormat.read("<gpx version=\"1.1\"><metadata><name>x</name></metadata></gpx>")
        )
    }

    @Test
    fun aPackageSaysWhatIsInItBeforeItIsSent() {
        assertEquals("1 track · 2 pins", pkg.describe())
        assertEquals("Nothing to send", SharePackage("x").describe())
        assertEquals(
            "2 tracks",
            SharePackage("x", tracks = List(2) { ShareTrack("$it", "t", emptyList()) }).describe()
        )
    }
}
