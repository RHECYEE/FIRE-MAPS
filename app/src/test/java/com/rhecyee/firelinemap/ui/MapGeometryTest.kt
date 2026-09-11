package com.rhecyee.firelinemap.ui

import com.rhecyee.firelinemap.geopdf.GeoPdfReader
import com.rhecyee.firelinemap.geopdf.MapFrame
import com.rhecyee.firelinemap.geopdf.TerrainSheet
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
    fun `the canvas never clamps tiles below what the tile source will serve`() {
        // This is the fault that lost the contour lines, and it was not the
        // zoom ceiling: at the old ceiling the view already asked for zoom 16,
        // and drawBasemap clamped it to 15 on the way out. Contours and their
        // elevation labels are only drawn on USGS topo at 16, so the map came
        // back as shaded relief with roads on it.
        //
        // BasemapTileCache.zoomFor already stops at the deepest level The
        // National Map publishes, so the canvas clamping under that can only
        // ever throw away detail that was there for the asking.
        val fittedSpanMeters = 2 * TerrainSheet.HALF_SPAN_METERS
        val viewportPixels = 1080

        listOf(SHEET_MAX_SCALE, TERRAIN_MAX_SCALE).forEach { ceiling ->
            val wanted = BasemapTileCache.zoomFor(
                36.3838, (fittedSpanMeters / ceiling) / viewportPixels
            )
            assertTrue(
                "at a ceiling of $ceiling the view wants zoom $wanted, " +
                    "but the canvas clamps at $MAX_BASEMAP_ZOOM",
                MAX_BASEMAP_ZOOM >= wanted
            )
        }
    }

    @Test
    fun `terrain is allowed further in than a fixed sheet`() {
        // A sheet is a raster and stops resolving; terrain is re-tiled at
        // whatever zoom is asked for, so the ceiling that suits one starves
        // the other.
        assertTrue(TERRAIN_MAX_SCALE > SHEET_MAX_SCALE)
    }

    @Test
    fun `refusing a pinch at the end of the range leaves the view alone`() {
        // A ratio of one is what the gesture computes once the zoom is clamped,
        // and it must not shift anything.
        val (x, y) = zoomedOffset(-17_708f, 940f, 300f, -200f, 1f)
        assertEquals(-17_708f, x, 0f)
        assertEquals(940f, y, 0f)
    }

    // ---- terrain filling the whole view ----

    private val viewWidth = 1080f
    private val viewHeight = 1200f

    /** The two-corner box the canvas used to ask terrain for. */
    private fun twoCornerBounds(frame: MapFrame, view: View): List<Double> {
        fun geo(x: Float, y: Float) = frame.pageToGeo(
            ((x - view.originX) / view.w) * pageWidth.toDouble(),
            (1f - (y - view.originY) / view.h) * pageHeight.toDouble()
        )!!
        val topLeft = geo(0f, 0f)
        val bottomRight = geo(viewWidth, viewHeight)
        return listOf(
            minOf(topLeft.latitude, bottomRight.latitude),
            minOf(topLeft.longitude, bottomRight.longitude),
            maxOf(topLeft.latitude, bottomRight.latitude),
            maxOf(topLeft.longitude, bottomRight.longitude)
        )
    }

    private fun bounds(frame: MapFrame, view: View) = visibleGeoBounds(
        frame = frame,
        pageWidthPoints = pageWidth,
        pageHeightPoints = pageHeight,
        originX = view.originX,
        originY = view.originY,
        drawWidth = view.w,
        drawHeight = view.h,
        viewWidth = viewWidth,
        viewHeight = viewHeight
    )

    @Test
    fun `the terrain box covers every corner of the view`() {
        // The one that leaves bare strips down the map. Page space and true
        // north are not aligned, so the view is a rotated rectangle on the
        // ground and two opposite corners do not bound it.
        val frame = frame()
        for (scale in listOf(1f, 3f, 8f)) {
            val view = viewOn(frame, scale)
            val box = bounds(frame, view)
            assertNotNull("no bounds at ${scale}x", box)
            box!!

            for (x in listOf(0f, viewWidth / 2f, viewWidth)) {
                for (y in listOf(0f, viewHeight / 2f, viewHeight)) {
                    val geo = frame.pageToGeo(
                        ((x - view.originX) / view.w) * pageWidth.toDouble(),
                        (1f - (y - view.originY) / view.h) * pageHeight.toDouble()
                    )!!
                    assertTrue(
                        "($x,$y) at ${scale}x falls outside the terrain box",
                        geo.latitude in box.south..box.north &&
                            geo.longitude in box.west..box.east
                    )
                }
            }
        }
    }

    @Test
    fun `the four corner box is wider than the two corner box it replaced`() {
        // Pins the defect rather than only the fix: on this real sheet the old
        // box genuinely fell short, so a future change back to two corners
        // fails here instead of quietly stranding strips of background.
        val frame = frame()
        val view = viewOn(frame, 1f)
        val box = bounds(frame, view)!!
        val (oldSouth, oldWest, oldNorth, oldEast) = twoCornerBounds(frame, view)

        val shortfall = maxOf(
            oldSouth - box.south, box.north - oldNorth,
            oldWest - box.west, box.east - oldEast
        )
        assertTrue(
            "the old box already covered the view, so this test proves nothing",
            shortfall > 0.0
        )
        assertTrue(box.south <= oldSouth && box.north >= oldNorth)
        assertTrue(box.west <= oldWest && box.east >= oldEast)
    }

    @Test
    fun `the shortfall is a visible band, not a rounding error`() {
        // Worth measuring: if it were a metre of ground nobody would see it.
        // On this sheet it is tens of pixels, and on a sheet turned to fit a
        // fire's long axis it is a quarter of the screen.
        val frame = frame()
        val view = viewOn(frame, 1f)
        val box = bounds(frame, view)!!
        val (oldSouth, oldWest, oldNorth, oldEast) = twoCornerBounds(frame, view)

        fun pixelsX(fromLon: Double, toLon: Double, atLat: Double): Float {
            val a = frame.geoToPage(atLat, fromLon)!!
            val b = frame.geoToPage(atLat, toLon)!!
            return abs((b.first - a.first)).toFloat() / pageWidth * view.w
        }
        fun pixelsY(fromLat: Double, toLat: Double, atLon: Double): Float {
            val a = frame.geoToPage(fromLat, atLon)!!
            val b = frame.geoToPage(toLat, atLon)!!
            return abs((b.second - a.second)).toFloat() / pageHeight * view.h
        }

        val middleLat = (box.north + box.south) / 2
        val middleLon = (box.west + box.east) / 2
        val widest = maxOf(
            pixelsX(box.west, oldWest, middleLat),
            pixelsX(oldEast, box.east, middleLat),
            pixelsY(oldNorth, box.north, middleLon),
            pixelsY(box.south, oldSouth, middleLon)
        )
        assertTrue("bare band was only $widest px", widest > 10f)
    }

    @Test
    fun `a view with no size asks for no terrain`() {
        val frame = frame()
        val view = viewOn(frame, 1f)
        assertEquals(null, bounds(frame, View(view.originX, view.originY, 0f, view.h)))
        assertEquals(null, bounds(frame, View(view.originX, view.originY, view.w, 0f)))
    }

    // ---- covering a wide view rather than giving up on it ----

    @Test
    fun `a wide view gets coarser terrain rather than none`() {
        // The old guard returned early past two hundred tiles, which left the
        // operator looking at background colour with nothing to tell that from
        // the app being broken.
        val wide = com.rhecyee.firelinemap.map.GeoBounds(
            south = 32.0, west = -124.0, north = 42.0, east = -114.0
        )
        val zoom = basemapZoom(wide, targetMetersPerPixel = 2.0)
        assertNotNull("a wide view came back with no terrain at all", zoom)
        assertTrue(
            "the chosen zoom still needs more tiles than a frame can draw",
            com.rhecyee.firelinemap.map.TileMath.tileCount(wide, zoom!!) <= MAX_TILES_PER_FRAME
        )
    }

    @Test
    fun `a close view still gets the detail it asked for`() {
        // Stepping down must only happen when it has to.
        val close = com.rhecyee.firelinemap.map.GeoBounds(
            south = 35.995, west = -106.070, north = 36.002, east = -106.060
        )
        val zoom = basemapZoom(close, targetMetersPerPixel = 2.0)
        assertEquals(MAX_BASEMAP_ZOOM, zoom)
    }

    @Test
    fun `the chosen zoom never exceeds what the tile service serves`() {
        val close = com.rhecyee.firelinemap.map.GeoBounds(
            south = 35.999, west = -106.065, north = 36.000, east = -106.063
        )
        assertEquals(MAX_BASEMAP_ZOOM, basemapZoom(close, targetMetersPerPixel = 0.01))
    }

    @Test
    fun `stepping down stops at the coarsest zoom worth drawing`() {
        val whole = com.rhecyee.firelinemap.map.GeoBounds(
            south = -80.0, west = -179.0, north = 80.0, east = 179.0
        )
        val zoom = basemapZoom(whole, targetMetersPerPixel = 1.0)
        assertTrue(zoom == null || zoom >= MIN_BASEMAP_ZOOM)
    }
}
