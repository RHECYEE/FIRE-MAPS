package com.rhecyee.firelinemap.map

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
    const val METERS_PER_DEGREE = Math.PI * RADIUS_METERS / 180.0
}
