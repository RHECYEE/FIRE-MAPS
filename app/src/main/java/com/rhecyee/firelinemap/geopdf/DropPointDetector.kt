package com.rhecyee.firelinemap.geopdf

import kotlin.math.abs

/** A symbol found on the sheet, resolved to ground coordinates. */
data class DropPoint(
    val latitude: Double,
    val longitude: Double,
    val pageX: Double,
    val pageY: Double,
    val heightPixels: Int,
    val widthPixels: Int
) {
    /** Stable identifier derived from position, so repeat detections match up. */
    val id: String
        get() = "dp_${"%.5f".format(latitude)}_${"%.5f".format(longitude)}"
}

/**
 * Tuning for symbol detection.
 *
 * Exposed rather than fixed because symbology is not consistent between
 * products or between the shops that draw them. The defaults were measured
 * from real Burnt Creek sheets, where drop points are drawn in pure blue at
 * roughly sixteen pixels when the page is rendered at 150 dpi.
 */
data class DropPointSettings(
    val targetRed: Int = 0,
    val targetGreen: Int = 0,
    val targetBlue: Int = 255,
    /** Per-channel slack. Anti-aliased edges drift a long way from the nominal. */
    val tolerance: Int = 70,
    /**
     * Symbol height in pixels.
     *
     * Height rather than overall size, because a drop point is a labelled
     * plate whose width grows with the number inside it -- "5" and "190" are
     * the same badge at very different widths -- while its height stays put.
     */
    val minHeightPixels: Int = 8,
    val maxHeightPixels: Int = 40,
    /** Width over height. Allows a one to three digit label. */
    val minAspectRatio: Double = 0.7,
    val maxAspectRatio: Double = 3.4,
    /**
     * Fraction of the bounding box that must be filled.
     *
     * A drop point is a solid blue plate with the number knocked out in white,
     * so it is mostly but not entirely filled. The upper bound rejects plain
     * blocks of colour with nothing knocked out of them.
     */
    val minimumFillRatio: Double = 0.45,
    val maximumFillRatio: Double = 0.97
)

/**
 * Finds drop-point symbols on a rendered map sheet by colour.
 *
 * Restricted to the inside of the map frame's neatline. Without that the
 * legend swatches and title block dominate the results -- on the Burnt Creek
 * transportation sheet the only perfectly solid blue square on the page is the
 * legend's own drop-point key, which is exactly the thing not to return.
 *
 * This is best-effort image matching, not a data source. It reads what was
 * drawn, so it will miss symbols the cartographer styled differently and will
 * occasionally pick up something blue that is not a drop point. Callers are
 * expected to present the result as provisional.
 */
object DropPointDetector {

    /**
     * @param pixels ARGB pixels of the rendered page, row-major.
     * @param frame the map frame whose neatline bounds the search.
     */
    fun detect(
        pixels: IntArray,
        width: Int,
        height: Int,
        frame: MapFrame,
        pageWidthPoints: Double,
        pageHeightPoints: Double,
        settings: DropPointSettings = DropPointSettings()
    ): List<DropPoint> {
        if (width <= 0 || height <= 0 || pageWidthPoints <= 0 || pageHeightPoints <= 0) {
            return emptyList()
        }
        if (pixels.size < width * height) return emptyList()

        val scaleX = width / pageWidthPoints
        val scaleY = height / pageHeightPoints

        // The neatline in bitmap coordinates. PDF y runs upward, bitmap down.
        val left = (frame.box.left * scaleX).toInt().coerceIn(0, width - 1)
        val right = (frame.box.right * scaleX).toInt().coerceIn(0, width - 1)
        val top = (height - frame.box.top * scaleY).toInt().coerceIn(0, height - 1)
        val bottom = (height - frame.box.bottom * scaleY).toInt().coerceIn(0, height - 1)
        if (right <= left || bottom <= top) return emptyList()

        val visited = BooleanArray(width * height)
        val results = mutableListOf<DropPoint>()
        val stack = ArrayDeque<Int>()

        for (y in top..bottom) {
            for (x in left..right) {
                val index = y * width + x
                if (visited[index] || !matches(pixels[index], settings)) continue

                var minX = x; var maxX = x; var minY = y; var maxY = y; var count = 0
                stack.addLast(index)
                visited[index] = true

                while (stack.isNotEmpty()) {
                    val current = stack.removeLast()
                    val cx = current % width
                    val cy = current / width
                    count++
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val nx = cx + dx
                            val ny = cy + dy
                            if (nx < left || nx > right || ny < top || ny > bottom) continue
                            val neighbour = ny * width + nx
                            if (visited[neighbour] || !matches(pixels[neighbour], settings)) continue
                            visited[neighbour] = true
                            stack.addLast(neighbour)
                        }
                    }
                }

                val boxWidth = maxX - minX + 1
                val boxHeight = maxY - minY + 1
                if (!isSymbol(boxWidth, boxHeight, count, settings)) continue

                val centreX = (minX + maxX) / 2.0
                val centreY = (minY + maxY) / 2.0
                val pageX = centreX / scaleX
                val pageY = (height - centreY) / scaleY
                val geo = frame.pageToGeo(pageX, pageY) ?: continue

                results += DropPoint(
                    latitude = geo.latitude,
                    longitude = geo.longitude,
                    pageX = pageX,
                    pageY = pageY,
                    heightPixels = boxHeight,
                    widthPixels = boxWidth
                )
            }
        }
        return results
    }

    private fun isSymbol(
        boxWidth: Int,
        boxHeight: Int,
        filled: Int,
        settings: DropPointSettings
    ): Boolean {
        if (boxHeight < settings.minHeightPixels || boxHeight > settings.maxHeightPixels) {
            return false
        }
        val aspect = boxWidth.toDouble() / boxHeight
        if (aspect < settings.minAspectRatio || aspect > settings.maxAspectRatio) return false
        val ratio = filled.toDouble() / (boxWidth * boxHeight)
        return ratio >= settings.minimumFillRatio && ratio <= settings.maximumFillRatio
    }

    private fun matches(argb: Int, settings: DropPointSettings): Boolean {
        // Fully transparent pixels carry no colour worth testing.
        if ((argb ushr 24 and 0xFF) < 128) return false
        val red = argb shr 16 and 0xFF
        val green = argb shr 8 and 0xFF
        val blue = argb and 0xFF
        return abs(red - settings.targetRed) <= settings.tolerance &&
            abs(green - settings.targetGreen) <= settings.tolerance &&
            abs(blue - settings.targetBlue) <= settings.tolerance
    }
}
