package com.rhecyee.firelinemap.terrain

/**
 * The vertical spacing between contour lines, and how often one is an index.
 *
 * Feet, because that is what slope, elevation and helispot minimums are all
 * quoted in on a US fire, and because a crew reading a line off a phone should
 * not have to convert anything.
 */
data class ContourInterval(
    val feet: Int,
    val indexEvery: Int = 5
) {
    /** Spacing of the heavier, labelled lines. */
    val indexFeet: Int get() = feet * indexEvery

    val meters: Double get() = feet / FEET_PER_METER

    /** Whether an elevation in feet falls on an index line. */
    fun isIndex(elevationFeet: Int): Boolean =
        indexFeet > 0 && elevationFeet % indexFeet == 0

    /** "40 ft · index 200 ft", for the key. */
    fun describe(): String = "$feet ft · index $indexFeet ft"

    companion object {
        const val FEET_PER_METER = 3.280839895
    }
}

/**
 * Choosing the interval.
 *
 * Two things decide it. Zoom sets the starting point, because a line every
 * twenty feet across a whole district is a grey smear and a line every five
 * hundred feet across one drainage is nothing at all. Relief then corrects it,
 * because the same zoom over the Wallowas and over the Columbia basin are not
 * the same map: the ladder step that reads well on a canyon wall draws no
 * lines whatever on wheat ground.
 *
 * The result is that the interval changes as the operator zooms, which is why
 * the key has to say what it currently is rather than being printed once.
 */
object ContourIntervals {

    /**
     * The intervals allowed, in feet.
     *
     * The USGS quadrangle ladder. Sticking to it means a line drawn here is a
     * line someone has seen before on a paper quad, rather than an arbitrary
     * number that happens to divide the relief.
     */
    val LADDER = listOf(10, 20, 40, 80, 100, 200, 500, 1000)

    /** Below this many lines across the view the interval is too coarse. */
    const val MIN_LINES = 4

    /** Above this many the lines stop being separable on a phone. */
    const val MAX_LINES = 26

    /** Where the ladder starts for a given tile zoom, before relief is read. */
    fun baseFeetForZoom(zoom: Int): Int = when {
        zoom <= 9 -> 500
        zoom <= 11 -> 200
        zoom == 12 -> 100
        zoom == 13 -> 80
        zoom == 14 -> 40
        else -> 20
    }

    fun forZoom(zoom: Int): ContourInterval = ContourInterval(baseFeetForZoom(zoom))

    /**
     * The interval for a view, given how much relief is actually in it.
     *
     * [reliefFeet] is the difference between the highest and lowest ground on
     * screen. Null when nothing has loaded yet, in which case zoom decides
     * alone.
     */
    fun forView(zoom: Int, reliefFeet: Double?): ContourInterval {
        val base = baseFeetForZoom(zoom)
        var index = LADDER.indexOf(base).takeIf { it >= 0 } ?: nearestRung(base)
        val relief = reliefFeet ?: return ContourInterval(LADDER[index])
        if (relief <= 0.0) return ContourInterval(LADDER[index])

        // Coarsen while the view would be a smear.
        while (index < LADDER.lastIndex && relief / LADDER[index] > MAX_LINES) index++
        // Then refine while it would be nearly empty. Never past the ladder's
        // floor: ten feet is already inside the vertical error of the national
        // elevation data, and drawing finer would be drawing its noise.
        while (index > 0 && relief / LADDER[index] < MIN_LINES) index--

        return ContourInterval(LADDER[index])
    }

    private fun nearestRung(feet: Int): Int =
        LADDER.indices.minByOrNull { kotlin.math.abs(LADDER[it] - feet) } ?: 0
}
