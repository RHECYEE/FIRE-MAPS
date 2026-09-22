package com.rhecyee.firelinemap.geopdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A window of the sheet, rasterised at the resolution it is being looked at.
 *
 * The rectangle is in PDF points with the origin at the top-left of the page
 * and y running downward, which is the space [PdfRenderer] draws in. Page
 * space elsewhere in this app runs upward from the bottom-left, following the
 * PDF specification, so the two are converted at this boundary and nowhere
 * else.
 */
data class SheetDetail(
    val bitmap: Bitmap,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double
) {
    val widthPoints get() = right - left
    val heightPoints get() = bottom - top
}

/**
 * Rasterises a map sheet at whatever resolution it is currently being viewed.
 *
 * Incident products are published at Arch E: 3456 by 2592 points, which is 48
 * by 36 inches. Rendering one of those whole into a 2048-pixel bitmap, which
 * is what this used to do, works out at 43 DPI. Zoomed in as far as the canvas
 * allowed, the screen was asking for around 270 DPI and getting a six-fold
 * upscale of that thumbnail instead. That is the entire reason imported sheets
 * went to mush the moment anyone looked closely at one, and why they read
 * worse than the same products do in every other viewer. The detail was in the
 * file the whole time.
 *
 * Rendering the page higher is not the answer on its own: the same sheet at
 * 300 DPI is 14400 by 10800 pixels, which is 622 MB of ARGB_8888 and an
 * immediate kill on any phone. So there are two rasters. The overview is the
 * whole page, cheap, always present, and what a gesture is drawn against while
 * it is in flight. Over it sits a render of just the visible window at true
 * screen resolution, refreshed once the gesture settles. Memory is bounded by
 * the size of the screen rather than the size of the sheet.
 *
 * One page is open at a time and every call is serialised, because
 * [PdfRenderer] permits neither concurrent use nor two open pages. The lock
 * is a plain one rather than a coroutine mutex so that [close] can take it
 * too: a sheet being swapped out while a render is still in flight on it has
 * to wait for that render, not free the descriptor underneath it. Every
 * method here blocks, and callers run them on an IO dispatcher.
 */
class MapSheetRenderer(private val file: File) : Closeable {

    private val lock = ReentrantLock()
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var released = false

    /** Page size in PDF points, or null when the file will not open. */
    fun pageSize(): Pair<Int, Int>? = lock.withLock {
        onPage { page -> page.width to page.height }
    }

    /**
     * The whole page at a bounded width.
     *
     * Deliberately modest. It exists to be instantly available at any pan or
     * zoom, not to be read from.
     */
    fun renderOverview(targetWidth: Int = OVERVIEW_WIDTH): Bitmap? = lock.withLock {
        onPage { page ->
            val width = targetWidth.coerceIn(256, OVERVIEW_WIDTH)
            val height = (page.height.toDouble() * width / page.width).roundToInt()
                .coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    /**
     * One window of the page at the pixel size it occupies on screen.
     *
     * [left], [top], [right] and [bottom] are in PDF points measured downward
     * from the top-left corner. The output is clamped to [MAX_DETAIL_PIXELS];
     * beyond that the window is rendered slightly softer rather than the
     * allocation being attempted and the process being killed for it.
     */
    fun renderWindow(
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
        outWidth: Int,
        outHeight: Int
    ): SheetDetail? {
        val windowWidth = right - left
        val windowHeight = bottom - top
        if (windowWidth <= 0.0 || windowHeight <= 0.0) return null
        if (outWidth <= 0 || outHeight <= 0) return null

        // Shrink both axes together so the window keeps its shape; letting one
        // axis shrink alone would stretch the render against the page.
        var width = outWidth
        var height = outHeight
        val pixels = width.toLong() * height.toLong()
        if (pixels > MAX_DETAIL_PIXELS) {
            val shrink = sqrt(MAX_DETAIL_PIXELS.toDouble() / pixels.toDouble())
            width = (width * shrink).roundToInt().coerceAtLeast(1)
            height = (height * shrink).roundToInt().coerceAtLeast(1)
        }

        return lock.withLock {
            onPage { page ->
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                // Nothing clears the bitmap for us, and a sheet's margin is
                // white rather than transparent.
                bitmap.eraseColor(Color.WHITE)
                val transform = Matrix().apply {
                    setScale(
                        (width / windowWidth).toFloat(),
                        (height / windowHeight).toFloat()
                    )
                    postTranslate(
                        (-left * width / windowWidth).toFloat(),
                        (-top * height / windowHeight).toFloat()
                    )
                }
                page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                SheetDetail(bitmap, left, top, right, bottom)
            }
        }
    }

    /**
     * Runs [block] against page one, opening the document if needed.
     *
     * A file that will not parse returns null once and keeps returning null,
     * rather than reopening a broken descriptor on every pan.
     */
    private inline fun <T> onPage(block: (PdfRenderer.Page) -> T): T? = runCatching {
        if (released) return null
        val active = renderer ?: run {
            val opened = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val created = PdfRenderer(opened)
            if (created.pageCount == 0) {
                created.close()
                opened.close()
                released = true
                return null
            }
            descriptor = opened
            renderer = created
            created
        }
        active.openPage(0).use { page -> block(page) }
    }.getOrNull()

    override fun close() = lock.withLock {
        released = true
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
        renderer = null
        descriptor = null
    }

    companion object {
        /**
         * Width of the whole-page overview.
         *
         * 2048 across an Arch E sheet is 43 DPI, which is unreadable on its
         * own and is meant to be: it is the backdrop the detail render sits
         * on, and it costs 12 MB where a legible full-page render would cost
         * hundreds.
         */
        const val OVERVIEW_WIDTH = 2048

        /**
         * Ceiling on one detail render, about 16 MB at four bytes a pixel.
         *
         * Comfortably larger than any phone screen and most tablet screens,
         * so in practice the window renders one-to-one with the display.
         */
        const val MAX_DETAIL_PIXELS = 4_200_000L
    }
}
