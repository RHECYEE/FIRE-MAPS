package com.rhecyee.firelinemap.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.up
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rhecyee.firelinemap.data.MarkerEntity
import com.rhecyee.firelinemap.geopdf.GeoPdfDocument
import com.rhecyee.firelinemap.geopdf.GeoPoint
import com.rhecyee.firelinemap.geopdf.ImportedMap
import com.rhecyee.firelinemap.geopdf.MapFrame
import com.rhecyee.firelinemap.geopdf.PageBox
import com.rhecyee.firelinemap.geopdf.PdfKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Gesture regressions on the map surface.
 *
 * These exist because every gesture fault in this app so far reached the
 * field: three competing pointer handlers that swallowed taps, detectors
 * restarting on every recomposition, and taps outside the sheet being
 * discarded. None of that is reachable from a JVM test, and all of it is
 * reachable from here.
 */
@RunWith(AndroidJUnit4::class)
class MapCanvasGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private val pageWidth = 612
    private val pageHeight = 792

    /** A frame spanning 45.0-45.5 N, 118.0-117.0 W over the whole page. */
    private val frame = MapFrame(
        name = "test",
        box = PageBox(0.0, 0.0, pageWidth.toDouble(), pageHeight.toDouble()),
        geoCorners = listOf(
            GeoPoint(45.0, -118.0),
            GeoPoint(45.5, -118.0),
            GeoPoint(45.5, -117.0),
            GeoPoint(45.0, -117.0)
        ),
        localCorners = listOf(0.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0, 1.0 to 0.0),
        wkt = null
    )

    private val map = ImportedMap(
        id = "test-map",
        displayName = "test.pdf",
        file = File("/dev/null"),
        document = GeoPdfDocument(listOf(frame), PdfKind.GEOREFERENCED)
    )

    private fun bitmap(): Bitmap =
        Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)

    private fun marker(id: String, latitude: Double, longitude: Double) = MarkerEntity(
        id = id,
        incidentId = "incident",
        category = "EQUIPMENT",
        symbol = "engine",
        title = "E-621",
        latitude = latitude,
        longitude = longitude,
        createdAt = 0L,
        updatedAt = 0L
    )

    @Test
    fun tappingTheMapReportsAPosition() {
        var tapped: Pair<Double, Double>? = null
        compose.setContent {
            MapCanvas(
                map = map,
                bitmap = bitmap(),
                pageWidthPoints = pageWidth,
                pageHeightPoints = pageHeight,
                latitude = null,
                longitude = null,
                onMapTap = { latitude, longitude -> tapped = latitude to longitude },
                modifier = Modifier.fillMaxSize()
            )
        }

        compose.onRoot().performTouchInput { click(center) }
        compose.waitForIdle()

        assertNotNull("a tap in the middle of the map reported nothing", tapped)
        // The centre of this frame is the middle of its geographic span.
        assertEquals(45.25, tapped!!.first, 0.1)
        assertEquals(-117.5, tapped!!.second, 0.2)
    }

    @Test
    fun repeatedTapsAllRegister() {
        val taps = mutableListOf<Pair<Double, Double>>()
        compose.setContent {
            MapCanvas(
                map = map,
                bitmap = bitmap(),
                pageWidthPoints = pageWidth,
                pageHeightPoints = pageHeight,
                latitude = null,
                longitude = null,
                onMapTap = { latitude, longitude -> taps += latitude to longitude },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Measuring a road means tap after tap after tap; dropping any of them
        // is the difference between a working tool and a dead one.
        repeat(5) {
            compose.onRoot().performTouchInput { click(center) }
            compose.waitForIdle()
        }
        assertEquals(5, taps.size)
    }

    @Test
    fun tapsNearTheEdgeOfTheViewStillRegister() {
        var tapped: Pair<Double, Double>? = null
        compose.setContent {
            MapCanvas(
                map = map,
                bitmap = bitmap(),
                pageWidthPoints = pageWidth,
                pageHeightPoints = pageHeight,
                latitude = null,
                longitude = null,
                onMapTap = { latitude, longitude -> tapped = latitude to longitude },
                modifier = Modifier.fillMaxSize()
            )
        }

        // The sheet is letterboxed inside the view, so this lands beyond it.
        // Ground off the neatline is where ICP and the drive in are.
        compose.onRoot().performTouchInput { click(Offset(8f, height / 2f)) }
        compose.waitForIdle()

        assertNotNull("a tap off the sheet was discarded", tapped)
    }

    @Test
    fun tappingAPinReportsThePinAndNotTheGround() {
        var tappedMarker: MarkerEntity? = null
        var tappedGround: Pair<Double, Double>? = null
        compose.setContent {
            MapCanvas(
                map = map,
                bitmap = bitmap(),
                pageWidthPoints = pageWidth,
                pageHeightPoints = pageHeight,
                latitude = null,
                longitude = null,
                markers = listOf(marker("m1", 45.25, -117.5)),
                onMarkerTap = { tappedMarker = it },
                onMarkerMoved = { _, _, _ -> },
                onMapTap = { latitude, longitude -> tappedGround = latitude to longitude },
                modifier = Modifier.fillMaxSize()
            )
        }

        compose.onRoot().performTouchInput { click(center) }
        compose.waitForIdle()

        assertNotNull("the pin under the tap was not reported", tappedMarker)
        assertEquals("m1", tappedMarker!!.id)
        assertNull("the tap also fell through to the ground", tappedGround)
    }

    @Test
    fun draggingAPinReportsANewPositionAndNotATap() {
        var movedTo: Pair<Double, Double>? = null
        var tappedMarker: MarkerEntity? = null
        compose.setContent {
            MapCanvas(
                map = map,
                bitmap = bitmap(),
                pageWidthPoints = pageWidth,
                pageHeightPoints = pageHeight,
                latitude = null,
                longitude = null,
                markers = listOf(marker("m1", 45.25, -117.5)),
                onMarkerTap = { tappedMarker = it },
                onMarkerMoved = { _, latitude, longitude -> movedTo = latitude to longitude },
                modifier = Modifier.fillMaxSize()
            )
        }

        compose.onRoot().performTouchInput {
            down(center)
            moveTo(center + Offset(0f, -120f))
            up()
        }
        compose.waitForIdle()

        assertNotNull("dragging a pin reported no new position", movedTo)
        assertNull("a drag was also treated as a tap", tappedMarker)
        // Dragged up the screen, so northward.
        assertTrue("expected to move north, got ${movedTo!!.first}", movedTo!!.first > 45.25)
    }

    @Test
    fun aTapWithNoMapLoadedDoesNotCrash() {
        var tapped = false
        compose.setContent {
            MapCanvas(
                map = null,
                bitmap = null,
                pageWidthPoints = 0,
                pageHeightPoints = 0,
                latitude = null,
                longitude = null,
                onMapTap = { _, _ -> tapped = true },
                modifier = Modifier.fillMaxSize()
            )
        }

        compose.onRoot().performTouchInput { click(center) }
        compose.waitForIdle()
        assertTrue("nothing to tap, and nothing should have fired", !tapped)
    }
}
