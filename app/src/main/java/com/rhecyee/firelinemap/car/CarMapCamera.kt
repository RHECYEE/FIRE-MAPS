package com.rhecyee.firelinemap.car

import kotlin.math.ln
import kotlin.math.max

/**
 * What the car display is currently looking at.
 *
 * Held apart from the drawing so the rules a driver actually notices -- that
 * panning drops the map out of follow, that recentring puts it back, that zoom
 * cannot run off to somewhere with no terrain cached -- are testable without a
 * head unit.
 */
class CarMapCamera {

    /** Whether the view rides with the vehicle. Panning turns this off. */
    var following: Boolean = true
        private set

    /**
     * Whether north is pinned regardless of what the driver would otherwise
     * get, because a georeferenced sheet is the map.
     *
     * A sheet has writing on it -- road names, division letters, drop point
     * numbers -- and writing only reads one way up. Turning the sheet to the
     * direction of travel makes the terrain easier to follow and the sheet
     * impossible to read, and the sheet is the reason it is on screen. So the
     * page stays square and the vehicle marker turns instead, which is how a
     * paper map on a seat has always worked.
     */
    var northLocked: Boolean = false
        private set

    /**
     * The driver's explicit choice, or null to take whatever suits the map.
     *
     * Kept apart from the answer so that opening a sheet gives north-up
     * without permanently overriding anyone who wants it the other way.
     */
    private var headingUpChoice: Boolean? = null

    /** Whether the map turns so the direction of travel is up. */
    val headingUp: Boolean get() = headingUpChoice ?: !northLocked

    var zoom: Double = DEFAULT_ZOOM
        private set

    /** Where the view sits when it is not following. Null until it is panned. */
    var browseLatitude: Double? = null
        private set
    var browseLongitude: Double? = null
        private set

    /**
     * Bearing carried between fixes.
     *
     * A stationary fix reports no bearing at all, so reading it straight off
     * the location would spin the map back to north every time the vehicle
     * stopped at a drop point. The last real heading is held instead.
     */
    private var lastBearing: Double = 0.0

    fun projection(
        vehicleLatitude: Double?,
        vehicleLongitude: Double?,
        vehicleBearing: Float?,
        vehicleMoving: Boolean,
        widthPixels: Int,
        heightPixels: Int,
        anchorX: Float,
        anchorY: Float
    ): CarMapProjection? {
        if (vehicleBearing != null && vehicleMoving) {
            lastBearing = vehicleBearing.toDouble()
        }
        val latitude = if (following) vehicleLatitude else browseLatitude ?: vehicleLatitude
        val longitude = if (following) vehicleLongitude else browseLongitude ?: vehicleLongitude
        if (latitude == null || longitude == null) return null
        return CarMapProjection(
            centerLatitude = latitude,
            centerLongitude = longitude,
            zoom = zoom,
            bearingDegrees = if (headingUp) lastBearing else 0.0,
            widthPixels = widthPixels,
            heightPixels = heightPixels,
            anchorX = anchorX,
            anchorY = anchorY
        )
    }

    /** Direction the map is currently turned to, in degrees. */
    fun bearing(): Double = if (headingUp) lastBearing else 0.0

    fun zoomIn() = zoomBy(ZOOM_STEP)

    fun zoomOut() = zoomBy(-ZOOM_STEP)

    fun zoomBy(delta: Double) {
        zoom = (zoom + delta).coerceIn(CarMapProjection.MIN_ZOOM, CarMapProjection.MAX_ZOOM)
    }

    /** Pinch, whose scale factor is a ratio rather than a zoom step. */
    fun scaleBy(factor: Float) {
        if (factor <= 0f || !factor.isFinite()) return
        zoomBy(ln(factor.toDouble()) / LN_2)
    }

    fun toggleHeadingUp() {
        headingUpChoice = !headingUp
    }

    /**
     * Says whether a sheet is the map.
     *
     * Changing it clears any override, so switching between a sheet and plain
     * terrain lands on the right default each time rather than carrying a
     * choice made about a different map.
     */
    fun setNorthLocked(locked: Boolean) {
        if (locked == northLocked) return
        northLocked = locked
        headingUpChoice = null
    }

    /**
     * Drags the map, in surface pixels.
     *
     * Panning deliberately drops follow. A map that slid back under the
     * operator's thumb a second later would be unusable for the thing panning
     * is for: looking up the road at where the vehicle is going.
     */
    fun pan(distanceX: Float, distanceY: Float, projection: CarMapProjection) {
        val target = projection.toGround(
            projection.anchorX + distanceX,
            projection.anchorY + distanceY
        )
        browseLatitude = target.latitude.coerceIn(
            -CarMapProjection.MAX_LATITUDE, CarMapProjection.MAX_LATITUDE
        )
        browseLongitude = wrapLongitude(target.longitude)
        following = false
    }

    /** Puts the view back on the vehicle. */
    fun recenter() {
        following = true
        browseLatitude = null
        browseLongitude = null
    }

    companion object {
        /** Close enough to read a road off the terrain at vehicle speed. */
        const val DEFAULT_ZOOM = 14.0

        private const val ZOOM_STEP = 1.0
        private val LN_2 = ln(2.0)

        fun wrapLongitude(longitude: Double): Double {
            var value = (longitude + 180.0) % 360.0
            if (value < 0) value += 360.0
            return value - 180.0
        }

        /** Speed below which a reported bearing is noise rather than a heading. */
        const val MOVING_METERS_PER_SECOND = 1.5f

        /** Zoom whose pixels are nearest to [metersPerPixel] at [latitude]. */
        fun zoomForResolution(latitude: Double, metersPerPixel: Double): Double {
            if (metersPerPixel <= 0.0) return CarMapProjection.MAX_ZOOM
            val equatorial = 156_543.03392 *
                kotlin.math.cos(Math.toRadians(latitude))
            return max(0.0, ln(equatorial / metersPerPixel) / LN_2)
        }
    }
}
