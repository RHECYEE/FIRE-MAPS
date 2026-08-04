package com.rhecyee.firelinemap.terrain

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** What the contour layer is doing, for the key to say. */
enum class ContourStatus {
    /** Nothing asked for yet. */
    IDLE,

    /** Cutting lines from elevation already on the device. */
    WORKING,

    /** Lines are drawn and complete enough to trust. */
    READY,

    /** Some of the ground on screen has no elevation held for it. */
    PARTIAL,

    /** None of this ground has been downloaded. */
    MISSING
}

/**
 * Contours for whatever is on screen.
 *
 * Kept off the drawing path on purpose. Cutting contours means decoding a
 * handful of PNGs and marching a grid of tens of thousands of cells across
 * twenty levels, which is far too much to do per frame; done once per settled
 * view it is imperceptible. Drawing then only has to project points it has
 * already been given.
 *
 * A view is only rebuilt when it has actually changed enough to matter, so
 * panning a few metres does not re-cut the same lines.
 */
class ContourLayer(context: Context) {

    val cache = DemTileCache(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _contours = MutableStateFlow(ContourSet.NONE)
    val contours: StateFlow<ContourSet> = _contours.asStateFlow()

    private val _status = MutableStateFlow(ContourStatus.IDLE)
    val status: StateFlow<ContourStatus> = _status.asStateFlow()

    private var job: Job? = null
    private var lastRequest: Request? = null

    private data class Request(
        val north: Double,
        val south: Double,
        val west: Double,
        val east: Double,
        val zoom: Int
    ) {
        /**
         * Whether a new view is close enough to this one to reuse.
         *
         * A tenth of the span. Tighter and a moving vehicle re-cuts constantly
         * for lines that land in the same pixels; looser and the operator pans
         * to the edge of the contours and finds nothing there.
         */
        fun covers(other: Request): Boolean {
            if (other.zoom != zoom) return false
            val slackLatitude = (north - south) * 0.1
            val slackLongitude = (east - west) * 0.1
            return other.north <= north + slackLatitude &&
                other.south >= south - slackLatitude &&
                other.west >= west - slackLongitude &&
                other.east <= east + slackLongitude
        }
    }

    /**
     * Asks for contours over a view.
     *
     * [reliefHintFeet] is unused directly -- the relief is measured from the
     * grid once it is assembled -- but the interval it implies is what decides
     * how many levels get cut, so it is derived here rather than guessed.
     */
    fun request(
        north: Double,
        south: Double,
        west: Double,
        east: Double,
        viewZoom: Int
    ) {
        if (north <= south || east <= west) return
        val demZoom = viewZoom.coerceIn(MIN_DEM_ZOOM, MAX_DEM_ZOOM)
        val request = Request(north, south, west, east, demZoom)
        if (lastRequest?.covers(request) == true && _status.value == ContourStatus.READY) return
        lastRequest = request

        job?.cancel()
        job = scope.launch {
            _status.value = ContourStatus.WORKING
            val grid = ElevationGridAssembler.assemble(
                cache = cache,
                north = north,
                south = south,
                west = west,
                east = east,
                zoom = demZoom
            )
            if (grid == null) {
                _contours.value = ContourSet.NONE
                _status.value = ContourStatus.MISSING
                return@launch
            }

            val coverage = grid.coverage()
            if (coverage <= 0.0) {
                _contours.value = ContourSet.NONE
                _status.value = ContourStatus.MISSING
                // Nothing held for this ground, so let the next look try again
                // rather than treating an empty answer as settled.
                lastRequest = null
                return@launch
            }

            yield()
            val relief = grid.relief()
            val reliefFeet = relief?.let {
                (it.second - it.first) * ContourInterval.FEET_PER_METER
            }
            // The view's own zoom sets the starting interval, not the DEM's:
            // the DEM stops at fifteen but the operator can keep zooming, and
            // the lines should keep getting finer while there is data to
            // support it.
            val interval = ContourIntervals.forView(viewZoom, reliefFeet)
            val set = ContourBuilder.build(grid, interval)

            _contours.value = set
            _status.value = when {
                coverage >= COMPLETE_ENOUGH -> ContourStatus.READY
                else -> ContourStatus.PARTIAL
            }
            // A partial answer is not a settled one; the next look should try
            // again in case the missing tiles have since arrived.
            if (coverage < COMPLETE_ENOUGH) lastRequest = null
        }
    }

    /**
     * Fetches the elevation covering a view, nearest the middle first.
     *
     * Separate from [request] because downloading spends someone's data and
     * has to be gated on the settings, while cutting lines from what is
     * already here does not.
     */
    suspend fun download(
        north: Double,
        south: Double,
        west: Double,
        east: Double,
        zoom: Int,
        limit: Int = ElevationGridAssembler.MAX_TILES
    ): Int {
        val demZoom = zoom.coerceIn(MIN_DEM_ZOOM, MAX_DEM_ZOOM)
        var fetched = 0
        for ((z, x, y) in ElevationGridAssembler.tilesFor(north, south, west, east, demZoom)) {
            if (fetched >= limit) break
            if (cache.isCached(z, x, y)) continue
            if (cache.download(z, x, y)) fetched++
        }
        if (fetched > 0) lastRequest = null
        return fetched
    }

    fun clear() {
        job?.cancel()
        lastRequest = null
        cache.clear()
        _contours.value = ContourSet.NONE
        _status.value = ContourStatus.IDLE
    }

    companion object {
        /**
         * Below this, one contour line every couple of hundred metres of
         * ground: too coarse to be a contour and better served by the topo
         * basemap's own lines.
         */
        const val MIN_DEM_ZOOM = 9

        /** Where the elevation source stops. */
        const val MAX_DEM_ZOOM = 14

        /** Coverage above which a view counts as answered rather than partial. */
        const val COMPLETE_ENOUGH = 0.98
    }
}
