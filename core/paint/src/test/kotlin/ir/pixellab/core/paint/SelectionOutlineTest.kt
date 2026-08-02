package ir.pixellab.core.paint

import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Rect
import org.junit.jupiter.api.Test

/**
 * The marching ants.
 *
 * They are how the user knows what is chosen, and getting them wrong is not cosmetic: a boundary
 * that runs where the selection is not means every subsequent gesture is aimed at the wrong place.
 */
class SelectionOutlineTest {

    @Test
    fun `nothing selected has no boundary`() {
        SelectionOutline.edges(PixelSelection.nothing(32, 32)).isEmpty() shouldBe true
    }

    @Test
    fun `a rectangle's boundary is its perimeter`() {
        val selection = Marquee.rectangle(32, 32, Rect(8f, 8f, 16f, 16f))
        val edges = SelectionOutline.edges(selection)
        // Eight pixels a side gives thirty-two edges. A boundary that came back with the area
        // instead would be sixty-four.
        edges.size shouldBe 32
    }

    @Test
    fun `everything selected has a boundary only at the image edge`() {
        val edges = SelectionOutline.edges(PixelSelection.everything(16, 16))
        // Outside the image counts as unselected, so the frame itself is the boundary: 4 x 16.
        edges.size shouldBe 64
    }

    @Test
    fun `a feathered selection traces the half-coverage line rather than every soft pixel`() {
        val soft = Marquee.rectangle(64, 64, Rect(16f, 16f, 48f, 48f)).feathered(8f)
        val hard = Marquee.rectangle(64, 64, Rect(16f, 16f, 48f, 48f))
        // Drawing every partially covered pixel would turn a soft selection into a wide band of
        // ants that says nothing about where it actually is.
        val softEdges = SelectionOutline.edges(soft).size
        val hardEdges = SelectionOutline.edges(hard).size
        (softEdges < hardEdges * 2) shouldBe true
    }

    @Test
    fun `sampling coarsely returns fewer edges over the same region`() {
        val selection = Marquee.rectangle(64, 64, Rect(8f, 8f, 56f, 56f))
        val fine = SelectionOutline.edges(selection, step = 1).size
        val coarse = SelectionOutline.edges(selection, step = 4).size
        // A boundary on a large document would otherwise be a segment list bigger than the artwork,
        // and at screen scale the difference is invisible.
        (coarse < fine) shouldBe true
        (coarse > 0) shouldBe true
    }

    @Test
    fun `two separate regions each get their own boundary`() {
        val left = Marquee.rectangle(64, 32, Rect(4f, 4f, 12f, 12f))
        val right = Marquee.rectangle(64, 32, Rect(40f, 4f, 48f, 12f))
        val both = left.combine(right, SelectionMode.ADD)
        // A contour tracer has to decide what to do when the boundary is not one loop; loose
        // segments have no such case, and a selection built from three wand clicks is never one loop.
        SelectionOutline.edges(both).size shouldBe
            SelectionOutline.edges(left).size + SelectionOutline.edges(right).size
    }
}
