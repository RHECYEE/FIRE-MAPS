package com.rhecyee.firelinemap.terrain

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.rhecyee.firelinemap.map.MapProjection
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    MISSING,

    /** Cutting failed. The layer stays off rather than trying again forever. */
    FAILED
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

    private val _failure = MutableStateFlow<String?>(null)

    /** Set when cutting failed, so the layer can say so instead of dying. */
    val failure: StateFlow<String?> = _failure.asStateFlow()

    /**
     * A failure here must not take the app with it.
     *
     * Without a handler an exception in this coroutine reaches the thread's
     * uncaught handler and the process is killed -- and contours are the one
     * part of this app doing heavy allocation on data fetched off the network,
     * so it is the likeliest place to run out of memory. A map with no
     * contours is still a map. A map that is not running is not.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, error ->
                if (error is CancellationException) return@CoroutineExceptionHandler
                _contours.value = ContourRender.NONE
                _status.value = ContourStatus.FAILED
                _failure.value = error::class.java.simpleName +
                    (error.message?.let { ": $it" } ?: "")
                lastRequest = null
            }
    )

    private val _contours = MutableStateFlow(ContourRender.NONE)
    val contours: StateFlow<ContourRender> = _contours.asStateFlow()

    private val _status = MutableStateFlow(ContourStatus.IDLE)
    val status: StateFlow<ContourStatus> = _status.asStateFlow()

    private var job: Job? = null
    private var lastRequest: Request? = null

    private data class Request(
        val north: Double,
        val south: Double,
        val west: Double,
        val east: Double,
        val zoom: Int,
        val projection: MapProjection,
        val detail: ContourDetail
    ) {
        /**
         * Whether a new view is close enough to this one to reuse.
         *
         * A tenth of the span. Tighter and a moving vehicle re-cuts constantly
         * for lines that land in the same pixels; looser and the operator pans
         * to the edge of the contours and finds nothing there.
         */
        fun covers(other: Request): Boolean {
            if (other.zoom != zoom || other.projection !== projection) return false
            if (other.detail != detail) return false
            val slackLatitude = (north - south) * 0.1
            val slackLongitude = (east - west) * 0.1
            return other.north <= north + slackLatitude &&
                other.south >= south - slackLatitude &&
                other.west >= west - slackLongitude &&
                other.east <= east + slackLongitude
        }
    }

    /**
     * Asks for contours over a view, ready to draw.
     *
     * The map frame comes in with the request so the cut lines can be
     * projected into page space here, on the worker, rather than on the
     * main thread. Projection is twenty thousand Transverse Mercator forwards
     * for a screenful; done anywhere the gesture can reach it, zooming stops
     * being possible.
     */
    fun request(
        north: Double,
        south: Double,
        west: Double,
        east: Double,
        viewZoom: Int,
        projection: MapProjection,
        detail: ContourDetail = ContourDetail.NORMAL
    ) {
        if (north <= south || east <= west) return
        val demZoom = viewZoom.coerceIn(MIN_DEM_ZOOM, MAX_DEM_ZOOM)
        // The projection is part of the key: the same ground drawn through a
        // sheet and through plain terrain lands in different places, so lines
        // cut for one are wrong for the other.
        val request = Request(north, south, west, east, demZoom, projection, detail)
        if (lastRequest?.covers(request) == true && _status.value == ContourStatus.READY) return
        lastRequest = request

        job?.cancel()
        _failure.value = null
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
                _contours.value = ContourRender.NONE
                _status.value = ContourStatus.MISSING
                return@launch
            }

            val coverage = grid.coverage()
            if (coverage <= 0.0) {
                _contours.value = ContourRender.NONE
                _status.value = ContourStatus.MISSING
                // Nothing held for this ground, so let the next look try again
                // rather than treating an empty answer as settled.
                lastRequest = null
                return@launch
            }

            ensureActive()
            val relief = grid.relief()
            val reliefFeet = relief?.let {
                (it.second - it.first) * ContourInterval.FEET_PER_METER
            }
            // The view's own zoom sets the starting interval, not the DEM's:
            // the DEM stops at fifteen but the operator can keep zooming, and
            // the lines should keep getting finer while there is data to
            // support it.
            val interval = ContourIntervals.forView(viewZoom, reliefFeet, detail)
            // Cancellation is checked inside both of these. A view that has
            // moved on has no use for the lines being cut for the old one, and
            // without a check the abandoned work runs to completion while the
            // next one queues behind it.
            val set = ContourBuilder.build(grid, interval) { isActive }
            ensureActive()
            val render = ContourProjector.project(set, projection) { isActive }
            ensureActive()

            _contours.value = render
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
        _failure.value = null
        cache.clear()
        _contours.value = ContourRender.NONE
        _status.value = ContourStatus.IDLE
    }

    /**
     * Drops the drawn lines but keeps the elevation behind them.
     *
     * For changing incident. The lines on screen were cut and projected for the
     * sheet that is being put away, so they have to go; the elevation they were
     * cut from is ground, and the next incident is very often the next drainage
     * over. Wiping the cache here would make an operator re-download a district
     * they already have, over whatever signal is left at the end of a road.
     */
    fun reset() {
        job?.cancel()
        lastRequest = null
        _failure.value = null
        _contours.value = ContourRender.NONE
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
