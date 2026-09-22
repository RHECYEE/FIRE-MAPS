package com.rhecyee.firelinemap.annotations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationGeometryTest {

    private fun run(vararg points: Pair<Double, Double>) = points.toList()

    private fun assertSame(
        expected: List<List<Pair<Double, Double>>>,
        actual: List<List<Pair<Double, Double>>>
    ) {
        assertEquals("ring count", expected.size, actual.size)
        expected.zip(actual).forEach { (want, got) ->
            assertEquals("point count", want.size, got.size)
            want.zip(got).forEach { (a, b) ->
                assertEquals(a.first, b.first, 1e-6)
                assertEquals(a.second, b.second, 1e-6)
            }
        }
    }

    @Test
    fun anOpenRunSurvivesTheRoundTrip() {
        val line = listOf(run(45.1 to -117.1, 45.2 to -117.2, 45.3 to -117.15))
        val text = AnnotationGeometry.encode(AnnotationKind.MEASURE_LINE, line)

        assertTrue(text.contains("LineString"))
        assertSame(line, AnnotationGeometry.decode(text))
    }

    @Test
    fun geoJsonIsWrittenLongitudeFirst() {
        // Everything else in this app stores longitude first because GeoJSON
        // says so, and a reader that disagrees puts the fire in Mongolia.
        val text = AnnotationGeometry.encode(
            AnnotationKind.MEASURE_LINE,
            listOf(run(45.0 to -117.0, 46.0 to -118.0))
        )
        assertTrue("longitude leads each pair", text.contains("[-117.0000000,45.0000000]"))
    }

    @Test
    fun anEnclosedShapeComesBackClosedButIsReadOpen() {
        val ring = listOf(run(45.0 to -117.0, 45.0 to -117.1, 45.1 to -117.1, 45.1 to -117.0))
        val text = AnnotationGeometry.encode(AnnotationKind.MEASURE_AREA, ring)

        assertTrue(text.contains("Polygon"))
        // Stored closed, as GeoJSON requires: first point repeated at the end.
        assertEquals(5, text.split("],[").size)
        // Read back with the closing point still on it, so drawing it as a
        // path needs no special case at the seam.
        val decoded = AnnotationGeometry.decode(text)
        assertEquals(1, decoded.size)
        assertEquals(decoded.first().first(), decoded.first().last())
    }

    @Test
    fun aPerimeterKeepsItsHoles() {
        // An unburnt island inside a fire is a second ring, and losing it
        // would turn a doughnut into a disc and overstate the acreage.
        val outer = run(45.0 to -117.0, 45.0 to -117.2, 45.2 to -117.2, 45.2 to -117.0)
        val hole = run(45.08 to -117.08, 45.08 to -117.12, 45.12 to -117.12, 45.12 to -117.08)
        val text = AnnotationGeometry.encode(
            AnnotationKind.FIRELINE_PERIMETER, listOf(outer, hole)
        )

        val decoded = AnnotationGeometry.decode(text)
        assertEquals(2, decoded.size)
        assertEquals(5, decoded[0].size)
        assertEquals(5, decoded[1].size)
        assertEquals(45.0, decoded[0][0].first, 1e-6)
        assertEquals(45.08, decoded[1][0].first, 1e-6)
    }

    @Test
    fun aRingAlreadyClosedIsNotClosedTwice() {
        val ring = run(
            45.0 to -117.0, 45.0 to -117.1, 45.1 to -117.1, 45.0 to -117.0
        )
        val decoded = AnnotationGeometry.decode(
            AnnotationGeometry.encode(AnnotationKind.MEASURE_AREA, listOf(ring))
        )
        assertEquals(4, decoded.first().size)
    }

    @Test
    fun rubbishDecodesToNothingRatherThanThrowing() {
        listOf("", "{}", """{"type":"LineString"}""", """{"coordinates":[}""").forEach {
            assertTrue("decoding $it", AnnotationGeometry.decode(it).isEmpty())
        }
    }

    @Test
    fun aRunTooShortToDrawIsNotStored() {
        val text = AnnotationGeometry.encode(
            AnnotationKind.MEASURE_LINE, listOf(run(45.0 to -117.0))
        )
        assertTrue(AnnotationGeometry.decode(text).isEmpty())
    }

    @Test
    fun theLabelHangsOffTheShape() {
        val annotation = MapAnnotation(
            id = "a",
            kind = AnnotationKind.MEASURE_LINE,
            label = "1.2 mi",
            rings = listOf(run(45.0 to -117.0, 45.1 to -117.0, 45.2 to -117.0)),
            createdAt = 0L
        )
        assertEquals(45.1, annotation.labelAnchor()!!.first, 1e-6)
    }
}
