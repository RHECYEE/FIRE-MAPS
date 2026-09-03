package com.rhecyee.firelinemap.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The car display's geometry.
 *
 * Everything here is a thing that would put the incident map in the wrong
 * place under a driver, which is not a class of fault worth discovering on a
 * head unit halfway up a fire road.
 */
class CarMapProjectionTest {

    private fun projection(
        latitude: Double = 40.4231,
        longitude: Double = -121.5108,
        zoom: Double = 14.0,
        bearing: Double = 0.0
    ) = CarMapProjection(
        centerLatitude = latitude,
        centerLongitude = longitude,
        zoom = zoom,
        bearingDegrees = bearing,
        widthPixels = 1000,
        heightPixels = 600,
        anchorX = 500f,
        anchorY = 400f
    )

    @Test
    fun `the centre lands on the anchor`() {
        val subject = projection()
        val point = subject.toScreen(subject.centerLatitude, subject.centerLongitude)
        assertEquals(500f, point.x, 0.01f)
        assertEquals(400f, point.y, 0.01f)
    }

    @Test
    fun `north is up and east is right on a north-up map`() {
        val subject = projection()
        val north = subject.toScreen(subject.centerLatitude + 0.01, subject.centerLongitude)
        val east = subject.toScreen(subject.centerLatitude, subject.centerLongitude + 0.01)

        assertTrue("north should draw above the anchor", north.y < 400f)
        assertEquals(500f, north.x, 0.01f)
        assertTrue("east should draw right of the anchor", east.x > 500f)
        assertEquals(400f, east.y, 0.01f)
    }

    @Test
    fun `heading-up turns the direction of travel to the top of the display`() {
        val subject = projection(bearing = 90.0)
        val east = subject.toScreen(subject.centerLatitude, subject.centerLongitude + 0.01)

        assertTrue("driving east, east should be ahead", east.y < 400f)
        assertEquals(500f, east.x, 0.5f)
    }

    @Test
    fun `a screen position converts back to the ground it came from`() {
        listOf(0.0, 37.0, 90.0, 213.5).forEach { bearing ->
            val subject = projection(bearing = bearing)
            listOf(
                40.4231 to -121.5108,
                40.4402 to -121.4870,
                40.4008 to -121.5390
            ).forEach { (latitude, longitude) ->
                val screen = subject.toScreen(latitude, longitude)
                val ground = subject.toGround(screen.x, screen.y)
                assertEquals("latitude at bearing $bearing", latitude, ground.latitude, 1e-6)
                assertEquals("longitude at bearing $bearing", longitude, ground.longitude, 1e-6)
            }
        }
    }

    @Test
    fun `unrotated placement matches a rotated placement turned back`() {
        // Terrain and the map sheet are drawn into an already-rotated canvas.
        // The two paths have to agree or the sheet slides off the terrain the
        // moment the vehicle turns.
        val bearing = 55.0
        val subject = projection(bearing = bearing)
        val latitude = 40.4402
        val longitude = -121.4870

        val flat = subject.toUnrotated(latitude, longitude)
        val rotated = subject.toScreen(latitude, longitude)

        val radians = Math.toRadians(-bearing)
        val dx = flat.x - subject.anchorX
        val dy = flat.y - subject.anchorY
        val expectedX = subject.anchorX + dx * Math.cos(radians) - dy * Math.sin(radians)
        val expectedY = subject.anchorY + dx * Math.sin(radians) + dy * Math.cos(radians)

        assertEquals(expectedX.toFloat(), rotated.x, 0.01f)
        assertEquals(expectedY.toFloat(), rotated.y, 0.01f)
    }

    @Test
    fun `pixel size matches the published web mercator resolution`() {
        // 156543.03392 m/px at the equator on zoom zero is the number every
        // tile server is cut against; the scale bar is only honest if this is.
        val latitude = 40.4231
        listOf(10, 13, 16).forEach { zoom ->
            val expected = 156_543.03392 * Math.cos(Math.toRadians(latitude)) /
                Math.pow(2.0, zoom.toDouble())
            assertEquals(
                "zoom $zoom",
                expected,
                projection(latitude = latitude, zoom = zoom.toDouble()).metersPerPixel(),
                expected * 1e-9
            )
        }
    }

    @Test
    fun `the visible box covers every corner of the display`() {
        val subject = projection(bearing = 33.0)
        val (south, west, north, east) = subject.visibleBounds().toList()

        assertTrue(south < subject.centerLatitude && north > subject.centerLatitude)
        assertTrue(west < subject.centerLongitude && east > subject.centerLongitude)

        listOf(
            0f to 0f,
            1000f to 0f,
            0f to 600f,
            1000f to 600f,
            500f to 300f
        ).forEach { (x, y) ->
            val ground = subject.toGround(x, y)
            assertTrue("($x,$y) latitude", ground.latitude in south..north)
            assertTrue("($x,$y) longitude", ground.longitude in west..east)
        }
    }

    @Test
    fun `ground just across the antimeridian draws next door, not a world away`() {
        val subject = projection(latitude = 51.9, longitude = 179.99, zoom = 12.0)
        val across = subject.toScreen(51.9, -179.99)
        // Two hundredths of a degree apart, so a couple of hundred metres: it
        // has to land near the anchor rather than most of a world width off.
        assertTrue("landed at ${across.x}", Math.abs(across.x - 500f) < 60f)
    }

    @Test
    fun `latitude is clamped to what web mercator can represent`() {
        val subject = projection(latitude = 84.0, longitude = 0.0, zoom = 4.0)
        val point = subject.toScreen(89.9, 0.0)
        assertTrue("pole should be finite", point.y.isFinite())
    }

    @Test
    fun `offsets are reported in metres east and north`() {
        val (east, north) = CarMapProjection.offsetMeters(40.0, -121.0, 40.0, -120.99)
        assertEquals(0.0, north, 0.01)
        // One hundredth of a degree of longitude at 40 degrees north.
        assertEquals(852.0, east, 5.0)
    }

    private operator fun <T> List<T>.component4(): T = this[3]
}
