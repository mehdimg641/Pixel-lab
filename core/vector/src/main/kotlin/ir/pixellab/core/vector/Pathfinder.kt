package ir.pixellab.core.vector

import ir.pixellab.core.model.Contour
import ir.pixellab.core.model.PathNode
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Vec2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Boolean operations on paths — Illustrator's Pathfinder, and Photoshop's shape combine modes.
 *
 * The last thing keeping this project's vector work below the reference. Every other piece has been
 * here since wave 6 — the pen, node editing, variable width, SVG in and out — and without these four
 * operations any compound shape has to be drawn by hand, which is the difference between a vector
 * tool you can use and one you can finish work in.
 *
 * **Flattened, deliberately.** The operations run on polylines rather than on Bézier curves. Exact
 * curve–curve intersection is a root-finding problem with a long tail of degenerate cases — tangency,
 * overlapping segments, self-intersection — and the well-known implementations that get it right are
 * tens of thousands of lines. Flattening trades that for a tolerance, and the tolerance is chosen
 * against the same measure everything else in this file uses: a fraction of the shape's own size, so
 * the error stays under a screen pixel at any zoom the app reaches. Illustrator's own Pathfinder
 * flattens too, which is why its output is a polygon and not a curve.
 *
 * The algorithm is scanline-free: for each candidate edge, decide whether its midpoint is inside the
 * other shape, keep the edges the operation asks for, and stitch what survives back into loops. That
 * is slower than a sweep for very large inputs and it is *far* easier to make correct, which for an
 * operation a designer presses on two shapes at a time is the right trade.
 */
object Pathfinder {

    enum class Operation(val persianLabel: String) {
        /** Everything covered by either shape. */
        UNITE("اتحاد"),

        /** The first shape with the second cut out of it. */
        SUBTRACT("تفریق"),

        /** Only what both cover. */
        INTERSECT("اشتراک"),

        /** Everything covered by exactly one of them — Illustrator's Exclude. */
        EXCLUDE("حذف اشتراک"),
    }

    /**
     * Combines two paths.
     *
     * @return the result, or null when the operation leaves nothing at all — subtracting a shape
     *   from inside itself, or intersecting two shapes that do not touch. Null rather than an empty
     *   path because "no shape" and "a shape with no contours" are different things to a caller, and
     *   the second one renders as an invisible layer that a user cannot select or delete.
     */
    fun combine(
        a: ShapeGeometry.Path,
        b: ShapeGeometry.Path,
        operation: Operation,
        tolerance: Float = DEFAULT_TOLERANCE,
    ): ShapeGeometry.Path? {
        val left = polygonsOf(a, tolerance)
        val right = polygonsOf(b, tolerance)
        if (left.isEmpty()) {
            // Nothing on the left: union and exclude are just the right shape, the other two vanish.
            return if (operation == Operation.UNITE || operation == Operation.EXCLUDE) b else null
        }
        if (right.isEmpty()) {
            return if (operation == Operation.INTERSECT) null else a
        }

        // Exclude is the symmetric difference and is *defined* as two subtractions. Writing it that
        // way rather than as a fifth case in the edge filter means it cannot disagree with subtract.
        if (operation == Operation.EXCLUDE) {
            val ab = combine(a, b, Operation.SUBTRACT, tolerance)
            val ba = combine(b, a, Operation.SUBTRACT, tolerance)
            return when {
                ab == null -> ba
                ba == null -> ab
                else -> ShapeGeometry.Path(ab.contours + ba.contours)
            }
        }

        val split = mutableListOf<Edge>()
        for (poly in left) split += splitAgainst(poly, right, tolerance).map { Edge(it.first, it.second, fromLeft = true) }
        for (poly in right) split += splitAgainst(poly, left, tolerance).map { Edge(it.first, it.second, fromLeft = false) }

        // Coincident edges have to be settled before anything is asked about "inside", because a
        // point that lies exactly on the other shape's boundary has no honest answer to that
        // question — the ray test will say outside, and a shape united with itself then vanishes.
        //
        // The rule is orientation. Two coincident edges running the *same* way mean the shapes agree
        // about which side is solid, so the wall is a real outline and one copy survives. Running
        // *opposite* ways they are the seam between two shapes that meet — interior to a union,
        // and a zero-width sliver in an intersection — so both go.
        val settled = resolveCoincident(split, operation, tolerance)

        val kept = settled.filter { edge ->
            if (edge.decided) return@filter true
            val mid = Vec2((edge.from.x + edge.to.x) / 2f, (edge.from.y + edge.to.y) / 2f)
            val inside = if (edge.fromLeft) insideAny(right, mid) else insideAny(left, mid)
            when (operation) {
                // The outline of a union is the parts of each shape that are *not* buried in the
                // other. The outline of an intersection is exactly the opposite.
                Operation.UNITE -> !inside
                Operation.INTERSECT -> inside
                // A subtraction keeps the first shape's outside and the second shape's inside — the
                // second contributes the wall of the hole it cuts.
                Operation.SUBTRACT -> if (edge.fromLeft) !inside else inside
                Operation.EXCLUDE -> error("exclude is handled above")
            }
        }

        val loops = stitch(kept, tolerance)
        if (loops.isEmpty()) return null
        return ShapeGeometry.Path(loops.map { loop -> Contour(loop.map { PathNode(it) }, closed = true) })
    }

    /**
     * Removes or halves the pairs of edges that lie on top of each other.
     *
     * The one degeneracy this method cannot answer by asking "is the midpoint inside?", and the one
     * every polygon-clipping implementation has to special-case. Two rectangles sharing a wall and a
     * shape united with itself are both entirely made of this case.
     */
    private fun resolveCoincident(
        edges: List<Edge>,
        operation: Operation,
        tolerance: Float,
    ): List<Edge> {
        val snap = max(tolerance, MIN_SNAP) * COINCIDENT_SLACK
        val used = BooleanArray(edges.size)
        val out = mutableListOf<Edge>()

        for (i in edges.indices) {
            if (used[i]) continue
            val edge = edges[i]
            var partner = -1
            var sameDirection = false
            for (j in i + 1 until edges.size) {
                if (used[j] || edges[j].fromLeft == edge.fromLeft) continue
                val other = edges[j]
                when {
                    near(other.from, edge.from, snap) && near(other.to, edge.to, snap) -> {
                        partner = j; sameDirection = true
                    }
                    near(other.from, edge.to, snap) && near(other.to, edge.from, snap) -> {
                        partner = j; sameDirection = false
                    }
                    else -> continue
                }
                break
            }

            if (partner < 0) {
                out += edge
                continue
            }
            used[i] = true
            used[partner] = true

            val keep = when (operation) {
                // The shapes agree the wall is an outline, so it is one — but only once.
                Operation.UNITE, Operation.INTERSECT -> sameDirection
                // A minus B: where the two coincide there is nothing left, and where they merely
                // meet, A's wall is still A's outline.
                Operation.SUBTRACT -> !sameDirection && edge.fromLeft
                Operation.EXCLUDE -> error("exclude is handled above")
            }
            if (keep) {
                // Always the left shape's copy when there is a choice, so a subtraction keeps the
                // orientation the first shape had rather than inheriting the second's.
                val survivor = if (edge.fromLeft) edge else edges[partner]
                out += Edge(survivor.from, survivor.to, survivor.fromLeft, decided = true)
            }
        }
        return out
    }

    /** Whether [point] is inside the shape — the even-odd rule, matching how these paths render. */
    fun contains(polygons: List<List<Vec2>>, point: Vec2): Boolean = insideAny(polygons, point)

    /**
     * Flattens a path into closed polygons.
     *
     * Open contours are closed on the way through. A boolean operation on an open path is not
     * defined — there is no inside — and silently treating it as closed is what every vector editor
     * does, because it is what the user meant by drawing a nearly-closed shape.
     */
    fun polygonsOf(path: ShapeGeometry.Path, tolerance: Float = DEFAULT_TOLERANCE): List<List<Vec2>> =
        PathMath.flatten(path, tolerance)
            .map { points -> dedupe(points, tolerance) }
            .filter { it.size >= 3 }

    // ---- the pieces ----------------------------------------------------------------------------

    /**
     * @param decided true for a wall whose fate the coincidence rule already settled. Such an edge
     *   must skip the midpoint test below: its midpoint lies exactly on the other shape's boundary,
     *   where "inside?" has no honest answer and the ray test always says no.
     */
    private class Edge(
        val from: Vec2,
        val to: Vec2,
        val fromLeft: Boolean,
        val decided: Boolean = false,
    )

    /**
     * Cuts a polygon at every point where it crosses any of [others].
     *
     * This is the step the whole method rests on: once every edge either lies wholly inside the
     * other shape or wholly outside it, "keep the outside ones" is a well-defined instruction. An
     * edge that straddles a boundary has no single answer, which is why it has to be split first.
     */
    private fun splitAgainst(
        polygon: List<Vec2>,
        others: List<List<Vec2>>,
        tolerance: Float,
    ): List<Pair<Vec2, Vec2>> {
        val out = mutableListOf<Pair<Vec2, Vec2>>()
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]

            // Parameters along a→b where a crossing happens, in order.
            val cuts = mutableListOf(0f, 1f)
            for (other in others) {
                for (j in other.indices) {
                    val c = other[j]
                    val d = other[(j + 1) % other.size]
                    val t = intersectionParameter(a, b, c, d) ?: continue
                    if (t > tolerance && t < 1f - tolerance) cuts += t
                }
            }
            cuts.sort()

            for (k in 0 until cuts.size - 1) {
                val t0 = cuts[k]
                val t1 = cuts[k + 1]
                if (t1 - t0 <= tolerance) continue
                out += lerp(a, b, t0) to lerp(a, b, t1)
            }
        }
        return out
    }

    /**
     * Where two segments cross, as a parameter along the first — or null if they do not.
     *
     * Endpoint touches are deliberately excluded at the caller rather than here: a shape whose
     * corner grazes another's edge should not be cut there, and admitting the touch produces a
     * zero-length edge that the stitcher then has to discard anyway.
     */
    private fun intersectionParameter(a: Vec2, b: Vec2, c: Vec2, d: Vec2): Float? {
        val rx = b.x - a.x
        val ry = b.y - a.y
        val sx = d.x - c.x
        val sy = d.y - c.y
        val denominator = rx * sy - ry * sx
        // Parallel or collinear. Collinear overlap is genuinely ambiguous — the two shapes share a
        // wall — and both plausible answers give the same rendered result, so it is left uncut.
        if (abs(denominator) < PARALLEL_EPSILON) return null
        val t = ((c.x - a.x) * sy - (c.y - a.y) * sx) / denominator
        val u = ((c.x - a.x) * ry - (c.y - a.y) * rx) / denominator
        if (t < 0f || t > 1f || u < 0f || u > 1f) return null
        return t
    }

    private fun lerp(a: Vec2, b: Vec2, t: Float) = Vec2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    private fun insideAny(polygons: List<List<Vec2>>, point: Vec2): Boolean {
        var crossings = 0
        for (polygon in polygons) {
            var j = polygon.size - 1
            for (i in polygon.indices) {
                val a = polygon[i]
                val b = polygon[j]
                if ((a.y > point.y) != (b.y > point.y) &&
                    point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x
                ) {
                    crossings++
                }
                j = i
            }
        }
        // Even-odd rather than non-zero, so a path with a counter — a letter O, a washer — keeps its
        // hole. Non-zero would fill it whenever the two contours happened to wind the same way.
        return crossings % 2 == 1
    }

    /**
     * Joins the surviving edges back into closed loops.
     *
     * Greedy, from whichever end is nearest within the tolerance. A survivor set from a correct
     * split is already a set of closed chains, so greedy is not an approximation here — it is the
     * only walk available. What the tolerance buys is robustness against the float error introduced
     * by cutting the same crossing from both sides, which lands the two endpoints a hair apart.
     */
    private fun stitch(edges: List<Edge>, tolerance: Float): List<List<Vec2>> {
        val remaining = edges.toMutableList()
        val loops = mutableListOf<List<Vec2>>()
        val snap = max(tolerance, MIN_SNAP)

        while (remaining.isNotEmpty()) {
            val start = remaining.removeAt(remaining.size - 1)
            val loop = mutableListOf(start.from, start.to)

            var extended = true
            while (extended) {
                extended = false
                val tail = loop.last()
                if (near(tail, loop.first(), snap) && loop.size > 2) break

                val index = remaining.indexOfFirst { near(it.from, tail, snap) || near(it.to, tail, snap) }
                if (index < 0) break
                val next = remaining.removeAt(index)
                loop += if (near(next.from, tail, snap)) next.to else next.from
                extended = true
            }

            // A chain that never closed is float debris from a near-tangency, not a shape. Dropping
            // it is better than emitting an open contour the renderer would fill unpredictably.
            if (loop.size >= 4 && near(loop.last(), loop.first(), snap * CLOSE_SLACK)) {
                loops += dedupe(loop.dropLast(1), tolerance)
            }
        }
        return loops.filter { it.size >= 3 }
    }

    private fun near(a: Vec2, b: Vec2, tolerance: Float) =
        abs(a.x - b.x) <= tolerance && abs(a.y - b.y) <= tolerance

    /** Removes points a flattener or a cut left on top of each other. */
    private fun dedupe(points: List<Vec2>, tolerance: Float): List<Vec2> {
        if (points.isEmpty()) return points
        val snap = max(tolerance, MIN_SNAP)
        val out = mutableListOf(points.first())
        for (p in points.drop(1)) {
            if (!near(p, out.last(), snap)) out += p
        }
        while (out.size > 1 && near(out.last(), out.first(), snap)) out.removeAt(out.size - 1)
        return out
    }

    /** The bounding box of a set of polygons, for callers that need to scale the tolerance. */
    fun extent(polygons: List<List<Vec2>>): Float {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (polygon in polygons) {
            for (p in polygon) {
                minX = min(minX, p.x); minY = min(minY, p.y)
                maxX = max(maxX, p.x); maxY = max(maxY, p.y)
            }
        }
        if (minX > maxX) return 0f
        return max(maxX - minX, maxY - minY)
    }

    /**
     * A twentieth of a canvas unit.
     *
     * Fine enough that the flattening error is invisible at any zoom the app reaches, and coarse
     * enough that two crossings computed from opposite sides land on the same point.
     */
    const val DEFAULT_TOLERANCE = 0.05f

    /** Below this, float error on a large canvas exceeds the snap and loops fail to close. */
    private const val MIN_SNAP = 1e-3f

    /** The closing gap may be a little wider than a joining gap; it has accumulated round the loop. */
    private const val CLOSE_SLACK = 4f

    private const val PARALLEL_EPSILON = 1e-9f

    /**
     * How far apart two edges may be and still count as the same wall.
     *
     * Wider than the stitching snap, because a shared wall is usually cut from both sides at
     * slightly different parameters and the two halves end up a hair out of step.
     */
    private const val COINCIDENT_SLACK = 4f
}
