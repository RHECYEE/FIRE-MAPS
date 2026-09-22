package com.rhecyee.firelinemap.fireline

import com.rhecyee.firelinemap.map.Earth
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Whether a dropped feature is fire, or is known not to be. */
enum class FirelineKind {
    /** On the fire, or on its edge. The perimeter is pulled out to include it. */
    FIRE,

    /** Known clean. The perimeter is cut back so it never reaches here. */
    NOT_FIRE
}

/** A position on a dropped feature. */
data class FirelineVertex(val latitude: Double, val longitude: Double)

/**
 * One thing the operator dropped.
 *
 * A single vertex is a point; two or more is a segment, and the whole run of
 * it counts, not just its ends. Walking a road with the tool running and
 * dropping a segment says "all of this is line", which is a far stronger
 * statement than two separate points and is treated as one.
 */
data class FirelineFeature(
    val id: String,
    val kind: FirelineKind,
    val vertices: List<FirelineVertex>
) {
    val isSegment: Boolean get() = vertices.size >= 2
}

/** One closed ring of an inferred perimeter. */
data class InferredRing(
    val points: List<FirelineVertex>,
    val areaSquareMeters: Double,
    /** True when this ring encloses unburnt ground inside a larger ring. */
    val isHole: Boolean
)

/** The perimeter inferred from everything dropped so far. */
data class InferredPerimeter(
    val rings: List<InferredRing>,
    val areaSquareMeters: Double,
    val perimeterMeters: Double,
    /**
     * How far the fire was allowed to reach beyond a dropped feature where
     * nothing contradicted it. The single assumption in the result, kept
     * explicit so it can be shown to the operator and changed by them.
     */
    val reachMeters: Double,
    val fireFeatureCount: Int,
    val excludedFeatureCount: Int
) {
    val isEmpty: Boolean get() = rings.isEmpty()

    /** Separate bodies of fire. More than one means the negatives split it. */
    val polygonCount: Int get() = rings.count { !it.isHole }

    companion object {
        fun empty(reachMeters: Double = 0.0) =
            InferredPerimeter(emptyList(), 0.0, 0.0, reachMeters, 0, 0)
    }
}

/**
 * Infers a fire perimeter from points and segments the operator dropped.
 *
 * Built for fires that have no published product yet, where the only thing
 * anyone actually knows is where people have been and what they saw. So the
 * input is exactly that: places that are fire, and places that certainly are
 * not.
 *
 * It works in three steps, on a grid laid over the observations.
 *
 * * Claim the ground within one reach of anything burning, and give back any
 *   of it that sits nearer a clean observation than a burning one. That is
 *   what dropping a negative means, and it is why two points either side of a
 *   road are enough to hold a perimeter off the road.
 * * Flood in from beyond the edge of the map. Whatever the flood cannot get
 *   to is fire -- including ground nobody has been to, if the observations
 *   surround it. This is the step that makes walking a perimeter work:
 *   without it, a ring of points comes back as a ring, with a hole where the
 *   fire is. Clean observations seed the flood too, so an unburnt island
 *   inside the fire is reported as a hole rather than swallowed.
 * * Pull the edge back off the reach and onto the observations, so the
 *   polygon lands where people actually stood rather than a reach outside
 *   them. See [SKIRT_FRACTION].
 *
 * Where clean observations separate two groups of fire, the flood gets
 * between them and the result comes back as two rings. The split is inferred
 * from the negatives rather than declared, which is what the negatives are
 * for. And the more observations there are, the less of the shape is
 * inference: twenty points along a road describe an edge that two points can
 * only suggest.
 *
 * Nothing here is a fire model. It is an interpolation between observations,
 * and it is only ever as good as the points behind it -- which is why
 * [InferredPerimeter.reachMeters] is reported rather than buried.
 */
object PerimeterInference {

    /** Longest side of the sampling grid. Bounds the cost of a live redraw. */
    private const val MAX_CELLS = 224

    /** No finer than this, whatever the numbers say. */
    private const val MIN_CELL_METERS = 5.0

    /**
     * How much of the reach is left as a skirt around the observations.
     *
     * The reach does two jobs and they want different sizes. Joining two
     * observations that belong to the same edge needs it to be generous --
     * gaps of up to about one and a half reaches are bridged. Deciding how far
     * past an observation the fire actually runs needs it to be mean, because
     * past the last place anybody stood, nobody knows. So the ground is
     * claimed out to a full reach to work out what connects to what, and then
     * most of that is given back.
     *
     * The effect is the one you want in the field: walk a perimeter and the
     * polygon lands on your footprints rather than a reach outside them, while
     * a single point dropped from a lookout still returns an honest blob
     * instead of collapsing to nothing.
     */
    private const val SKIRT_FRACTION = 0.35

    /** Bounds on the reach, both as a default and as a slider range. */
    const val MIN_REACH_METERS = 50.0
    const val MAX_REACH_METERS = 5_000.0

    /**
     * Works out a reach when the operator has not chosen one.
     *
     * With no clean features to push back, the only scale in the problem is
     * how far apart the fire points are, so the reach follows their spacing
     * and the result hugs them. Once there are clean features the question
     * changes: the operator is saying the fire is somewhere between the two,
     * so the reach opens up to the typical gap between them and the negatives
     * decide the rest. Anything else would make two points across a canyon
     * produce a perimeter that ignored the canyon.
     */
    fun defaultReachMeters(features: List<FirelineFeature>): Double {
        val fire = features.filter { it.kind == FirelineKind.FIRE }
        if (fire.isEmpty()) return MIN_REACH_METERS
        val clean = features.filter { it.kind == FirelineKind.NOT_FIRE }

        val reference = referenceLatitude(features)
        val fireLines = fire.map { it.project(reference) }
        val cleanLines = clean.map { it.project(reference) }
        val fireVertices = fireLines.flatten()

        val suggested = if (cleanLines.isEmpty()) {
            // A blob one-and-a-bit spacings wide: wide enough to bridge the
            // gaps between consecutive points, tight enough to still be
            // recognisably the shape that was walked.
            medianNearestNeighbour(fireVertices)?.times(1.35) ?: 300.0
        } else {
            val gaps = fireVertices.map { vertex ->
                cleanLines.minOf { line -> distanceToLine(vertex, line) }
            }
            median(gaps) ?: 300.0
        }
        return suggested.coerceIn(MIN_REACH_METERS, MAX_REACH_METERS)
    }

    /**
     * Builds the perimeter.
     *
     * [reachMeters] overrides [defaultReachMeters] when the operator has set
     * it. Returns empty when nothing burning has been dropped: a set of clean
     * points on its own says where the fire is not, which is not enough to say
     * where it is.
     */
    fun infer(
        features: List<FirelineFeature>,
        reachMeters: Double? = null
    ): InferredPerimeter {
        val fire = features.filter { it.kind == FirelineKind.FIRE && it.vertices.isNotEmpty() }
        val clean = features.filter { it.kind == FirelineKind.NOT_FIRE && it.vertices.isNotEmpty() }
        val reach = (reachMeters ?: defaultReachMeters(features))
            .coerceIn(MIN_REACH_METERS, MAX_REACH_METERS)
        if (fire.isEmpty()) return InferredPerimeter.empty(reach)

        val reference = referenceLatitude(features)
        val fireLines = fire.map { it.project(reference) }
        val cleanLines = clean.map { it.project(reference) }

        // The grid has to stretch a full reach past the outermost fire point,
        // plus a cell, so the field is negative all the way round the border
        // and every ring closes inside the grid rather than running off it.
        val all = fireLines.flatten()
        var minX = all.minOf { it.x }
        var maxX = all.maxOf { it.x }
        var minY = all.minOf { it.y }
        var maxY = all.maxOf { it.y }
        val pad = reach * 1.15
        minX -= pad; maxX += pad; minY -= pad; maxY += pad

        // Cell size follows the extent, not the reach: the grid is what bounds
        // the cost, and the longer side always gets MAX_CELLS of it.
        val span = max(maxX - minX, maxY - minY)
        val cell = max(span / MAX_CELLS, MIN_CELL_METERS)
        val nx = ((maxX - minX) / cell).toInt().coerceIn(2, MAX_CELLS)
        val ny = ((maxY - minY) / cell).toInt().coerceIn(2, MAX_CELLS)
        val stepX = (maxX - minX) / nx
        val stepY = (maxY - minY) / ny

        // Distance to the nearest observation of each kind, at every grid
        // corner. Everything below is a statement about these two numbers.
        val samples = (nx + 1) * (ny + 1)
        val toFire = DoubleArray(samples)
        val toClean = DoubleArray(samples)
        for (iy in 0..ny) {
            val y = minY + iy * stepY
            for (ix in 0..nx) {
                val point = PlanarPoint(minX + ix * stepX, y)
                val index = iy * (nx + 1) + ix
                toFire[index] = fireLines.minOf { distanceToLine(point, it) }
                toClean[index] =
                    if (cleanLines.isEmpty()) Double.MAX_VALUE
                    else cleanLines.minOf { distanceToLine(point, it) }
            }
        }

        // Everything the observations could account for, before anything has
        // decided which side of them is out. Ground closer to a clean
        // observation than to a burning one is excluded outright: that is what
        // dropping a negative means.
        val covered = BooleanArray(samples) { toFire[it] <= reach && toFire[it] <= toClean[it] }

        // Which of the remaining ground is genuinely outside: ground you could
        // walk to from beyond the edge of the map without crossing fire, plus
        // the clean observations themselves. Anything left over is enclosed --
        // and enclosed ground is fire whether or not anybody stood in it.
        //
        // This is the whole reason for the flood rather than a plain distance
        // threshold. Walking the edge of a fire and dropping points as you go
        // is the ordinary way to use this, and a threshold alone returns that
        // as a ring with a hole in the middle: it has no way to tell the
        // unvisited centre of a fire from the unvisited ground outside it.
        val outside = floodOutside(covered, toFire, toClean, nx, ny)
        if (outside.all { it }) return InferredPerimeter.empty(reach)

        // The dilation above pushed the edge a full reach past the outermost
        // observation, which would hand back a perimeter fattened by however
        // far the tool was told it might bridge. Pulling most of it back puts
        // the edge near the observations again while leaving enclosed ground
        // untouched, since enclosed ground is nowhere near the outside.
        val skirt = reach * SKIRT_FRACTION
        val fromOutside = distanceToSeeds(outside, nx, ny, stepX, stepY)
        val field = DoubleArray(samples) { fromOutside[it] - (reach - skirt) }

        val loops = marchingSquares(field, nx, ny, minX, minY, stepX, stepY)
        if (loops.isEmpty()) return InferredPerimeter.empty(reach)

        val tolerance = max(stepX, stepY) * 0.7
        val rings = loops
            .map { simplifyRing(it, tolerance) }
            .map { smoothRing(it) }
            .filter { it.size >= 3 }
            .map { ring -> ring.map { it.toGeo(reference) } }

        // Nesting decides which rings are unburnt islands: a ring inside an
        // odd number of others is a hole. Winding order is not trusted for
        // this -- it is one sign error away from reporting a fire as a hole in
        // itself and calling the acreage zero.
        val classified = rings.mapIndexed { index, ring ->
            val probe = ring.first()
            val depth = rings.indices.count { other ->
                other != index && ringContains(rings[other], probe)
            }
            InferredRing(
                points = ring,
                areaSquareMeters = sphericalArea(ring),
                isHole = depth % 2 == 1
            )
        }

        val area = classified.sumOf { if (it.isHole) -it.areaSquareMeters else it.areaSquareMeters }
        val perimeter = classified.filter { !it.isHole }.sumOf { ringLength(it.points) }

        return InferredPerimeter(
            rings = classified,
            areaSquareMeters = max(0.0, area),
            perimeterMeters = perimeter,
            reachMeters = reach,
            fireFeatureCount = fire.size,
            excludedFeatureCount = clean.size
        )
    }

    // ---- projection -------------------------------------------------------

    /**
     * A local flat-earth frame in metres, centred on the features.
     *
     * Equirectangular about the mean latitude. Over the tens of kilometres a
     * fire spans this is accurate to well under a metre, and it keeps every
     * distance in the field a plain subtraction instead of a trigonometric
     * one -- which matters when there are fifty thousand grid samples being
     * refreshed as the operator drops points. Areas are not taken from it;
     * those go back through the sphere.
     */
    internal data class PlanarPoint(val x: Double, val y: Double)

    private fun referenceLatitude(features: List<FirelineFeature>): Double {
        val all = features.flatMap { it.vertices }
        if (all.isEmpty()) return 0.0
        return all.sumOf { it.latitude } / all.size
    }

    private fun FirelineFeature.project(reference: Double): List<PlanarPoint> =
        vertices.map { it.project(reference) }

    private fun FirelineVertex.project(reference: Double): PlanarPoint {
        val metersPerDegreeLongitude =
            Earth.METERS_PER_DEGREE * cos(Math.toRadians(reference))
        return PlanarPoint(longitude * metersPerDegreeLongitude, latitude * Earth.METERS_PER_DEGREE)
    }

    private fun PlanarPoint.toGeo(reference: Double): FirelineVertex {
        val metersPerDegreeLongitude =
            Earth.METERS_PER_DEGREE * cos(Math.toRadians(reference))
        return FirelineVertex(
            latitude = y / Earth.METERS_PER_DEGREE,
            longitude = if (metersPerDegreeLongitude == 0.0) 0.0 else x / metersPerDegreeLongitude
        )
    }

    // ---- distances --------------------------------------------------------

    /** Distance to a feature: to the point if it is one, to the run if it is a segment. */
    internal fun distanceToLine(point: PlanarPoint, line: List<PlanarPoint>): Double {
        if (line.isEmpty()) return Double.MAX_VALUE
        if (line.size == 1) return hypot(point.x - line[0].x, point.y - line[0].y)
        var best = Double.MAX_VALUE
        for (i in 0 until line.size - 1) {
            val d = distanceToSegment(point, line[i], line[i + 1])
            if (d < best) best = d
        }
        return best
    }

    private fun distanceToSegment(p: PlanarPoint, a: PlanarPoint, b: PlanarPoint): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 1e-9) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    private fun hypot(dx: Double, dy: Double) = sqrt(dx * dx + dy * dy)

    private fun medianNearestNeighbour(points: List<PlanarPoint>): Double? {
        if (points.size < 2) return null
        val nearest = points.mapIndexed { index, point ->
            points.indices.filter { it != index }
                .minOf { hypot(point.x - points[it].x, point.y - points[it].y) }
        }
        return median(nearest)
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0
        else sorted[middle]
    }

    // ---- region -----------------------------------------------------------

    /**
     * Marks the ground you could reach from off the edge of the map without
     * crossing fire.
     *
     * Seeded from the border, which is a full reach clear of every
     * observation and so is outside by construction, and from any sample that
     * sits nearer a clean observation than a burning one -- which is how an
     * unburnt island in the middle of a fire gets reported as a hole rather
     * than being swallowed by the ground around it.
     */
    private fun floodOutside(
        covered: BooleanArray,
        toFire: DoubleArray,
        toClean: DoubleArray,
        nx: Int,
        ny: Int
    ): BooleanArray {
        val width = nx + 1
        val height = ny + 1
        val outside = BooleanArray(width * height)
        val queue = IntArray(width * height)
        var head = 0
        var tail = 0

        fun seed(index: Int) {
            if (outside[index] || covered[index]) return
            outside[index] = true
            queue[tail++] = index
        }

        for (x in 0 until width) {
            seed(x)
            seed((height - 1) * width + x)
        }
        for (y in 0 until height) {
            seed(y * width)
            seed(y * width + width - 1)
        }
        for (index in covered.indices) {
            if (toClean[index] < toFire[index]) seed(index)
        }

        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            if (x > 0) seed(index - 1)
            if (x < width - 1) seed(index + 1)
            if (y > 0) seed(index - width)
            if (y < height - 1) seed(index + width)
        }
        return outside
    }

    /**
     * Distance from every sample to the nearest seeded one, in metres.
     *
     * A two-pass chamfer rather than an exact transform. Its error is a couple
     * of percent of a cell, which is far inside the accuracy of the
     * observations it is being applied to, and it is two linear sweeps instead
     * of a search.
     */
    private fun distanceToSeeds(
        seeds: BooleanArray,
        nx: Int,
        ny: Int,
        stepX: Double,
        stepY: Double
    ): DoubleArray {
        val width = nx + 1
        val height = ny + 1
        val diagonal = sqrt(stepX * stepX + stepY * stepY)
        val unreached = Double.MAX_VALUE / 4.0
        val distance = DoubleArray(width * height) { if (seeds[it]) 0.0 else unreached }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                var best = distance[index]
                if (x > 0) best = min(best, distance[index - 1] + stepX)
                if (y > 0) best = min(best, distance[index - width] + stepY)
                if (x > 0 && y > 0) best = min(best, distance[index - width - 1] + diagonal)
                if (x < width - 1 && y > 0) best = min(best, distance[index - width + 1] + diagonal)
                distance[index] = best
            }
        }
        for (y in height - 1 downTo 0) {
            for (x in width - 1 downTo 0) {
                val index = y * width + x
                var best = distance[index]
                if (x < width - 1) best = min(best, distance[index + 1] + stepX)
                if (y < height - 1) best = min(best, distance[index + width] + stepY)
                if (x < width - 1 && y < height - 1) {
                    best = min(best, distance[index + width + 1] + diagonal)
                }
                if (x > 0 && y < height - 1) best = min(best, distance[index + width - 1] + diagonal)
                distance[index] = best
            }
        }
        return distance
    }

    // ---- contouring -------------------------------------------------------

    /**
     * Traces the zero crossing of the sampled field into closed rings.
     *
     * Marching squares, with every edge crossing computed once and referred to
     * by index afterwards. Neighbouring cells therefore share the identical
     * point rather than two values that ought to be equal and are not quite,
     * so rings stitch together by integer identity and no tolerance has to be
     * invented for joining them.
     */
    private fun marchingSquares(
        field: DoubleArray,
        nx: Int,
        ny: Int,
        minX: Double,
        minY: Double,
        stepX: Double,
        stepY: Double
    ): List<List<PlanarPoint>> {
        fun value(ix: Int, iy: Int) = field[iy * (nx + 1) + ix]
        fun inside(ix: Int, iy: Int) = value(ix, iy) > 0.0

        val horizontalBase = 0
        val verticalBase = nx * (ny + 1)
        val crossings = HashMap<Int, PlanarPoint>()

        fun fraction(a: Double, b: Double): Double {
            val span = a - b
            return if (abs(span) < 1e-12) 0.5 else (a / span).coerceIn(0.0, 1.0)
        }

        // Horizontal edges: (ix,iy) to (ix+1,iy).
        for (iy in 0..ny) {
            for (ix in 0 until nx) {
                val a = value(ix, iy)
                val b = value(ix + 1, iy)
                if ((a > 0.0) == (b > 0.0)) continue
                val t = fraction(a, b)
                crossings[horizontalBase + iy * nx + ix] =
                    PlanarPoint(minX + (ix + t) * stepX, minY + iy * stepY)
            }
        }
        // Vertical edges: (ix,iy) to (ix,iy+1).
        for (iy in 0 until ny) {
            for (ix in 0..nx) {
                val a = value(ix, iy)
                val b = value(ix, iy + 1)
                if ((a > 0.0) == (b > 0.0)) continue
                val t = fraction(a, b)
                crossings[verticalBase + iy * (nx + 1) + ix] =
                    PlanarPoint(minX + ix * stepX, minY + (iy + t) * stepY)
            }
        }
        if (crossings.isEmpty()) return emptyList()

        val links = mutableListOf<Pair<Int, Int>>()
        for (iy in 0 until ny) {
            for (ix in 0 until nx) {
                val bottom = horizontalBase + iy * nx + ix
                val top = horizontalBase + (iy + 1) * nx + ix
                val left = verticalBase + iy * (nx + 1) + ix
                val right = verticalBase + iy * (nx + 1) + (ix + 1)

                var code = 0
                if (inside(ix, iy)) code = code or 1          // bottom left
                if (inside(ix + 1, iy)) code = code or 2      // bottom right
                if (inside(ix + 1, iy + 1)) code = code or 4  // top right
                if (inside(ix, iy + 1)) code = code or 8      // top left

                when (code) {
                    1, 14 -> links += left to bottom
                    2, 13 -> links += bottom to right
                    3, 12 -> links += left to right
                    4, 11 -> links += right to top
                    6, 9 -> links += bottom to top
                    7, 8 -> links += left to top
                    5, 10 -> {
                        // A saddle: the two diagonal corners either join
                        // through the middle of the cell or pinch apart there.
                        // The centre value is what decides it, and guessing
                        // instead is what puts a spurious neck between two
                        // separate bodies of fire.
                        val centre = (value(ix, iy) + value(ix + 1, iy) +
                            value(ix + 1, iy + 1) + value(ix, iy + 1)) / 4.0
                        val joined = centre > 0.0
                        if (code == 5) {
                            if (joined) { links += left to top; links += bottom to right }
                            else { links += left to bottom; links += right to top }
                        } else {
                            if (joined) { links += left to bottom; links += right to top }
                            else { links += left to top; links += bottom to right }
                        }
                    }
                }
            }
        }
        if (links.isEmpty()) return emptyList()

        val adjacency = HashMap<Int, MutableList<Int>>()
        links.forEachIndexed { index, (a, b) ->
            adjacency.getOrPut(a) { mutableListOf() } += index
            adjacency.getOrPut(b) { mutableListOf() } += index
        }

        val used = BooleanArray(links.size)
        val rings = mutableListOf<List<PlanarPoint>>()
        for (start in links.indices) {
            if (used[start]) continue
            used[start] = true
            val (first, second) = links[start]
            val walk = mutableListOf(first, second)
            var current = second
            while (true) {
                val next = adjacency[current]?.firstOrNull { !used[it] } ?: break
                used[next] = true
                val (a, b) = links[next]
                current = if (a == current) b else a
                if (current == walk.first()) break
                walk += current
            }
            if (walk.size < 3) continue
            rings += walk.mapNotNull { crossings[it] }
        }
        return rings.filter { it.size >= 3 }
    }

    // ---- ring tidying -----------------------------------------------------

    /**
     * Drops the staircase a grid leaves behind.
     *
     * Douglas-Peucker at a fraction of the cell size: everything it removes is
     * smaller than the grid could resolve in the first place, so nothing real
     * is lost and the ring stops looking like it was drawn on graph paper.
     */
    internal fun simplifyRing(ring: List<PlanarPoint>, tolerance: Double): List<PlanarPoint> {
        if (ring.size < 4 || tolerance <= 0.0) return ring
        val kept = BooleanArray(ring.size)
        kept[0] = true
        kept[ring.size - 1] = true

        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to ring.size - 1)
        while (stack.isNotEmpty()) {
            val (from, to) = stack.removeLast()
            if (to <= from + 1) continue
            var worstIndex = -1
            var worst = tolerance
            for (i in from + 1 until to) {
                val d = distanceToSegment(ring[i], ring[from], ring[to])
                if (d > worst) { worst = d; worstIndex = i }
            }
            if (worstIndex < 0) continue
            kept[worstIndex] = true
            stack.addLast(from to worstIndex)
            stack.addLast(worstIndex to to)
        }
        return ring.filterIndexed { index, _ -> kept[index] }
    }

    /** One Chaikin pass, which rounds the remaining corners without moving the ring. */
    internal fun smoothRing(ring: List<PlanarPoint>): List<PlanarPoint> {
        if (ring.size < 4) return ring
        val out = ArrayList<PlanarPoint>(ring.size * 2)
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            out += PlanarPoint(a.x * 0.75 + b.x * 0.25, a.y * 0.75 + b.y * 0.25)
            out += PlanarPoint(a.x * 0.25 + b.x * 0.75, a.y * 0.25 + b.y * 0.75)
        }
        return out
    }

    // ---- measurement ------------------------------------------------------

    /** Ray casting, counting crossings of the ring by a ray running east. */
    internal fun ringContains(ring: List<FirelineVertex>, point: FirelineVertex): Boolean {
        var inside = false
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            val straddles = (a.latitude > point.latitude) != (b.latitude > point.latitude)
            if (!straddles) continue
            val span = b.latitude - a.latitude
            if (abs(span) < 1e-15) continue
            val crossing =
                a.longitude + (point.latitude - a.latitude) / span * (b.longitude - a.longitude)
            if (point.longitude < crossing) inside = !inside
        }
        return inside
    }

    /**
     * Spherical excess area in square metres.
     *
     * The same calculation the measuring tool uses, deliberately: an operator
     * who measures a perimeter by hand and one who infers it should not be
     * handed two different acreages for the same ground.
     */
    internal fun sphericalArea(ring: List<FirelineVertex>): Double {
        if (ring.size < 3) return 0.0
        var total = 0.0
        for (i in ring.indices) {
            val current = ring[i]
            val next = ring[(i + 1) % ring.size]
            total += Math.toRadians(next.longitude - current.longitude) *
                (2.0 + sin(Math.toRadians(current.latitude)) + sin(Math.toRadians(next.latitude)))
        }
        return abs(total * Earth.RADIUS_METERS * Earth.RADIUS_METERS / 2.0)
    }

    private fun ringLength(ring: List<FirelineVertex>): Double {
        if (ring.size < 2) return 0.0
        val reference = ring.sumOf { it.latitude } / ring.size
        val metersPerDegreeLongitude = Earth.METERS_PER_DEGREE * cos(Math.toRadians(reference))
        var total = 0.0
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            total += hypot(
                (b.longitude - a.longitude) * metersPerDegreeLongitude,
                (b.latitude - a.latitude) * Earth.METERS_PER_DEGREE
            )
        }
        return total
    }
}
