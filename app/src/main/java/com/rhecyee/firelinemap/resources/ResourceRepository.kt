package com.rhecyee.firelinemap.resources

import com.rhecyee.firelinemap.data.FirelineDao
import com.rhecyee.firelinemap.data.MarkerEntity
import com.rhecyee.firelinemap.data.ResourcePositionHistoryEntity
import java.util.UUID

/**
 * Places and moves operational resources.
 *
 * Moving a resource appends to its position history rather than overwriting
 * where it was. These are manually reported positions, not tracking, and the
 * previous report is often the more useful of the two -- knowing an engine was
 * at the drop point an hour ago is information, and silently discarding it
 * would not be.
 */
class ResourceRepository(private val dao: FirelineDao) {

    suspend fun place(
        incidentId: String,
        symbol: ResourceSymbol,
        title: String,
        note: String?,
        latitude: Double,
        longitude: Double
    ): MarkerEntity {
        val now = System.currentTimeMillis()
        val marker = MarkerEntity(
            id = UUID.randomUUID().toString(),
            incidentId = incidentId,
            category = symbol.category.name,
            symbol = symbol.id,
            title = title.ifBlank { symbol.label },
            note = note?.ifBlank { null },
            latitude = latitude,
            longitude = longitude,
            createdAt = now,
            updatedAt = now
        )
        dao.upsertMarker(marker)
        // The initial placement is itself a reported position.
        dao.insertPositionHistory(
            ResourcePositionHistoryEntity(
                id = UUID.randomUUID().toString(),
                markerId = marker.id,
                latitude = latitude,
                longitude = longitude,
                recordedAt = now
            )
        )
        return marker
    }

    suspend fun move(marker: MarkerEntity, latitude: Double, longitude: Double) {
        val now = System.currentTimeMillis()
        dao.upsertMarker(marker.copy(latitude = latitude, longitude = longitude, updatedAt = now))
        dao.insertPositionHistory(
            ResourcePositionHistoryEntity(
                id = UUID.randomUUID().toString(),
                markerId = marker.id,
                latitude = latitude,
                longitude = longitude,
                recordedAt = now
            )
        )
    }

    /** Cascades the position history with it. */
    suspend fun delete(markerId: String) = dao.deleteMarker(markerId)

    suspend fun reportCount(markerId: String): Int = dao.positionHistoryCount(markerId)
}
