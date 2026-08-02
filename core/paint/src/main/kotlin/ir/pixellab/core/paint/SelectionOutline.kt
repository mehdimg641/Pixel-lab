package ir.pixellab.core.paint

import ir.pixellab.core.model.Vec2

/** One straight piece of a selection's boundary, in canvas coordinates. */
data class Edge(val from: Vec2, val to: Vec2)

/**
 * Traces the boundary of a selection.
 *
 * The marching ants have to be drawn somewhere, and drawing the coverage itself as a tint is not a
 * substitute: a tint hides the artwork exactly where the user is looking. The boundary is the only
 * representation that shows what is chosen without obscuring it.
 *
 * Computed once when the selection changes rather than per frame. A boundary on a large document is
 * a few thousand segments, which is nothing to draw and far too much to re-derive at sixty hertz.
 */
object SelectionOutline {

    /**
     * Every edge between a selected pixel and an unselected neighbour.
     *
     * Cell edges rather than a traced contour: a contour has to decide what to do where the
     * boundary touches itself, and a selection built from three wand clicks does that constantly.
     * Loose segments have no such case and draw identically.
     *
     * @param step samples every nth pixel, so a selection on a 6000-pixel document does not produce
     *   a segment list larger than the artwork.
     */
    fun edges(selection: PixelSelection, step: Int = 1): List<Edge> {
        val stride = step.coerceAtLeast(1)
        val out = ArrayList<Edge>()
        val size = stride.toFloat()

        var y = 0
        while (y < selection.height) {
            var x = 0
            while (x < selection.width) {
                if (!inside(selection, x, y)) {
                    x += stride
                    continue
                }
                val left = x.toFloat()
                val top = y.toFloat()
                if (!inside(selection, x - stride, y)) out += Edge(Vec2(left, top), Vec2(left, top + size))
                if (!inside(selection, x + stride, y)) {
                    out += Edge(Vec2(left + size, top), Vec2(left + size, top + size))
                }
                if (!inside(selection, x, y - stride)) out += Edge(Vec2(left, top), Vec2(left + size, top))
                if (!inside(selection, x, y + stride)) {
                    out += Edge(Vec2(left, top + size), Vec2(left + size, top + size))
                }
                x += stride
            }
            y += stride
        }
        return out
    }

    /**
     * Photoshop's rule: the ants run along the half-coverage line.
     *
     * A feathered selection has no single edge, and drawing every partially covered pixel would
     * turn a soft selection into a wide band of ants that says nothing about where it actually is.
     */
    private fun inside(selection: PixelSelection, x: Int, y: Int): Boolean = selection[x, y] >= HALF

    private const val HALF = 128
}
