package com.rhecyee.firelinemap.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules a driver notices: what the map follows, which way it faces, how far out it sits. */
class CarMapCameraTest {

    private fun CarMapCamera.view(
        latitude: Double? = 40.4231,
        longitude: Double? = -121.5108,
        bearing: Float? = null,
        moving: Boolean = false
    ) = projection(
        vehicleLatitude = latitude,
        vehicleLongitude = longitude,
        vehicleBearing = bearing,
        vehicleMoving = moving,
        widthPixels = 1000,
        heightPixels = 600,
        anchorX = 500f,
        anchorY = 400f
    )

    @Test
    fun `there is no view before there is a fix`() {
        assertEquals(null, CarMapCamera().view(latitude = null, longitude = null))
    }

    @Test
    fun `the view follows the vehicle until it is panned`() {
        val camera = CarMapCamera()
        assertTrue(camera.following)

        val before = camera.view()!!
        camera.pan(200f, 0f, before)

        assertFalse("panning should drop follow", camera.following)
        assertNotNull(camera.browseLatitude)

        // The vehicle has moved on; the view stays where it was put.
        val after = camera.view(latitude = 40.5000, longitude = -121.4000)!!
        assertEquals(camera.browseLatitude!!, after.centerLatitude, 1e-9)
        assertEquals(camera.browseLongitude!!, after.centerLongitude, 1e-9)
    }

    @Test
    fun `panning east moves the view east`() {
        val camera = CarMapCamera()
        val before = camera.view()!!
        camera.pan(200f, 0f, before)
        assertTrue(
            "expected ${camera.browseLongitude} east of ${before.centerLongitude}",
            camera.browseLongitude!! > before.centerLongitude
        )
    }

    @Test
    fun `recentring puts the view back on the vehicle`() {
        val camera = CarMapCamera()
        camera.pan(200f, 120f, camera.view()!!)
        camera.recenter()

        assertTrue(camera.following)
        assertEquals(null, camera.browseLatitude)
        val view = camera.view(latitude = 40.5000, longitude = -121.4000)!!
        assertEquals(40.5000, view.centerLatitude, 1e-9)
        assertEquals(-121.4000, view.centerLongitude, 1e-9)
    }

    @Test
    fun `zoom stays within the range terrain is cached for`() {
        val camera = CarMapCamera()
        repeat(30) { camera.zoomIn() }
        assertEquals(CarMapProjection.MAX_ZOOM, camera.zoom, 1e-9)
        repeat(60) { camera.zoomOut() }
        assertEquals(CarMapProjection.MIN_ZOOM, camera.zoom, 1e-9)
    }

    @Test
    fun `a pinch to double size is one zoom level in`() {
        val camera = CarMapCamera()
        val before = camera.zoom
        camera.scaleBy(2f)
        assertEquals(before + 1.0, camera.zoom, 1e-9)
        camera.scaleBy(0.5f)
        assertEquals(before, camera.zoom, 1e-9)
    }

    @Test
    fun `a nonsense scale factor is ignored rather than destroying the view`() {
        val camera = CarMapCamera()
        val before = camera.zoom
        camera.scaleBy(0f)
        camera.scaleBy(-1f)
        camera.scaleBy(Float.NaN)
        assertEquals(before, camera.zoom, 1e-9)
    }

    @Test
    fun `the map holds its heading when the vehicle stops`() {
        val camera = CarMapCamera()
        camera.view(bearing = 270f, moving = true)
        assertEquals(270.0, camera.bearing(), 1e-9)

        // Parked at a drop point: the fix still reports a bearing, but it is
        // noise. Taking it would spin the map under someone reading it.
        camera.view(bearing = 12f, moving = false)
        assertEquals(270.0, camera.bearing(), 1e-9)
    }

    @Test
    fun `north-up ignores the heading entirely`() {
        val camera = CarMapCamera()
        camera.view(bearing = 270f, moving = true)
        camera.toggleHeadingUp()

        assertFalse(camera.headingUp)
        assertEquals(0.0, camera.bearing(), 1e-9)
        assertEquals(0.0, camera.view()!!.bearingDegrees, 1e-9)

        // And turning it back restores the heading rather than losing it.
        camera.toggleHeadingUp()
        assertEquals(270.0, camera.bearing(), 1e-9)
    }

    @Test
    fun `longitude wraps rather than running off the end of the world`() {
        assertEquals(-179.0, CarMapCamera.wrapLongitude(181.0), 1e-9)
        assertEquals(179.0, CarMapCamera.wrapLongitude(-181.0), 1e-9)
        assertEquals(-121.5, CarMapCamera.wrapLongitude(-121.5), 1e-9)
    }

    @Test
    fun `panning across the antimeridian stays a real longitude`() {
        val camera = CarMapCamera()
        val view = camera.view(latitude = 51.9, longitude = 179.99)!!
        camera.pan(4000f, 0f, view)
        assertTrue(
            "wrapped to ${camera.browseLongitude}",
            camera.browseLongitude!! in -180.0..180.0
        )
    }

    // ---- north up on a sheet ----

    @Test
    fun `a sheet on screen keeps north up`() {
        val camera = CarMapCamera()
        assertTrue("terrain should still turn with the vehicle", camera.headingUp)

        camera.setNorthLocked(true)

        assertFalse("a sheet was turned to the heading", camera.headingUp)
        assertEquals(0.0, camera.bearing(), 0.0)
    }

    @Test
    fun `the map really is drawn square when a sheet is open`() {
        val camera = CarMapCamera()
        camera.setNorthLocked(true)
        // A fix well off north, moving fast enough for the bearing to count.
        val projection = camera.projection(
            vehicleLatitude = 38.9,
            vehicleLongitude = -120.1,
            vehicleBearing = 137f,
            vehicleMoving = true,
            widthPixels = 800,
            heightPixels = 480,
            anchorX = 400f,
            anchorY = 240f
        )!!
        assertEquals(0.0, projection.bearingDegrees, 0.0)
    }

    @Test
    fun `putting the sheet away lets the map turn again`() {
        val camera = CarMapCamera()
        camera.setNorthLocked(true)
        camera.setNorthLocked(false)
        assertTrue(camera.headingUp)
    }

    @Test
    fun `the driver can still override north up while a sheet is open`() {
        val camera = CarMapCamera()
        camera.setNorthLocked(true)
        camera.toggleHeadingUp()
        assertTrue("the override was ignored", camera.headingUp)
    }

    @Test
    fun `an override does not follow a driver onto a different map`() {
        // Turning the sheet to the heading is a decision about that sheet.
        // Closing it and coming back should not carry the decision over.
        val camera = CarMapCamera()
        camera.setNorthLocked(true)
        camera.toggleHeadingUp()
        assertTrue(camera.headingUp)

        camera.setNorthLocked(false)
        camera.setNorthLocked(true)

        assertFalse("a stale override survived the map change", camera.headingUp)
    }

    @Test
    fun `saying nothing changed changes nothing`() {
        val camera = CarMapCamera()
        camera.setNorthLocked(true)
        camera.toggleHeadingUp()
        // The sheet is reloaded, reporting the same thing it reported before.
        camera.setNorthLocked(true)
        assertTrue("a redundant reload threw away the driver's choice", camera.headingUp)
    }

    @Test
    fun `terrain still turns to the heading by default`() {
        val camera = CarMapCamera()
        camera.setNorthLocked(false)
        camera.projection(
            vehicleLatitude = 38.9,
            vehicleLongitude = -120.1,
            vehicleBearing = 137f,
            vehicleMoving = true,
            widthPixels = 800,
            heightPixels = 480,
            anchorX = 400f,
            anchorY = 240f
        )
        assertEquals(137.0, camera.bearing(), 1e-9)
    }
}
