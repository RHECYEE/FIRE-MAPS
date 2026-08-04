package com.rhecyee.firelinemap.parcels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WkbGeometryTest {

    /** Builds well-known binary the way a GeoPackage writer would. */
    private class WkbBuilder(private val littleEndian: Boolean = true) {
        private val out = ByteArrayOutputStream()

        private fun int(value: Int) {
            val buffer = ByteBuffer.allocate(4)
                .order(if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            buffer.putInt(value)
            out.write(buffer.array())
        }

        private fun double(value: Double) {
            val buffer = ByteBuffer.allocate(8)
                .order(if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            buffer.putDouble(value)
            out.write(buffer.array())
        }

        fun polygon(vararg rings: List<Pair<Double, Double>>): WkbBuilder = apply {
            out.write(if (littleEndian) 1 else 0)
            int(3)
            int(rings.size)
            rings.forEach { ring ->
                int(ring.size)
                ring.forEach { (x, y) -> double(x); double(y) }
            }
        }

        fun multiPolygon(vararg polygons: List<List<Pair<Double, Double>>>): WkbBuilder = apply {
            out.write(if (littleEndian) 1 else 0)
            int(6)
            int(polygons.size)
            polygons.forEach { rings ->
                out.write(if (littleEndian) 1 else 0)
                int(3)
                int(rings.size)
                rings.forEach { ring ->
                    int(ring.size)
                    ring.forEach { (x, y) -> double(x); double(y) }
                }
            }
        }

        fun point(x: Double, y: Double): WkbBuilder = apply {
            out.write(if (littleEndian) 1 else 0)
            int(1)
            double(x); double(y)
        }

        fun build(): ByteArray = out.toByteArray()
    }

    /** A square running clockwise from its southwest corner. */
    private fun square(west: Double, south: Double, size: Double) = listOf(
        west to south,
        west to south + size,
        west + size to south + size,
        west + size to south,
        west to south
    )

    /** Wraps well-known binary in a GeoPackage header. */
    private fun geoPackageBlob(wkb: ByteArray, envelopeCode: Int = 0): ByteArray {
        val envelopeBytes = when (envelopeCode) {
            0 -> 0
            1 -> 32
            2, 3 -> 48
            else -> 64
        }
        val out = ByteArrayOutputStream()
        out.write('G'.code)
        out.write('P'.code)
        out.write(0) // version
        out.write(envelopeCode shl 1) // flags: big-endian header, envelope code
        out.write(ByteArray(4)) // srs id
        out.write(ByteArray(envelopeBytes))
        out.write(wkb)
        return out.toByteArray()
    }

    @Test
    fun readsASimplePolygon() {
        val wkb = WkbBuilder().polygon(square(-117.5, 45.5, 0.01)).build()
        val geometry = WkbGeometry.fromWkb(wkb)

        assertNotNull(geometry)
        assertEquals(1, geometry!!.polygons.size)
        assertEquals(1, geometry.polygons[0].size)
        assertEquals(-117.5, geometry.minLongitude, 1e-9)
        assertEquals(45.51, geometry.maxLatitude, 1e-9)
    }

    @Test
    fun readsAPolygonWrappedInAGeoPackageHeader() {
        val wkb = WkbBuilder().polygon(square(-117.5, 45.5, 0.01)).build()
        val geometry = WkbGeometry.fromGeoPackageBlob(geoPackageBlob(wkb))

        assertNotNull(geometry)
        assertEquals(-117.5, geometry!!.minLongitude, 1e-9)
    }

    @Test
    fun skipsTheEnvelopeWhateverSizeItIs() {
        val wkb = WkbBuilder().polygon(square(-117.5, 45.5, 0.01)).build()
        // Envelope codes carry different numbers of bytes; all must be stepped over.
        for (code in listOf(0, 1, 2, 3, 4)) {
            val geometry = WkbGeometry.fromGeoPackageBlob(geoPackageBlob(wkb, code))
            assertNotNull("envelope code $code was not skipped", geometry)
            assertEquals(-117.5, geometry!!.minLongitude, 1e-9)
        }
    }

    @Test
    fun readsBigEndianGeometry() {
        val wkb = WkbBuilder(littleEndian = false).polygon(square(-117.5, 45.5, 0.01)).build()
        val geometry = WkbGeometry.fromWkb(wkb)
        assertNotNull(geometry)
        assertEquals(-117.5, geometry!!.minLongitude, 1e-9)
    }

    @Test
    fun readsAMultiPolygonParcel() {
        // A parcel split by a road is two polygons under one record.
        val wkb = WkbBuilder().multiPolygon(
            listOf(square(-117.5, 45.5, 0.01)),
            listOf(square(-117.4, 45.5, 0.01))
        ).build()
        val geometry = WkbGeometry.fromWkb(wkb)

        assertNotNull(geometry)
        assertEquals(2, geometry!!.polygons.size)
        assertEquals(-117.5, geometry.minLongitude, 1e-9)
        assertEquals(-117.39, geometry.maxLongitude, 1e-9)
    }

    @Test
    fun aPointIsNotAParcelBoundary() {
        assertNull(WkbGeometry.fromWkb(WkbBuilder().point(-117.5, 45.5).build()))
    }

    @Test
    fun rubbishReturnsNullRatherThanThrowing() {
        assertNull(WkbGeometry.fromWkb(byteArrayOf(9, 9, 9)))
        assertNull(WkbGeometry.fromGeoPackageBlob(byteArrayOf(1, 2)))
        assertNull(WkbGeometry.fromGeoPackageBlob(ByteArray(0)))
    }

    @Test
    fun aPositionInsideTheParcelIsFound() {
        val wkb = WkbBuilder().polygon(square(-117.5, 45.5, 0.01)).build()
        val geometry = WkbGeometry.fromWkb(wkb)!!

        assertTrue(geometry.contains(45.505, -117.495))
        assertFalse(geometry.contains(45.6, -117.495))
        assertFalse(geometry.contains(45.505, -117.6))
    }

    @Test
    fun aPositionInsideAHoleIsNotOnTheParcel() {
        // A parcel with an inholding cut out of it.
        val wkb = WkbBuilder().polygon(
            square(-117.5, 45.5, 0.10),
            square(-117.46, 45.54, 0.02)
        ).build()
        val geometry = WkbGeometry.fromWkb(wkb)!!

        assertTrue("outside the hole should be on the parcel", geometry.contains(45.52, -117.48))
        assertFalse("inside the hole is not the parcel", geometry.contains(45.55, -117.45))
    }

    @Test
    fun boundsAreUsedToRejectParcelsOffScreen() {
        val geometry = WkbGeometry.fromWkb(
            WkbBuilder().polygon(square(-117.5, 45.5, 0.01)).build()
        )!!

        assertTrue(geometry.intersects(45.4, -117.6, 45.6, -117.4))
        assertFalse(geometry.intersects(46.0, -117.6, 46.5, -117.4))
        assertFalse(geometry.intersects(45.4, -118.6, 45.6, -118.4))
    }
}

class CountyCatalogTest {

    @Test
    fun parsesACatalogLine() {
        val record = CountyCatalog.parse("41063|OR|Wallowa County")
        assertNotNull(record)
        assertEquals("41063", record!!.fips)
        assertEquals("OR", record.stateCode)
        assertEquals("Wallowa County", record.countyName)
        assertEquals("Oregon", record.stateName)
        assertEquals("Wallowa County, OR", record.label)
    }

    @Test
    fun commentsAndRubbishAreSkipped() {
        assertNull(CountyCatalog.parse("# FIPS|STATE|COUNTY"))
        assertNull(CountyCatalog.parse(""))
        assertNull(CountyCatalog.parse("nonsense"))
        // A FIPS code is always five digits.
        assertNull(CountyCatalog.parse("4106|OR|Wallowa County"))
        assertNull(CountyCatalog.parse("4106X|OR|Wallowa County"))
    }

    @Test
    fun theSearchIndexCoversEveryWayOfNamingIt() {
        val record = CountyCatalog.parse("41063|OR|Wallowa County")!!
        for (query in listOf("wallowa", "or", "oregon", "41063")) {
            assertTrue("$query should match", record.searchName.contains(query))
        }
    }
}
