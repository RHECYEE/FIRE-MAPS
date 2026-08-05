package com.rhecyee.firelinemap.map

import kotlin.math.PI

/**
 * The single spherical earth model used for distances and offsets.
 *
 * Everything that measures or offsets ground distance shares these constants.
 * When the buffer used the equatorial degree while distances used the mean
 * radius, a requested 5 km buffer came out 5.6 km short of nothing in
 * particular -- harmless in isolation, but the kind of drift that makes two
 * correct-looking modules disagree about where the edge of a map is.
 *
 * Track distances and the measurement tool are specified to be geodesic and
 * should use an ellipsoidal calculation instead; this sphere is for coverage
 * extents and buffers, where sub-tenth-of-a-percent error is irrelevant.
 */
object Earth {
    /** IUGG mean radius. */
    const val RADIUS_METERS = 6_371_008.8

    /** Length of one degree of latitude on the sphere. */
    const val METERS_PER_DEGREE = PI * RADIUS_METERS / 180.0
}

/**
 * Degrees to radians and back.
 *
 * Written out because the Kotlin standard library has no equivalent and
 * java.lang.Math is not on every platform this code has to run on -- the same
 * geodesy serves the phone and the browser, and a second implementation of it
 * would eventually disagree about where somebody is standing.
 */
fun degreesToRadians(degrees: Double): Double = degrees * PI / 180.0

fun radiansToDegrees(radians: Double): Double = radians * 180.0 / PI
