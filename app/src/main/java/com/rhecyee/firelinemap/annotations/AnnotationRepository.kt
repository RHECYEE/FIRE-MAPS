package com.rhecyee.firelinemap.annotations

import com.rhecyee.firelinemap.data.FirelineDao
import com.rhecyee.firelinemap.data.MapAnnotationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/** What the tools have left on the map, held against the incident. */
class AnnotationRepository(private val dao: FirelineDao) {

    fun observe(incidentId: String): Flow<List<MapAnnotation>> =
        dao.observeMapAnnotations(incidentId).map { rows ->
            rows.mapNotNull { it.toAnnotation() }
        }

    /**
     * Keeps a shape, and hands back what was kept.
     *
     * Returns null rather than storing a shape with nothing in it: an empty
     * annotation would sit in the list taking up a row and drawing nothing,
     * and the operator would have no way to tell it apart from one that had
     * failed to draw.
     */
    suspend fun keep(
        incidentId: String,
        kind: AnnotationKind,
        label: String,
        rings: List<List<Pair<Double, Double>>>,
        note: String? = null
    ): MapAnnotation? {
        val usable = rings.filter { it.size >= 2 }
        if (usable.isEmpty()) return null
        val annotation = MapAnnotation(
            id = UUID.randomUUID().toString(),
            kind = kind,
            label = label,
            rings = usable,
            createdAt = System.currentTimeMillis(),
            note = note
        )
        dao.upsertMapAnnotation(
            MapAnnotationEntity(
                id = annotation.id,
                incidentId = incidentId,
                kind = kind.name,
                label = label,
                geometryGeoJson = AnnotationGeometry.encode(kind, usable),
                createdAt = annotation.createdAt,
                note = note
            )
        )
        return annotation
    }

    suspend fun remove(id: String) = dao.deleteMapAnnotation(id)

    suspend fun removeAll(incidentId: String, kind: AnnotationKind) =
        dao.deleteMapAnnotations(incidentId, kind.name)

    private fun MapAnnotationEntity.toAnnotation(): MapAnnotation? {
        val parsed = AnnotationKind.from(kind) ?: return null
        val rings = AnnotationGeometry.decode(geometryGeoJson)
        if (rings.isEmpty()) return null
        return MapAnnotation(id, parsed, label, rings, createdAt, note)
    }
}
