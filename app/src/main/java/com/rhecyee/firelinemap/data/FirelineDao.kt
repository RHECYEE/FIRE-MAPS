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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMarker(marker: MarkerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPositionHistory(position: ResourcePositionHistoryEntity)
}
