package ir.pixellab.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ir.pixellab.core.codec.RasterImage
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.paint.Edge
import ir.pixellab.core.paint.MagicWand
import ir.pixellab.core.paint.Marquee
import ir.pixellab.core.paint.PixelSelection
import ir.pixellab.core.paint.SelectionMode
import ir.pixellab.core.paint.SelectionOutline

/** Which shape a drag defines. */
enum class SelectionShape { RECTANGLE, ELLIPSE, LASSO, WAND }

/**
 * Choosing pixels.
 *
 * Held apart from the view model because a selection is not part of the document: it survives an
 * undo of the artwork, it is not saved, and it is shared by every tool that narrows what it touches.
 * Putting it in the document would make every marquee drag an undo step.
 */
class SelectionController {

    var shape: SelectionShape by mutableStateOf(SelectionShape.RECTANGLE)
    var mode: SelectionMode by mutableStateOf(SelectionMode.REPLACE)

    /** Tolerance for the wand, in the same 0..255 colour distance Photoshop's slider means. */
    var tolerance: Float by mutableStateOf(32f)
    var contiguous: Boolean by mutableStateOf(true)
    var feather: Float by mutableStateOf(0f)

    var selection: PixelSelection? by mutableStateOf(null)
        private set

    /** The boundary, recomputed only when the selection changes rather than per frame. */
    var outline: List<Edge> by mutableStateOf(emptyList())
        private set

    /** The shape being dragged right now, drawn live before it is committed. */
    var draft: List<Vec2> by mutableStateOf(emptyList())
        private set

    private var anchor: Vec2? = null

    fun begin(at: Vec2) {
        anchor = at
        draft = listOf(at)
    }

    fun extend(at: Vec2) {
        val start = anchor ?: return
        draft = when (shape) {
            // A lasso keeps every point; the others need only the two corners, and keeping the rest
            // would make a slow drag build a list thousands long for a rectangle.
            SelectionShape.LASSO -> draft + at
            else -> listOf(start, at)
        }
    }

    /**
     * Commits the drag.
     *
     * @param pixels the layer being sampled, for the wand. Null for the shape tools, which do not
     *   look at the artwork at all.
     */
    fun end(at: Vec2, width: Int, height: Int, pixels: RasterImage?) {
        val start = anchor ?: return
        val region = when (shape) {
            SelectionShape.RECTANGLE -> Marquee.rectangle(width, height, boxOf(start, at), feather)
            SelectionShape.ELLIPSE -> Marquee.ellipse(width, height, boxOf(start, at), feather)
            SelectionShape.LASSO -> Marquee.polygon(width, height, draft + at, feather)
            SelectionShape.WAND -> {
                val image = pixels
                if (image == null) {
                    null
                } else {
                    MagicWand.select(image.pixels, image.width, image.height, at, tolerance, contiguous)
                        .let { if (feather > 0f) it.feathered(feather) else it }
                }
            }
        }
        anchor = null
        draft = emptyList()
        if (region == null) return

        val existing = selection
        replace(
            if (existing == null || mode == SelectionMode.REPLACE) {
                region
            } else {
                existing.combine(region, mode)
            },
        )
    }

    fun selectAll(width: Int, height: Int) = replace(PixelSelection.everything(width, height))

    fun clear() {
        selection = null
        outline = emptyList()
        draft = emptyList()
        anchor = null
    }

    fun invert(width: Int, height: Int) {
        replace(selection?.inverted() ?: PixelSelection.everything(width, height))
    }

    fun grow(pixels: Int) = selection?.let { replace(it.grown(pixels)) }

    fun soften(radius: Float) = selection?.let { replace(it.feathered(radius)) }

    private fun replace(next: PixelSelection) {
        selection = if (next.isEmpty) null else next
        outline = selection?.let {
            // Sampled rather than exact: a boundary on a 6000-pixel document would otherwise be a
            // segment list larger than the artwork, and at screen scale the difference is invisible.
            SelectionOutline.edges(it, step = outlineStep(it))
        } ?: emptyList()
    }

    private fun outlineStep(selection: PixelSelection): Int =
        (maxOf(selection.width, selection.height) / OUTLINE_TARGET).coerceAtLeast(1)

    private fun boxOf(a: Vec2, b: Vec2) = Rect(
        minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y),
    )

    private companion object {
        /** Roughly one sample per screen pixel on a phone; finer than that draws nothing new. */
        const val OUTLINE_TARGET = 1080
    }
}
