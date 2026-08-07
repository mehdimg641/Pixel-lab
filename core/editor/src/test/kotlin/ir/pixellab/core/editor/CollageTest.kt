package ir.pixellab.core.editor

import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.AssetId
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The collage layout engine.
 *
 * The arithmetic is small and the ways to get it subtly wrong are not: an off-centre grid, a doubled
 * gap at the frame's edge, a photograph silently dropped, a picture sitting at its own pixel size in
 * a cell it does not match. Each of those is invisible in code review and obvious the moment someone
 * looks at the result.
 */
class CollageTest {

    private fun photos(n: Int, size: Vec2 = Vec2(1200f, 800f)) =
        List(n) { Collage.Photo(AssetId("photo-$it"), size) }

    /** Every cell's frame, taken from the shape layer that defines it. */
    private fun frames(document: Document): List<Rect> =
        document.layers.filterIsInstance<Layer.Shape>().map { layer ->
            val size = (layer.geometry as ShapeGeometry.Rectangle).size
            val at = layer.transform.translation
            Rect(at.x, at.y, at.x + size.x, at.y + size.y)
        }

    /** Where a photograph actually lands, after its cover-scale is applied. */
    private fun placed(layer: Layer.Image, natural: Vec2): Rect {
        val at = layer.transform.translation
        val scale = layer.transform.scale
        return Rect(at.x, at.y, at.x + natural.x * scale.x, at.y + natural.y * scale.y)
    }

    @Test
    fun `every cell gets a photograph, in order`() {
        val document = Collage.build(photos(4), Collage.grid(2, 2), 1000, 1000)
        val images = document.layers.filterIsInstance<Layer.Image>()
        images.size shouldBe 4
        images.map { it.asset.value } shouldBe listOf("photo-0", "photo-1", "photo-2", "photo-3")
    }

    @Test
    fun `each photograph is clipped to the cell directly beneath it`() {
        // The whole structure rests on this. A vector mask would travel with the layer's transform,
        // so dragging the picture would drag its window too and nothing would stay aligned; a
        // clipping mask leaves the cell where it is. If the order or the flag ever slips, the
        // photographs stop being cut to their cells and simply overlap the page.
        val document = Collage.build(photos(4), Collage.grid(2, 2), 1000, 1000)
        document.layers.chunked(2).forEach { pair ->
            (pair[0] is Layer.Shape) shouldBe true
            (pair[1] as Layer.Image).clipped shouldBe true
            pair[0].clipped shouldBe false
        }
    }

    @Test
    fun `a photograph covers its cell rather than fitting inside it`() {
        // Fitting would leave a band of background down two sides of every cell whose shape does not
        // match the layout's — which is most of them, most of the time.
        val natural = Vec2(1200f, 800f)
        val document = Collage.build(photos(4, natural), Collage.grid(2, 2), 1000, 1000)
        val cells = frames(document)
        document.layers.filterIsInstance<Layer.Image>().forEachIndexed { index, image ->
            val box = placed(image, natural)
            val cell = cells[index]
            (box.left <= cell.left + 0.5f) shouldBe true
            (box.top <= cell.top + 0.5f) shouldBe true
            (box.right >= cell.right - 0.5f) shouldBe true
            (box.bottom >= cell.bottom - 0.5f) shouldBe true
        }
    }

    @Test
    fun `the covered photograph is centred, so the crop takes the same from both sides`() {
        val natural = Vec2(2000f, 1000f)
        val document = Collage.build(photos(1, natural), Collage.LAYOUTS.first(), 1000, 1000)
        val cell = frames(document).first()
        val box = placed(document.layers.filterIsInstance<Layer.Image>().first(), natural)
        abs((cell.left - box.left) - (box.right - cell.right)) shouldBeLessThan 0.5f
    }

    @Test
    fun `the photograph keeps its aspect ratio`() {
        // A cover that stretched to fit would be the one distortion nobody forgives.
        val natural = Vec2(1600f, 900f)
        val document = Collage.build(photos(6, natural), Collage.grid(3, 2), 900, 1200)
        document.layers.filterIsInstance<Layer.Image>().forEach {
            abs(it.transform.scale.x - it.transform.scale.y) shouldBeLessThan 1e-4f
        }
    }

    @Test
    fun `a grid is centred rather than shifted by its gaps`() {
        // The classic mistake: taking a whole gap off one side of each cell leaves the first column
        // narrower than the rest and the whole grid sitting off-centre. Half a gap on each internal
        // side is what keeps it symmetric, and a square layout shows the error immediately.
        val document = Collage.build(photos(4), Collage.grid(2, 2), 1000, 1000, Collage.Style(spacing = 0.05f))
        val cells = frames(document)

        val leftMargin = cells[0].left
        val rightMargin = 1000f - cells[1].right
        abs(leftMargin - rightMargin) shouldBeLessThan 0.5f

        // And the two columns are the same width.
        abs(cells[0].width - cells[1].width) shouldBeLessThan 0.5f
    }

    @Test
    fun `an outer edge gets the margin and an inner seam gets the gap, not both`() {
        val style = Collage.Style(spacing = 0.04f, margin = 0.02f)
        val cells = frames(Collage.build(photos(2), Collage.grid(2, 1), 1000, 1000, style))

        // Frame edge: exactly the margin.
        abs(cells[0].left - 20f) shouldBeLessThan 0.5f
        abs(1000f - cells[1].right - 20f) shouldBeLessThan 0.5f
        // Internal seam: exactly the gap, shared.
        abs((cells[1].left - cells[0].right) - 40f) shouldBeLessThan 0.5f
    }

    @Test
    fun `cells never overlap`() {
        // Overlap is the one failure a user cannot work around, because the photograph underneath is
        // simply gone.
        for (layout in Collage.LAYOUTS) {
            val cells = frames(Collage.build(photos(layout.count), layout, 900, 1200))
            for (i in cells.indices) {
                for (j in i + 1 until cells.size) {
                    val a = cells[i]
                    val b = cells[j]
                    val overlaps = a.left < b.right - 1f && b.left < a.right - 1f &&
                        a.top < b.bottom - 1f && b.top < a.bottom - 1f
                    overlaps shouldBe false
                }
            }
        }
    }

    @Test
    fun `no cell escapes the canvas`() {
        for (layout in Collage.LAYOUTS) {
            val cells = frames(Collage.build(photos(layout.count), layout, 800, 1000))
            for (cell in cells) {
                (cell.left >= -0.5f) shouldBe true
                (cell.top >= -0.5f) shouldBe true
                (cell.right <= 800.5f) shouldBe true
                (cell.bottom <= 1000.5f) shouldBe true
            }
        }
    }

    @Test
    fun `spacing is a fraction, so the same setting looks the same at any size`() {
        // A collage exported at print size and at thumbnail size has to look like the same design.
        // Pixel spacing would make the thumbnail all gap and the print all photograph.
        fun gapFraction(size: Int): Float {
            val cells = frames(
                Collage.build(photos(2), Collage.grid(2, 1), size, size, Collage.Style(spacing = 0.05f)),
            )
            return (cells[1].left - cells[0].right) / size
        }
        abs(gapFraction(400) - gapFraction(4000)) shouldBeLessThan 0.001f
    }

    @Test
    fun `a missing photograph leaves its cell drawn and empty rather than reflowing`() {
        // Reflowing would silently change the design because one file failed to load, and the user
        // could not see what had gone. The empty cell is what they tap to fill it.
        val document = Collage.build(photos(2), Collage.grid(2, 2), 1000, 1000)
        document.layers.filterIsInstance<Layer.Image>().size shouldBe 2
        frames(document).size shouldBe 4
        // The two that are present sit in the first two cells, unchanged.
        val full = frames(Collage.build(photos(4), Collage.grid(2, 2), 1000, 1000))
        abs(frames(document)[3].left - full[3].left) shouldBeLessThan 0.5f
    }

    @Test
    fun `the corners are vector, so they stay crisp at any export size`() {
        val document = Collage.build(photos(1), Collage.LAYOUTS.first(), 1000, 1000, Collage.Style(cornerRadius = 0.05f))
        val shape = document.layers.filterIsInstance<Layer.Shape>().first().geometry as ShapeGeometry.Rectangle
        shape.cornerRadius.topLeft shouldBeGreaterThan 40f
    }

    @Test
    fun `the photograph keeps its full extent so it can still be moved inside its cell`() {
        // A crop would bake the framing in, and sliding the picture inside its cell is the first
        // thing anyone does to a collage.
        val document = Collage.build(photos(4), Collage.grid(2, 2), 1000, 1000)
        document.layers.filterIsInstance<Layer.Image>().all { it.crop == null } shouldBe true
    }

    @Test
    fun `an empty cell is not the same colour as the gaps between cells`() {
        // Otherwise it is invisible, and a user cannot tap what they cannot see.
        val style = Collage.Style()
        (style.cell == style.background) shouldBe false
    }

    @Test
    fun `choosing a layout for a count prefers an exact fit`() {
        Collage.bestFor(4).count shouldBe 4
        Collage.bestFor(6).count shouldBe 6
    }

    @Test
    fun `a count with no exact layout gets the smallest that holds them all`() {
        // A photograph left out is worse than an empty cell, because the user cannot see what is
        // missing.
        val chosen = Collage.bestFor(7)
        (chosen.count >= 7) shouldBe true
        chosen.count shouldBe 9
    }

    @Test
    fun `every layout is named and none is a duplicate`() {
        Collage.LAYOUTS.map { it.name }.toSet().size shouldBe Collage.LAYOUTS.size
        Collage.LAYOUTS.all { it.persianLabel.isNotBlank() } shouldBe true
    }

    @Test
    fun `a canvas with no size is refused rather than dividing by it`() {
        try {
            Collage.build(photos(1), Collage.LAYOUTS.first(), 0, 100)
            error("a zero-width canvas was accepted")
        } catch (expected: IllegalArgumentException) {
            (expected.message?.contains("positive") == true) shouldBe true
        }
    }

    @Test
    fun `a photograph with no size is refused rather than dividing by it`() {
        try {
            Collage.Photo(AssetId("empty"), Vec2(0f, 100f))
            error("a zero-width photograph was accepted")
        } catch (expected: IllegalArgumentException) {
            (expected.message?.contains("positive") == true) shouldBe true
        }
    }
}
