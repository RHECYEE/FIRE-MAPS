package com.rhecyee.firelinemap.web

import com.rhecyee.firelinemap.measure.AreaUnit
import com.rhecyee.firelinemap.measure.DistanceUnit
import com.rhecyee.firelinemap.measure.MeasureMode
import com.rhecyee.firelinemap.measure.MeasureSession
import com.rhecyee.firelinemap.medical.MedicalReport
import com.rhecyee.firelinemap.medical.MedicalResource
import com.rhecyee.firelinemap.medical.Priority
import com.rhecyee.firelinemap.medical.RadioReadout
import com.rhecyee.firelinemap.medical.ReportFormat
import com.rhecyee.firelinemap.medical.TransportMode
import kotlin.js.Json
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

    /**
     * Builds a 206 and reads it back in radio order.
     *
     * The order is the whole point of the form: whoever is copying writes the
     * same things in the same sequence every time, and a version that reorders
     * them costs the person copying more than it saves. So the browser calls
     * the phone's own readout rather than composing its own.
     */
    fun medicalPlan(fieldsJson: String): String {
        val raw = JSON.parse<Json>(fieldsJson)
        fun text(key: String): String? =
            (raw[key] as? String)?.takeIf { it.isNotBlank() }
        fun number(key: String): Double? = (raw[key] as? Number)?.toDouble()

        val report = MedicalReport(
            id = "web",
            incidentId = "web",
            createdAt = (raw["createdAt"] as? Number)?.toLong() ?: 0L,
            incidentName = text("incidentName") ?: "Incident",
            mapName = null,
            latitude = number("latitude") ?: 0.0,
            longitude = number("longitude") ?: 0.0,
            elevationMeters = number("elevationMeters"),
            accuracyMeters = number("accuracyMeters")?.toFloat(),
            reporterName = text("reporterName"),
            reporterQualification = text("reporterQualification"),
            priority = Priority.entries.firstOrNull { it.name == raw["priority"] }
                ?: Priority.RED,
            patientCount = (raw["patientCount"] as? Number)?.toInt() ?: 1,
            transport = TransportMode.entries.firstOrNull { it.name == raw["transport"] }
                ?: TransportMode.GROUND,
            resources = (raw["resources"] as? Array<*>).orEmpty()
                .mapNotNull { name -> MedicalResource.entries.firstOrNull { it.name == name } }
                .toSet(),
            natureOfInjury = text("natureOfInjury"),
            patientAssessment = text("patientAssessment"),
            lzHazards = text("lzHazards"),
            notes = text("notes"),
            airPickupName = text("airPickupName"),
            airPickupLatitude = number("airPickupLatitude"),
            airPickupLongitude = number("airPickupLongitude"),
            hasPosition = number("latitude") != null,
            format = ReportFormat.entries.firstOrNull { it.name == raw["format"] }
                ?: ReportFormat.MIR
        )

        return JSON.stringify(
            json(
                "lines" to RadioReadout.lines(report).map { line ->
                    json(
                        "number" to line.number,
                        "heading" to line.heading,
                        "body" to line.body
                    )
                }.toTypedArray(),
                "script" to RadioReadout.script(report),
                "spoken" to RadioReadout.spoken(report),
                // What would leave a gap on the radio, so the form can say so
                // before somebody keys the mic rather than after.
                "missing" to report.missing.toTypedArray(),
                "ready" to report.isReadyToTransmit,
                "needsAir" to report.transport.needsAir,
                "radioName" to report.radioName
            )
        )
    }

    /** The choices the phone offers, so the two forms cannot drift apart. */
    fun medicalChoices(): String = JSON.stringify(
        json(
            "priority" to Priority.entries.map {
                json("id" to it.name, "label" to it.label)
            }.toTypedArray(),
            "transport" to TransportMode.entries.map {
                json("id" to it.name, "label" to it.label, "needsAir" to it.needsAir)
            }.toTypedArray(),
            "resources" to MedicalResource.entries.map {
                json("id" to it.name, "label" to it.label)
            }.toTypedArray()
        )
    )
}
