package com.rhecyee.firelinemap.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A track's shape, as stored.
 *
 * The reader and the writer are checked against each other because a track
 * that reads back differently from how it was written is a track drawn
 * somewhere nobody went -- and nothing on screen would say so.
 */
class TrackGeometryTest {

    private val walk = listOf(
        Fix(45.20575, -117.63700, 1_754_390_000_000L),
        Fix(45.20600, -117.63650, 1_754_390_005_000L),
        Fix(45.20650, -117.63600, 1_754_390_010_000L)
    )

    @Test
    fun aTrackComesBackExactlyAsItWentIn() {
        val back = TrackGeometry.read(TrackGeometry.write(walk))
        assertEquals(walk.size, back.size)
        walk.forEachIndexed { index, fix ->
            assertEquals(fix.latitude, back[index].latitude, 1e-9)
            assertEquals(fix.longitude, back[index].longitude, 1e-9)
            assertEquals(fix.timeMillis, back[index].timeMillis)
        }
    }

    @Test
    fun itIsStillGeoJsonAndStillLongitudeFirst() {
        val json = TrackGeometry.write(walk)
        assertTrue(json.startsWith("{\"type\":\"LineString\",\"coordinates\":["))
        // Longitude first is the part of GeoJSON everybody gets wrong once.
        assertTrue(json.contains("[-117.637,45.20575]"))
        assertTrue(json.endsWith("}"))
    }

    /**
     * The times are the reason this exists.
     *
     * Without them a saved track is a line and nothing more: no speed, and no
     * way to answer how long a road takes, which is what several passes over
     * the same ground are for.
     */
    @Test
    fun theTimesAreCarriedAlongsideTheShape() {
        val json = TrackGeometry.write(walk)
        assertTrue("times must be stored", json.contains("\"coordTimes\""))
        val times = TrackGeometry.read(json).map { it.timeMillis }
        assertEquals(listOf(1_754_390_000_000L, 1_754_390_005_000L, 1_754_390_010_000L), times)
    }

    @Test
    fun aTrackStoredBeforeTimesWereKeptStillDraws() {
        // Exactly what the previous version of the app wrote. These are on
        // people's phones now and must not come back empty.
        val old = "{\"type\":\"LineString\",\"coordinates\":" +
            "[[-117.637,45.20575],[-117.6365,45.206],[-117.636,45.2065]]}"
        val back = TrackGeometry.read(old)
        assertEquals(3, back.size)
        assertEquals(45.20575, back.first().latitude, 1e-9)
        // Not known, rather than 1970.
        assertTrue(back.all { it.timeMillis == 0L })
    }

    @Test
    fun anElevationInTheCoordinatesDoesNotDisplaceTheLatitude() {
        // GeoJSON allows a third element. A reader that takes the last two
        // numbers instead of the first two puts every point in the sea.
        val withHeight = "{\"type\":\"LineString\",\"coordinates\":" +
            "[[-117.637,45.20575,1310.0],[-117.6365,45.206,1315.5]]}"
        val back = TrackGeometry.read(withHeight)
        assertEquals(2, back.size)
        assertEquals(45.20575, back[0].latitude, 1e-9)
        assertEquals(-117.637, back[0].longitude, 1e-9)
    }

    @Test
    fun aShortTimesArrayIsUsedOnlyAsFarAsItGoes() {
        val ragged = "{\"type\":\"LineString\",\"coordinates\":" +
            "[[-117.637,45.20575],[-117.6365,45.206],[-117.636,45.2065]]," +
            "\"coordTimes\":[1754390000000,1754390005000]}"
        val back = TrackGeometry.read(ragged)
        assertEquals(3, back.size)
        assertEquals(1_754_390_000_000L, back[0].timeMillis)
        assertEquals(1_754_390_005_000L, back[1].timeMillis)
        assertEquals(0L, back[2].timeMillis)
    }

    @Test
    fun aTraceWithNoTimesDoesNotClaimToHaveThem() {
        val json = TrackGeometry.writePositions(
            listOf(45.20575 to -117.63700, 45.20600 to -117.63650)
        )
        assertTrue("no times means no times array", !json.contains("coordTimes"))
        assertEquals(2, TrackGeometry.read(json).size)
    }

    @Test
    fun anEmptyOrBrokenGeometryGivesNothingRatherThanThrowing() {
        assertTrue(TrackGeometry.read("").isEmpty())
        assertTrue(TrackGeometry.read("{}").isEmpty())
        assertTrue(TrackGeometry.read("not json").isEmpty())
        assertTrue(
            TrackGeometry.read("{\"type\":\"LineString\",\"coordinates\":[]}").isEmpty()
        )
        assertEquals(0, TrackGeometry.read(TrackGeometry.write(emptyList())).size)
    }

    @Test
    fun aStoredTrackFeedsTheOverlapAnalysisDirectly() {
        // The whole chain: recorded, stored, read back, and asked how fast it
        // was going. This is what the feature actually depends on.
        val json = TrackGeometry.write(
            (0..40).map { step ->
                Fix(45.20575 + step * 8.0 / 111_194.93, -117.63700, 1_754_390_000_000L + step * 5000L)
            }
        )
        val line = TrackLine("t", "Stored run", TrackGeometry.read(json))
        val report = TrackOverlap.at(45.20575 + 100.0 / 111_194.93, -117.63700, listOf(line))
        assertEquals(1, report.passes.size)
        assertEquals(1.6, report.passes.first().speedMetersPerSecond!!, 0.3)
    }
}
