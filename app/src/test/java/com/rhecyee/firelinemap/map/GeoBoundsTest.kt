package com.rhecyee.firelinemap.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoBoundsTest {

    private val bounds = GeoBounds(45.10, -117.75, 45.32, -117.50)

    @Test
    fun roundTripsThroughJson() {
        assertEquals(bounds, GeoBounds.fromJson(bounds.toJson()))
    }

    @Test
    fun parsesRegardlessOfKeyOrderAndWhitespace() {
        val json = """{ "north" : 45.32 , "east": -117.50, "south":45.10, "west" : -117.75 }"""
        assertEquals(bounds, GeoBounds.fromJson(json))
    }

    @Test
    fun containsIsInclusiveOfEdges() {
        assertTrue(bounds.contains(45.10, -117.75))
        assertTrue(bounds.contains(45.32, -117.50))
        assertFalse(bounds.contains(45.09, -117.60))
    }

    @Test
    fun nearestPointClampsToTheBox() {
        assertEquals(45.32 to -117.50, bounds.nearestPointTo(45.90, -117.10))
        assertEquals(45.20 to -117.75, bounds.nearestPointTo(45.20, -118.40))
    }

    @Test
    fun nearestPointOfAnInteriorPositionIsItself() {
        assertEquals(45.20 to -117.60, bounds.nearestPointTo(45.20, -117.60))
    }

    @Test
    fun malformedOrAbsentJsonYieldsNull() {
        // An unreadable extent must degrade to "unknown", never to a wrong box.
        assertNull(GeoBounds.fromJson(null))
        assertNull(GeoBounds.fromJson(""))
        assertNull(GeoBounds.fromJson("not json"))
        assertNull(GeoBounds.fromJson("""{"south":45.1,"west":-117.75}"""))
        assertNull(GeoBounds.fromJson("""{"south":45.1,"west":"x","north":45.3,"east":-117.5}"""))
    }

    @Test
    fun invertedOrOutOfRangeBoxesAreRejected() {
        assertNull(GeoBounds.fromJson("""{"south":45.9,"west":-117.75,"north":45.1,"east":-117.5}"""))
        assertNull(GeoBounds.fromJson("""{"south":-95.0,"west":-117.75,"north":45.1,"east":-117.5}"""))
    }
}
