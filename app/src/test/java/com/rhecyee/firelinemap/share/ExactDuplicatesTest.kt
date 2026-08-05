package com.rhecyee.firelinemap.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dropping copies of a record already held, and nothing else.
 *
 * The line these draw is the whole point. Removing a record that encodes to
 * exactly the bytes of one already here is safe: it is the same thing arriving
 * twice, which happens for ordinary reasons. Deciding that two pins near each
 * other are the same thing on the ground is not safe, needs a radio and a
 * person, and is never done here.
 */
class ExactDuplicatesTest {

    private val lat = 45.20575
    private val lon = -117.63700

    private fun north(meters: Double) = lat + meters / 111_194.93

    private fun road(count: Int, fromMillis: Long = 1_754_390_000_000L) =
        (0 until count).map { step ->
            SharePoint(north(step * 25.0), lon, timeMillis = fromMillis + step * 5_000L)
        }

    private fun pin(title: String, atMeters: Double = 0.0, symbol: String = "drop_point") =
        SharePin("id-$title", title, north(atMeters), lon, symbol, "a note")

    private val held = SharePackage(
        incidentName = "Burnt Creek 2026",
        pins = listOf(pin("DP 12"), pin("Helispot 3", 800.0, "helispot")),
        tracks = listOf(ShareTrack("t1", "Div Z morning", road(40)))
    )

    /**
     * The case this exists for.
     *
     * A sender adds one track and re-sends the whole incident, which is the
     * natural thing to do. Without this the receiver ends up with two of
     * everything else.
     */
    @Test
    fun aResendPlantsOnlyWhatIsNew() {
        val again = held.copy(
            tracks = held.tracks + ShareTrack("t2", "Afternoon", road(60, 1_754_400_000_000L))
        )
        val result = ExactDuplicates.filter(again, held)

        assertEquals(0, result.kept.pins.size)
        assertEquals(1, result.kept.tracks.size)
        assertEquals("Afternoon", result.kept.tracks.first().name)
        assertEquals(2, result.duplicatePins)
        assertEquals(1, result.duplicateTracks)
    }

    @Test
    fun theSamePackageTwiceAddsNothingTheSecondTime() {
        val result = ExactDuplicates.filter(held, held)
        assertTrue(result.kept.isEmpty)
        assertEquals(3, result.duplicates)
        assertTrue(result.describe(), result.describe().contains("Nothing new"))
    }

    @Test
    fun anIdOrAPlacementTimeDoesNotMakeItADifferentRecord() {
        // The same pin on two phones has two ids and two placement times.
        // Counting those as different is how a re-send doubles everything.
        val theirCopy = held.copy(
            pins = held.pins.map { it.copy(id = "somebody-elses-id", createdAt = 999_999L) }
        )
        val result = ExactDuplicates.filter(theirCopy, held)
        assertEquals(0, result.kept.pins.size)
        assertEquals(2, result.duplicatePins)
    }

    /**
     * The judgement this must never make.
     *
     * Two pins a few metres apart are routinely two real things -- a drop
     * point and the turnaround beside it, two hazards on the same stretch.
     * Merging them is a decision for a person on a radio.
     */
    @Test
    fun aPinNearAnotherOneIsNotADuplicate() {
        // Five metres away. Well inside GPS scatter, and still its own pin.
        val nearby = SharePackage("x", pins = listOf(pin("DP 12", atMeters = 5.0)))
        val result = ExactDuplicates.filter(nearby, held)
        assertEquals(1, result.kept.pins.size)
        assertEquals(0, result.duplicatePins)
    }

    @Test
    fun aDifferentNameOrSymbolOrNoteKeepsItSeparate() {
        val variations = listOf(
            pin("DP 12").copy(title = "DP 12A"),
            pin("DP 12").copy(symbolId = "helispot"),
            pin("DP 12").copy(note = "different note")
        )
        variations.forEach { variant ->
            val result = ExactDuplicates.filter(SharePackage("x", pins = listOf(variant)), held)
            assertEquals(
                "a pin differing only in one field must still be its own pin",
                1,
                result.kept.pins.size
            )
        }
    }

    @Test
    fun aTrackOverTheSameRoadAtADifferentTimeIsItsOwnPass() {
        // The same road driven again is the whole reason the merge readout
        // exists. It must never be collapsed into the earlier pass.
        val secondPass = SharePackage(
            "x",
            tracks = listOf(ShareTrack("t9", "Div Z morning", road(40, 1_754_500_000_000L)))
        )
        val result = ExactDuplicates.filter(secondPass, held)
        assertEquals(1, result.kept.tracks.size)
        assertEquals(0, result.duplicateTracks)
    }

    @Test
    fun aTrackDownADifferentRoadIsItsOwnTrack() {
        val elsewhere = SharePackage(
            "x",
            tracks = listOf(
                ShareTrack(
                    "t9", "Div Z morning",
                    road(40).map { it.copy(longitude = it.longitude + 0.01) }
                )
            )
        )
        assertEquals(1, ExactDuplicates.filter(elsewhere, held).kept.tracks.size)
    }

    @Test
    fun aPackageCarryingTheSameRecordTwicePlantsItOnce() {
        val doubled = SharePackage("x", pins = listOf(pin("New one", 50.0), pin("New one", 50.0)))
        val result = ExactDuplicates.filter(doubled, held)
        assertEquals(1, result.kept.pins.size)
        assertEquals(1, result.duplicatePins)
    }

    @Test
    fun anEmptyIncidentTakesEverything() {
        val result = ExactDuplicates.filter(held, SharePackage("empty"))
        assertEquals(2, result.kept.pins.size)
        assertEquals(1, result.kept.tracks.size)
        assertEquals(0, result.duplicates)
        assertTrue(result.describe(), result.describe().endsWith("added"))
    }

    @Test
    fun theCountIsReportedSoNothingDisappearsQuietly() {
        val mixed = held.copy(pins = held.pins + pin("Genuinely new", 400.0))
        val result = ExactDuplicates.filter(mixed, held)
        val said = result.describe()
        assertTrue(said, said.contains("1 pin added"))
        assertTrue(said, said.contains("3 already here"))
    }

    /**
     * The comparison is on the bytes that were actually sent.
     *
     * Not on the fields, because the encoded record is what the two phones
     * agreed on -- and if the two ever diverge, this is what catches it.
     */
    @Test
    fun twoRecordsAreTheSameExactlyWhenTheyEncodeTheSame() {
        val a = pin("DP 12")
        val b = pin("DP 12").copy(id = "other", createdAt = 5L)
        val c = pin("DP 12", atMeters = 5.0)

        assertEquals(TextCodec.recordOf(a), TextCodec.recordOf(b))
        assertTrue(TextCodec.recordOf(a) != TextCodec.recordOf(c))
    }
}
