package ir.pixellab.core.editor

import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Vec2

/** Which edge or centre line the chosen layers line up on. */
enum class AlignEdge {
    LEFT, CENTER_X, RIGHT, TOP, CENTER_Y, BOTTOM,
    ;

    /** True for the three that move layers horizontally; the rest move them vertically. */
    val isHorizontal: Boolean get() = this == LEFT || this == CENTER_X || this == RIGHT

    val persianLabel: String
        get() = when (this) {
            LEFT -> "چپ"
            CENTER_X -> "وسط افقی"
            RIGHT -> "راست"
            TOP -> "بالا"
            CENTER_Y -> "وسط عمودی"
            BOTTOM -> "پایین"
        }
}

/** Whether spacing is evened out across or down the page. */
enum class DistributeAxis {
    HORIZONTAL, VERTICAL,
    ;

    val persianLabel: String get() = if (this == HORIZONTAL) "افقی" else "عمودی"
}

/** What the chosen layers line up against. */
enum class AlignTarget {
    /** The box around everything selected — Photoshop's behaviour with two or more layers. */
    SELECTION,

    /** The artboard. What a single selected layer aligns to, since it has nothing else to meet. */
    CANVAS,
    ;

    val persianLabel: String get() = if (this == SELECTION) "انتخاب" else "بوم"
}

/** One layer's current box on the canvas, which is all aligning needs to know about it. */
data class Placed(val id: LayerId, val bounds: Rect)

/**
 * Lining layers up, and evening out the gaps between them.
 *
 * Pure arithmetic on boxes, deliberately: alignment has nothing to do with what a layer *is*, and
 * writing it against the document tree would mean re-deriving the same six cases for text, shapes
 * and images. The editor hands over boxes and applies the offsets that come back.
 *
 * Both operations return **offsets**, not new positions. A layer's transform is not simply its
 * position — it carries scale, rotation and an anchor — so the only safe thing to hand back is how
 * far to move, which composes with whatever the layer already has.
 */
object Arrange {

    /**
     * How far each layer moves to line up on [edge].
     *
     * Layers already on the line are omitted rather than returned with a zero offset, so a caller
     * can tell whether anything actually happened and skip an undo step that changes nothing.
     */
    fun align(
        layers: List<Placed>,
        edge: AlignEdge,
        target: AlignTarget,
        canvas: Rect,
    ): Map<LayerId, Vec2> {
        if (layers.isEmpty()) return emptyMap()
        val frame = when (target) {
            AlignTarget.CANVAS -> canvas
            AlignTarget.SELECTION -> layers.map { it.bounds }.reduce(Rect::union)
        }

        val moves = LinkedHashMap<LayerId, Vec2>()
        for (placed in layers) {
            val delta = when (edge) {
                AlignEdge.LEFT -> Vec2(frame.left - placed.bounds.left, 0f)
                AlignEdge.RIGHT -> Vec2(frame.right - placed.bounds.right, 0f)
                AlignEdge.CENTER_X -> Vec2(centreOf(frame).x - centreOf(placed.bounds).x, 0f)
                AlignEdge.TOP -> Vec2(0f, frame.top - placed.bounds.top)
                AlignEdge.BOTTOM -> Vec2(0f, frame.bottom - placed.bounds.bottom)
                AlignEdge.CENTER_Y -> Vec2(0f, centreOf(frame).y - centreOf(placed.bounds).y)
            }
            if (!delta.isNegligible) moves[placed.id] = delta
        }
        return moves
    }

    /**
     * Evens out the gaps between layers along [axis].
     *
     * The two outermost layers stay where they are and everything between them is respaced — which
     * is what "distribute" means to anyone who has used it, and it is the only definition that is
     * idempotent. Distributing by *centres* instead is the version that looks wrong the moment the
     * layers are different sizes: three boxes of widths 100, 400 and 100 end up overlapping.
     *
     * Needs at least three layers. With two there is one gap and nothing to even out.
     */
    fun distribute(layers: List<Placed>, axis: DistributeAxis): Map<LayerId, Vec2> {
        if (layers.size < MIN_TO_DISTRIBUTE) return emptyMap()
        val horizontal = axis == DistributeAxis.HORIZONTAL
        val ordered = layers.sortedBy { if (horizontal) it.bounds.left else it.bounds.top }

        val first = ordered.first().bounds
        val last = ordered.last().bounds
        val span = if (horizontal) last.right - first.left else last.bottom - first.top
        val occupied = ordered.sumOf { (if (horizontal) it.bounds.width else it.bounds.height).toDouble() }
        // Negative when the layers already overlap more than the span allows; the arithmetic still
        // produces a consistent, evenly overlapping row, which is what the user asked for.
        val gap = ((span - occupied) / (ordered.size - 1)).toFloat()

        val moves = LinkedHashMap<LayerId, Vec2>()
        var cursor = if (horizontal) first.left else first.top
        for (placed in ordered) {
            val size = if (horizontal) placed.bounds.width else placed.bounds.height
            val current = if (horizontal) placed.bounds.left else placed.bounds.top
            val delta = cursor - current
            val move = if (horizontal) Vec2(delta, 0f) else Vec2(0f, delta)
            if (!move.isNegligible) moves[placed.id] = move
            cursor += size + gap
        }
        return moves
    }

    private fun centreOf(rect: Rect) = Vec2((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)

    /** Below a twentieth of a pixel nothing on screen moves, and the undo step would be a lie. */
    private val Vec2.isNegligible: Boolean
        get() = kotlin.math.abs(x) < EPSILON && kotlin.math.abs(y) < EPSILON

    private const val EPSILON = 0.05f

    private const val MIN_TO_DISTRIBUTE = 3
}
