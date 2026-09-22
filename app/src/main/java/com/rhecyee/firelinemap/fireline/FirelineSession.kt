package com.rhecyee.firelinemap.fireline

import java.util.UUID

/**
 * What the operator has dropped so far, and how the next tap is read.
 *
 * Mirrors the measuring tool: the session owns the list, the screen holds a
 * snapshot of it, and every mutation hands back a fresh one. Compose is not
 * watching the mutable list.
 */
class FirelineSession {

    private val features = mutableListOf<FirelineFeature>()

    /** What the next tap drops. */
    var kind: FirelineKind = FirelineKind.FIRE

    /**
     * Whether consecutive taps join into one run.
     *
     * Off, each tap is a point: somewhere you stood and looked. On, taps chain
     * into a segment: ground you covered continuously. The difference is real
     * and only the operator knows which they mean, so it is a switch rather
     * than something inferred from how fast they tapped.
     */
    var linking: Boolean = false

    /** Null until the operator overrides the inferred reach. */
    var reachOverride: Double? = null

    val current: List<FirelineFeature> get() = features.toList()

    val isEmpty: Boolean get() = features.isEmpty()

    fun vertexCount(kind: FirelineKind): Int =
        features.filter { it.kind == kind }.sumOf { it.vertices.size }

    fun drop(latitude: Double, longitude: Double) {
        val vertex = FirelineVertex(latitude, longitude)
        val last = features.lastOrNull()
        if (linking && last != null && last.kind == kind) {
            features[features.lastIndex] = last.copy(vertices = last.vertices + vertex)
        } else {
            features += FirelineFeature(UUID.randomUUID().toString(), kind, listOf(vertex))
        }
    }

    /** Ends the run in progress so the next tap starts a new one. */
    fun breakRun() {
        if (features.lastOrNull()?.vertices?.isNotEmpty() == true) {
            features += FirelineFeature(UUID.randomUUID().toString(), kind, emptyList())
        }
    }

    /** Removes the last vertex dropped, and the feature with it once empty. */
    fun undo(): Boolean {
        while (features.isNotEmpty() && features.last().vertices.isEmpty()) {
            features.removeAt(features.lastIndex)
        }
        val last = features.lastOrNull() ?: return false
        if (last.vertices.size <= 1) {
            features.removeAt(features.lastIndex)
        } else {
            features[features.lastIndex] = last.copy(vertices = last.vertices.dropLast(1))
        }
        return true
    }

    fun clear() {
        features.clear()
        reachOverride = null
    }

    /** Drops everything of one kind, for when the negatives were the mistake. */
    fun clear(kind: FirelineKind) {
        features.removeAll { it.kind == kind }
    }

    fun load(saved: List<FirelineFeature>) {
        features.clear()
        features += saved
    }
}
