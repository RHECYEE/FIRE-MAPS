package com.rhecyee.firelinemap.map

/** Connection quality, as it matters to a download decision. */
enum class Connectivity {
    NONE,

    /** Cellular or a hotspot. Allowed, but the operator is told the size first. */
    METERED,

    UNMETERED
}

/**
 * What the single "download area data" button can do right now.
 *
 * Every state that cannot proceed carries the reason, so the button is never
 * just greyed out. A disabled control with no explanation is worthless to
 * someone standing at ICP trying to work out whether to wait for better
 * signal or drive somewhere else.
 */
sealed interface AreaDataState {

    /** No map imported and no position fix, so there is nothing to plan against. */
    data object NoTarget : AreaDataState

    /** Coverage is already on the device. */
    data class Ready(
        val downloadedBytes: Long,
        val bounds: GeoBounds
    ) : AreaDataState

    /** Ready to start. [metered] drives the confirmation copy, not a block. */
    data class Available(
        val plan: AreaDataPlan,
        val metered: Boolean
    ) : AreaDataState

    data class Downloading(
        val plan: AreaDataPlan,
        val downloadedBytes: Long
    ) : AreaDataState {
        val fraction: Float
            get() = if (plan.estimatedBytes <= 0) 0f
            else (downloadedBytes.toDouble() / plan.estimatedBytes).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * A previous attempt stopped part-way and can be resumed.
     *
     * Expected rather than exceptional: connectivity at an incident drops
     * constantly, so an interrupted download is a normal state to be in, not
     * an error to report.
     */
    data class Interrupted(
        val plan: AreaDataPlan,
        val downloadedBytes: Long
    ) : AreaDataState

    data class NoConnection(val plan: AreaDataPlan) : AreaDataState

    data class InsufficientStorage(
        val plan: AreaDataPlan,
        val freeBytes: Long
    ) : AreaDataState {
        val shortfallBytes: Long get() = (plan.requiredBytes - freeBytes).coerceAtLeast(0)
    }
}

object AreaDataStateResolver {

    /**
     * Resolves the button state.
     *
     * Order matters. Storage is checked before connectivity because a device
     * that cannot hold the download should say so while the operator still has
     * signal and options, rather than after they have driven out of coverage.
     */
    fun resolve(
        plan: AreaDataPlan?,
        connectivity: Connectivity,
        freeBytes: Long,
        existingBytes: Long = 0,
        isComplete: Boolean = false,
        isDownloading: Boolean = false
    ): AreaDataState {
        if (plan == null) return AreaDataState.NoTarget
        if (isComplete) return AreaDataState.Ready(existingBytes, plan.bounds)
        if (isDownloading) return AreaDataState.Downloading(plan, existingBytes)

        val outstanding = (plan.requiredBytes - existingBytes).coerceAtLeast(0)
        if (freeBytes < outstanding) {
            return AreaDataState.InsufficientStorage(plan, freeBytes)
        }
        if (connectivity == Connectivity.NONE) {
            return AreaDataState.NoConnection(plan)
        }
        if (existingBytes > 0) {
            return AreaDataState.Interrupted(plan, existingBytes)
        }
        return AreaDataState.Available(plan, metered = connectivity == Connectivity.METERED)
    }

    /** Compact size for button captions, e.g. "24 MB". */
    fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return when {
            bytes < 1024 * 1024 -> "${(bytes / 1024.0).toInt()} KB"
            mb < 1024 -> "${Math.round(mb)} MB"
            else -> String.format("%.1f GB", mb / 1024.0)
        }
    }
}
