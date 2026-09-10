package com.rhecyee.firelinemap.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarMarkerLabelsTest {

    private fun request(
        id: String,
        x: Float = 0f,
        y: Float = 0f,
        title: String = "E-3341",
        type: String = "Engine",
        priority: Int = 0,
    ) = MarkerLabelRequest(id, x, y, title, type, priority)

    // ---- what a caption says ----

    @Test
    fun `a pin is named and its kind stated`() {
        assertEquals(listOf("E-3341", "Engine"), CarMarkerLabels.lines("E-3341", "Engine"))
    }

    @Test
    fun `a name that already says the kind does not say it twice`() {
        assertEquals(listOf("Engine"), CarMarkerLabels.lines("Engine", "Engine"))
        assertEquals(listOf("engine"), CarMarkerLabels.lines("engine", "Engine"))
        assertEquals(listOf("Engine 41"), CarMarkerLabels.lines("Engine 41", "Engine"))
    }

    @Test
    fun `an unnamed pin still says what it is`() {
        assertEquals(listOf("Drop point"), CarMarkerLabels.lines("", "Drop point"))
        assertEquals(listOf("Drop point"), CarMarkerLabels.lines("   ", "Drop point"))
    }

    @Test
    fun `a pin with nothing to say says nothing`() {
        assertEquals(emptyList<String>(), CarMarkerLabels.lines("", ""))
    }

    @Test
    fun `a long name is clipped visibly rather than silently`() {
        val long = "Sawtooth Interagency Hotshot Crew Squad Two"
        val lines = CarMarkerLabels.lines(long, "Hotshots")
        assertTrue(lines.first().length <= CarMarkerLabels.MAX_TITLE)
        assertTrue("a clipped name must read as clipped", lines.first().endsWith("…"))
    }

    @Test
    fun `a name that just fits is left alone`() {
        val exact = "A".repeat(CarMarkerLabels.MAX_TITLE)
        assertEquals(exact, CarMarkerLabels.lines(exact, "Engine").first())
    }

    // ---- which pins get one ----

    @Test
    fun `pins spread across the screen all get named`() {
        val requests = (0 until 5).map { request("m$it", x = it * 300f, y = 100f) }
        val placed = CarMarkerLabels.place(requests, horizontalSpacing = 140f, verticalSpacing = 40f)
        assertEquals(5, placed.size)
    }

    @Test
    fun `pins stacked on a drop point do not overwrite each other`() {
        val requests = (0 until 6).map { request("m$it", x = 400f, y = 240f + it) }
        val placed = CarMarkerLabels.place(requests, horizontalSpacing = 140f, verticalSpacing = 40f)
        assertEquals("captions were drawn on top of one another", 1, placed.size)
    }

    @Test
    fun `the most important pin in a crowd is the one named`() {
        val requests = listOf(
            request("a", x = 400f, y = 240f, title = "Engine 12", priority = 0),
            request("b", x = 405f, y = 243f, title = "Medic 1", priority = 9),
            request("c", x = 402f, y = 238f, title = "Water tender", priority = 3),
        )
        val placed = CarMarkerLabels.place(requests, horizontalSpacing = 140f, verticalSpacing = 40f)
        assertEquals(1, placed.size)
        assertEquals("b", placed.single().id)
    }

    @Test
    fun `the same pins keep their captions frame after frame`() {
        // The database returns rows in whatever order it likes; the captions
        // must not swap between names four times a second because of it.
        val requests = listOf(
            request("a", x = 400f, y = 240f),
            request("b", x = 405f, y = 243f),
            request("c", x = 402f, y = 238f),
        )
        val first = CarMarkerLabels.place(requests, 140f, 40f)
        val second = CarMarkerLabels.place(requests.reversed(), 140f, 40f)
        val third = CarMarkerLabels.place(requests.shuffled(), 140f, 40f)
        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals(first.map { it.id }, third.map { it.id })
    }

    @Test
    fun `a screenful of pins does not become a screenful of text`() {
        val requests = (0 until 200).map { request("m$it", x = it * 37f % 1200f, y = it * 53f % 700f) }
        val placed = CarMarkerLabels.place(requests, horizontalSpacing = 10f, verticalSpacing = 10f)
        assertTrue(placed.size <= CarMarkerLabels.MAX_LABELS)
    }

    @Test
    fun `a pin off the edge of the world is skipped rather than drawn`() {
        val requests = listOf(
            request("a", x = Float.NaN, y = 100f),
            request("b", x = 100f, y = Float.POSITIVE_INFINITY),
            request("c", x = 100f, y = 100f),
        )
        val placed = CarMarkerLabels.place(requests, 140f, 40f)
        assertEquals(listOf("c"), placed.map { it.id })
    }

    @Test
    fun `no pins means no captions`() {
        assertTrue(CarMarkerLabels.place(emptyList(), 140f, 40f).isEmpty())
    }

    @Test
    fun `asking for no captions draws none`() {
        val requests = (0 until 5).map { request("m$it", x = it * 300f) }
        assertTrue(CarMarkerLabels.place(requests, 140f, 40f, limit = 0).isEmpty())
    }

    @Test
    fun `pins near each other horizontally but far apart vertically both fit`() {
        val requests = listOf(
            request("a", x = 400f, y = 100f),
            request("b", x = 402f, y = 400f),
        )
        val placed = CarMarkerLabels.place(requests, horizontalSpacing = 140f, verticalSpacing = 40f)
        assertEquals(2, placed.size)
    }
}
