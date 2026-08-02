package ir.pixellab.core.vector

import ir.pixellab.core.model.Contour
import ir.pixellab.core.model.PathNode
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Vec2
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * What a node does to the curve through it.
 *
 * Not stored on the node — it is *derived* from where the two handles sit, which is the only
 * representation that cannot go out of step with the geometry. Storing the type as well means a file
 * can say "smooth" about a node whose handles are not collinear, and every consumer then has to
 * decide which of the two to believe.
 */
enum class NodeType {
    /** Handles independent; the curve breaks direction here. */
    CORNER,

    /** Handles collinear but different lengths — the curve is smooth, the curvature is not. */
    SMOOTH,

    /** Handles collinear and equal; the classic symmetric point. */
    SYMMETRIC,
}

/**
 * Geometry over the path model.
 *
 * Cubic Bézier throughout, because that is what every design format stores and what every font
 * outline that is not quadratic already is. Converting to a different representation to do the work
 * and back again is where precision goes.
 */
object PathMath {

    fun typeOf(node: PathNode, tolerance: Float = COLLINEAR_TOLERANCE): NodeType {
        val into = node.point - node.controlIn
        val outOf = node.controlOut - node.point
        val lengthIn = hypot(into.x, into.y)
        val lengthOut = hypot(outOf.x, outOf.y)
        if (lengthIn <= EPSILON || lengthOut <= EPSILON) return NodeType.CORNER

        // Cross product of the two directions: zero means they point the same way.
        val cross = (into.x * outOf.y - into.y * outOf.x) / (lengthIn * lengthOut)
        if (abs(cross) > tolerance || (into.x * outOf.x + into.y * outOf.y) <= 0f) return NodeType.CORNER
        return if (abs(lengthIn - lengthOut) <= tolerance * maxOf(lengthIn, lengthOut)) {
            NodeType.SYMMETRIC
        } else {
            NodeType.SMOOTH
        }
    }

    /**
     * Re-shapes a node's handles to match a type.
     *
     * Keeps the *outgoing* handle and moves the incoming one, because a node is almost always
     * converted while the user is drawing forwards, and moving the handle they just placed is the
     * one change they will read as the tool fighting them.
     */
    fun retype(node: PathNode, type: NodeType): PathNode = when (type) {
        NodeType.CORNER -> node.copy(controlIn = node.point, controlOut = node.point)
        NodeType.SMOOTH, NodeType.SYMMETRIC -> {
            val outOf = node.controlOut - node.point
            val length = hypot(outOf.x, outOf.y)
            if (length <= EPSILON) {
                node
            } else {
                val into = node.point - node.controlIn
                val keep = if (type == NodeType.SYMMETRIC) length else hypot(into.x, into.y).coerceAtLeast(length)
                val unit = Vec2(outOf.x / length, outOf.y / length)
                node.copy(controlIn = Vec2(node.point.x - unit.x * keep, node.point.y - unit.y * keep))
            }
        }
    }

    /** A point on one cubic segment. */
    fun evaluate(from: PathNode, to: PathNode, t: Float): Vec2 {
        val u = 1f - t
        val a = u * u * u
        val b = 3f * u * u * t
        val c = 3f * u * t * t
        val d = t * t * t
        return Vec2(
            a * from.point.x + b * from.controlOut.x + c * to.controlIn.x + d * to.point.x,
            a * from.point.y + b * from.controlOut.y + c * to.controlIn.y + d * to.point.y,
        )
    }

    /** The tangent direction at t, which is what a variable-width outline is built from. */
    fun tangent(from: PathNode, to: PathNode, t: Float): Vec2 {
        val u = 1f - t
        val x = 3f * u * u * (from.controlOut.x - from.point.x) +
            6f * u * t * (to.controlIn.x - from.controlOut.x) +
            3f * t * t * (to.point.x - to.controlIn.x)
        val y = 3f * u * u * (from.controlOut.y - from.point.y) +
            6f * u * t * (to.controlIn.y - from.controlOut.y) +
            3f * t * t * (to.point.y - to.controlIn.y)
        val length = hypot(x, y)
        // A zero tangent happens at a cusp, where the direction is genuinely undefined; the
        // horizontal fallback keeps a stroke's width finite rather than collapsing it to nothing.
        return if (length <= EPSILON) Vec2(1f, 0f) else Vec2(x / length, y / length)
    }

    /**
     * Turns a contour into a polyline.
     *
     * Adaptive: a nearly straight segment gets two points and a tight curl gets many. A fixed
     * subdivision either shows facets on the curls or wastes hundreds of points on the straights,
     * and a path from a real design has both in the same contour.
     */
    fun flatten(contour: Contour, tolerance: Float = FLATTEN_TOLERANCE): List<Vec2> {
        if (contour.nodes.isEmpty()) return emptyList()
        if (contour.nodes.size == 1) return listOf(contour.nodes[0].point)

        val out = ArrayList<Vec2>()
        out += contour.nodes.first().point
        for ((from, to) in segments(contour)) {
            val steps = subdivisionsFor(from, to, tolerance)
            for (i in 1..steps) out += evaluate(from, to, i.toFloat() / steps)
        }
        return out
    }

    fun flatten(path: ShapeGeometry.Path, tolerance: Float = FLATTEN_TOLERANCE): List<List<Vec2>> =
        path.contours.map { flatten(it, tolerance) }

    /**
     * The exact bounds of a path, not of its handles.
     *
     * A control point routinely sits well outside the curve it shapes — a circle drawn with the
     * usual 0.5523 handles has them a tenth of its radius outside it. Taking the hull of the nodes
     * and handles is the easy answer, and it makes every selection box on a curved shape too big.
     */
    fun bounds(path: ShapeGeometry.Path, tolerance: Float = FLATTEN_TOLERANCE): Rect? {
        var box: Rect? = null
        for (points in flatten(path, tolerance)) {
            for (point in points) {
                val at = Rect(point.x, point.y, point.x, point.y)
                box = box?.union(at) ?: at
            }
        }
        return box
    }

    /** Total length, walked along the flattened curve. */
    fun length(contour: Contour, tolerance: Float = FLATTEN_TOLERANCE): Float {
        val points = flatten(contour, tolerance)
        var total = 0f
        for (i in 1 until points.size) {
            total += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        }
        return total
    }

    /** Whether a point is inside a closed path, by the even-odd rule. */
    fun contains(path: ShapeGeometry.Path, point: Vec2, tolerance: Float = FLATTEN_TOLERANCE): Boolean {
        var inside = false
        for (points in flatten(path, tolerance)) {
            if (points.size < 3) continue
            var j = points.size - 1
            for (i in points.indices) {
                val a = points[i]
                val b = points[j]
                if ((a.y > point.y) != (b.y > point.y) &&
                    point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x
                ) {
                    inside = !inside
                }
                j = i
            }
        }
        return inside
    }

    /** Distance from a point to the nearest place on the path, for hit-testing an open stroke. */
    fun distanceTo(path: ShapeGeometry.Path, point: Vec2, tolerance: Float = FLATTEN_TOLERANCE): Float {
        var best = Float.MAX_VALUE
        for (points in flatten(path, tolerance)) {
            for (i in 1 until points.size) {
                best = minOf(best, distanceToSegment(point, points[i - 1], points[i]))
            }
        }
        return best
    }

    /**
     * Splits a segment at t, producing the node that lands between them.
     *
     * De Casteljau, which gives the *same* curve as two — the whole point of splitting rather than
     * resampling. Any approximation here changes the shape at the moment the user asks to add a
     * point to it, which reads as the tool damaging their work.
     */
    fun split(from: PathNode, to: PathNode, t: Float): Triple<PathNode, PathNode, PathNode> {
        val p0 = from.point
        val p1 = from.controlOut
        val p2 = to.controlIn
        val p3 = to.point

        val a = lerp(p0, p1, t)
        val b = lerp(p1, p2, t)
        val c = lerp(p2, p3, t)
        val d = lerp(a, b, t)
        val e = lerp(b, c, t)
        val middle = lerp(d, e, t)

        return Triple(
            from.copy(controlOut = a),
            PathNode(point = middle, controlIn = d, controlOut = e),
            to.copy(controlIn = c),
        )
    }

    /**
     * Every segment of a contour, including the closing one.
     *
     * A list rather than a callback. A callback would have to be `inline` for a caller to return
     * out of it, and an inline function cannot be called from the Android modules, which compile to
     * older bytecode — a path is a few dozen nodes, so the list costs nothing worth that.
     */
    fun segments(contour: Contour): List<Pair<PathNode, PathNode>> {
        val nodes = contour.nodes
        if (nodes.size < 2) return emptyList()
        val out = ArrayList<Pair<PathNode, PathNode>>(nodes.size)
        for (i in 0 until nodes.size - 1) out += nodes[i] to nodes[i + 1]
        if (contour.closed && nodes.size > 2) out += nodes.last() to nodes.first()
        return out
    }

    /**
     * How many straight pieces this segment needs.
     *
     * Measured by how far the curve strays from its chord, not by how long it is. Length is the
     * obvious budget and it is wrong in the case that matters: a straight segment written as a
     * cubic — which is most of an icon — would get as many subdivisions as a curl of the same
     * extent, and a long gentle arc would get far more than it needs.
     *
     * Deviation is measured *perpendicular* to the chord, not against the evenly-spaced thirds the
     * textbook bound uses. A straight segment is stored here with both handles sitting on their own
     * endpoints — the degenerate cubic that still traces a line — and the textbook bound reads that
     * as a curve of a third of the chord's length, so every straight edge in an icon would be
     * subdivided as heavily as a curl.
     */
    private fun subdivisionsFor(from: PathNode, to: PathNode, tolerance: Float): Int {
        val chordX = to.point.x - from.point.x
        val chordY = to.point.y - from.point.y
        val chord = hypot(chordX, chordY)
        if (chord <= EPSILON) {
            // A segment that returns to where it started is a loop; the chord says nothing about
            // it, so the handles' own reach is the only available measure.
            val reach = maxOf(
                hypot(from.controlOut.x - from.point.x, from.controlOut.y - from.point.y),
                hypot(to.controlIn.x - to.point.x, to.controlIn.y - to.point.y),
            )
            return sqrt(reach / tolerance.coerceAtLeast(EPSILON)).toInt().coerceIn(1, MAX_SUBDIVISIONS)
        }

        val d1 = abs(chordX * (from.controlOut.y - from.point.y) - chordY * (from.controlOut.x - from.point.x)) / chord
        val d2 = abs(chordX * (to.controlIn.y - from.point.y) - chordY * (to.controlIn.x - from.point.x)) / chord
        val deviation = FLATNESS_BOUND * maxOf(d1, d2)
        if (deviation <= tolerance) return 1
        return kotlin.math.ceil(sqrt(deviation / tolerance.coerceAtLeast(EPSILON)))
            .toInt()
            .coerceIn(1, MAX_SUBDIVISIONS)
    }

    private fun distanceToSegment(point: Vec2, a: Vec2, b: Vec2): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= EPSILON) return hypot(point.x - a.x, point.y - a.y)
        val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypot(point.x - (a.x + dx * t), point.y - (a.y + dy * t))
    }

    private fun lerp(a: Vec2, b: Vec2, t: Float) = Vec2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    const val EPSILON = 0.0001f

    /** How far the polyline may stray from the curve, in canvas units. */
    const val FLATTEN_TOLERANCE = 0.25f

    /** A single segment past this is a pathological curl, not a design. */
    const val MAX_SUBDIVISIONS = 128

    /** A cubic strays at most three quarters of its controls' offset from the chord. */
    private const val FLATNESS_BOUND = 0.75f

    /** Handles within this of collinear read as smooth; anything tighter is noise from a finger. */
    const val COLLINEAR_TOLERANCE = 0.02f
}
