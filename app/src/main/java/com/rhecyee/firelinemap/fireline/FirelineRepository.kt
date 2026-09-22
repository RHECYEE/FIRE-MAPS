package com.rhecyee.firelinemap.fireline

import com.rhecyee.firelinemap.data.FirelineDao
import com.rhecyee.firelinemap.data.FirelineObservationEntity
import java.util.Locale
import java.util.UUID

/**
 * Holds the perimeter tool's observations against an incident.
 *
 * Stores what was seen, never the perimeter drawn from it. Reloading an
 * incident re-infers the polygon from the points, so a perimeter saved under
 * one reach and reopened under another is recomputed rather than restored
 * stale -- and correcting one bad point a week later corrects the shape.
 */
class FirelineRepository(private val dao: FirelineDao) {

    suspend fun load(incidentId: String): List<FirelineFeature> =
        dao.firelineObservations(incidentId).mapNotNull { it.toFeature() }

    suspend fun save(incidentId: String, features: List<FirelineFeature>) {
        val now = System.currentTimeMillis()
        val rows = features
            .filter { it.vertices.isNotEmpty() }
            .mapIndexed { index, feature ->
                FirelineObservationEntity(
                    // Keeps the feature's own id, so saving twice updates in
                    // place rather than accumulating duplicates.
                    id = feature.id.ifBlank { UUID.randomUUID().toString() },
                    incidentId = incidentId,
                    kind = feature.kind.name,
                    geometryGeoJson = encode(feature.vertices),
                    // Ordering is the order they were dropped in, which is the
                    // order they were seen in.
                    recordedAt = now + index
                )
            }
        dao.replaceFirelineObservations(incidentId, rows)
    }

    suspend fun clear(incidentId: String) = dao.deleteFirelineObservations(incidentId)

    private fun FirelineObservationEntity.toFeature(): FirelineFeature? {
        val vertices = decode(geometryGeoJson)
        if (vertices.isEmpty()) return null
        val parsedKind = runCatching { FirelineKind.valueOf(kind) }.getOrNull() ?: return null
        return FirelineFeature(id, parsedKind, vertices)
    }

    private companion object {
        /**
         * Written and read by hand rather than through a JSON library.
         *
         * The same reasoning as the track geometry this mirrors: the shape is
         * fixed, this app is the only thing that writes it, and org.json is a
         * stub on the unit test classpath.
         */
        fun encode(vertices: List<FirelineVertex>): String = buildString {
            append("{\"type\":\"LineString\",\"coordinates\":[")
            vertices.forEachIndexed { index, vertex ->
                if (index > 0) append(',')
                // Longitude first, as GeoJSON requires.
                append(
                    String.format(
                        Locale.US, "[%.7f,%.7f]", vertex.longitude, vertex.latitude
                    )
                )
            }
            append("]}")
        }

        fun decode(geoJson: String): List<FirelineVertex> =
            Regex("""\[\s*(-?[0-9.eE+-]+)\s*,\s*(-?[0-9.eE+-]+)\s*]""")
                .findAll(geoJson)
                .mapNotNull { match ->
                    val longitude = match.groupValues[1].toDoubleOrNull()
                        ?: return@mapNotNull null
                    val latitude = match.groupValues[2].toDoubleOrNull()
                        ?: return@mapNotNull null
                    FirelineVertex(latitude, longitude)
                }
                .toList()
    }
}
