package ir.pixellab.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ir.pixellab.core.codec.RasterImage
import ir.pixellab.core.editor.AspectRatio
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.paint.Edge
import ir.pixellab.core.paint.MagicWand
import ir.pixellab.core.paint.Marquee
import ir.pixellab.core.paint.PixelSelection
import ir.pixellab.core.paint.QuickSelect
import ir.pixellab.core.paint.SelectionMode
import ir.pixellab.core.paint.SelectionOutline

/** Which shape a drag defines. */
enum class SelectionShape {
    RECTANGLE, ELLIPSE, LASSO, WAND,

    /**
     * Quick Selection: drag across a region and it grows to fit.
     *
     * A drag like the lasso's, but the path is a *sample* rather than a boundary — which is why it
     * keeps the points rather than the two corners, and why it is the only shape here whose result
     * depends on the picture underneath.
     */
    QUICK,
}

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

    /**
     * Photoshop's *Fixed Ratio* marquee style, and the crop tool's ratio, which are the same thing.
     *
     * Null is freeform. When it is set, a dragged rectangle or ellipse is reshaped to the proportion
     * before it becomes a selection, so the frame the user is about to crop to is already the right
     * shape while they are still moving it — rather than being corrected after they let go, which is
     * the version that feels like the app disagreeing with them.
     *
     * It lives on the controller rather than in the document because it is a tool setting: it must
     * survive an undo and must not be saved into the file.
     */
    var ratio: AspectRatio? by mutableStateOf(null)

    var selection: PixelSelection? by mutableStateOf(null)
        private set

    /** The boundary, recomputed only when the selection changes rather than per frame. */
    var outline: List<Edge> by mutableStateOf(emptyList())
        private set

    /** The shape being dragged right now, drawn live before it is committed. */
    var draft: List<Vec2> by mutableStateOf(emptyList())
        private set

    private var anchor: Vec2? = null

    /** Remembered from [begin] so the live draft can be shaped and clamped the same way [end] will. */
    private var canvasWidth = 0
    private var canvasHeight = 0

    /** How wide a quick-selection drag samples, in canvas pixels. */
    var quickRadius: Float by mutableStateOf(DEFAULT_QUICK_RADIUS)

    fun begin(at: Vec2, width: Int, height: Int) {
        anchor = at
        canvasWidth = width
        canvasHeight = height
        draft = listOf(at)
    }

    fun extend(at: Vec2) {
        val start = anchor ?: return
        draft = when (shape) {
            // A lasso keeps every point; the others are described by their box, and keeping every
            // sample would make a slow drag build a list thousands long for a rectangle.
            SelectionShape.LASSO -> draft + at
            // The box itself, not the diagonal across it. Drawing two points meant a marquee drag
            // showed a line where the selection was going to be — legible only to someone who
            // already knew what it stood for, and useless the moment a ratio reshapes the box under
            // the finger, because the corner being dragged is no longer where the finger is.
            SelectionShape.RECTANGLE -> outlineOf(framed(start, at, canvasWidth, canvasHeight))
            SelectionShape.ELLIPSE -> ellipseOf(framed(start, at, canvasWidth, canvasHeight))
            // Every point is kept, like the lasso's — but as a sample of the region rather than as
            // its boundary, which is what the grower needs.
            SelectionShape.QUICK -> draft + at
            SelectionShape.WAND -> listOf(start, at)
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
            SelectionShape.RECTANGLE -> Marquee.rectangle(width, height, framed(start, at, width, height), feather)
            SelectionShape.ELLIPSE -> Marquee.ellipse(width, height, framed(start, at, width, height), feather)
            SelectionShape.LASSO -> Marquee.polygon(width, height, draft + at, feather)
            SelectionShape.QUICK -> {
                val image = pixels
                if (image == null) {
                    null
                } else {
                    // Grown from whatever is already selected, so a second drag adds to the first
                    // rather than starting over — which is how the tool is actually used.
                    QuickSelect.select(
                        image.pixels, image.width, image.height,
                        stroke = draft + at,
                        radius = quickRadius,
                        tolerance = tolerance,
                        existing = if (mode == SelectionMode.REPLACE) null else selection,
                    ).let { if (feather > 0f) it.feathered(feather) else it }
                }
            }
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
        use(region)
    }

    fun selectAll(width: Int, height: Int) = replace(PixelSelection.everything(width, height))

    /**
     * Lays the biggest frame of the current [ratio] over the whole canvas.
     *
     * The half of a crop interface that is not dragging: press 1:1 and the frame appears, already
     * correct and already the largest it can be. Without it, choosing a ratio would do nothing
     * visible until the user happened to draw something.
     */
    fun frame(width: Int, height: Int) {
        val canvas = Rect(0f, 0f, width.toFloat(), height.toFloat())
        val box = ratio?.fit(canvas) ?: canvas
        shape = SelectionShape.RECTANGLE
        // Replaces outright rather than going through `use`: a frame is not a correction to an
        // existing selection, and combining it with one would produce a non-rectangular crop box.
        replace(Marquee.rectangle(width, height, box, feather))
    }

    /**
     * Adopts a selection computed somewhere else, honouring the combining mode.
     *
     * Through the mode rather than replacing outright: subject selection is exactly the tool a user
     * runs and then corrects — take the subject, then subtract the arm it grabbed with it — and a
     * result that always replaced would throw away the correction on every re-run.
     */
    fun use(region: PixelSelection) {
        val existing = selection
        replace(
            if (existing == null || mode == SelectionMode.REPLACE) {
                region
            } else {
                existing.combine(region, mode)
            },
        )
    }

    /**
     * Replaces the selection outright, ignoring the combining mode.
     *
     * For callers that have computed the finished answer rather than a contribution to it — Quick
     * Mask painting, and the crop frame. Going through [use] would let a subtract-mode brush stroke
     * invert the mask it was meant to be editing.
     */
    fun set(region: PixelSelection) = replace(region)

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

    /** The dragged box, reshaped by [ratio] and kept on the canvas when one is set. */
    private fun framed(a: Vec2, b: Vec2, width: Int, height: Int): Rect {
        val box = boxOf(a, b)
        val fixed = ratio ?: return box
        val bounds = if (width > 0 && height > 0) Rect(0f, 0f, width.toFloat(), height.toFloat()) else null
        return fixed.constrain(box, bounds)
    }

    /** The four corners, closed, so the draft draws as the rectangle rather than as its diagonal. */
    private fun outlineOf(box: Rect) = listOf(
        Vec2(box.left, box.top),
        Vec2(box.right, box.top),
        Vec2(box.right, box.bottom),
        Vec2(box.left, box.bottom),
        Vec2(box.left, box.top),
    )

    /**
     * The ellipse inscribed in [box], as a closed polygon.
     *
     * Enough segments that the curve reads as a curve at any zoom a phone reaches, and few enough
     * that rebuilding it on every pointer sample costs nothing.
     */
    private fun ellipseOf(box: Rect): List<Vec2> {
        val cx = (box.left + box.right) / 2f
        val cy = (box.top + box.bottom) / 2f
        val rx = box.width / 2f
        val ry = box.height / 2f
        return (0..ELLIPSE_SEGMENTS).map { step ->
            val angle = step * 2f * Math.PI.toFloat() / ELLIPSE_SEGMENTS
            Vec2(cx + rx * kotlin.math.cos(angle), cy + ry * kotlin.math.sin(angle))
        }
    }

    private companion object {
        /** Roughly one sample per screen pixel on a phone; finer than that draws nothing new. */
        const val OUTLINE_TARGET = 1080

        /** Segments in a drafted ellipse. Past this the extra vertices land inside one screen pixel. */
        const val ELLIPSE_SEGMENTS = 64

        /** About a fingertip on a phone-sized canvas — wide enough to sample a region, not a line. */
        const val DEFAULT_QUICK_RADIUS = 16f
    }
}
