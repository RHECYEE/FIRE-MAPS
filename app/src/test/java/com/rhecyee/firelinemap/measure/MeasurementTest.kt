package com.rhecyee.firelinemap.measure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementTest {

    private val lat = 45.20575
    private val lon = -117.6370

    private fun north(meters: Double) = lat + meters / 111_194.93
    private fun east(meters: Double) = lon + meters / (111_194.93 * Math.cos(Math.toRadians(lat)))

    @Test
    fun twoPointsMeasureAStraightLine() {
        val session = MeasureSession(MeasureMode.DISTANCE)
        session.add(lat, lon)
        session.add(north(1_000.0), lon)

        val result = session.result()
        assertEquals(1, result.segments.size)
        assertEquals(1_000.0, result.totalDistanceMeters, 2.0)
        // Due north.
        assertEquals(0.0, result.segments.single().bearingDegrees, 0.5)
    }

    @Test
    fun morePointsAccumulateAlongThePath() {
        val session = MeasureSession(MeasureMode.DISTANCE)
        session.add(lat, lon)
        session.add(north(500.0), lon)
        session.add(north(500.0), east(500.0))
        session.add(north(1_000.0), east(500.0))

        val result = session.result()
        assertEquals(3, result.segments.size)
        assertEquals(1_500.0, result.totalDistanceMeters, 5.0)
        // The middle leg runs due east.
        assertEquals(90.0, result.segments[1].bearingDegrees, 1.0)
    }

    @Test
    fun aMeasurementNeedsTwoPointsAndAnAreaNeedsThree() {
        val line = MeasureSession(MeasureMode.DISTANCE)
        line.add(lat, lon)
        assertFalse(line.isMeasurable)
        line.add(north(100.0), lon)
        assertTrue(line.isMeasurable)

        val area = MeasureSession(MeasureMode.AREA)
        area.add(lat, lon)
        area.add(north(100.0), lon)
        assertFalse("two points enclose nothing", area.isMeasurable)
        area.add(north(100.0), east(100.0))
        assertTrue(area.isMeasurable)
    }

    @Test
    fun aSquareEnclosesTheAreaItLooksLike() {
        val session = MeasureSession(MeasureMode.AREA)
        val side = 1_000.0
        session.add(lat, lon)
        session.add(north(side), lon)
        session.add(north(side), east(side))
        session.add(lat, east(side))

        val squareMeters = session.result().areaSquareMeters
        assertNotNull(squareMeters)
        // One square kilometre, within the spherical approximation.
        assertEquals(1_000_000.0, squareMeters!!, 5_000.0)
    }

    @Test
    fun areaIsReportedInAcresForFireUse() {
        val session = MeasureSession(MeasureMode.AREA)
        val side = 1_000.0
        session.add(lat, lon)
        session.add(north(side), lon)
        session.add(north(side), east(side))
        session.add(lat, east(side))

        val acres = AreaUnit.ACRES.from(session.result().areaSquareMeters!!)
        assertEquals(247.1, acres, 2.0)
    }

    @Test
    fun windingDirectionDoesNotChangeTheArea() {
        val side = 800.0
        val clockwise = MeasureSession(MeasureMode.AREA).apply {
            add(lat, lon); add(lat, east(side)); add(north(side), east(side)); add(north(side), lon)
        }
        val anticlockwise = MeasureSession(MeasureMode.AREA).apply {
            add(lat, lon); add(north(side), lon); add(north(side), east(side)); add(lat, east(side))
        }
        assertEquals(
            clockwise.result().areaSquareMeters!!,
            anticlockwise.result().areaSquareMeters!!,
            1.0
        )
    }

    @Test
    fun areaPerimeterClosesTheRing() {
        val session = MeasureSession(MeasureMode.AREA)
        val side = 1_000.0
        session.add(lat, lon)
        session.add(north(side), lon)
        session.add(north(side), east(side))
        session.add(lat, east(side))

        // Four sides, including the closing leg back to the start.
        assertEquals(4_000.0, session.result().totalDistanceMeters, 20.0)
    }

    @Test
    fun aDistancePathIsNotClosed() {
        val session = MeasureSession(MeasureMode.DISTANCE)
        val side = 1_000.0
        session.add(lat, lon)
        session.add(north(side), lon)
        session.add(north(side), east(side))

        assertEquals(2_000.0, session.result().totalDistanceMeters, 10.0)
        assertNull(session.result().areaSquareMeters)
    }

    @Test
    fun slopeIsReportedOnceElevationsAreKnown() {
        val session = MeasureSession(MeasureMode.DISTANCE)
        session.add(lat, lon)
        session.add(north(1_000.0), lon)

        assertNull("no slope before elevations arrive", session.result().segments[0].slopePercent)

        session.setElevation(0, 1_200.0)
        session.setElevation(1, 1_300.0)

        val segment = session.result().segments.single()
        assertEquals(100.0, segment.riseMeters!!, 0.001)
        // 100 m of rise over 1000 m of run is a ten percent grade.
        assertEquals(10.0, segment.slopePercent!!, 0.1)
        assertEquals(5.71, segment.slopeDegrees!!, 0.1)
    }

    @Test
    fun descendingGroundGivesNegativeSlope() {
        val session = MeasureSession(MeasureMode.DISTANCE)
        session.add(lat, lon)
        session.add(north(500.0), lon)
        session.setElevation(0, 1_500.0)
        session.setElevation(1, 1_400.0)

        assertTrue(session.result().segments.single().slopePercent!! < 0)
    }

    @Test
    fun gainAndLossAreAccumulatedSeparately() {
        val session = MeasureSession(MeasureMode.DISTANCE)
        session.add(lat, lon)
        session.add(north(500.0), lon)
        session.add(north(1_000.0), lon)
        session.setElevation(0, 1_000.0)
        session.setElevation(1, 1_150.0)
        session.setElevation(2, 1_100.0)

        val result = session.result()
        assertEquals(150.0, result.gainMeters!!, 0.001)
        assertEquals(50.0, result.lossMeters!!, 0.001)
        // Net 100 m over 1000 m.
        assertEquals(10.0, result.overallSlopePercent!!, 0.2)
    }

    @Test
    fun partialElevationsSuppressTheTotals() {
        val session = MeasureSession(MeasureMode.DISTANCE)
        session.add(lat, lon)
        session.add(north(500.0), lon)
        session.add(north(1_000.0), lon)
        session.setElevation(0, 1_000.0)
        session.setElevation(1, 1_100.0)
        // The third elevation never arrived.

        val result = session.result()
        assertNull("a partial profile must not be reported as a total", result.gainMeters)
        assertNull(result.overallSlopePercent)
        // The leg that does have both ends still reports.
        assertNotNull(result.segments[0].slopePercent)
        assertNull(result.segments[1].slopePercent)
    }

    @Test
    fun undoRemovesTheLastPointOnly() {
        val session = MeasureSession(MeasureMode.DISTANCE)
        session.add(lat, lon)
        session.add(north(100.0), lon)
        session.add(north(200.0), lon)

        assertTrue(session.undo())
        assertEquals(2, session.size)
        assertTrue(session.undo())
        assertTrue(session.undo())
        assertFalse("nothing left to undo", session.undo())
        assertTrue(session.isEmpty)
    }

    @Test
    fun slopeDistanceExceedsPlanDistanceOnAPitch() {
        // 100 m of rise over 100 m of plan distance is a 45 degree pitch.
        assertEquals(141.4, MeasureSession.slopeDistance(100.0, 100.0), 0.1)
        assertEquals(100.0, MeasureSession.slopeDistance(100.0, 0.0), 0.001)
    }

    @Test
    fun unitsConvertAndCycle() {
        assertEquals(3_280.84, DistanceUnit.FEET.from(1_000.0), 0.1)
        assertEquals(1.0, DistanceUnit.MILES.from(1_609.344), 0.0001)
        assertEquals(1.0, DistanceUnit.NAUTICAL_MILES.from(1_852.0), 0.0001)
        assertEquals(2.4711, AreaUnit.ACRES.from(10_000.0), 0.001)

        // Cycling walks every unit and returns to the start.
        var unit = DistanceUnit.FEET
        repeat(DistanceUnit.entries.size) { unit = unit.next() }
        assertEquals(DistanceUnit.FEET, unit)
    }
}
