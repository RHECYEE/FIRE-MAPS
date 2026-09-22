package com.rhecyee.firelinemap.terrain

import com.rhecyee.firelinemap.map.Earth
import com.rhecyee.firelinemap.map.ElevationGrid
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * How steep the ground is, in the bands it gets talked about in.
 *
 * Percent rather than degrees throughout, because cut slope, dozer limits and
 * engine limits are all quoted that way on a fire, and a crew reading a map
 * should not have to convert to compare a figure against what their equipment
 * will do.
 *
 * The boundaries are the conventional five-band split used in fire behaviour
 * work. They are a description of the ground, not a rule about it: what a
 * machine or a crew can actually hold depends on soil, fuel, aspect, weather
 * and who is driving, and none of that is in a terrain model.
 */
enum class SlopeClass(
    val label: String,
    val lowerPercent: Int,
    /** Null on the open-ended top band. */
    val upperPercent: Int?,
    /** Note on what the band tends to mean for getting equipment onto it. */
    val note: String,
    val colorArgb: Int
) {
    /**
     * Left untinted on purpose.
     *
     * Most of most maps is gentle, and colouring it would put a wash over the
     * whole sheet to say nothing. The tint starting at all is the signal.
     */
    GENTLE("Gentle", 0, 25, "Most equipment travels", 0x00000000),

    MODERATE("Moderate", 25, 40, "Dozer work gets directional", 0x66FFF176),

    STEEP("Steep", 40, 55, "Past most dozer limits", 0x73FFB300),

    VERY_STEEP("Very steep", 55, 75, "Handline and hose lays only", 0x80F4511E.toInt()),

    /**
     * Where a fire stops behaving like it does on the rest of the map.
     *
     * Chutes and chimneys live up here, and so does most of the reason to
     * look at a slope layer at all.
     */
    EXTREME("Extreme", 75, null, "Chutes and chimneys", 0x8CB71C1C.toInt());

    /** "40-55%", or "75%+" on the top band. */
    val range: String
        get() = if (upperPercent == null) "$lowerPercent%+" else "$lowerPercent-$upperPercent%"

    companion object {
        fun of(slopePercent: Double): SlopeClass {
            if (slopePercent.isNaN()) return GENTLE
            return entries.lastOrNull { slopePercent >= it.lowerPercent } ?: GENTLE
        }

        /** Bands that actually put colour on the map, for the key. */
        val TINTED: List<SlopeClass> get() = entries.filter { it.colorArgb ushr 24 != 0 }
    }
}

/** What to draw, and how hard. */
data class ShadingOptions(
    val hillshade: Boolean,
    val slopeClasses: Boolean,
    /**
     * Where the light comes from, compass degrees.
     *
     * Northwest by convention. Lighting terrain from the southeast inverts
     * how people read it -- ridges sink and valleys rise -- and the illusion
     * is strong enough to survive being told about, so this is not offered as
     * a free choice.
     */
    val sunAzimuthDegrees: Double = 315.0,
    val sunAltitudeDegrees: Double = 45.0,
    /**
     * Vertical exaggeration for the shading only.
     *
     * Slope classes are never exaggerated -- a number a crew might act on has
     * to be the real one. This steepens the relief for the eye alone.
     */
    val reliefExaggeration: Double = 1.6
)

/** A shaded raster covering exactly the ground its grid covered. */
class ShadedRelief(
    /** ARGB_8888, row-major, north row first. */
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val north: Double,
    val south: Double,
    val west: Double,
    val east: Double
)

/**
 * Turns elevation into something you can see the shape of the ground in.
 *
 * Contour lines carry the same information and are the more precise reading,
 * but they have to be counted and interpolated between, and a crew boss
 * glancing at a phone in a vehicle is not counting lines. Shading answers
 * "how steep is that, and which way does it face" in the time it takes to
 * look, which is the only time available.
 *
 * Two layers come out of one pass because they want compositing together
 * rather than stacking: the slope tint says how steep, the relief says what
 * shape, and a tint over a separately drawn relief loses the relief under it.
 */
object TerrainShading {

    /**
     * Samples across a shaded window.
     *
     * Coarser than the elevation data underneath, which reaches about four
     * metres a sample, and finer than the contour trace at 256 -- a soft
     * gradient tolerates being stretched, but the edge of a slope band is a
     * line somebody reads a number off, and mush there is worse than none.
     * The ceiling is cost: this is a per-pixel pass over the square of it.
     */
    const val SAMPLES = 384

    /** How dark the deepest shadow goes. Relief, not night. */
    private const val SHADOW_STRENGTH = 0.55f

    /** And how much the sunward faces are lifted. */
    private const val HIGHLIGHT_STRENGTH = 0.16f

    /**
     * Shades a grid, or returns null when there is not enough of it.
     *
     * [ElevationGrid.coverage] below this means most of the window is still
     * being fetched, and shading the fraction that has landed would draw a
     * hard edge across the map where the data stops -- which reads as a
     * feature of the ground rather than of the download.
     */
    fun render(
        grid: ElevationGrid,
        options: ShadingOptions,
        minimumCoverage: Float = 0.6f
    ): ShadedRelief? {
        if (!options.hillshade && !options.slopeClasses) return null
        if (grid.width < 2 || grid.height < 2) return null
        if (grid.coverage < minimumCoverage) return null

        val centreLatitude = (grid.north + grid.south) / 2.0
        val metersPerDegreeEast =
            Earth.METERS_PER_DEGREE * cos(Math.toRadians(centreLatitude))
        val cellNorth = (grid.north - grid.south) / (grid.height - 1) * Earth.METERS_PER_DEGREE
        val cellEast = (grid.east - grid.west) / (grid.width - 1) * metersPerDegreeEast
        if (cellNorth <= 0.0 || cellEast <= 0.0) return null

        val zenith = Math.toRadians(90.0 - options.sunAltitudeDegrees)
        val cosZenith = cos(zenith)
        val sinZenith = sin(zenith)

        val pixels = IntArray(grid.width * grid.height)
        val gradients = DoubleArray(2)

        for (row in 0 until grid.height) {
            for (column in 0 until grid.width) {
                val index = row * grid.width + column
                val known = TerrainMath.gradientsAt(
                    values = grid.values,
                    width = grid.width,
                    height = grid.height,
                    column = column,
                    row = row,
                    cellEastMeters = cellEast,
                    cellNorthMeters = cellNorth,
                    out = gradients
                )
                if (!known) {
                    // Unknown ground is left clear rather than shaded flat,
                    // which would claim it had been measured and was level.
                    pixels[index] = 0
                    continue
                }

                val rise = hypot(gradients[0], gradients[1])
                val slopePercent = rise * 100.0

                var red = 0f
                var green = 0f
                var blue = 0f
                var alpha = 0f

                if (options.slopeClasses) {
                    val colour = SlopeClass.of(slopePercent).colorArgb
                    alpha = ((colour ushr 24) and 0xFF) / 255f
                    red = ((colour ushr 16) and 0xFF) / 255f
                    green = ((colour ushr 8) and 0xFF) / 255f
                    blue = (colour and 0xFF) / 255f
                }

                if (options.hillshade) {
                    val slope = atan(rise * options.reliefExaggeration)
                    val illumination = if (rise < 1e-9) {
                        cosZenith
                    } else {
                        val aspect = TerrainMath.aspectFromGradients(gradients[0], gradients[1])
                        val difference =
                            Math.toRadians(options.sunAzimuthDegrees - aspect)
                        cosZenith * cos(slope) + sinZenith * sin(slope) * cos(difference)
                    }.coerceIn(-1.0, 1.0)

                    // Below the flat-ground level is shadow, above it is a
                    // face turned into the light. Shadow is drawn much harder
                    // than highlight: an eye reads terrain from its shadows,
                    // and a map that is mostly brightened just looks washed.
                    val relative = ((illumination - cosZenith) / (1.0 - cosZenith + 1e-9))
                        .coerceIn(-1.0, 1.0)
                    val (shadeRed, shadeGreen, shadeBlue, shadeAlpha) = if (relative < 0) {
                        val strength = (-relative).toFloat() * SHADOW_STRENGTH
                        Shade(0.04f, 0.05f, 0.06f, strength)
                    } else {
                        val strength = relative.toFloat() * HIGHLIGHT_STRENGTH
                        Shade(1f, 0.99f, 0.92f, strength)
                    }

                    // The tint goes over the relief, so the relief still reads
                    // through a coloured band instead of being painted out.
                    val outAlpha = alpha + shadeAlpha * (1f - alpha)
                    if (outAlpha > 1e-4f) {
                        val keep = shadeAlpha * (1f - alpha)
                        red = (red * alpha + shadeRed * keep) / outAlpha
                        green = (green * alpha + shadeGreen * keep) / outAlpha
                        blue = (blue * alpha + shadeBlue * keep) / outAlpha
                    }
                    alpha = outAlpha
                }

                pixels[index] = argb(alpha, red, green, blue)
            }
        }

        return ShadedRelief(
            pixels = pixels,
            width = grid.width,
            height = grid.height,
            north = grid.north,
            south = grid.south,
            west = grid.west,
            east = grid.east
        )
    }

    /** Slope in percent at one sample, for a readout rather than a picture. */
    fun slopePercentAt(grid: ElevationGrid, column: Int, row: Int): Double? {
        val centreLatitude = (grid.north + grid.south) / 2.0
        val metersPerDegreeEast =
            Earth.METERS_PER_DEGREE * cos(Math.toRadians(centreLatitude))
        val cellNorth = (grid.north - grid.south) / (grid.height - 1) * Earth.METERS_PER_DEGREE
        val cellEast = (grid.east - grid.west) / (grid.width - 1) * metersPerDegreeEast
        val gradients = DoubleArray(2)
        val known = TerrainMath.gradientsAt(
            grid.values, grid.width, grid.height, column, row, cellEast, cellNorth, gradients
        )
        if (!known) return null
        return hypot(gradients[0], gradients[1]) * 100.0
    }

    private data class Shade(val red: Float, val green: Float, val blue: Float, val alpha: Float)

    private fun argb(alpha: Float, red: Float, green: Float, blue: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        if (a == 0) return 0
        val r = (red.coerceIn(0f, 1f) * 255f).roundToInt()
        val g = (green.coerceIn(0f, 1f) * 255f).roundToInt()
        val b = (blue.coerceIn(0f, 1f) * 255f).roundToInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
