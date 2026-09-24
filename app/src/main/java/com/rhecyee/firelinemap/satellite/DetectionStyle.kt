package com.rhecyee.firelinemap.satellite

/**
 * How a detection is drawn, decided once for both maps.
 *
 * The phone draws with Compose and the car with a bare Canvas, and they must
 * not disagree about what a colour means: somebody reads the phone in the
 * cab, hands it to the driver, and the car screen has to be saying the same
 * thing. So the colours and sizes live here as plain ARGB and pixels, and
 * each renderer only does the drawing.
 */
object DetectionStyle {

    /**
     * Warm for the confident ones, grey for the ungraded.
     *
     * Deliberately not a red/green scale. Nothing on this layer is good news,
     * and the question the colour answers is "how sure was the algorithm",
     * not "is this dangerous".
     */
    fun colour(confidence: DetectionConfidence): Int = when (confidence) {
        DetectionConfidence.HIGH -> 0xFFD32F2F.toInt()
        DetectionConfidence.NOMINAL -> 0xFFF57C00.toInt()
        DetectionConfidence.LOW -> 0xFFFBC02D.toInt()
        DetectionConfidence.UNKNOWN -> 0xFF9E9E9E.toInt()
    }

    /**
     * Whether this one has been through processing yet.
     *
     * Drawn hollow when it has not. That is the distinction between the two
     * feeds people mean when they talk about the fast one and the checked
     * one: they are the same observation at different ages, and a detection
     * that arrived within the minute will be replaced by a processed version
     * within hours -- or withdrawn, if processing does not confirm it.
     */
    fun isProvisional(level: ProcessingLevel): Boolean =
        level == ProcessingLevel.ULTRA_REAL_TIME || level == ProcessingLevel.REAL_TIME

    /**
     * The footprint at its true size on the ground, with a floor.
     *
     * A VIIRS detection is a 375 metre pixel, not a point, and drawing it as
     * a dot invites it to be read as a located thing. Zoomed out far enough
     * that the footprint is smaller than the floor it becomes a marker
     * instead, which is a compromise the caption covers by naming the
     * resolution.
     */
    fun radiusPixels(resolutionMeters: Int, metersPerPixel: Double): Float {
        if (metersPerPixel <= 0.0 || !metersPerPixel.isFinite()) return MIN_RADIUS
        val onScreen = (resolutionMeters / 2.0) / metersPerPixel
        return onScreen.toFloat().coerceIn(MIN_RADIUS, MAX_RADIUS)
    }

    /** Small enough not to smother the map, big enough to see on a dashboard. */
    private const val MIN_RADIUS = 5f

    /**
     * Capped so a deep zoom does not fill the screen with one pixel.
     *
     * Past this the circle stops growing and stops claiming to be a footprint.
     * Nothing is gained by zooming into a 375 metre sample.
     */
    private const val MAX_RADIUS = 46f
}
