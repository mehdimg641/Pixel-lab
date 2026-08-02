package ir.pixellab.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ir.pixellab.core.model.PathNode
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.vector.Handle
import ir.pixellab.core.vector.Hit
import ir.pixellab.core.vector.NodeRef
import ir.pixellab.core.vector.NodeType
import ir.pixellab.core.vector.PathEditor
import ir.pixellab.core.vector.PathMath

/** What the vector tool is doing. */
enum class PenMode {
    /** Placing new nodes at the end of a contour. */
    DRAW,

    /** Moving nodes and handles of a path that already exists. */
    EDIT,
}

/**
 * The pen and node tools.
 *
 * The gesture vocabulary is the part that decides whether this feels like a pen: a tap places a
 * corner, a drag out of that tap shapes the curve leaving it, and a tap back on the first node
 * closes the shape. That is what every drawing tool does and what everyone who has drawn a path
 * before already knows.
 *
 * The path being drawn is held here rather than in the document until it is finished. A contour of
 * one node is not a shape, and putting each tap through the editor would fill the undo stack with
 * states that cannot be rendered.
 */
class PenController {

    var mode: PenMode by mutableStateOf(PenMode.DRAW)

    /** The path under construction, or the one being edited. */
    var path: ShapeGeometry.Path by mutableStateOf(ShapeGeometry.Path(emptyList()))
        private set

    /** Which node's handles are shown, so a tap near one reaches it rather than the curve. */
    var active: NodeRef? by mutableStateOf(null)
        private set

    private var dragging: Pair<NodeRef, Handle>? = null

    val isEmpty: Boolean get() = path.contours.all { it.nodes.size < 2 }

    fun reset() {
        path = ShapeGeometry.Path(emptyList())
        active = null
        dragging = null
    }

    fun load(existing: ShapeGeometry.Path) {
        path = existing
        active = null
        dragging = null
        mode = PenMode.EDIT
    }

    /**
     * A tap.
     *
     * In draw mode it places a node, or closes the contour when it lands on the first one. In edit
     * mode it selects a node, or inserts one where it lands on the curve — the second being the
     * gesture that makes a path editable without a separate "add point" tool to switch to.
     */
    fun tap(at: Vec2, touchRadius: Float) {
        val hit = PathEditor.hitTest(path, at, touchRadius, active)

        if (mode == PenMode.EDIT) {
            when (hit) {
                is Hit.Node -> active = hit.ref
                is Hit.Segment -> {
                    path = PathEditor.insert(path, hit.contour, hit.index, hit.t)
                    active = NodeRef(hit.contour, hit.index + 1)
                }
                null -> active = null
            }
            return
        }

        val contour = path.contours.lastIndex
        if (contour < 0) {
            path = PathEditor.beginContour(path, at)
            active = NodeRef(0, 0)
            return
        }

        // Landing on the first node closes the shape, which is how every pen tool ends a contour.
        val first = path.contours[contour].nodes.firstOrNull()
        if (first != null &&
            path.contours[contour].nodes.size > 2 &&
            kotlin.math.hypot(first.point.x - at.x, first.point.y - at.y) <= touchRadius
        ) {
            path = PathEditor.close(path, contour)
            active = null
            return
        }

        path = PathEditor.append(path, contour, PathNode(at))
        active = NodeRef(contour, path.contours[contour].nodes.lastIndex)
    }

    /**
     * The start of a drag.
     *
     * In draw mode a drag out of the node just placed shapes the curve leaving it — the gesture is
     * continuous with the tap, which is why the node is placed on press rather than on release.
     */
    fun dragStart(at: Vec2, touchRadius: Float) {
        if (mode == PenMode.DRAW) {
            tap(at, touchRadius)
            active?.let { dragging = it to Handle.CONTROL_OUT }
            return
        }
        dragging = when (val hit = PathEditor.hitTest(path, at, touchRadius, active)) {
            is Hit.Node -> {
                active = hit.ref
                hit.ref to hit.handle
            }
            else -> null
        }
    }

    fun drag(at: Vec2) {
        val (ref, handle) = dragging ?: return
        path = if (mode == PenMode.DRAW && handle == Handle.CONTROL_OUT) {
            // Both handles at once while drawing: the node is symmetric until the user says
            // otherwise, which is what makes a dragged-out curve continue smoothly.
            val node = path.contours.getOrNull(ref.contour)?.nodes?.getOrNull(ref.node) ?: return
            val mirrored = Vec2(2f * node.point.x - at.x, 2f * node.point.y - at.y)
            PathEditor.move(
                PathEditor.move(path, ref, Handle.CONTROL_IN, mirrored),
                ref, Handle.CONTROL_OUT, at,
            )
        } else {
            PathEditor.move(path, ref, handle, at)
        }
    }

    fun dragEnd() {
        dragging = null
    }

    /** Converts the selected node between corner, smooth and symmetric. */
    fun retype(type: NodeType) {
        val ref = active ?: return
        path = PathEditor.retype(path, ref, type)
    }

    fun removeActive() {
        val ref = active ?: return
        path = PathEditor.remove(path, ref)
        active = null
    }

    fun splitActive() {
        val ref = active ?: return
        path = PathEditor.split(path, ref)
        active = null
    }

    /** The type of the selected node, for the interface to show which of the three is current. */
    fun activeType(): NodeType? =
        active?.let { path.contours.getOrNull(it.contour)?.nodes?.getOrNull(it.node) }?.let(PathMath::typeOf)
}
