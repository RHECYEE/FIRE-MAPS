package com.rhecyee.firelinemap.web

import com.rhecyee.firelinemap.geopdf.GeoPdfParse
import com.rhecyee.firelinemap.geopdf.MapFrame
import kotlin.js.json

/**
 * Product sheets, read in the browser.
 *
 * An incident's operations map is a georeferenced PDF, and until now the
 * browser could not open one -- which meant the page could show where somebody
 * was but not on the map their briefing came from. That is most of the job.
 *
 * The browser inflates the PDF's compressed object streams (a platform job,
 * done with the browser's own decompression) and hands the text here. Finding
 * the geospatial viewports and turning them into a transform is the phone's
 * own code, unchanged, because a sheet that lines up on one and is a few
 * hundred metres out on the other is worse than no sheet at all.
 */
@JsExport
@JsName("FirelineSheets")
object Sheets {

    /** Where each compressed object stream starts, for the caller to inflate. */
    fun objectStreamOffsets(latin1: String): Array<Int> =
        GeoPdfParse.objectStreamOffsets(latin1).toTypedArray()

    /**
     * Reads the georeferencing out of an assembled sheet.
     *
     * Reports every frame, and says which is the main map: a sheet routinely
     * carries detail insets, and drawing a position against an inset places it
     * by the wrong transform entirely.
     */
    fun read(text: String): String {
        val document = GeoPdfParse.readText(text)
        val primary = document.primaryFrame

        return JSON.stringify(
            json(
                "georeferenced" to document.isGeoreferenced,
                "frameCount" to document.frames.size,
                "insetCount" to document.insetFrames.size,
                "primary" to primary?.let { describe(it) },
                // Said plainly rather than implied by a blank map: a product
                // with no georeferencing is still readable, but a position
                // drawn on it would mean nothing.
                "why" to if (document.isGeoreferenced) null
                else "No geospatial viewport in this PDF. It can be read, but a " +
                    "position cannot be placed on it."
            )
        )
    }

    private fun describe(frame: MapFrame): dynamic {
        val corners = frame.geoCorners
        return json(
            "name" to frame.name,
            "left" to frame.box.left,
            "bottom" to frame.box.bottom,
            "right" to frame.box.right,
            "top" to frame.box.top,
            "usesProjection" to frame.usesProjection,
            "north" to corners.maxOf { it.latitude },
            "south" to corners.minOf { it.latitude },
            "west" to corners.minOf { it.longitude },
            "east" to corners.maxOf { it.longitude }
        )
    }

    /**
     * A sheet's transform, held open for as long as it is on screen.
     *
     * Kept as an object rather than a call per point because the page converts
     * every pin, every track vertex and the live position on every frame, and
     * rebuilding the affine for each of those would be the slowest thing on
     * the screen.
     */
    fun open(text: String): SheetFrame? =
        GeoPdfParse.readText(text).primaryFrame?.let { SheetFrame(it) }
}

/** One sheet's page-to-ground transform. */
@JsExport
class SheetFrame internal constructor(private val frame: MapFrame) {

    val name: String? get() = frame.name
    val usesProjection: Boolean get() = frame.usesProjection

    val left: Double get() = frame.box.left
    val bottom: Double get() = frame.box.bottom
    val right: Double get() = frame.box.right
    val top: Double get() = frame.box.top

    val north: Double get() = frame.geoCorners.maxOf { it.latitude }
    val south: Double get() = frame.geoCorners.minOf { it.latitude }
    val west: Double get() = frame.geoCorners.minOf { it.longitude }
    val east: Double get() = frame.geoCorners.maxOf { it.longitude }

    /**
     * Where a position falls on the page, as a fraction of the page box.
     *
     * Returns null outside the frame rather than a number off the edge, so the
     * page can draw an off-sheet marker instead of a pin somewhere it is not.
     */
    fun pageX(latitude: Double, longitude: Double): Double =
        frame.geoToPage(latitude, longitude)?.first ?: Double.NaN

    fun pageY(latitude: Double, longitude: Double): Double =
        frame.geoToPage(latitude, longitude)?.second ?: Double.NaN

    /** The ground under a page point. */
    fun latitudeAt(pageX: Double, pageY: Double): Double =
        frame.pageToGeo(pageX, pageY)?.latitude ?: Double.NaN

    fun longitudeAt(pageX: Double, pageY: Double): Double =
        frame.pageToGeo(pageX, pageY)?.longitude ?: Double.NaN
}
