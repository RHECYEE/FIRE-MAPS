package com.rhecyee.firelinemap.medical

/** Triage priority, in the colours everyone already uses. */
enum class Priority(val label: String, val spoken: String, val colorArgb: Int) {
    RED("RED", "Red priority", 0xFFD50000.toInt()),
    YELLOW("YELLOW", "Yellow priority", 0xFFF9A825.toInt()),
    GREEN("GREEN", "Green priority", 0xFF2E7D32.toInt())
}

enum class TransportMode(val label: String, val spoken: String) {
    GROUND("GROUND", "ground ambulance"),
    AIR("AIR", "air ambulance"),
    BOTH("BOTH", "air and ground ambulance")
}

/** Kit called for alongside the transport request. */
enum class MedicalResource(val label: String, val spoken: String) {
    HOIST("Hoist", "hoist capability"),
    LITTER("Litter", "litter"),
    ROPE("Rope", "rope team"),
    ALS("ALS", "advanced life support"),
    OXYGEN("Oxygen", "oxygen"),
    AED("AED", "an AED"),
    EXTRICATION("Extrication", "extrication")
}

/**
 * Which form the readout follows.
 *
 * The Medical Incident Report has largely replaced the older eight-line on
 * current incidents, and is what appears in the IRPG and the ICS-206 WF. Crews
 * still say "the eight-line" out of habit, and some divisions still run it, so
 * both are carried and the same captured data drives either.
 */
enum class ReportFormat(val label: String) {
    MIR("Medical Incident Report"),
    EIGHT_LINE("8-Line")
}

/** One entry in the running record of a medical incident. */
data class ReportUpdate(val recordedAt: Long, val text: String)

/**
 * Everything a medical incident report holds.
 *
 * The fields above [natureOfInjury] are filled from what the app already
 * knows. Nobody should be typing an incident name or a coordinate with a
 * patient on the ground.
 */
data class MedicalReport(
    val id: String,
    val incidentId: String,
    val createdAt: Long,

    // Filled in automatically.
    val incidentName: String,
    val mapName: String?,
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double?,
    val accuracyMeters: Float?,
    val reporterName: String?,
    val reporterQualification: String?,

    // One tap each.
    val priority: Priority = Priority.RED,
    val patientCount: Int = 1,
    val transport: TransportMode = TransportMode.GROUND,
    val resources: Set<MedicalResource> = emptySet(),

    // Spoken.
    val natureOfInjury: String? = null,
    val patientAssessment: String? = null,
    val lzHazards: String? = null,
    val notes: String? = null,

    val incidentCommander: String? = null,
    val medicalProvider: String? = null,
    val groundContact: String? = null,

    val markerId: String? = null,
    val trackId: String? = null,
    val photoCount: Int = 0,
    val updates: List<ReportUpdate> = emptyList(),
    val format: ReportFormat = ReportFormat.MIR,

    /**
     * False when the form was opened before the receiver had a fix.
     *
     * The form opens regardless -- an emergency does not wait on GPS -- but a
     * coordinate that was never measured must never be read out as though it
     * were, so the readout says so and the report is not ready to transmit.
     */
    val hasPosition: Boolean = true
) {
    /** Fields that would leave a gap on the radio if left empty. */
    val missing: List<String>
        get() = buildList {
            if (!hasPosition) add("a position fix")
            if (natureOfInjury.isNullOrBlank()) add("nature of injury")
            if (patientAssessment.isNullOrBlank()) add("patient assessment")
            if (transport != TransportMode.GROUND && lzHazards.isNullOrBlank()) {
                add("LZ hazards")
            }
        }

    val isReadyToTransmit: Boolean get() = missing.isEmpty()
}
