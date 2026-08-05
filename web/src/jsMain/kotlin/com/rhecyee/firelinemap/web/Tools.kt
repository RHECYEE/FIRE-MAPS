package com.rhecyee.firelinemap.web

import com.rhecyee.firelinemap.measure.AreaUnit
import com.rhecyee.firelinemap.measure.DistanceUnit
import com.rhecyee.firelinemap.measure.MeasureMode
import com.rhecyee.firelinemap.measure.MeasureSession
import kotlin.js.json

/**
 * Measuring, for the browser.
 *
 * Runs the phone's own session rather than a second implementation. A distance
 * measured on a laptop and the same distance measured on a phone have to
 * agree: one of them is going into a radio call about how much line a crew has
 * left to cut, and two answers to that is worse than none.
 */
@JsExport
@JsName("FirelineTools")
object Tools {

    /**
     * Measures a run of points.
     *
     * [pointsJson] is a flat array of latitude and longitude pairs, which is
     * what the page already holds while the operator is tapping.
     */
    fun measure(pointsJson: String, area: Boolean): String {
        val flat = JSON.parse<Array<Double>>(pointsJson)
        val session = MeasureSession(if (area) MeasureMode.AREA else MeasureMode.DISTANCE)
        var index = 0
        while (index + 1 < flat.size) {
            session.add(flat[index], flat[index + 1])
            index += 2
        }

        if (!session.isMeasurable) {
            return JSON.stringify(
                json(
                    "ready" to false,
                    "points" to session.size,
                    // Says what it still needs rather than showing nothing.
                    "needs" to if (area) "Tap three points to close an area"
                    else "Tap a second point"
                )
            )
        }

        val result = session.result()
        val metres = result.totalDistanceMeters

        return JSON.stringify(
            json(
                "ready" to true,
                "points" to result.pointCount,
                // Feet up to a mile, miles past it -- the same rule the phone
                // uses, so the two never quote a road differently.
                "distance" to DistanceUnit.readable(metres),
                // Line is called in chains, always, whatever its length.
                "chains" to DistanceUnit.inChains(metres),
                "distanceMeters" to metres,
                "area" to result.areaSquareMeters?.let {
                    round(AreaUnit.ACRES.from(it), 2) + " acres"
                },
                "legs" to result.segments.map { segment ->
                    json(
                        "distance" to DistanceUnit.readable(segment.distanceMeters),
                        "chains" to DistanceUnit.inChains(segment.distanceMeters),
                        "bearing" to round(segment.bearingDegrees, 0) + "°"
                    )
                }.toTypedArray()
            )
        )
    }

    private fun round(value: Double, places: Int): String {
        var scale = 1.0
        repeat(places) { scale *= 10 }
        val scaled = kotlin.math.round(value * scale) / scale
        return if (places == 0) scaled.toInt().toString() else scaled.toString()
    }
}
