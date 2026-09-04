package com.rhecyee.firelinemap.ui

import com.rhecyee.firelinemap.geopdf.GeoPdfReader
import com.rhecyee.firelinemap.geopdf.MapFrame
import com.rhecyee.firelinemap.map.BasemapTileCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The map canvas a long way off the sheet.
 *
 * Both faults covered here were invisible on the sheet and obvious off it,
 * which is the worst way round: whoever hits them is in a vehicle somewhere
 * with the wrong map, and whoever could fix them never sees it. Run against the
 * real Burnt Creek product rather than an invented frame, because what goes
 * wrong is the projection the product is actually drawn in.
 */
class MapGeometryTest {

    private val fixture = File("src/test/resources/geopdf/transportation_burntcreek_20260728.pdf")
    private val pageWidth = 612
    private val pageHeight = 792

    // Far off the sheet, roughly where the reported screenshot was taken.
    private val latitude = 35.99823
    private val longitude = -106.06415

    private fun frame(): MapFrame {
        assertTrue("fixture missing", fixture.exists())
        return GeoPdfReader.read(fixture).primaryFrame!!
    }

    private class View(val originX: Float, val originY: Float, val w: Float, val h: Float)

    /** The view centred on the operator, the way "centre on me" leaves it. */
    private fun viewOn(frame: MapFrame, scale: Float): View {
        val page = frame.geoToPage(latitude, longitude)!!
        val imageWidth = 2048f
        val imageHeight = 2048f * pageHeight / pageWidth
        val fit = minOf(1080f / imageWidth, 1200f / imageHeight)
        val drawWidth = imageWidth * fit * scale
        val drawHeight = imageHeight * fit * scale
        val fx = (page.first / pageWidth).toFloat()
        val fy = 1f - (page.second / pageHeight).toFloat()
        return View(
            originX = (1080f - drawWidth) / 2f + drawWidth * (0.5f - fx),
            originY = (1200f - drawHeight) / 2f + drawHeight * (0.5f - fy),
            w = drawWidth,
            h = drawHeight
        )
    }

    private fun quad(frame: MapFrame, view: View, zoom: Int, x: Int, y: Int) = tileQuad(
        frame = frame,
        north = BasemapTileCache.tileNorth(y, zoom),
        south = BasemapTileCache.tileNorth(y + 1, zoom),
        west = BasemapTileCache.tileWest(x, zoom),
        east = BasemapTileCache.tileWest(x + 1, zoom),
        pageWidthPoints = pageWidth,
        pageHeightPoints = pageHeight,
        originX = view.originX,
        originY = view.originY,
        drawWidth = view.w,
        drawHeight = view.h
    )

    @Test
    fun `a tile is not a rectangle on a projected sheet`() {
        // The premise of the bug. A sheet drawn in UTM is rotated against true
        // north, so a square of latitude and longitude lands on the page as a
        // rotated quad. Fitting that into a rectangle through two opposite
        // corners is what left wedges of background between neighbours.
        val frame = frame()
        val view = viewOn(frame, 1f)
        val zoom = 12
        val corners = quad(
            frame, view, zoom,
            BasemapTileCache.tileX(longitude, zoom),
            BasemapTileCache.tileY(latitude, zoom)
        )
        assertNotNull(corners)

        val topEdgeDrop = abs(corners!![3] - corners[1])
        val leftEdgeLean = abs(corners[6] - corners[0])
        assertTrue(
            "expected a visibly rotated tile, got drop=$topEdgeDrop lean=$leftEdgeLean",
            topEdgeDrop > 5f && leftEdgeLean > 5f
        )
    }

    @Test
    fun `neighbouring tiles share their edges exactly`() {
        // Which is what lets the quads tile the plane with nothing showing
        // through, however rotated the frame leaves them.
        val frame = frame()
        val view = viewOn(frame, 1f)
        val zoom = 12
        val x = BasemapTileCache.tileX(longitude, zoom)
        val y = BasemapTileCache.tileY(latitude, zoom)

        val here = quad(frame, view, zoom, x, y)!!
        val below = quad(frame, view, zoom, x, y + 1)!!
        val right = quad(frame, view, zoom, x + 1, y)!!

        // Corners run NW, NE, SE, SW.
        assertEquals("SW meets NW below", here[6], below[0], 0f)
        assertEquals("SW meets NW below", here[7], below[1], 0f)
        assertEquals("SE meets NE below", here[4], below[2], 0f)
        assertEquals("SE meets NE below", here[5], below[3], 0f)

        assertEquals("NE meets NW right", here[2], right[0], 0f)
        assertEquals("NE meets NW right", here[3], right[1], 0f)
        assertEquals("SE meets SW right", here[4], right[6], 0f)
        assertEquals("SE meets SW right", here[5], right[7], 0f)
    }

    @Test
    fun `growing a quad pushes every corner outward`() {
        val original = floatArrayOf(10f, 10f, 110f, 6f, 114f, 106f, 14f, 110f)
        val grown = growQuad(original, byPixels = 1f)
        val centreX = (original[0] + original[2] + original[4] + original[6]) / 4f
        val centreY = (original[1] + original[3] + original[5] + original[7]) / 4f
        for (corner in 0 until 4) {
            val before = hypot(
                original[corner * 2] - centreX, original[corner * 2 + 1] - centreY
            )
            val after = hypot(
                grown[corner * 2] - centreX, grown[corner * 2 + 1] - centreY
            )
            assertEquals("corner $corner", before + 1f, after, 0.01f)
        }
    }

    @Test
    fun `a collapsed quad does not blow up when grown`() {
        growQuad(FloatArray(8) { 42f }).forEach { assertTrue(it.isFinite()) }
    }

    @Test
    fun `zooming holds the ground under the pinch still`() {
        // The teleport. Content sits at centre + offset + unit * scale, so
        // changing scale alone moves it by its own distance from the centre
        // times the change -- which off the sheet is tens of page widths.
        listOf(-17_708f, 0f, 250f).forEach { offset ->
            listOf(0.5f, 1.02f, 4f).forEach { ratio ->
                val focus = 120f
                val scale = 3f
                val unit = (focus - offset) / scale
                val (zoomedX, _) = zoomedOffset(offset, offset, focus, focus, ratio)
                val after = zoomedX + unit * (scale * ratio)
                assertEquals(
                    "offset=$offset ratio=$ratio",
                    focus.toDouble(), after.toDouble(), 0.05
                )
            }
        }
    }

    @Test
    fun `a pinch off the sheet no longer throws the map sideways`() {
        val frame = frame()
        val page = frame.geoToPage(latitude, longitude)!!
        val fx = (page.first / pageWidth).toFloat()
        assertTrue("the fixture should put the operator well off the page", abs(fx) > 5f)

        val ratio = 1.02f
        val before = viewOn(frame, 1f)
        val operatorBefore = before.originX + fx * before.w

        val focus = operatorBefore - 1080f / 2f
        val offsetBefore = before.originX - (1080f - before.w) / 2f
        val (offsetAfter, _) = zoomedOffset(offsetBefore, 0f, focus, 0f, ratio)
        val widthAfter = before.w * ratio
        val operatorAfter = (1080f - widthAfter) / 2f + offsetAfter + fx * widthAfter

        assertEquals(
            "the ground under the pinch must not move",
            operatorBefore.toDouble(), operatorAfter.toDouble(), 1.0
        )
    }

    @Test
    fun `refusing a pinch at the end of the range leaves the view alone`() {
        // A ratio of one is what the gesture computes once the zoom is clamped,
        // and it must not shift anything.
        val (x, y) = zoomedOffset(-17_708f, 940f, 300f, -200f, 1f)
        assertEquals(-17_708f, x, 0f)
        assertEquals(940f, y, 0f)
    }
}
