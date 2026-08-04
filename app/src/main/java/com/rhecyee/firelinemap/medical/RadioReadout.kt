package com.rhecyee.firelinemap.medical

import com.rhecyee.firelinemap.util.CoordinateFormat
import com.rhecyee.firelinemap.util.CoordinateFormatter

/** One numbered block of a readout, so it can be read a line at a time. */
data class ReadoutLine(val number: Int, val heading: String, val body: String)

/**
 * Turns a report into something that can be read straight off the screen.
 *
 * Written to be spoken, not printed. Under stress the sequence is the thing
 * people lose, so the readout keeps the numbered order of the form and puts
 * the words in the order they are said -- there is nothing to compose and
 * nothing to remember.
 *
 * Coordinates go out in degrees and decimal minutes. That is what aircraft
 * work in, and the air ambulance is the caller most likely to be writing it
 * down at speed.
 *
 * The field content follows the structure supplied by the crew this is built
 * for. The exact wording of the current Medical Incident Report should be
 * checked against the IRPG before this is relied on in place of the card.
 */
object RadioReadout {

    const val STANDBY = "Communications, stand by for emergency traffic."

    fun lines(report: MedicalReport): List<ReadoutLine> = when (report.format) {
        ReportFormat.EIGHT_LINE -> eightLine(report)
        ReportFormat.MIR -> medicalIncidentReport(report)
    }

    /** The whole thing as one block, for reading aloud or copying out. */
    fun script(report: MedicalReport): String = buildString {
        appendLine(STANDBY)
        appendLine()
        lines(report).forEach { line ->
            appendLine("${line.number}. ${line.heading}")
            appendLine("   ${line.body}")
        }
    }.trimEnd()

    /**
     * A single spoken paragraph, for when there is no time for headings.
     *
     * This is the version that gets read while walking.
     */
    fun spoken(report: MedicalReport): String = buildString {
        append(STANDBY)
        append(" ")
        append(report.priority.spoken)
        append(", ")
        append(patients(report))
        report.natureOfInjury?.takeIf { it.isNotBlank() }?.let { append(" $it") }
        append(". ")
        append("${report.radioName} Medical. ")
        report.incidentCommander?.takeIf { it.isNotBlank() }?.let { append("IC $it. ") }
        report.medicalProvider?.takeIf { it.isNotBlank() }?.let { append("Patient care $it. ") }
        append("Pickup at ${coordinates(report)}. ")
        append("Request ${report.transport.spoken}")
        if (report.resources.isNotEmpty()) {
            append(" with ${spokenList(report.resources.map { it.spoken })}")
        }
        append(". ")
        // Not capitalised: it follows a colon, where a capital reads oddly.
        report.lzHazards?.takeIf { it.isNotBlank() }?.let {
            append("LZ hazards: ${it.trim().trimEnd('.')}. ")
        }
        report.patientAssessment?.takeIf { it.isNotBlank() }?.let { append("${sentence(it)}. ") }
    }.trim()

    private fun eightLine(report: MedicalReport): List<ReadoutLine> = listOf(
        ReadoutLine(1, "Contact Communications", STANDBY),
        ReadoutLine(
            2, "Incident Status",
            buildString {
                append(patients(report).replaceFirstChar { it.uppercase() })
                report.natureOfInjury?.takeIf { it.isNotBlank() }?.let { append(", $it") }
                append(". ${report.radioName} Medical.")
                report.incidentCommander?.takeIf { it.isNotBlank() }?.let { append(" IC $it.") }
                report.medicalProvider?.takeIf { it.isNotBlank() }?.let {
                    append(" Medical provider $it.")
                }
                append(" Pickup ${coordinates(report)}.")
            }
        ),
        ReadoutLine(
            3, "Initial Patient Assessment",
            buildString {
                append(report.priority.spoken)
                report.patientAssessment?.takeIf { it.isNotBlank() }?.let {
                    append(". ${sentence(it)}")
                }
                append(".")
            }
        ),
        ReadoutLine(
            4, "Transport Request",
            buildString {
                append("Request ${report.transport.spoken}.")
                report.groundContact?.takeIf { it.isNotBlank() }?.let {
                    append(" Ground contact $it.")
                }
                append(" LZ ${coordinates(report)}.")
                append(
                    report.lzHazards?.takeIf { it.isNotBlank() }
                        ?.let { " LZ hazards: $it." }
                        ?: " LZ hazards: none reported."
                )
            }
        ),
        ReadoutLine(
            5, "Additional Resources Needed",
            if (report.resources.isEmpty()) "None."
            else report.resources.joinToString(", ") { it.label } + "."
        ),
        ReadoutLine(6, "Documentation", documentation(report)),
        ReadoutLine(
            7, "Updates",
            if (report.updates.isEmpty()) "None yet."
            else report.updates.joinToString(" ") { sentence(it.text) + "." }
        ),
        ReadoutLine(
            8, "Patient Transport / Incident Changes",
            report.notes?.takeIf { it.isNotBlank() } ?: "No changes reported."
        )
    )

    private fun medicalIncidentReport(report: MedicalReport): List<ReadoutLine> = listOf(
        ReadoutLine(1, "Contact Communications", STANDBY),
        ReadoutLine(
            2, "Incident Status",
            buildString {
                append("${report.radioName} Medical. ")
                append(patients(report).replaceFirstChar { it.uppercase() })
                report.natureOfInjury?.takeIf { it.isNotBlank() }?.let { append(", $it") }
                append(".")
                report.incidentCommander?.takeIf { it.isNotBlank() }?.let { append(" IC $it.") }
                report.medicalProvider?.takeIf { it.isNotBlank() }?.let {
                    append(" Medical provider $it.")
                }
            }
        ),
        ReadoutLine(
            3, "Patient Assessment",
            buildString {
                append(report.priority.spoken)
                report.patientAssessment?.takeIf { it.isNotBlank() }?.let {
                    append(". ${sentence(it)}")
                }
                append(".")
            }
        ),
        ReadoutLine(
            4, "Transport Plan",
            buildString {
                append("Request ${report.transport.spoken}. ")
                append("Pickup ${coordinates(report)}.")
                report.groundContact?.takeIf { it.isNotBlank() }?.let {
                    append(" Ground contact $it.")
                }
                append(
                    report.lzHazards?.takeIf { it.isNotBlank() }
                        ?.let { " LZ hazards: $it." }
                        ?: " LZ hazards: none reported."
                )
            }
        ),
        ReadoutLine(
            5, "Additional Resources",
            if (report.resources.isEmpty()) "None."
            else report.resources.joinToString(", ") { it.label } + "."
        ),
        ReadoutLine(6, "Documentation", documentation(report)),
        ReadoutLine(
            7, "Updates",
            if (report.updates.isEmpty()) "None yet."
            else report.updates.joinToString(" ") { sentence(it.text) + "." }
        ),
        ReadoutLine(
            8, "Transport / Changes",
            report.notes?.takeIf { it.isNotBlank() } ?: "No changes reported."
        )
    )

    private fun documentation(report: MedicalReport): String = buildString {
        report.reporterName?.takeIf { it.isNotBlank() }?.let {
            append("Reported by $it")
            report.reporterQualification?.takeIf { q -> q.isNotBlank() }?.let { q ->
                append(", $q")
            }
            append(". ")
        }
        report.mapName?.takeIf { it.isNotBlank() }?.let { append("Map $it. ") }
        report.accuracyMeters?.let { append("GPS accurate to ${it.toInt()} m. ") }
        if (report.photoCount > 0) {
            append("${report.photoCount} photo${if (report.photoCount == 1) "" else "s"}. ")
        }
        if (report.trackId != null) append("Travel track attached. ")
        if (isEmpty()) append("Recorded in Fireline Map.")
    }.trim()

    /**
     * Dictated text, capitalised for the position it lands in.
     *
     * Speech recognition returns lower case, and a sentence that starts small
     * reads as a stumble when someone is reading it aloud under pressure.
     */
    private fun sentence(text: String): String =
        text.trim().replaceFirstChar { it.uppercase() }.trimEnd('.')

    private fun patients(report: MedicalReport): String =
        if (report.patientCount == 1) "one patient"
        else "${spellOut(report.patientCount)} patients"

    /** Coordinates in degrees and decimal minutes, as aircraft use them. */
    private fun coordinates(report: MedicalReport): String =
        if (!report.hasPosition) "POSITION NOT YET FIXED"
        else CoordinateFormatter.format(report.latitude, report.longitude, CoordinateFormat.DDM)

    private fun spokenList(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + ", and " + items.last()
    }

    /** Small counts read better spoken than as digits. */
    private fun spellOut(count: Int): String = when (count) {
        2 -> "two"
        3 -> "three"
        4 -> "four"
        5 -> "five"
        6 -> "six"
        7 -> "seven"
        8 -> "eight"
        9 -> "nine"
        else -> count.toString()
    }
}
