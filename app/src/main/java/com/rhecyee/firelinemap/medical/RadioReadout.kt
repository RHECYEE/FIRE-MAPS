package com.rhecyee.firelinemap.medical

import com.rhecyee.firelinemap.util.CoordinateFormat
import com.rhecyee.firelinemap.util.CoordinateFormatter

/**
 * One numbered block of a readout, so it can be read a line at a time.
 *
 * [spokenNow] is false for the slots that exist to be filled later. Lines 7
 * and 8 are follow-up traffic: on the first call there is genuinely nothing to
 * say, and reading "updates: none yet, changes: none reported" aloud is two
 * lines of nothing at the end of an emergency transmission. The form still
 * shows them, because the numbering is what people are copying.
 */
data class ReadoutLine(
    val number: Int,
    val heading: String,
    val body: String,
    val spokenNow: Boolean = true
)

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

    /**
     * The whole thing as one block, for reading aloud or copying out.
     *
     * Only the lines that have something to say. Line 1 already carries the
     * standby call, so the script does not print it again above the list, and
     * the empty follow-up slots are left out rather than read as blanks.
     *
     * It ends by handing the channel back. A transmission that just stops
     * leaves the other station waiting for more, which on a medical is the
     * worst pause there is.
     */
    fun script(report: MedicalReport): String = buildString {
        lines(report).filter { it.spokenNow }.forEach { line ->
            appendLine("${line.number}. ${line.heading}")
            appendLine("   ${line.body}")
        }
        appendLine()
        append(signOff(report))
    }.trimEnd()

    /** How the call ends: who is talking, and a prompt for the read-back. */
    private fun signOff(report: MedicalReport): String =
        "${report.radioName} Medical, how copy?"

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
        report.natureOfInjury?.takeIf { it.isNotBlank() }?.let { append(", $it") }
        append(". ")
        append("${report.radioName} Medical. ")
        report.incidentCommander?.takeIf { it.isNotBlank() }?.let { append("IC $it. ") }
        report.medicalProvider?.takeIf { it.isNotBlank() }?.let { append("Patient care $it. ") }
        if (report.hasSeparateLandingZone) {
            append("Patient at ${coordinates(report)}. ")
            append("Ground to ${report.airPickupName?.takeIf { it.isNotBlank() }
                ?: "the landing zone"} for air pickup. ")
        } else {
            append("Pickup at ${coordinates(report)}. ")
        }
        append("Request ${report.transport.spoken}")
        if (report.resources.isNotEmpty()) {
            append(" with ${spokenList(report.resources.map { it.spoken })}")
        }
        append(". ")
        // Only for air, and not capitalised: it follows a colon, where a
        // capital reads oddly.
        if (report.transport.needsAir) {
            report.lzHazards?.takeIf { it.isNotBlank() }?.let {
                append("LZ hazards: ${it.trim().trimEnd('.')}. ")
            }
        }
        report.patientAssessment?.takeIf { it.isNotBlank() }?.let { append("${sentence(it)}. ") }
        append(signOff(report))
    }.trim()


    /**
     * Where the patient is picked up, and how they get there.
     *
     * The distinction the old wording lost: when an aircraft cannot land on
     * the patient, there are two places and a carry between them. Saying only
     * "LZ at <coordinates>" leaves whoever is listening to work out whether
     * that is where the patient is, and the answer decides whether a ground
     * unit is needed at all.
     */
    private fun transportPlan(report: MedicalReport): String = buildString {
        append("Request ${report.transport.spoken}.")

        if (report.hasSeparateLandingZone) {
            val place = report.airPickupName?.takeIf { it.isNotBlank() }
            val where = if (
                report.airPickupLatitude != null && report.airPickupLongitude != null
            ) {
                spokenPosition(report.airPickupLatitude, report.airPickupLongitude)
            } else {
                null
            }
            append(" Patient at ${coordinates(report)}.")
            // Named first: a helispot has a name on the IAP and that is what
            // goes over the radio, not a coordinate.
            append(" Ground to ${place ?: "the landing zone"}")
            where?.let { append(" at $it") }
            append(" for air pickup.")
        } else {
            append(" Pickup ${coordinates(report)}.")
            if (report.transport.needsAir) append(" Aircraft lands at the patient.")
        }

        report.groundContact?.takeIf { it.isNotBlank() }?.let {
            append(" Ground contact $it.")
        }

        // Only for air. A ground request has no landing zone, so a line about
        // its hazards is a blank that reads as an omission.
        if (report.transport.needsAir) {
            append(
                report.lzHazards?.takeIf { it.isNotBlank() }
                    ?.let { " LZ hazards: $it." }
                    ?: " LZ hazards: none reported."
            )
        }
    }

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
        ReadoutLine(4, "Transport Request", transportPlan(report)),
        ReadoutLine(
            5, "Additional Resources Needed",
            if (report.resources.isEmpty()) "None."
            else report.resources.joinToString(", ") { it.label } + "."
        ),
        documentationLine(report),
        updatesLine(report),
        changesLine(report, "Patient Transport / Incident Changes")
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
        ReadoutLine(4, "Transport Plan", transportPlan(report)),
        ReadoutLine(
            5, "Additional Resources",
            if (report.resources.isEmpty()) "None."
            else report.resources.joinToString(", ") { it.label } + "."
        ),
        documentationLine(report),
        updatesLine(report),
        changesLine(report, "Transport / Changes")
    )

    /**
     * Who wrote it down. Read out only when it names somebody.
     *
     * "Recorded in Fireline Map" is a note to whoever opens the file later,
     * not something to say on a medical.
     */
    private fun documentationLine(report: MedicalReport): ReadoutLine {
        val named = !report.reporterName.isNullOrBlank()
        return ReadoutLine(6, "Documentation", documentation(report), spokenNow = named)
    }

    private fun updatesLine(report: MedicalReport): ReadoutLine = ReadoutLine(
        7, "Updates",
        if (report.updates.isEmpty()) "Nothing yet — read these back as they come."
        else report.updates.joinToString(" ") { sentence(it.text) + "." },
        spokenNow = report.updates.isNotEmpty()
    )

    private fun changesLine(report: MedicalReport, heading: String): ReadoutLine {
        val notes = report.notes?.takeIf { it.isNotBlank() }
        return ReadoutLine(
            8, heading,
            notes ?: "Nothing yet — this is the follow-up call, once they move.",
            spokenNow = notes != null
        )
    }

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
        else spokenPosition(report.latitude, report.longitude)

    /**
     * A position as it is said, not as it is tabulated.
     *
     * The formatter pads latitude and longitude apart so a column of them
     * lines up on screen. Read aloud that gap is a stumble, so the two halves
     * are separated by a comma here instead.
     */
    private fun spokenPosition(latitude: Double, longitude: Double): String =
        CoordinateFormatter.format(latitude, longitude, CoordinateFormat.DDM)
            .replace("  ", ", ")

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
