package com.rhecyee.firelinemap.share

import com.rhecyee.firelinemap.map.MapCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tracks and pins as text somebody pastes back in.
 *
 * The last path that cannot fail. A file can be refused by a carrier for its
 * size, refused by a messaging app for its type, or arrive on a phone with
 * nothing that opens it. Text in the body of a message has none of those
 * failure modes -- but only if what is pasted back redraws exactly what was
 * sent, which is what these check.
 */
class TextCodecTest {

    private val lat = 45.20575
    private val lon = -117.63700

    private fun north(meters: Double) = lat + meters / 111_194.93
    private fun east(meters: Double) = lon + meters / (111_194.93 * 0.7009)

    private fun road(count: Int, fromMillis: Long = 1_754_390_000_000L) =
        (0 until count).map { step ->
            SharePoint(
                north(step * 25.0),
                east(60.0 * kotlin.math.sin(step / 8.0)),
                timeMillis = fromMillis + step * 5_000L
            )
        }

    private val pkg = SharePackage(
        incidentName = "Burnt Creek 2026",
        author = "L. Yee",
        pins = listOf(
            SharePin("p1", "DP 12", lat, lon, "drop_point", "Turnaround for tenders"),
            SharePin("p2", "Helispot 3", north(800.0), east(400.0), "helispot")
        ),
        tracks = listOf(ShareTrack("t1", "Div Z morning", road(120)))
    )

    private fun roundTrip(source: SharePackage): SharePackage {
        var assembly = TextCodec.Assembly()
        TextCodec.parts(source).forEach { part ->
            assembly = assembly.plus(TextCodec.readPart(part)!!)
        }
        assertTrue("checksum must verify", assembly.verified())
        return TextCodec.decode(assembly.body()!!)!!
    }

    @Test
    fun everythingComesBackAndRedraws() {
        val back = roundTrip(pkg)
        assertEquals("Burnt Creek 2026", back.incidentName)
        assertEquals("L. Yee", back.author)
        assertEquals(2, back.pins.size)
        assertEquals(1, back.tracks.size)
        assertEquals(120, back.tracks.first().points.size)
    }

    /**
     * The point of the whole thing: the shape survives.
     *
     * A metre is finer than any receiver reports and finer than a track
     * thinned to fit a message. Anything looser and a road comes back as a
     * road nobody can drive.
     */
    @Test
    fun everyPointLandsWithinAMetreOfWhereItWas() {
        val back = roundTrip(pkg)
        val sent = pkg.tracks.first().points
        val received = back.tracks.first().points
        assertEquals(sent.size, received.size)
        sent.forEachIndexed { index, point ->
            val off = MapCoverage.distanceMeters(
                point.latitude, point.longitude,
                received[index].latitude, received[index].longitude
            )
            assertTrue("point $index came back $off m out", off < 1.0)
        }
    }

    @Test
    fun aPinKeepsItsNameSymbolAndNote() {
        val back = roundTrip(pkg)
        val pin = back.pins.first { it.title == "DP 12" }
        assertEquals("drop_point", pin.symbolId)
        assertEquals("Turnaround for tenders", pin.note)
        assertEquals(lat, pin.latitude, 1e-5)
        assertEquals(lon, pin.longitude, 1e-5)
    }

    /**
     * Times ride along, so a pasted track still answers how fast the road is.
     */
    @Test
    fun theTimesSurviveSoSpeedStillWorks() {
        val back = roundTrip(pkg)
        val sent = pkg.tracks.first().points
        val received = back.tracks.first().points
        sent.forEachIndexed { index, point ->
            // Whole seconds: nothing here is measured finer.
            assertEquals(point.timeMillis!! / 1000, received[index].timeMillis!! / 1000)
        }
    }

    @Test
    fun aTrackWithNoTimesStillDrawsRatherThanLandingInNineteenSeventy() {
        val shapeOnly = SharePackage(
            "x",
            tracks = listOf(
                ShareTrack("t", "T", road(20).map { it.copy(timeMillis = null) })
            )
        )
        val back = roundTrip(shapeOnly)
        assertEquals(20, back.tracks.first().points.size)
        assertTrue(back.tracks.first().points.all { it.timeMillis == null })
    }

    // ------------------------------------------------------- pasting it back

    @Test
    fun partsCanBePastedInAnyOrder() {
        // Deliberately small parts, to force several and prove the ordering
        // does not matter. The encoding is compact enough that a real send of
        // this size takes two.
        val parts = TextCodec.parts(pkg, partLength = 200)
        assertTrue("should need several parts, was ${parts.size}", parts.size > 2)

        var assembly = TextCodec.Assembly()
        parts.reversed().forEach { assembly = assembly.plus(TextCodec.readPart(it)!!) }
        assertTrue(assembly.isComplete)
        assertTrue(assembly.verified())
        assertEquals(120, TextCodec.decode(assembly.body()!!)!!.tracks.first().points.size)
    }

    @Test
    fun theAppSaysWhichPartsAreStillMissing() {
        val parts = TextCodec.parts(pkg, partLength = 400)
        var assembly = TextCodec.Assembly()
        assertEquals("Nothing pasted yet", assembly.describe())

        assembly = assembly.plus(TextCodec.readPart(parts[0])!!)
        assertTrue(assembly.describe(), assembly.describe().contains("still need"))
        assertTrue(!assembly.isComplete)

        parts.drop(1).forEach { assembly = assembly.plus(TextCodec.readPart(it)!!) }
        assertTrue(assembly.describe().contains("ready"))
    }

    @Test
    fun pastingTheSamePartTwiceChangesNothing() {
        val parts = TextCodec.parts(pkg, partLength = 400)
        var assembly = TextCodec.Assembly()
        parts.forEach { assembly = assembly.plus(TextCodec.readPart(it)!!) }
        val before = assembly.parts.size
        assembly = assembly.plus(TextCodec.readPart(parts[1])!!)
        assertEquals(before, assembly.parts.size)
        assertTrue(assembly.verified())
    }

    /**
     * Two sends must not be spliced together.
     *
     * Parts from different messages would assemble into something that looks
     * like a track and is not one, and whoever pasted them has no reason to
     * suspect it. Starting again is the only safe answer.
     */
    @Test
    fun aPartFromADifferentSendStartsAgainRatherThanMixing() {
        val other = SharePackage("Other fire", tracks = listOf(ShareTrack("t", "T", road(90))))
        val mine = TextCodec.parts(pkg, partLength = 400)
        val theirs = TextCodec.parts(other, partLength = 400)

        var assembly = TextCodec.Assembly()
        assembly = assembly.plus(TextCodec.readPart(mine[0])!!)
        assembly = assembly.plus(TextCodec.readPart(theirs[0])!!)

        assertEquals("the earlier send must be dropped", 1, assembly.parts.size)
        assertEquals(theirs.size, assembly.total)
    }

    /**
     * Messages get wrapped, quoted, indented and re-flowed on the way through.
     * None of that changes what was sent, and a reader that has not allowed
     * for it rejects a good paste with no way to say why.
     */
    @Test
    fun aPasteManglesByTheTransportStillReads() {
        val part = TextCodec.parts(pkg, partLength = 400).first()
        val mangled = "Here you go\n\n" + part.chunked(40).joinToString("\n") + "\n\n"
        val read = TextCodec.readPart(mangled)
        assertNotNull(read)
        assertEquals(TextCodec.readPart(part)!!.slice, read!!.slice)
    }

    @Test
    fun aTruncatedPasteIsCaughtRatherThanDrawnWrong() {
        val parts = TextCodec.parts(pkg, partLength = 400)
        var assembly = TextCodec.Assembly()
        parts.dropLast(1).forEach { assembly = assembly.plus(TextCodec.readPart(it)!!) }
        // Last part cut in half, as a paste that missed the end would be.
        val short = parts.last().let { it.substring(0, it.length - 40) }
        assembly = assembly.plus(TextCodec.readPart(short)!!)

        assertTrue("it looks complete", assembly.isComplete)
        assertTrue("but the checksum must refuse it", !assembly.verified())
    }

    @Test
    fun somethingThatIsNotAPasteIsRefused() {
        assertNull(TextCodec.readPart(""))
        assertNull(TextCodec.readPart("hello, where are you"))
        assertNull(TextCodec.readPart("N 45 12.345 W 117 38.220"))
        assertNull(TextCodec.readPart("FL1;abc"))
        assertNull(TextCodec.readPart("FL1;abc;0;3;xyz"))
        assertNull(TextCodec.readPart("FL1;abc;4;3;xyz"))
        assertNull(TextCodec.decode(""))
        assertNull(TextCodec.decode("nonsense"))
    }

    // --------------------------------------------------------------- размер

    @Test
    fun aTrackIsFarShorterThanWritingOutTheNumbers() {
        val encoded = TextCodec.encode(pkg)
        // The same points as plain decimal degrees, which is the obvious way
        // and the reason this encoding exists.
        val plain = pkg.tracks.first().points.joinToString(",") {
            "${it.latitude},${it.longitude}"
        }
        assertTrue(
            "encoded $encoded.length vs plain ${plain.length}",
            encoded.length < plain.length / 3
        )
    }

    @Test
    fun aShiftSizedTrackStillFitsAHandfulOfParts() {
        // Two hundred points is about what a thinned shift comes to.
        val shift = SharePackage(
            "Burnt Creek 2026",
            tracks = listOf(ShareTrack("t", "Shift", road(200)))
        )
        val parts = TextCodec.parts(shift)
        assertTrue("was ${parts.size} parts", parts.size <= 3)
        parts.forEach {
            assertTrue(
                "a part ran to ${it.length} characters",
                it.length <= TextCodec.DEFAULT_PART_LENGTH
            )
        }
    }

    @Test
    fun namesWithAwkwardCharactersComeBackIntact() {
        val awkward = SharePackage(
            incidentName = "Ben & Jerry's; Ridge!",
            pins = listOf(SharePin("p", "DP 12 % north!", lat, lon, note = "watch; the wire"))
        )
        val back = roundTrip(awkward)
        assertEquals("Ben & Jerry's; Ridge!", back.incidentName)
        assertEquals("DP 12 % north!", back.pins.first().title)
        assertEquals("watch; the wire", back.pins.first().note)
    }

    /**
     * The separators must never appear inside an encoded shape, or a track
     * splits itself in half when it is read back.
     */
    @Test
    fun anEncodedShapeNeverContainsTheSeparators() {
        val wandering = (0 until 500).map { step ->
            SharePoint(
                north(step * 13.0 - 3000.0),
                east(900.0 * kotlin.math.sin(step / 3.0))
            )
        }
        val encoded = PolylineCodec.encodePoints(wandering.map { it.latitude to it.longitude })
        assertTrue(encoded.none { it == ';' || it == '!' || it == '%' })
        assertTrue(encoded.none { it.isWhitespace() })
        assertTrue(encoded.all { it >= PolylineCodec.LOWEST_CHARACTER })
    }
}
