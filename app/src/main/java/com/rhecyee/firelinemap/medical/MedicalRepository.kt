package com.rhecyee.firelinemap.medical

import android.content.Context
import com.rhecyee.firelinemap.data.FirelineDao
import com.rhecyee.firelinemap.data.MedicalReportEntity
import com.rhecyee.firelinemap.data.MedicalReportUpdateEntity
import java.util.UUID

/** Who is filling the form in. Asked once, then filled in automatically. */
class ReporterProfile(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences("reporter", Context.MODE_PRIVATE)

    var name: String
        get() = preferences.getString("name", "") ?: ""
        set(value) = preferences.edit().putString("name", value).apply()

    /** EMT, Paramedic, REMS, EMR, or whatever they carry. */
    var qualification: String
        get() = preferences.getString("qualification", "") ?: ""
        set(value) = preferences.edit().putString("qualification", value).apply()

    val isSet: Boolean get() = name.isNotBlank()
}

class MedicalRepository(private val dao: FirelineDao) {

    suspend fun save(report: MedicalReport) {
        dao.upsertMedicalReport(report.toEntity())
    }

    suspend fun addUpdate(reportId: String, text: String) {
        dao.insertMedicalUpdate(
            MedicalReportUpdateEntity(
                id = UUID.randomUUID().toString(),
                reportId = reportId,
                recordedAt = System.currentTimeMillis(),
                text = text
            )
        )
    }

    suspend fun load(reportId: String): MedicalReport? {
        val entity = dao.getMedicalReport(reportId) ?: return null
        val updates = dao.medicalUpdates(reportId)
            .map { ReportUpdate(it.recordedAt, it.text) }
        return entity.toReport(updates)
    }

    suspend fun delete(reportId: String) = dao.deleteMedicalReport(reportId)
}

fun MedicalReport.toEntity(): MedicalReportEntity = MedicalReportEntity(
    id = id,
    incidentId = incidentId,
    createdAt = createdAt,
    updatedAt = System.currentTimeMillis(),
    incidentName = incidentName,
    mapName = mapName,
    latitude = latitude,
    longitude = longitude,
    elevationMeters = elevationMeters,
    accuracyMeters = accuracyMeters,
    reporterName = reporterName,
    reporterQualification = reporterQualification,
    priority = priority.name,
    patientCount = patientCount,
    transport = transport.name,
    resources = resources.joinToString(",") { it.name },
    natureOfInjury = natureOfInjury,
    patientAssessment = patientAssessment,
    lzHazards = lzHazards,
    notes = notes,
    incidentCommander = incidentCommander,
    medicalProvider = medicalProvider,
    groundContact = groundContact,
    markerId = markerId,
    trackId = trackId,
    photoCount = photoCount,
    format = format.name,
    hasPosition = hasPosition,
    radioNameOverride = radioNameOverride
)

fun MedicalReportEntity.toReport(updates: List<ReportUpdate> = emptyList()): MedicalReport =
    MedicalReport(
        id = id,
        incidentId = incidentId,
        createdAt = createdAt,
        incidentName = incidentName,
        mapName = mapName,
        latitude = latitude,
        longitude = longitude,
        elevationMeters = elevationMeters,
        accuracyMeters = accuracyMeters,
        reporterName = reporterName,
        reporterQualification = reporterQualification,
        priority = runCatching { Priority.valueOf(priority) }.getOrDefault(Priority.RED),
        patientCount = patientCount,
        transport = runCatching { TransportMode.valueOf(transport) }
            .getOrDefault(TransportMode.GROUND),
        resources = resources.split(",").filter { it.isNotBlank() }
            .mapNotNull { name -> runCatching { MedicalResource.valueOf(name) }.getOrNull() }
            .toSet(),
        natureOfInjury = natureOfInjury,
        patientAssessment = patientAssessment,
        lzHazards = lzHazards,
        notes = notes,
        incidentCommander = incidentCommander,
        medicalProvider = medicalProvider,
        groundContact = groundContact,
        markerId = markerId,
        trackId = trackId,
        photoCount = photoCount,
        hasPosition = hasPosition,
        radioNameOverride = radioNameOverride,
        updates = updates,
        format = runCatching { ReportFormat.valueOf(format) }.getOrDefault(ReportFormat.MIR)
    )
