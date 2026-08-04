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
