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
 * How closely spaced the operator wants the lines.
 *
 * A preference rather than a fact. The default aims for what reads cleanly on
 * a phone at arm's length in daylight; someone reading slope on a cut, or
 * working ground that is nearly flat, wants more line than that, and someone
 * planning a division at a glance wants less. The multiplier applies to how
 * many lines a view may carry, so it changes the answer without leaving the
 * quadrangle ladder.
 */
enum class ContourDetail(val label: String, val lineAllowance: Double) {
    COARSE("Coarse", 0.5),
    NORMAL("Normal", 1.0),
    FINE("Fine", 2.0),
    FINEST("Finest", 3.5);

    companion object {
        fun fromName(name: String?): ContourDetail =
            entries.firstOrNull { it.name == name } ?: NORMAL
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
    fun forView(
        zoom: Int,
        reliefFeet: Double?,
        detail: ContourDetail = ContourDetail.NORMAL
    ): ContourInterval {
        val base = baseFeetForZoom(zoom)
        var index = LADDER.indexOf(base).takeIf { it >= 0 } ?: nearestRung(base)

        // Detail shifts the starting rung as well as the allowance. Without
        // that, close in over steep ground the relief rule does all the work
        // and the setting has no effect where it is most wanted -- which is
        // exactly the case that prompted it: a hundred feet is not enough to
        // read a cut by.
        index = (index - detail.rungShift()).coerceIn(0, LADDER.lastIndex)

        val relief = reliefFeet ?: return ContourInterval(LADDER[index])
        if (relief <= 0.0) return ContourInterval(LADDER[index])

        val ceiling = MAX_LINES * detail.lineAllowance
        val floor = MIN_LINES * detail.lineAllowance

        // Coarsen while the view would be a smear.
        while (index < LADDER.lastIndex && relief / LADDER[index] > ceiling) index++
        // Then refine while it would be nearly empty. Never past the ladder's
        // floor: ten feet is already inside the vertical error of the national
        // elevation data, and drawing finer would be drawing its noise.
        while (index > 0 && relief / LADDER[index] < floor) index--

        return ContourInterval(LADDER[index])
    }

    /** How many rungs finer than the default a detail setting starts. */
    private fun ContourDetail.rungShift(): Int = when (this) {
        ContourDetail.COARSE -> -1
        ContourDetail.NORMAL -> 0
        ContourDetail.FINE -> 1
        ContourDetail.FINEST -> 2
    }

    private fun nearestRung(feet: Int): Int =
        LADDER.indices.minByOrNull { kotlin.math.abs(LADDER[it] - feet) } ?: 0
}
