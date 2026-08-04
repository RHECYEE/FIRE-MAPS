package com.rhecyee.firelinemap.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "incidents")
data class IncidentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val incidentNumber: String? = null,
    val year: Int,
    val createdAt: Long,
    val archivedAt: Long? = null,
    val isActive: Boolean = false
)

@Entity(
    tableName = "map_documents",
    foreignKeys = [ForeignKey(
        entity = IncidentEntity::class,
        parentColumns = ["id"],
        childColumns = ["incidentId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("incidentId")]
)
data class MapDocumentEntity(
    @PrimaryKey val id: String,
    val incidentId: String,
    val filename: String,
    val originalUri: String,
    val importedAt: Long,
    val isGeoreferenced: Boolean,
    val crs: String? = null,
    val boundsJson: String? = null,
    val tilePath: String? = null,
    val opacity: Float = 1f,
    val visible: Boolean = true
)

@Entity(
    tableName = "tracks",
    foreignKeys = [ForeignKey(
        entity = IncidentEntity::class,
        parentColumns = ["id"],
        childColumns = ["incidentId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("incidentId")]
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val incidentId: String,
    val name: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val elapsedSeconds: Long = 0,
    val distanceMeters: Double = 0.0,
    val geometryGeoJson: String = "{\"type\":\"LineString\",\"coordinates\":[]}",
    val activityType: String = "OTHER",
    val note: String? = null,
    val isRecording: Boolean = false
)

@Entity(
    tableName = "markers",
    foreignKeys = [ForeignKey(
        entity = IncidentEntity::class,
        parentColumns = ["id"],
        childColumns = ["incidentId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("incidentId")]
)
data class MarkerEntity(
    @PrimaryKey val id: String,
    val incidentId: String,
    val category: String,
    val symbol: String,
    val title: String,
    val note: String? = null,
    val latitude: Double,
    val longitude: Double,
    val createdAt: Long,
    val updatedAt: Long,
    val status: String? = null,
    val priority: Int = 0
)

/**
 * A medical incident report held against its incident.
 *
 * Everything above [natureOfInjury] is captured from what the app already
 * knows at the moment the pin is dropped. Nobody types an incident name or a
 * coordinate with a patient on the ground.
 */
@Entity(
    tableName = "medical_reports",
    foreignKeys = [ForeignKey(
        entity = IncidentEntity::class,
        parentColumns = ["id"],
        childColumns = ["incidentId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("incidentId")]
)
data class MedicalReportEntity(
    @PrimaryKey val id: String,
    val incidentId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val incidentName: String,
    val mapName: String? = null,
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double? = null,
    val accuracyMeters: Float? = null,
    val reporterName: String? = null,
    val reporterQualification: String? = null,
    val priority: String = "RED",
    val patientCount: Int = 1,
    val transport: String = "GROUND",
    /** Comma-separated resource names. */
    val resources: String = "",
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
    val format: String = "MIR",
    val hasPosition: Boolean = true,
    val radioNameOverride: String? = null,
    val closedAt: Long? = null
)

/** One entry in a report's running record. */
@Entity(
    tableName = "medical_report_updates",
    foreignKeys = [ForeignKey(
        entity = MedicalReportEntity::class,
        parentColumns = ["id"],
        childColumns = ["reportId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("reportId")]
)
data class MedicalReportUpdateEntity(
    @PrimaryKey val id: String,
    val reportId: String,
    val recordedAt: Long,
    val text: String
)

/**
 * A user-drawn area to hold offline.
 *
 * The general case, of which a county is one shortcut. Elevation, hydrography
 * and names do not follow county lines, and incidents routinely cross them, so
 * the area is a polygon the operator drew and everything else -- which layers,
 * which zooms, how big -- hangs off it.
 *
 * [polygonJson] is a GeoJSON ring. [layers] names the components requested.
 * Status moves through PLANNED, DOWNLOADING, READY and FAILED; components are
 * fetched and verified separately so one failure does not cost the rest.
 */
@Entity(tableName = "offline_regions")
data class OfflineRegionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val polygonJson: String,
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
    val minZoom: Int = 5,
    val maxZoom: Int = 15,
    /** Comma-separated component names: BASEMAP, CONTOURS, HILLSHADE, NAMES, HYDRO, PARCELS. */
    val layers: String = "BASEMAP",
    val contourIntervalFeet: Int = 40,
    val status: String = "PLANNED",
    val estimatedBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val createdAt: Long,
    val completedAt: Long? = null
)

/**
 * A preloaded terrain basemap region held on local storage.
 *
 * Intentionally has no foreign key to an incident. Terrain is shared
 * geography, not incident data: two incidents in the same district use the
 * same ridges, and deleting one incident must not delete the terrain the
 * other one is still standing on. [incidentId] records which incident caused
 * the download so storage can be attributed and offered for cleanup, but it
 * never drives a cascade.
 */
@Entity(tableName = "basemap_regions", indices = [Index("incidentId")])
data class BasemapRegionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val incidentId: String? = null,
    /** TERRAIN_VECTOR, HILLSHADE_DEM, or BUNDLED_COARSE. */
    val source: String,
    val localPath: String,
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
    val minZoom: Int,
    val maxZoom: Int,
    val sizeBytes: Long = 0,
    val downloadedAt: Long,
    /** False while a download is in flight; incomplete regions are never drawn. */
    val complete: Boolean = false
)

@Entity(
    tableName = "resource_position_history",
    foreignKeys = [ForeignKey(
        entity = MarkerEntity::class,
        parentColumns = ["id"],
        childColumns = ["markerId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("markerId")]
)
data class ResourcePositionHistoryEntity(
    @PrimaryKey val id: String,
    val markerId: String,
    val latitude: Double,
    val longitude: Double,
    val recordedAt: Long
)
