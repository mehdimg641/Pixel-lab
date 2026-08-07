package ir.pixellab.core.canvas

import ir.pixellab.core.model.Guide
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Vec2
import kotlin.math.abs

enum class Axis { HORIZONTAL, VERTICAL }

/** What a moving layer aligned to, so the canvas can draw the line the user is snapped against. */
data class SnapGuide(
    val axis: Axis,
    /** Canvas coordinate of the line: an x for [Axis.VERTICAL], a y for [Axis.HORIZONTAL]. */
    val position: Float,
    val kind: Kind,
    /** Extent along the guide, so it can be drawn just long enough to show what it connects. */
    val from: Float,
    val to: Float,
) {
    /**
     * Declaration order is the tie-break order.
     *
     * Two guides landing on the same coordinate is common — a layer's edge often sits on the canvas
     * centre — and without an explicit ranking the winner would depend on the order candidates
     * happen to be generated in, so the guide the user sees would change when unrelated code moved.
     * The canvas comes first because it is the one thing every layer shares.
     */
    enum class Kind {
        CANVAS_EDGE,
        CANVAS_CENTRE,

        /**
         * A guide the user placed, ranked above every layer.
         *
         * Deliberate beats incidental: a guide exists because someone dragged it there on purpose,
         * while a layer edge happens to be wherever that layer happens to sit.
         */
        GUIDE,

        LAYER_EDGE,
        LAYER_CENTRE,
        SPACING,

        /**
         * Last, because the grid is everywhere.
         *
         * A grid line is never more than half a step away, so ranking it any higher would let it
         * win ties against the guide or the layer edge the user was actually aiming for.
         */
        GRID,
    }
}

data class SnapResult(val offset: Vec2, val guides: List<SnapGuide>) {
    val snapped: Boolean get() = guides.isNotEmpty()

    companion object {
        val NONE = SnapResult(Vec2.ZERO, emptyList())
    }
}

data class SnapConfig(
    /**
     * Tolerance in **screen** pixels.
     *
     * Expressing it in canvas units is the obvious choice and it makes snapping grow stronger the
     * further you zoom in — at 800% a layer becomes impossible to place freely, at 10% it never
     * catches at all. In screen pixels the pull feels identical at every zoom.
     */
    val toleranceScreen: Float = 8f,
    val snapToCanvas: Boolean = true,
    val snapToLayers: Boolean = true,
    val snapToSpacing: Boolean = true,
    val snapToGuides: Boolean = true,
)

/**
 * Alignment guides.
 *
 * Both axes are resolved independently and each keeps only its single best candidate, so a layer
 * cannot be pulled by two competing guides on the same axis and end up between them.
 */
object SnapEngine {

    fun snap(
        moving: Rect,
        canvas: Vec2,
        others: List<Rect>,
        viewport: Viewport,
        config: SnapConfig = SnapConfig(),
        guides: List<Guide> = emptyList(),
        grid: GridSpec? = null,
    ): SnapResult {
        val tolerance = viewport.toCanvasDistance(config.toleranceScreen)
        if (tolerance <= 0f) return SnapResult.NONE

        val vertical = candidates(moving, canvas, others, Axis.VERTICAL, config, guides, grid)
        val horizontal = candidates(moving, canvas, others, Axis.HORIZONTAL, config, guides, grid)

        val order = compareBy<Candidate>({ abs(it.delta) }, { it.kind.ordinal })
        val bestX = vertical.filter { abs(it.delta) <= tolerance }.minWithOrNull(order)
        val bestY = horizontal.filter { abs(it.delta) <= tolerance }.minWithOrNull(order)

        val guides = listOfNotNull(bestX, bestY).map { it.guide(moving) }
        return SnapResult(Vec2(bestX?.delta ?: 0f, bestY?.delta ?: 0f), guides)
    }

    private class Candidate(
        val axis: Axis,
        val delta: Float,
        val line: Float,
        val kind: SnapGuide.Kind,
        val neighbour: Rect?,
    ) {
        fun guide(moving: Rect): SnapGuide {
            val span = if (neighbour == null) {
                if (axis == Axis.VERTICAL) moving.top to moving.bottom else moving.left to moving.right
            } else if (axis == Axis.VERTICAL) {
                minOf(moving.top, neighbour.top) to maxOf(moving.bottom, neighbour.bottom)
            } else {
                minOf(moving.left, neighbour.left) to maxOf(moving.right, neighbour.right)
            }
            return SnapGuide(axis, line, kind, span.first, span.second)
        }
    }

    private fun candidates(
        moving: Rect,
        canvas: Vec2,
        others: List<Rect>,
        axis: Axis,
        config: SnapConfig,
        guides: List<Guide>,
        grid: GridSpec?,
    ): List<Candidate> {
        val out = ArrayList<Candidate>()
        val (near, centre, far) = if (axis == Axis.VERTICAL) {
            Triple(moving.left, (moving.left + moving.right) / 2f, moving.right)
        } else {
            Triple(moving.top, (moving.top + moving.bottom) / 2f, moving.bottom)
        }
        val extent = if (axis == Axis.VERTICAL) canvas.x else canvas.y

        fun consider(line: Float, kind: SnapGuide.Kind, neighbour: Rect? = null) {
            // A layer's near edge, centre and far edge can all reach the same guide; each is a
            // separate candidate so the closest one wins rather than an arbitrary one.
            out += Candidate(axis, line - near, line, kind, neighbour)
            out += Candidate(axis, line - centre, line, kind, neighbour)
            out += Candidate(axis, line - far, line, kind, neighbour)
        }

        if (config.snapToCanvas) {
            consider(0f, SnapGuide.Kind.CANVAS_EDGE)
            consider(extent, SnapGuide.Kind.CANVAS_EDGE)
            consider(extent / 2f, SnapGuide.Kind.CANVAS_CENTRE)
        }

        if (config.snapToLayers) {
            for (other in others) {
                val (oNear, oCentre, oFar) = if (axis == Axis.VERTICAL) {
                    Triple(other.left, (other.left + other.right) / 2f, other.right)
                } else {
                    Triple(other.top, (other.top + other.bottom) / 2f, other.bottom)
                }
                consider(oNear, SnapGuide.Kind.LAYER_EDGE, other)
                consider(oFar, SnapGuide.Kind.LAYER_EDGE, other)
                consider(oCentre, SnapGuide.Kind.LAYER_CENTRE, other)
            }
        }

        if (config.snapToGuides) {
            for (guide in guides) {
                if (guide.vertical == (axis == Axis.VERTICAL)) {
                    consider(guide.position, SnapGuide.Kind.GUIDE)
                }
            }
        }

        if (config.snapToSpacing && others.size >= 2) out += spacing(moving, others, axis)

        // Only the nearest line to each of the three edges: every other grid line is further away
        // by construction, so generating them all would be work that can never win.
        if (grid != null && grid.snap) {
            consider(grid.nearest(near), SnapGuide.Kind.GRID)
            consider(grid.nearest(centre), SnapGuide.Kind.GRID)
            consider(grid.nearest(far), SnapGuide.Kind.GRID)
        }

        return out
    }

    /**
     * Equal-distribution guides.
     *
     * When two layers are already a fixed distance apart, the next one wants to continue that
     * rhythm. Edge alignment alone cannot express it, and laying out a row of items by eye is one of
     * the things that makes mobile editors feel amateur.
     */
    private fun spacing(moving: Rect, others: List<Rect>, axis: Axis): List<Candidate> {
        val nearOf = { r: Rect -> if (axis == Axis.VERTICAL) r.left else r.top }
        val farOf = { r: Rect -> if (axis == Axis.VERTICAL) r.right else r.bottom }
        val sorted = others.sortedBy(nearOf)
        val out = ArrayList<Candidate>()
        for (i in 0 until sorted.size - 1) {
            val gap = nearOf(sorted[i + 1]) - farOf(sorted[i])
            if (gap <= 0f) continue
            // Continue the run in both directions.
            val after = farOf(sorted[i + 1]) + gap
            val before = nearOf(sorted[i]) - gap - (farOf(moving) - nearOf(moving))
            out += Candidate(axis, after - nearOf(moving), after, SnapGuide.Kind.SPACING, sorted[i + 1])
            out += Candidate(axis, before - nearOf(moving), before, SnapGuide.Kind.SPACING, sorted[i])
        }
        return out
    }
}
