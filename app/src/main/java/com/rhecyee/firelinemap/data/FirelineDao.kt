package com.rhecyee.firelinemap.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface FirelineDao {
    @Query("SELECT * FROM incidents ORDER BY isActive DESC, createdAt DESC")
    fun observeIncidents(): Flow<List<IncidentEntity>>

    @Query("SELECT * FROM incidents WHERE isActive = 1 LIMIT 1")
    fun observeActiveIncident(): Flow<IncidentEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIncident(incident: IncidentEntity)

    @Query("UPDATE incidents SET isActive = 0")
    suspend fun clearActiveIncident()

    @Query("UPDATE incidents SET isActive = 1 WHERE id = :incidentId")
    suspend fun markIncidentActive(incidentId: String)

    @Transaction
    suspend fun setActiveIncident(incidentId: String) {
        clearActiveIncident()
        markIncidentActive(incidentId)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrack(track: TrackEntity)

    @Query("SELECT * FROM tracks WHERE incidentId = :incidentId ORDER BY startedAt DESC")
    fun observeTracks(incidentId: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE isRecording = 1 LIMIT 1")
    suspend fun getActiveTrack(): TrackEntity?

    @Query("DELETE FROM tracks WHERE id = :trackId")
    suspend fun deleteTrack(trackId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMarker(marker: MarkerEntity)

    @Query("SELECT * FROM markers WHERE incidentId = :incidentId ORDER BY updatedAt DESC")
    fun observeMarkers(incidentId: String): Flow<List<MarkerEntity>>

    @Query("DELETE FROM markers WHERE id = :markerId")
    suspend fun deleteMarker(markerId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPositionHistory(position: ResourcePositionHistoryEntity)

    @Query(
        "SELECT * FROM resource_position_history WHERE markerId = :markerId " +
            "ORDER BY recordedAt DESC"
    )
    suspend fun positionHistory(markerId: String): List<ResourcePositionHistoryEntity>

    @Query("SELECT COUNT(*) FROM resource_position_history WHERE markerId = :markerId")
    suspend fun positionHistoryCount(markerId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBasemapRegion(region: BasemapRegionEntity)

    @Query("SELECT * FROM basemap_regions ORDER BY downloadedAt DESC")
    fun observeBasemapRegions(): Flow<List<BasemapRegionEntity>>

    /**
     * Complete regions whose extent covers a position, best detail first.
     *
     * Kept as a query rather than an in-memory filter so the coverage check
     * stays cheap as preloaded regions accumulate across a season.
     */
    @Query(
        """
        SELECT * FROM basemap_regions
        WHERE complete = 1
          AND :latitude BETWEEN south AND north
          AND :longitude BETWEEN west AND east
        ORDER BY maxZoom DESC
        """
    )
    suspend fun getBasemapRegionsCovering(
        latitude: Double,
        longitude: Double
    ): List<BasemapRegionEntity>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM basemap_regions")
    suspend fun getBasemapStorageBytes(): Long

    @Query("DELETE FROM basemap_regions WHERE id = :regionId")
    suspend fun deleteBasemapRegion(regionId: String)
}
