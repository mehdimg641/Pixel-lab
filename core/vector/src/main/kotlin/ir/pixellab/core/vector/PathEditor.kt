package ir.pixellab.core.vector

import ir.pixellab.core.model.Contour
import ir.pixellab.core.model.PathNode
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Vec2
import kotlin.math.hypot

/** Which part of a node a gesture has hold of. */
enum class Handle { POINT, CONTROL_IN, CONTROL_OUT }

/** A node identified within a whole path. */
data class NodeRef(val contour: Int, val node: Int)

/** What a touch found on a path. */
sealed interface Hit {
    data class Node(val ref: NodeRef, val handle: Handle) : Hit

    /** A place on the curve between two nodes, where a new node would be inserted. */
    data class Segment(val contour: Int, val index: Int, val t: Float, val at: Vec2) : Hit
}

/**
 * Editing a path.
 *
 * Every operation returns a new path. That is not a style preference: the editor's undo stack stores
 * documents, and an in-place edit would have already destroyed the state it needs to return to.
 *
 * The rules here are the ones that make a pen tool feel like a pen tool rather than like a polygon
 * editor, and every one of them exists because its absence is immediately noticeable.
 */
object PathEditor {

    /**
     * Appends a node to the end of a contour, the pen tool's core gesture.
     *
     * The previous node's outgoing handle is left alone. Photoshop and Illustrator both set it while
     * the finger is still down and freeze it on release; overwriting it afterwards would undo the
     * curve the user just shaped.
     */
    fun append(path: ShapeGeometry.Path, contour: Int, node: PathNode): ShapeGeometry.Path =
        mapContour(path, contour) { it.copy(nodes = it.nodes + node) }

    /** Starts a new contour, so one shape can have several outlines — a letter with a counter. */
    fun beginContour(path: ShapeGeometry.Path, at: Vec2): ShapeGeometry.Path =
        ShapeGeometry.Path(path.contours + Contour(listOf(PathNode(at)), closed = false))

    /**
     * Closes a contour.
     *
     * If the last node lands on the first, it is dropped rather than left as a duplicate — two nodes
     * at the same place give a zero-length segment, which every stroker turns into a visible blob at
     * the join.
     */
    fun close(path: ShapeGeometry.Path, contour: Int, weld: Float = WELD_DISTANCE): ShapeGeometry.Path =
        mapContour(path, contour) { existing ->
            if (existing.nodes.size < 2) return@mapContour existing.copy(closed = true)
            val first = existing.nodes.first()
            val last = existing.nodes.last()
            if (hypot(last.point.x - first.point.x, last.point.y - first.point.y) <= weld) {
                existing.copy(
                    nodes = existing.nodes.dropLast(1).toMutableList().also {
                        // The incoming handle the user drew at the closing node belongs to the
                        // first node now, or the last segment loses its curve entirely.
                        it[0] = first.copy(controlIn = last.controlIn)
                    },
                    closed = true,
                )
            } else {
                existing.copy(closed = true)
            }
        }

    /**
     * Moves a node or one of its handles.
     *
     * Dragging a handle on a smooth node moves the opposite one to match, which is what "smooth"
     * means; on a corner node the two are independent. The type is read from the geometry rather
     * than stored, so this can never disagree with what is drawn.
     */
    fun move(path: ShapeGeometry.Path, ref: NodeRef, handle: Handle, to: Vec2): ShapeGeometry.Path =
        mapNode(path, ref) { node ->
            when (handle) {
                Handle.POINT -> {
                    // The handles travel with the point. Leaving them behind is the single most
                    // jarring thing a node editor can do — the curve springs away from the finger.
                    val delta = Vec2(to.x - node.point.x, to.y - node.point.y)
                    node.copy(
                        point = to,
                        controlIn = Vec2(node.controlIn.x + delta.x, node.controlIn.y + delta.y),
                        controlOut = Vec2(node.controlOut.x + delta.x, node.controlOut.y + delta.y),
                    )
                }
                Handle.CONTROL_IN -> withMirror(node, PathMath.typeOf(node), incoming = to)
                Handle.CONTROL_OUT -> withMirror(node, PathMath.typeOf(node), outgoing = to)
            }
        }

    fun retype(path: ShapeGeometry.Path, ref: NodeRef, type: NodeType): ShapeGeometry.Path =
        mapNode(path, ref) { PathMath.retype(it, type) }

    /**
     * Removes a node, keeping the curve as close to what it was as the remaining nodes allow.
     *
     * The neighbours keep their own handles rather than being straightened. Straightening is what
     * makes deleting one point of a smooth curve flatten a whole section, which is never what was
     * meant by removing a point.
     */
    fun remove(path: ShapeGeometry.Path, ref: NodeRef): ShapeGeometry.Path {
        val contour = path.contours.getOrNull(ref.contour) ?: return path
        if (ref.node !in contour.nodes.indices) return path
        // A contour of fewer than two nodes is not a shape; removing the last one removes it.
        if (contour.nodes.size <= 2) {
            return ShapeGeometry.Path(path.contours.filterIndexed { i, _ -> i != ref.contour })
        }
        return mapContour(path, ref.contour) { existing ->
            existing.copy(nodes = existing.nodes.filterIndexed { i, _ -> i != ref.node })
        }
    }

    /**
     * Inserts a node on a segment without changing the shape.
     *
     * De Casteljau, so the two halves are exactly the curve that was there. Any approximation would
     * alter the shape at the moment the user asked to add a point to it.
     */
    fun insert(path: ShapeGeometry.Path, contour: Int, segment: Int, t: Float): ShapeGeometry.Path =
        mapContour(path, contour) { existing ->
            val nodes = existing.nodes
            val from = nodes.getOrNull(segment) ?: return@mapContour existing
            val toIndex = if (segment + 1 < nodes.size) segment + 1 else if (existing.closed) 0 else return@mapContour existing
            val to = nodes[toIndex]

            val (start, middle, end) = PathMath.split(from, to, t.coerceIn(0f, 1f))
            val updated = nodes.toMutableList()
            updated[segment] = start
            updated[toIndex] = end
            updated.add(segment + 1, middle)
            existing.copy(nodes = updated)
        }

    /**
     * Breaks a closed contour open at a node, or an open one into two.
     *
     * The node is duplicated so both halves keep an end there — cutting between two nodes instead
     * would shorten the path by a whole segment.
     */
    fun split(path: ShapeGeometry.Path, ref: NodeRef): ShapeGeometry.Path {
        val contour = path.contours.getOrNull(ref.contour) ?: return path
        if (ref.node !in contour.nodes.indices) return path

        val rebuilt = if (contour.closed) {
            // Re-order so the cut lands at the ends: a ring opened at node k becomes a line from k
            // round to k.
            val rotated = contour.nodes.drop(ref.node) + contour.nodes.take(ref.node)
            listOf(Contour(rotated + rotated.first(), closed = false))
        } else {
            val before = contour.nodes.take(ref.node + 1)
            val after = contour.nodes.drop(ref.node)
            listOfNotNull(
                before.takeIf { it.size >= 2 }?.let { Contour(it, closed = false) },
                after.takeIf { it.size >= 2 }?.let { Contour(it, closed = false) },
            )
        }
        return ShapeGeometry.Path(
            path.contours.flatMapIndexed { i, existing -> if (i == ref.contour) rebuilt else listOf(existing) },
        )
    }

    /** Joins two open contours end to end, welding the ends if they nearly touch. */
    fun join(path: ShapeGeometry.Path, first: Int, second: Int, weld: Float = WELD_DISTANCE): ShapeGeometry.Path {
        val a = path.contours.getOrNull(first) ?: return path
        val b = path.contours.getOrNull(second) ?: return path
        if (first == second || a.closed || b.closed) return path

        val gap = hypot(b.nodes.first().point.x - a.nodes.last().point.x, b.nodes.first().point.y - a.nodes.last().point.y)
        val merged = if (gap <= weld) {
            a.nodes.dropLast(1) + b.nodes.first().copy(controlIn = a.nodes.last().controlIn) + b.nodes.drop(1)
        } else {
            a.nodes + b.nodes
        }
        return ShapeGeometry.Path(
            path.contours.filterIndexed { i, _ -> i != first && i != second } + Contour(merged, closed = false),
        )
    }

    /**
     * Finds what a touch is on.
     *
     * Handles are tested before points and points before segments, in that order, because a handle
     * usually sits *on top of* the curve it shapes — testing the segment first would make a handle
     * near its own node unreachable.
     */
    fun hitTest(
        path: ShapeGeometry.Path,
        at: Vec2,
        radius: Float,
        showHandlesFor: NodeRef? = null,
    ): Hit? {
        val squared = radius * radius

        if (showHandlesFor != null) {
            val node = path.contours.getOrNull(showHandlesFor.contour)?.nodes?.getOrNull(showHandlesFor.node)
            if (node != null) {
                if (near(node.controlOut, at, squared)) return Hit.Node(showHandlesFor, Handle.CONTROL_OUT)
                if (near(node.controlIn, at, squared)) return Hit.Node(showHandlesFor, Handle.CONTROL_IN)
            }
        }

        for ((c, contour) in path.contours.withIndex()) {
            for ((n, node) in contour.nodes.withIndex()) {
                if (near(node.point, at, squared)) return Hit.Node(NodeRef(c, n), Handle.POINT)
            }
        }

        for ((c, contour) in path.contours.withIndex()) {
            for ((index, segment) in PathMath.segments(contour).withIndex()) {
                val (from, to) = segment
                for (step in 0..SEGMENT_SAMPLES) {
                    val t = step.toFloat() / SEGMENT_SAMPLES
                    val point = PathMath.evaluate(from, to, t)
                    if (near(point, at, squared)) return Hit.Segment(c, index, t, point)
                }
            }
        }
        return null
    }

    private fun near(a: Vec2, b: Vec2, radiusSquared: Float): Boolean {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy <= radiusSquared
    }

    /**
     * Moves one handle, taking the other with it when the node is smooth.
     *
     * A symmetric node keeps both handles the same length; a smooth one keeps its own length and
     * only follows the direction. Treating the two the same would make every smooth node snap to
     * symmetric the first time it was touched, quietly losing the shape.
     */
    private fun withMirror(
        node: PathNode,
        type: NodeType,
        incoming: Vec2? = null,
        outgoing: Vec2? = null,
    ): PathNode {
        val updated = node.copy(
            controlIn = incoming ?: node.controlIn,
            controlOut = outgoing ?: node.controlOut,
        )
        if (type == NodeType.CORNER) return updated

        val moved = incoming ?: outgoing ?: return updated
        val direction = Vec2(node.point.x - moved.x, node.point.y - moved.y)
        val length = hypot(direction.x, direction.y)
        if (length <= PathMath.EPSILON) return updated
        val unit = Vec2(direction.x / length, direction.y / length)

        val other = if (incoming != null) node.controlOut else node.controlIn
        val otherLength = if (type == NodeType.SYMMETRIC) {
            length
        } else {
            hypot(other.x - node.point.x, other.y - node.point.y)
        }
        val mirrored = Vec2(node.point.x + unit.x * otherLength, node.point.y + unit.y * otherLength)
        return if (incoming != null) updated.copy(controlOut = mirrored) else updated.copy(controlIn = mirrored)
    }

    private fun mapContour(
        path: ShapeGeometry.Path,
        index: Int,
        body: (Contour) -> Contour,
    ): ShapeGeometry.Path {
        if (index !in path.contours.indices) return path
        return ShapeGeometry.Path(path.contours.mapIndexed { i, c -> if (i == index) body(c) else c })
    }

    private fun mapNode(
        path: ShapeGeometry.Path,
        ref: NodeRef,
        body: (PathNode) -> PathNode,
    ): ShapeGeometry.Path = mapContour(path, ref.contour) { contour ->
        if (ref.node !in contour.nodes.indices) {
            contour
        } else {
            contour.copy(nodes = contour.nodes.mapIndexed { i, n -> if (i == ref.node) body(n) else n })
        }
    }

    /** Two ends closer than this were meant to meet; a finger cannot do better than a few pixels. */
    const val WELD_DISTANCE = 8f

    private const val SEGMENT_SAMPLES = 24
}
